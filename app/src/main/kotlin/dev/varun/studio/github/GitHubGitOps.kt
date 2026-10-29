package dev.varun.studio.github

import dev.varun.studio.core.AppLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * Safe Git write operations built on the GitHub Git Data API.
 *
 * Why not a local git object store? Android has no git binary, and embedding
 * JGit adds ~3 MB plus a real compatibility surface for very little benefit on
 * a phone. GitHub's Git Data API is an officially supported way to create
 * branches, blobs, trees and commits over HTTPS, which is exactly the
 * "authorized GitHub workflow" this app needs.
 *
 * Hard safety rules implemented here, not left to the UI:
 *  - NEVER force-update a ref (`force` is always false).
 *  - NEVER delete a branch or a ref (no DELETE calls exist in this class).
 *  - NEVER commit straight to the default branch unless [allowDefaultBranch]
 *    is explicitly true AND the caller confirms again in the UI.
 *  - Refuse protected branches outright.
 *  - Every commit is built from an explicit list of [StagedChange]; there is no
 *    "commit everything" path.
 */
class GitHubGitOps(private val client: GitHubClient) {

    /** Guard rails, resolved once per operation. */
    data class SafetyContext(
        val owner: String,
        val repo: String,
        val defaultBranch: String,
        val protectedBranches: Set<String>,
    )

    sealed interface CommitOutcome {
        data class Success(val outcome: GitWriteOutcome) : CommitOutcome
        data class Failure(val error: GitHubError) : CommitOutcome
        data class Blocked(val reason: String) : CommitOutcome
    }

    /**
     * Create a branch pointing at [baseSha]. Fails cleanly if it already exists.
     */
    suspend fun createBranch(
        ctx: SafetyContext,
        branchName: String,
        baseSha: String,
    ): CommitOutcome {
        val validation = validateBranchName(branchName)
        if (validation != null) return CommitOutcome.Blocked(validation)
        if (ctx.protectedBranches.contains(branchName)) {
            return CommitOutcome.Blocked("'$branchName' is a protected branch.")
        }
        val body = JSONObject().apply {
            put("ref", "refs/heads/$branchName")
            put("sha", baseSha)
        }
        return when (val r = client.rawJson("POST", "/repos/${e(ctx.owner)}/${e(ctx.repo)}/git/refs", body)) {
            is GitHubClient.ApiResult.Ok -> CommitOutcome.Success(
                GitWriteOutcome(branchName, null, 0, "https://github.com/${ctx.owner}/${ctx.repo}/tree/$branchName"),
            )
            is GitHubClient.ApiResult.Err -> {
                val msg = r.error.message
                if (msg.contains("already exists", true)) {
                    CommitOutcome.Blocked("Branch '$branchName' already exists on GitHub.")
                } else CommitOutcome.Failure(r.error)
            }
        }
    }

