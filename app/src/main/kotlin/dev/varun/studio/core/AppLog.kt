package dev.varun.studio.core

import android.util.Log

/**
 * Logging with mandatory secret redaction.
 *
 * Requirement: "Secrets do not appear in logs or application source code."
 * Rather than trusting every call site to be careful, every message passes
 * through [redact], which scrubs anything that looks like a GitHub token, a
 * bearer header, a private key, or a query-string credential.
 *
 * In release builds only WARN and above reach logcat, and DEBUG is a no-op.
 */
object AppLog {

    private const val TAG = "Varun"

    /** Compile-time constant from BuildConfig; debug builds log more. */
    var verbose: Boolean = false

    // GitHub fine-grained PATs, classic PATs, OAuth tokens, JWTs.
    private val PATTERNS = listOf(
        Regex("github_pat_[A-Za-z0-9_]{8,}"),
        Regex("gh[pousr]_[A-Za-z0-9]{8,}"),
        Regex("(?i)\\b(bearer|token|authorization|api[_-]?key|secret|password|passwd|pwd)\\b\\s*[:=]\\s*\\S+"),
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
        Regex("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"),
        Regex("(?i)[?&](token|access_token|code|client_secret|key)=[^&\\s]+"),
    )

    /** Replace anything credential-shaped with a fixed marker. */
    fun redact(message: String): String {
        var out = message
        for (p in PATTERNS) {
            out = p.replace(out) { m ->
                // Keep the key name when it is visible so the log stays useful.
                val text = m.value
                val eq = text.indexOfFirst { it == '=' || it == ':' }
                if (eq > 0 && eq < 24) text.substring(0, eq + 1) + "[REDACTED]" else "[REDACTED]"
            }
        }
        return out
    }

    fun d(message: String) {
        if (!verbose) return
        Log.d(TAG, redact(message))
    }

    fun i(message: String) {
        if (!verbose) return
        Log.i(TAG, redact(message))
    }

    fun w(message: String, t: Throwable? = null) {
        Log.w(TAG, redact(message), t)
    }

    fun e(message: String, t: Throwable? = null) {
        Log.e(TAG, redact(message), t)
    }

    /**
     * Safe wrapper for exception messages, which can embed URLs with tokens in
     * query strings.
     */
    fun describeError(t: Throwable?): String {
        if (t == null) return "unknown error"
        return redact("${t.javaClass.simpleName}: ${t.message ?: ""}").trim()
    }
}
