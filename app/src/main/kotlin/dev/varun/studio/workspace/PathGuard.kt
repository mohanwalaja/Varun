package dev.varun.studio.workspace

import java.io.File

/**
 * Path validation for the local workspace.
 *
 * Everything the workspace touches comes from untrusted input: a GitHub
 * tarball, a repository file listing, a model's proposed edit, or a URL. A
 * malicious archive entry such as `../../.ssh/authorized_keys` or a model that
 * "suggests" writing to `/etc/passwd` must never escape the workspace root.
 *
 * This class is the single choke point for that decision and is unit-tested.
 */
object PathGuard {

    /** Reason a path was rejected. Surfaced to the user, never swallowed. */
    enum class Reject {
        EMPTY,
        ABSOLUTE,
        TRAVERSAL,
        ESCAPES_ROOT,
        NULL_BYTE,
        RESERVED_NAME,
        TOO_LONG,
    }

    sealed interface Decision {
        data class Allowed(val file: File, val relativePath: String) : Decision
        data class Denied(val reason: Reject, val detail: String) : Decision
    }

    /** Maximum relative path length we accept. */
    const val MAX_PATH_LENGTH = 4000

    /** Windows-style or VCS-internal paths we refuse regardless of platform. */
    private val RESERVED = setOf(".git", ".hg", ".svn", "..", ".", "~", "CON", "NUL", "AUX")

    private val ILLEGAL_CHARS = charArrayOf('\u0000')

    /**
     * Normalize a possibly-hostile relative path and resolve it against [root].
     *
     * Accepts `/` and `\` separators, collapses `.` segments, and rejects
     * anything that walks above the root or is absolute.
     */
    fun resolve(root: File, relativePath: String): Decision {
        if (relativePath.isEmpty() || relativePath.isBlank()) {
            return Decision.Denied(Reject.EMPTY, "Path is empty.")
        }
        if (relativePath.any { it in ILLEGAL_CHARS }) {
            return Decision.Denied(Reject.NULL_BYTE, "Path contains a NUL byte.")
        }
        if (relativePath.length > MAX_PATH_LENGTH) {
            return Decision.Denied(Reject.TOO_LONG, "Path exceeds $MAX_PATH_LENGTH characters.")
        }

        val normalizedSeparators = relativePath.replace('\\', '/').trim()

        // Reject absolute POSIX paths and Windows drive letters.
        if (normalizedSeparators.startsWith("/") ||
            (normalizedSeparators.length >= 2 && normalizedSeparators[1] == ':' &&
                normalizedSeparators[0].isLetter())
        ) {
            return Decision.Denied(Reject.ABSOLUTE, "Absolute paths are not allowed: $relativePath")
        }
        if (normalizedSeparators.startsWith("~")) {
            return Decision.Denied(Reject.RESERVED_NAME, "Home-relative paths are not allowed.")
        }

        val segments = normalizedSeparators.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) {
            return Decision.Denied(Reject.EMPTY, "Path resolves to nothing.")
        }

        val resolvedSegments = ArrayList<String>(segments.size)
        for (raw in segments) {
            when (raw) {
                "." -> Unit // skip
                ".." -> {
                    if (resolvedSegments.isEmpty()) {
                        return Decision.Denied(
                            Reject.TRAVERSAL,
                            "Path escapes the workspace root: $relativePath",
                        )
                    }
                    resolvedSegments.removeAt(resolvedSegments.size - 1)
                }
                else -> {
                    if (raw.any { it in ILLEGAL_CHARS }) {
                        return Decision.Denied(Reject.NULL_BYTE, "Segment contains a NUL byte.")
                    }
                    resolvedSegments.add(raw)
                }
            }
        }
        if (resolvedSegments.isEmpty()) {
            return Decision.Denied(Reject.EMPTY, "Path resolves to the workspace root itself.")
        }
        if (resolvedSegments.any { it.uppercase() in RESERVED || it in RESERVED }) {
            return Decision.Denied(
                Reject.RESERVED_NAME,
                "Refusing to touch a reserved path segment: ${resolvedSegments.joinToString("/")}",
            )
        }

        val relative = resolvedSegments.joinToString(File.separator)
        val rootCanonical = runCatching { root.canonicalFile }.getOrElse { root.absoluteFile }
        val target = File(rootCanonical, relative)
        val targetCanonical = runCatching { target.canonicalFile }.getOrElse { target.absoluteFile }

        // Final belt-and-braces check: the canonical path must still be inside
        // the canonical root. This catches symlink tricks on filesystems that
        // support them.
        val rootPath = rootCanonical.path
        val targetPath = targetCanonical.path
        if (targetPath != rootPath && !targetPath.startsWith(rootPath + File.separator)) {
            return Decision.Denied(
                Reject.ESCAPES_ROOT,
                "Resolved path leaves the workspace: $relativePath",
            )
        }
        return Decision.Allowed(targetCanonical, resolvedSegments.joinToString("/"))
    }

    /** Convenience: true only when the path is safely inside [root]. */
    fun isSafe(root: File, relativePath: String): Boolean = resolve(root, relativePath) is Decision.Allowed

    /** Throw-style variant for call sites that treat a bad path as a bug. */
    fun requireSafe(root: File, relativePath: String): File = when (val d = resolve(root, relativePath)) {
        is Decision.Allowed -> d.file
        is Decision.Denied -> throw SecurityException("${d.reason}: ${d.detail}")
    }

    /** Relative path of [file] with respect to [root], or null if outside. */
    fun relativize(root: File, file: File): String? {
        val rootCanonical = runCatching { root.canonicalFile }.getOrElse { root.absoluteFile }
        val fileCanonical = runCatching { file.canonicalFile }.getOrElse { file.absoluteFile }
        val rp = rootCanonical.path
        val fp = fileCanonical.path
        if (fp == rp) return ""
        if (!fp.startsWith(rp + File.separator)) return null
        return fp.substring(rp.length + 1).replace(File.separatorChar, '/')
    }

    /**
     * Sanitize a display name into something safe to use as a single file name
     * (no separators, no traversal, bounded length).
     */
    fun sanitizeFileName(name: String, fallback: String = "file"): String {
        val cleaned = name
            .replace('\\', '_')
            .replace('/', '_')
            .replace('\u0000', '_')
            .trim()
            .trim('.')
        val bounded = if (cleaned.length > 120) cleaned.takeLast(120) else cleaned
        return if (bounded.isEmpty() || bounded in RESERVED) fallback else bounded
    }
}
