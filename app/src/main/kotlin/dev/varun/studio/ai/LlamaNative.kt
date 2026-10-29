@file:JvmName("LlamaNative")

package dev.varun.studio.ai

// JNI declarations for libvarunllama.so.
//
// These are TOP-LEVEL functions so Kotlin compiles them to STATIC native
// methods on the class `dev.varun.studio.ai.LlamaNative`, which matches the
// `jclass` second parameter used in app/src/main/cpp/llama_bridge.cpp.
// Changing a signature here requires the same change in the bridge.

external fun nativeRuntimeVersion(): String

external fun nativeSupportsMmap(): Boolean

external fun nativeEstimateKvCacheBytes(nLayer: Int, nHeadKv: Int, nEmbd: Int, nCtx: Int): Long

/** Allocates a native session. Returns an opaque handle; 0 means failure. */
external fun nativeCreate(): Long

/** Frees a native session. Idempotent. */
external fun nativeDestroy(handle: Long)

external fun nativeIsLoaded(handle: Long): Boolean

/**
 * Loads a GGUF model.
 * @throws Exception with an actionable message when the load fails.
 */
external fun nativeLoad(
    handle: Long,
    modelPath: String,
    nCtx: Int,
    nBatch: Int,
    nThreads: Int,
    nThreadsBatch: Int,
    useMmap: Boolean,
    progress: LoadProgressCallback?,
): Boolean

external fun nativeUnload(handle: Long)

/** Requests cancellation; observed between generated tokens. */
external fun nativeCancel(handle: Long)

external fun nativeResetContext(handle: Long)

external fun nativeStatsLongs(handle: Long): LongArray

external fun nativeStatsDoubles(handle: Long): DoubleArray

external fun nativeStatsStrings(handle: Long): Array<String>

external fun nativeCountTokens(handle: Long, text: String): Int

external fun nativeApplyChatTemplate(
    handle: Long,
    roles: Array<String>,
    contents: Array<String>,
    addAssistantSuffix: Boolean,
): String

/**
 * Blocking generation. MUST NOT be called on the main thread.
 * @return the ordinal of [GenerationResultCode].
 * @throws Exception carrying the engine's error message, if any.
 */
external fun nativeGenerate(
    handle: Long,
    prompt: String,
    maxTokens: Int,
    temperature: Float,
    topK: Int,
    topP: Float,
    minP: Float,
    repeatPenalty: Float,
    repeatLastN: Int,
    seed: Long,
    stopStrings: Array<String>,
    applyChatTemplate: Boolean,
    callback: TokenCallback?,
): Int

/** Called per generated token. Return false to stop generation early. */
fun interface TokenCallback {
    fun onToken(piece: String, tokenId: Int, generated: Int): Boolean
}

/** Model-load progress in 0..1. */
fun interface LoadProgressCallback {
    fun onProgress(progress: Float)
}

/** Mirrors varun::llm::GenerateResult in llama_session.h. Order matters. */
enum class GenerationResultCode(val value: Int) {
    OK(0),
    CANCELLED(1),
    STOPPED(2),
    MAX_TOKENS(3),
    CONTEXT_FULL(4),
    DECODE_FAILED(5),
    OUT_OF_MEMORY(6),
    NOT_LOADED(7),
    INVALID_SAMPLER(8),
    UNKNOWN(-1);

    companion object {
        fun from(code: Int): GenerationResultCode =
            entries.firstOrNull { it.value == code } ?: UNKNOWN
    }
}
