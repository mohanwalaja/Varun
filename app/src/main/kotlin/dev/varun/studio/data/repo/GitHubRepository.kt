package dev.varun.studio.data.repo

import dev.varun.studio.core.security.SecureVault
import dev.varun.studio.github.GitHubBranch
import dev.varun.studio.github.GitHubClient
import dev.varun.studio.github.GitHubCommit
import dev.varun.studio.github.GitHubContent
import dev.varun.studio.github.GitHubError
import dev.varun.studio.github.GitHubFileContent
import dev.varun.studio.github.GitHubGitOps
import dev.varun.studio.github.GitHubRateLimit
import dev.varun.studio.github.GitHubRepo
import dev.varun.studio.github.GitHubTreeEntry
import dev.varun.studio.github.GitHubUser
import dev.varun.studio.github.GitWriteOutcome
import dev.varun.studio.github.StagedChange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * GitHub connection state and operations.
 *
 * Credentials: a fine-grained personal access token chosen by the user, stored
 * encrypted by [SecureVault] (Android Keystore, AES-256-GCM) and never written
 * to disk in plaintext, never logged, and never embedded in the APK.
 *
 * GitHub is used ONLY for repository access. Inference never touches it.
 */
class GitHubRepository(private val vault: SecureVault) {

    private val client = GitHubClient(tokenProvider = { vault.getString(SecureVault.KEY_GITHUB_TOKEN) })
    private val ops = GitHubGitOps(client)

    private val _user = MutableStateFlow<GitHubUser?>(null)
    val user: StateFlow<GitHubUser?> = _user.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _rateLimit = MutableStateFlow<GitHubRateLimit?>(null)
    val rateLimit: StateFlow<GitHubRateLimit?> = _rateLimit.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    val isConnectedLocally: Boolean
        get() = !vault.getString(SecureVault.KEY_GITHUB_TOKEN).isNullOrBlank()

    fun storedLogin(): String? = vault.getString(SecureVault.KEY_GITHUB_LOGIN)