    /**
     * Commit a set of staged changes to [branch] and advance the ref.
     *
     * Sequence (all server-side, one logical transaction):
     *  1. resolve the branch head sha,
     *  2. upload one blob per changed file,
     *  3. create a tree based on the head tree,
     *  4. create a commit with the head as its single parent,
     *  5. fast-forward the ref WITHOUT force.
     *
     * If step 5 fails because someone else pushed, we report it instead of
     * force-pushing over their work.
     */
    suspend fun commitAndPush(
        ctx: SafetyContext,
        branch: String,
        message: String,
        changes: List<StagedChange>,
        allowDefaultBranch: Boolean = false,
    ): CommitOutcome {
        if (changes.isEmpty()) {
            return CommitOutcome.Blocked("Nothing to commit: no files were staged.")
        }
        if (message.isBlank()) {
            return CommitOutcome.Blocked("A commit message is required.")
        }
        if (message.length > 500) {
            return CommitOutcome.Blocked("Commit message is too long (max 500 characters).")
        }
        val branchValidation = validateBranchName(branch)
        if (branchValidation != null) return CommitOutcome.Blocked(branchValidation)

        if (branch == ctx.defaultBranch && !allowDefaultBranch) {
            return CommitOutcome.Blocked(
                "'$branch' is the default branch of ${ctx.owner}/${ctx.repo}. " +
                    "Create a dedicated branch instead, or explicitly allow committing to it.",
            )
        }
        if (ctx.protectedBranches.contains(branch)) {
            return CommitOutcome.Blocked(
                "'$branch' is protected. Varun will not commit to a protected branch.",
            )
        }
        // Defence in depth: never write to a ref outside refs/heads.
        if (!branch.matches(BRANCH_NAME)) {
            return CommitOutcome.Blocked("Branch name is not valid: $branch")
        }

        val base = "/repos/${e(ctx.owner)}/${e(ctx.repo)}"

        // 1. head sha
        val headSha = when (val r = client.refSha(ctx.owner, ctx.repo, branch)) {
            is GitHubClient.ApiResult.Ok -> r.value
            is GitHubClient.ApiResult.Err -> return CommitOutcome.Failure(r.error)
        }
        if (headSha.isBlank()) {
            return CommitOutcome.Blocked("Could not resolve branch '$branch' on GitHub.")
        }

        // 2. blobs
        val treeEntries = JSONArray()
        for (change in changes) {
            val pathValidation = validatePath(change.path)
            if (pathValidation != null) return CommitOutcome.Blocked(pathValidation)

            if (change.isDelete) {
                treeEntries.put(
                    JSONObject().apply {
                        put("path", change.path)
                        put("mode", "100644")
                        put("type", "blob")
                        put("sha", JSONObject.NULL)  // null sha == delete in a tree
                    },
                )
                continue
            }
            val text = change.newText
                ?: return CommitOutcome.Blocked("Staged change for ${change.path} has no content.")

            if (text.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) {
                return CommitOutcome.Blocked(
                    "${change.path} is larger than ${MAX_FILE_BYTES / (1024 * 1024)} MB and will not be committed.",
                )
            }
            val blobBody = JSONObject().apply {
                put("content", text)
                put("encoding", "utf-8")
            }
            val blobSha = when (val r = client.rawJson("POST", "$base/git/blobs", blobBody)) {
                is GitHubClient.ApiResult.Ok -> r.value.optString("sha")
                is GitHubClient.ApiResult.Err -> return CommitOutcome.Failure(r.error)
            }
            if (blobSha.isBlank()) {
                return CommitOutcome.Failure(GitHubError.Parse("GitHub did not return a blob sha."))
            }
            val mode = if (isExecutablePath(change.path)) "100755" else "100644"
            treeEntries.put(
                JSONObject().apply {
                    put("path", change.path)
                    put("mode", mode)
                    put("type", "blob")
                    put("sha", blobSha)
                },
            )
        }

        // 3. tree
        val treeBody = JSONObject().apply {
            put("base_tree", headSha)
            put("tree", treeEntries)
        }
        val treeSha = when (val r = client.rawJson("POST", "$base/git/trees", treeBody)) {
            is GitHubClient.ApiResult.Ok -> r.value.optString("sha")
            is GitHubClient.ApiResult.Err -> return CommitOutcome.Failure(r.error)
        }
        if (treeSha.isBlank()) {
            return CommitOutcome.Failure(GitHubError.Parse("GitHub did not return a tree sha."))
        }

        // 4. commit
        val commitBody = JSONObject().apply {
            put("message", message)
            put("tree", treeSha)
            put("parents", JSONArray().put(headSha))
        }
        val commitSha = when (val r = client.rawJson("POST", "$base/git/commits", commitBody)) {
            is GitHubClient.ApiResult.Ok -> r.value.optString("sha")
            is GitHubClient.ApiResult.Err -> return CommitOutcome.Failure(r.error)
        }
        if (commitSha.isBlank()) {
            return CommitOutcome.Failure(GitHubError.Parse("GitHub did not return a commit sha."))
        }

        // 5. advance the ref. force is ALWAYS false.
        val refBody = JSONObject().apply {
            put("sha", commitSha)
            put("force", false)
        }
        return when (val r = client.rawJson("PATCH", "$base/git/refs/heads/${e(branch)}", refBody)) {
            is GitHubClient.ApiResult.Ok -> CommitOutcome.Success(
                GitWriteOutcome(
                    branch = branch,
                    commitSha = commitSha,
                    filesChanged = changes.size,
                    htmlUrl = "https://github.com/${ctx.owner}/${ctx.repo}/commit/$commitSha",
                ),
            )
            is GitHubClient.ApiResult.Err -> {
                val msg = r.error.message.lowercase()
                if (msg.contains("not a fast forward") || msg.contains("force")) {
                    CommitOutcome.Blocked(
                        "GitHub rejected the update because '$branch' moved since you started " +
                            "(not a fast-forward). Varun will not force-push. Refresh, rebase your " +
                            "changes on the new head, and commit again.",
                    )
                } else {
                    CommitOutcome.Failure(r.error)
                }
            }
        }
    }

