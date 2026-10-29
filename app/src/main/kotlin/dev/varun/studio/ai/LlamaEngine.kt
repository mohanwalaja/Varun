package dev.varun.studio.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Everything needed to load a GGUF into RAM. */
data class ModelLoadConfig(
    val modelPath: String,
    val nCtx: Int = 2048,
    val nBatch: Int = 512,
    val nThreads: Int = 4,
    val nThreadsBatch: Int = 4,
    val useMmap: Boolean = true,
)

/** Sampling parameters for one request. */
data class GenerateConfig(
    val maxTokens: Int = 256,
    val temperature: Float = 0.2f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.1f,
    val repeatLastN: Int = 64,
    val seed: Long = 0L,
    val stopStrings: List<String> = emptyList(),
    val useChatTemplate: Boolean = true,
)

/** Snapshot of native engine state, decoded from the JNI stats arrays. */
data class EngineStats(
    val loaded: Boolean = false,
    val description: String = "",
    val ftype: String = "",
    val nVocab: Int = 0,
    val nCtx: Int = 0,
    val nCtxTrain: Int = 0,
    val nEmbd: Int = 0,
    val nLayer: Int = 0,
    val nParams: Long = 0,
    val modelBytes: Long = 0,
    val nThreads: Int = 0,
    val lastPromptTokens: Int = 0,
    val lastGeneratedTokens: Int = 0,
    val lastPromptMs: Long = 0,
    val lastGenerateMs: Long = 0,
    val promptTokensPerSec: Double = 0.0,
    val tokensPerSec: Double = 0.0,
)

/** Why a load failed, so the UI can give actionable advice. */
enum class EngineFailureKind {
    RUNTIME_UNAVAILABLE,
    OUT_OF_MEMORY,
    CORRUPT_OR_UNSUPPORTED,
    CONTEXT_ALLOCATION,
    ALREADY_LOADED,
    BAD_PATH,
    OTHER,
}

sealed interface EngineLoadResult {
    data class Success(val stats: EngineStats) : EngineLoadResult
    data class Failure(val kind: EngineFailureKind, val message: String) : EngineLoadResult
}

data class GenerateOutcome(
    val code: GenerationResultCode,
    val tokensGenerated: Int,
    val error: String?,
    val stats: EngineStats,
) {
    val succeeded: Boolean
        get() = code == GenerationResultCode.OK ||
            code == GenerationResultCode.STOPPED ||
            code == GenerationResultCode.MAX_TOKENS ||
            code == GenerationResultCode.CANCELLED
}

/** A (role, content) chat turn. */
data class ChatTurn(val role: String, val content: String)

/**
 * Single point of contact with the native llama.cpp runtime.
 *
 * Contract:
 *  - every blocking native call is confined to [io] (never the main thread);
 *  - at most one model is loaded at a time, guarded by [mutex];
 *  - [cancel] is safe to call from any thread while generation runs;
 *  - all failures are returned as values, not thrown, so a bad model or an
 *    OOM degrades the UI instead of crashing the app.
 *
 * This class has no Android framework dependencies, so it is unit-testable.
 */
class LlamaEngine(private val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO) {

    private val mutex = Mutex()

    @Volatile
    private var handle: Long = 0L

    @Volatile
    private var loadedPath: String? = null

    val runtimeVersion: String
        get() = if (NativeLoader.isAvailable) runCatching { nativeRuntimeVersion() }.getOrDefault("unknown")
        else "unavailable"

    val supportsMmap: Boolean
        get() = NativeLoader.isAvailable && runCatching { nativeSupportsMmap() }.getOrDefault(true)

    val isRuntimeAvailable: Boolean get() = NativeLoader.isAvailable

    val runtimeUnavailableReason: String? get() = NativeLoader.unavailableReason

    val currentModelPath: String? get() = loadedPath

    val isLoaded: Boolean
        get() {
            val h = handle
            if (h == 0L || !NativeLoader.isAvailable) return false
            return runCatching { nativeIsLoaded(h) }.getOrDefault(false)
        }

    private fun ensureHandle(): Long {
        if (handle != 0L) return handle
        val h = if (NativeLoader.isAvailable) runCatching { nativeCreate() }.getOrDefault(0L) else 0L
        handle = h
        return h
    }

