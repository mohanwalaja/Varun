package dev.varun.studio.ai

/**
 * Loads libvarunllama.so exactly once and reports whether it is usable.
 *
 * A failure here is not fatal: the rest of the app (GitHub browsing, the file
 * workspace, the editor, diffs, git operations) all still work. Only on-device
 * inference is disabled, and the UI says so plainly instead of crashing.
 */
object NativeLoader {

    @Volatile
    private var state: LoadState = LoadState.NotAttempted

    private sealed interface LoadState {
        data object NotAttempted : LoadState
        data object Ready : LoadState
        data class Failed(val error: String) : LoadState
    }

    /** True when the native inference runtime is available. */
    val isAvailable: Boolean
        get() {
            ensureLoaded()
            return state is LoadState.Ready
        }

    /** Human-readable reason the runtime is unavailable, or null if it is fine. */
    val unavailableReason: String?
        get() {
            ensureLoaded()
            return (state as? LoadState.Failed)?.error
        }

    @Synchronized
    private fun ensureLoaded() {
        if (state != LoadState.NotAttempted) return
        state = try {
            System.loadLibrary("varunllama")
            LoadState.Ready
        } catch (t: Throwable) {
            // Includes UnsatisfiedLinkError when the ABI is not in this APK
            // (e.g. running an arm64-only build on an x86_64 emulator).
            LoadState.Failed(t.javaClass.simpleName + ": " + (t.message ?: "unknown"))
        }
    }
}