    /**
     * Update a single file through the Contents API. Used only for tiny,
     * explicitly-confirmed edits; [commitAndPush] is the normal path.
     */
    suspend fun updateSingleFile(
        ctx: SafetyContext,
        branch: String,
        path: String,
        newText: String,
        message: String,
        expectedSha: String?,
        allowDefaultBranch: Boolean = false,
    ): CommitOutcome {
        if (branch == ctx.defaultBranch && !allowDefaultBranch) {
            return CommitOutcome.Blocked("Refusing to write directly to the default branch '$branch'.")
        }
        if (ctx.protectedBranches.contains(branch)) {
            return CommitOutcome.Blocked("'$branch' is protected.")
        }
        val pathValidation = validatePath(path)
        if (pathValidation != null) return CommitOutcome.Blocked(pathValidation)

        val body = JSONObject().apply {
            put("message", message)
            put(
                "content",
                java.util.Base64.getEncoder().encodeToString(newText.toByteArray(Charsets.UTF_8)),
            )
            put("branch", branch)
            if (!expectedSha.isNullOrBlank()) put("sha", expectedSha)
        }
        val base = "/repos/${e(ctx.owner)}/${e(ctx.repo)}/contents/" +
            path.split('/').joinToString("/") { e(it) }
        return when (val r = client.rawJson("PUT", base, body)) {
            is GitHubClient.ApiResult.Ok -> {
                val commitSha = r.value.optJSONObject("commit")?.optString("sha")
                CommitOutcome.Success(
                    GitWriteOutcome(
                        branch = branch,
                        commitSha = commitSha,
                        filesChanged = 1,
                        htmlUrl = r.value.optJSONObject("commit")
                            ?.optString("html_url")
                            ?.takeIf { it.isNotBlank() },
                    ),
                )
            }
            is GitHubClient.ApiResult.Err -> {
                // A 409 here means the file changed remotely: the sha we held is
                // stale. Report it; never overwrite blindly.
                if (r.error is GitHubError.Server && r.error.status == 409) {
                    CommitOutcome.Blocked(
                        "The file changed on GitHub since you opened it (409 conflict). " +
                            "Refresh and re-apply your edit.",
                    )
                } else CommitOutcome.Failure(r.error)
            }
        }
    }

    /**
     * git-check-ref-format inspired validation, restricted to what a phone user
     * can safely type. Rejects anything that could name a non-heads ref or
     * smuggle traversal.
     */
    fun validateBranchName(name: String): String? {
        if (name.isBlank()) return "Branch name is empty."
        if (name.length > 200) return "Branch name is too long."
        if (!name.matches(BRANCH_NAME)) {
            return "Branch name may only contain letters, digits, '/', '-', '_' and '.' " +
                "and must start with a letter or digit."
        }
        if (name.contains("..")) return "Branch name may not contain '..'."
        if (name.startsWith("refs/")) return "Do not include the 'refs/' prefix."
        if (name.endsWith("/") || name.endsWith(".lock") || name.contains("@{")) {
            return "Branch name has an invalid suffix or sequence."
        }
        return null
    }

    /** Path validation for anything we are about to write to GitHub. */
    fun validatePath(path: String): String? {
        if (path.isBlank()) return "File path is empty."
        if (path.startsWith("/") || path.startsWith("\\")) return "Absolute paths are not allowed."
        if (path.contains("..")) return "Path traversal is not allowed: $path"
        if (path.contains('\u0000')) return "Path contains a NUL byte."
        if (path.split('/').any { it.isEmpty() || it == "." }) return "Path has an empty segment."
        if (path.length > 1000) return "Path is too long."
        val first = path.substringBefore('/')
        if (first in setOf(".git", ".hg", ".svn")) return "Refusing to write inside $first."
        return null
    }

    private fun isExecutablePath(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in setOf("sh", "bash", "zsh", "py", "rb", "pl") ||
            path.substringAfterLast('/').startsWith("gradlew")
    }

    private fun e(s: String) = GitHubClient.enc(s)

    companion object {
        private val BRANCH_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._\\-/]{0,199}$")
        private const val MAX_FILE_BYTES = 8L * 1024 * 1024

        fun logFailure(where: String, outcome: CommitOutcome) {
            when (outcome) {
                is CommitOutcome.Failure -> AppLog.w("Git $where failed: ${outcome.error.message}")
                is CommitOutcome.Blocked -> AppLog.w("Git $where blocked: ${outcome.reason}")
                is CommitOutcome.Success -> AppLog.i("Git $where ok: ${outcome.outcome.commitSha}")
            }
        }
    }
}