    fun stats(): EngineStats {
        val h = handle
        if (h == 0L || !NativeLoader.isAvailable) return EngineStats()
        return runCatching { decodeStats(h) }.getOrElse { EngineStats() }
    }

    private fun decodeStats(h: Long): EngineStats {
        val longs = nativeStatsLongs(h)
        val doubles = nativeStatsDoubles(h)
        val strings = nativeStatsStrings(h)
        if (longs.size < 13) return EngineStats()
        return EngineStats(
            loaded = longs[0] != 0L,
            description = strings.getOrElse(0) { "" },
            ftype = strings.getOrElse(1) { "" },
            nVocab = longs[1].toInt(),
            nCtx = longs[2].toInt(),
            nCtxTrain = longs[3].toInt(),
            nEmbd = longs[4].toInt(),
            nLayer = longs[5].toInt(),
            nParams = longs[6],
            modelBytes = longs[7],
            nThreads = longs[8].toInt(),
            lastPromptTokens = longs[9].toInt(),
            lastGeneratedTokens = longs[10].toInt(),
            lastPromptMs = longs[11],
            lastGenerateMs = longs[12],
            promptTokensPerSec = doubles.getOrElse(0) { 0.0 },
            tokensPerSec = doubles.getOrElse(1) { 0.0 },
        )
    }

    /**
     * Load a GGUF. Returns a [EngineLoadResult] rather than throwing.
     * [onProgress] is invoked from the IO thread with 0..1 values.
     */
    suspend fun load(
        config: ModelLoadConfig,
        onProgress: (Float) -> Unit = {},
    ): EngineLoadResult = mutex.withLock {
        if (!NativeLoader.isAvailable) {
            return@withLock EngineLoadResult.Failure(
                EngineFailureKind.RUNTIME_UNAVAILABLE,
                NativeLoader.unavailableReason
                    ?: "The native inference runtime is not available on this device/ABI.",
            )
        }
        if (config.modelPath.isBlank()) {
            return@withLock EngineLoadResult.Failure(EngineFailureKind.BAD_PATH, "Model path is empty.")
        }
        val h = ensureHandle()
        if (h == 0L) {
            return@withLock EngineLoadResult.Failure(
                EngineFailureKind.OTHER,
                "Could not allocate a native inference session.",
            )
        }
        withContext(io) {
            try {
                val ok = nativeLoad(
                    h,
                    config.modelPath,
                    config.nCtx.coerceAtLeast(256),
                    config.nBatch.coerceIn(32, 4096),
                    config.nThreads.coerceIn(1, 16),
                    config.nThreadsBatch.coerceIn(1, 16),
                    config.useMmap,
                    LoadProgressCallback { p -> onProgress(p.coerceIn(0f, 1f)) },
                )
                if (ok) {
                    loadedPath = config.modelPath
                    EngineLoadResult.Success(decodeStats(h))
                } else {
                    EngineLoadResult.Failure(EngineFailureKind.OTHER, "Load returned false.")
                }
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                loadedPath = null
                EngineLoadResult.Failure(classifyLoadFailure(msg), msg)
            }
        }
    }

    /** Frees model + KV-cache memory. Safe to call repeatedly. */
    suspend fun unload() = mutex.withLock {
        val h = handle
        loadedPath = null
        if (h == 0L || !NativeLoader.isAvailable) return@withLock
        withContext(io) {
            runCatching {
                nativeCancel(h)
                nativeUnload(h)
            }
        }
        Unit
    }

    /** Hard release: unloads and destroys the native session. */
    fun release() {
        val h = handle
        handle = 0L
        loadedPath = null
        if (h == 0L || !NativeLoader.isAvailable) return
        runCatching {
            nativeCancel(h)
            nativeUnload(h)
            nativeDestroy(h)
        }
    }

    /** Ask an in-flight generation to stop. Non-blocking, thread-safe. */
    fun cancel() {
        val h = handle
        if (h == 0L || !NativeLoader.isAvailable) return
        runCatching { nativeCancel(h) }
    }

    fun resetContext() {
        val h = handle
        if (h == 0L || !NativeLoader.isAvailable) return
        runCatching { nativeResetContext(h) }
    }

