package dev.varun.studio.github

/**
 * GitHub API value types.
 *
 * Parsed with org.json (built into Android) rather than a serialization
 * library: it keeps the dependency count low and avoids a codegen step, which
 * matters for a phone-only build workflow.
 */

data class GitHubUser(
    val login: String,
    val name: String?,
    val avatarUrl: String?,
    val plan: String?,
)

data class GitHubRepo(
    val id: Long,
    val fullName: String,
    val name: String,
    val owner: String,
    val isPrivate: Boolean,
    val defaultBranch: String,
    val description: String?,
    val language: String?,
    val sizeKb: Long,
    val updatedAt: String?,
    val htmlUrl: String,
    val permissions: RepoPermissions,
    val isFork: Boolean,
) {
    val estimatedBytes: Long get() = sizeKb * 1024
}

data class RepoPermissions(
    val canPull: Boolean,
    val canPush: Boolean,
    val canAdmin: Boolean,
)

data class GitHubBranch(
    val name: String,
    val sha: String,
    val isProtected: Boolean,
)

data class GitHubCommit(
    val sha: String,
    val message: String,
    val authorName: String?,
    val authorLogin: String?,
    val date: String?,
    val htmlUrl: String?,
)

data class GitHubContent(
    val name: String,
    val path: String,
    val type: String,        // "file" | "dir" | "symlink" | "submodule"
    val sizeBytes: Long,
    val sha: String?,
    val downloadUrl: String?,
) {
    val isDirectory: Boolean get() = type == "dir"
}

data class GitHubFileContent(
    val path: String,
    val sha: String,
    val sizeBytes: Long,
    val encoding: String,
    val text: String?,
    val truncated: Boolean,
    val base64: String?,
)

data class GitHubTreeEntry(
    val path: String,
    val type: String,        // "blob" | "tree"
    val sizeBytes: Long?,
    val sha: String,
)

data class GitHubRateLimit(
    val limit: Int,
    val remaining: Int,
    val resetEpochSeconds: Long,
) {
    val nearlyExhausted: Boolean get() = limit > 0 && remaining <= 5
}

/** Everything that can go wrong talking to GitHub, as a value. */
sealed class GitHubError(open val message: String) {
    data class NoCredentials(override val message: String = "GitHub is not connected.") : GitHubError(message)
    data class Unauthorized(override val message: String) : GitHubError(message)
    data class Forbidden(override val message: String) : GitHubError(message)
    data class NotFound(override val message: String) : GitHubError(message)
    data class RateLimited(val resetEpochSeconds: Long, override val message: String) : GitHubError(message)
    data class ValidationFailed(override val message: String) : GitHubError(message)
    data class Server(val status: Int, override val message: String) : GitHubError(message)
    data class Network(override val message: String) : GitHubError(message)
    data class Parse(override val message: String) : GitHubError(message)
    data class Cancelled(override val message: String = "Request cancelled.") : GitHubError(message)
    data class Refused(override val message: String) : GitHubError(message)
}

/** Result of a push/commit attempt. */
data class GitWriteOutcome(
    val branch: String,
    val commitSha: String?,
    val filesChanged: Int,
    val htmlUrl: String?,
)

/** A single staged file change for a commit. */
data class StagedChange(
    val path: String,
    val newText: String?,     // null => delete
    val oldSha: String?,      // null => new file
    val isNewFile: Boolean,
    val isDelete: Boolean,
)