    /**
     * Store a token and validate it against /user.
     * A token that fails validation is NOT persisted, so the app can never end
     * up in a state where it believes it is connected but every call 401s.
     */
    suspend fun connect(token: String): Result<GitHubUser> = withContext(Dispatchers.IO) {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Token is empty."))
        }
        if (trimmed.length < 20) {
            return@withContext Result.failure(IllegalArgumentException("That does not look like a GitHub token."))
        }
        // Warn about obviously over-privileged classic tokens.
        val probe = GitHubClient(tokenProvider = { trimmed }).currentUser()
        when (probe) {
            is GitHubClient.ApiResult.Ok -> {
                val ok = vault.putString(SecureVault.KEY_GITHUB_TOKEN, trimmed)
                if (!ok) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            "Could not encrypt the token with the Android Keystore. " +
                                "The token was not saved.",
                        ),
                    )
                }
                vault.putString(SecureVault.KEY_GITHUB_LOGIN, probe.value.login)
                _user.value = probe.value
                _connected.value = true
                _lastError.value = null
                probe.rateLimit?.let { _rateLimit.value = it }
                Result.success(probe.value)
            }
            is GitHubClient.ApiResult.Err -> {
                _connected.value = false
                _user.value = null
                _lastError.value = probe.error.message
                Result.failure(GitHubException(probe.error))
            }
        }
    }

    /** Erase the token and all cached GitHub state. */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        vault.remove(SecureVault.KEY_GITHUB_TOKEN)
        vault.remove(SecureVault.KEY_GITHUB_LOGIN)
        vault.remove(SecureVault.KEY_GITHUB_SCOPES)
        _user.value = null
        _connected.value = false
        _lastError.value = null
    }

    /** Restore the session from the vault on app start (no network call). */
    fun restoreFromVault() {
        val login = vault.getString(SecureVault.KEY_GITHUB_LOGIN)
        _connected.value = isConnectedLocally
        if (login != null) _user.value = GitHubUser(login, null, null, null)
    }

    suspend fun refreshUser(): GitHubUser? = withContext(Dispatchers.IO) {
        when (val r = client.currentUser()) {
            is GitHubClient.ApiResult.Ok -> {
                _user.value = r.value
                _connected.value = true
                r.rateLimit?.let { _rateLimit.value = it }
                r.value
            }
            is GitHubClient.ApiResult.Err -> { report(r.error); null }
        }
    }

    suspend fun listRepos(page: Int = 1): Result<List<GitHubRepo>> = withContext(Dispatchers.IO) {
        when (val r = client.listRepos(page)) {
            is GitHubClient.ApiResult.Ok -> {
                r.rateLimit?.let { _rateLimit.value = it }
                Result.success(r.value)
            }
            is GitHubClient.ApiResult.Err -> { report(r.error); Result.failure(GitHubException(r.error)) }
        }
    }

    suspend fun searchRepos(query: String): Result<List<GitHubRepo>> = withContext(Dispatchers.IO) {
        when (val r = client.searchRepos(query)) {
            is GitHubClient.ApiResult.Ok -> Result.success(r.value)
            is GitHubClient.ApiResult.Err -> { report(r.error); Result.failure(GitHubException(r.error)) }
        }
    }

    suspend fun repo(owner: String, name: String): Result<GitHubRepo> = withContext(Dispatchers.IO) {
        when (val r = client.repo(owner, name)) {
            is GitHubClient.ApiResult.Ok -> Result.success(r.value)
            is GitHubClient.ApiResult.Err -> { report(r.error); Result.failure(GitHubException(r.error)) }
        }
    }

    suspend fun branches(owner: String, name: String): Result<List<GitHubBranch>> =
        withContext(Dispatchers.IO) {
            when (val r = client.listBranches(owner, name)) {
                is GitHubClient.ApiResult.Ok -> Result.success(r.value)
                is GitHubClient.ApiResult.Err -> { report(r.error); Result.failure(GitHubException(r.error)) }
            }
        }

    suspend fun commits(owner: String, name: String, sha: String?, path: String?): Result<List<GitHubCommit>> =
        withContext(Dispatchers.IO) {
            when (val r = client.listCommits(owner, name, sha, path)) {
                is GitHubClient.ApiResult.Ok -> Result.success(r.value)
                is GitHubClient.ApiResult.Err -> { report(r.error); Result.failure(GitHubException(r.error)) }
            }
        }

    suspend fun contents(owner: String, name: String, path: String, ref: String?): Result<List<GitHubContent>> =
        withContext(Dispatchers.IO) {
            when (val r = client.listContents(owner, name, path, ref)) {
                is GitHubClient.ApiResult.Ok -> Result.success(r.value)
                is GitHubClient.ApiResult.Err -> { report(r.error); Result.failure(GitHubException(r.error)) }
            }
        }

    suspend fun file(owner: String, name: String, path: String, ref: String?): Result<GitHubFileContent> =
        withContext(Dispatchers.IO) {
            when (val r = client.fileContent(owner, name, path, ref)) {
                is GitHubClient.ApiResult.Ok -> Result.success(r.value)
                is GitHubClient.ApiResult.Err -> { report(r.error); Result.failure(GitHubException(r.error)) }
            }
        }

    /** Resolve the commit SHA a branch currently points at. */
    suspend fun headSha(owner: String, name: String, branch: String): Result<String> =
        withContext(Dispatchers.IO) {
            when (val r = client.refSha(owner, name, branch)) {
                is GitHubClient.ApiResult.Ok ->
                    if (r.value.isBlank()) Result.failure(GitHubException(GitHubError.NotFound("Branch '$branch' not found.")))
                    else Result.success(r.value)
                is GitHubClient.ApiResult.Err -> { report(r.error); Result.failure(GitHubException(r.error)) }
            }
        }

    /**
     * Full path listing for a ref. The primary "targeted retrieval" tool: one
     * call, then the user picks files instead of importing everything.
     */
    suspend fun tree(owner: String, name: String, ref: String): Result<List<GitHubTreeEntry>> =
        withContext(Dispatchers.IO) {
            when (val r = client.tree(owner, name, ref)) {
                is GitHubClient.ApiResult.Ok -> Result.success(r.value)
                is GitHubClient.ApiResult.Err -> { report(r.error); Result.failure(GitHubException(r.error)) }
            }
        }

    suspend fun searchCode(query: String, owner: String, name: String): Result<List<GitHubClient.CodeSearchHit>> =
        withContext(Dispatchers.IO) {
            when (val r = client.searchCode(query, owner, name)) {
                is GitHubClient.ApiResult.Ok -> Result.success(r.value)
                is GitHubClient.ApiResult.Err -> { report(r.error); Result.failure(GitHubException(r.error)) }
            }
        }

    // ------------------------------------------------------------- writes

    data class SafetySnapshot(
        val ctx: GitHubGitOps.SafetyContext,
        val allowDefaultBranch: Boolean,
    )

    suspend fun safetyContext(owner: String, repo: String, defaultBranch: String): GitHubGitOps.SafetyContext =
        withContext(Dispatchers.IO) {
            val protected = when (val r = client.listBranches(owner, repo)) {
                is GitHubClient.ApiResult.Ok -> r.value.filter { it.isProtected }.map { it.name }.toSet()
                is GitHubClient.ApiResult.Err -> emptySet()
            }
            GitHubGitOps.SafetyContext(owner, repo, defaultBranch, protected)
        }

    suspend fun createBranch(
        ctx: GitHubGitOps.SafetyContext,
        branch: String,
        baseSha: String,
    ): Result<GitWriteOutcome> = withContext(Dispatchers.IO) {
        when (val r = ops.createBranch(ctx, branch, baseSha)) {
            is GitHubGitOps.CommitOutcome.Success -> Result.success(r.outcome)
            is GitHubGitOps.CommitOutcome.Failure -> { report(r.error); Result.failure(GitHubException(r.error)) }
            is GitHubGitOps.CommitOutcome.Blocked -> Result.failure(BlockedOperationException(r.reason))
        }
    }

    /**
     * Commit and push staged changes.
     *
     * `requireExplicitConfirmation` is a hard parameter, not a UI convention:
     * the caller must pass true, which only happens after the user taps through
     * a confirmation dialog showing the exact files and the branch.
     */
    suspend fun commitAndPush(
        ctx: GitHubGitOps.SafetyContext,
        branch: String,
        message: String,
        changes: List<StagedChange>,
        requireExplicitConfirmation: Boolean,
        allowDefaultBranch: Boolean = false,
    ): Result<GitWriteOutcome> = withContext(Dispatchers.IO) {
        if (!requireExplicitConfirmation) {
            return@withContext Result.failure(
                BlockedOperationException("Push requires explicit user confirmation."),
            )
        }
        when (val r = ops.commitAndPush(ctx, branch, message, changes, allowDefaultBranch)) {
            is GitHubGitOps.CommitOutcome.Success -> Result.success(r.outcome)
            is GitHubGitOps.CommitOutcome.Failure -> { report(r.error); Result.failure(GitHubException(r.error)) }
            is GitHubGitOps.CommitOutcome.Blocked -> Result.failure(BlockedOperationException(r.reason))
        }
    }

    private fun report(error: GitHubError) {
        _lastError.value = error.message
        if (error is GitHubError.Unauthorized) {
            _connected.value = false
            _user.value = null
        }
    }

    class GitHubException(val error: GitHubError) : Exception(error.message)

    /** A guard rail refused the operation; not a network or auth failure. */
    class BlockedOperationException(message: String) : Exception(message)
}