    /** Token count for a string, used for budgeting context. 0 if unavailable. */
    fun countTokens(text: String): Int {
        val h = handle
        if (h == 0L || !NativeLoader.isAvailable || text.isEmpty()) return 0
        return runCatching { nativeCountTokens(h, text) }.getOrDefault(0)
    }

    fun applyChatTemplate(turns: List<ChatTurn>, addAssistantSuffix: Boolean): String {
        val h = handle
        if (h == 0L || !NativeLoader.isAvailable || turns.isEmpty()) return ""
        return runCatching {
            nativeApplyChatTemplate(
                h,
                turns.map { it.role }.toTypedArray(),
                turns.map { it.content }.toTypedArray(),
                addAssistantSuffix,
            )
        }.getOrDefault("")
    }

    /**
     * Generate a completion. Blocking; runs on [io].
     *
     * [onToken] receives each piece, its id and the running count, and returns
     * false to stop. It is called on the IO worker, so callers must only touch
     * thread-safe state (a MutableStateFlow is fine).
     *
     * Coroutine cancellation is bridged to [nativeCancel], so cancelling the
     * enclosing job stops token generation promptly.
     */
    suspend fun generate(
        prompt: String,
        config: GenerateConfig,
        onToken: (piece: String, tokenId: Int, generated: Int) -> Boolean = { _, _, _ -> true },
    ): GenerateOutcome {
        val h = handle
        if (h == 0L || !NativeLoader.isAvailable) {
            return GenerateOutcome(GenerationResultCode.NOT_LOADED, 0, "No model is loaded.", EngineStats())
        }
        if (prompt.isEmpty()) {
            return GenerateOutcome(GenerationResultCode.DECODE_FAILED, 0, "Prompt is empty.", stats())
        }
        val job = coroutineContext[Job]
        return withContext(io) {
            job?.invokeOnCompletion { cause -> if (cause != null) cancel() }
            var error: String? = null
            var produced = 0
            val code = try {
                GenerationResultCode.from(
                    nativeGenerate(
                        h,
                        prompt,
                        config.maxTokens.coerceIn(1, 32_768),
                        config.temperature.coerceIn(0f, 2f),
                        config.topK.coerceIn(0, 1_000),
                        config.topP.coerceIn(0f, 1f),
                        config.minP.coerceIn(0f, 1f),
                        config.repeatPenalty.coerceIn(0f, 3f),
                        config.repeatLastN.coerceIn(0, 4096),
                        config.seed,
                        config.stopStrings.toTypedArray(),
                        config.useChatTemplate,
                        TokenCallback { piece, tokenId, generated ->
                            val stillWanted = job?.isActive ?: true
                            if (!stillWanted) {
                                false
                            } else {
                                produced = generated
                                onToken(piece, tokenId, generated)
                            }
                        },
                    ),
                )
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
                GenerationResultCode.UNKNOWN
            }
            GenerateOutcome(code, produced, error, stats())
        }
    }

    companion object {
        /**
         * Maps a native error message onto a failure kind so the UI can offer
         * the right remedy. Matching is deliberately keyword-based and
         * case-insensitive because llama.cpp messages vary between releases.
         */
        fun classifyLoadFailure(message: String): EngineFailureKind {
            val m = message.lowercase()
            return when {
                "already loaded" in m -> EngineFailureKind.ALREADY_LOADED
                "kv cache" in m || "could not be allocated" in m || "out of memory" in m ||
                    "alloc" in m && "context" in m -> EngineFailureKind.CONTEXT_ALLOCATION
                "not enough" in m || "insufficient" in m || "bad_alloc" in m ||
                    "oom" in m || "cannot allocate" in m || "failed to allocate" in m ->
                    EngineFailureKind.OUT_OF_MEMORY
                "could not load" in m || "corrupt" in m || "unsupported" in m ||
                    "unknown model architecture" in m || "invalid model" in m ->
                    EngineFailureKind.CORRUPT_OR_UNSUPPORTED
                "path is empty" in m || "no such file" in m || "not found" in m ->
                    EngineFailureKind.BAD_PATH
                else -> EngineFailureKind.OTHER
            }
        }
    }
}
