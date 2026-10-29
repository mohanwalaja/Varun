package dev.varun.studio.ai

import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * Pure-Kotlin memory maths for deciding whether a GGUF will actually fit.
 *
 * "The phone has 8 GB of RAM" is NOT sufficient reasoning: Android reserves a
 * large slice for the system, each process has a per-app heap ceiling, and a
 * llama.cpp context needs weights + KV cache + compute buffers at once. This
 * estimator is intentionally conservative and is unit-tested.
 *
 * No Android dependencies, so it runs on a JVM in CI.
 */
object MemoryEstimator {

    enum class Fit {
        /** Comfortable: leaves headroom for the OS and other apps. */
        OK,

        /** Might work, but close other apps and expect pressure. */
        TIGHT,

        /** Very likely to fail or thrash. Do not auto-load. */
        UNLIKELY,
    }

    data class Estimate(
        val modelBytes: Long,
        val kvCacheBytes: Long,
        val computeBytes: Long,
        val overheadBytes: Long,
        val totalBytes: Long,
        val availableBytes: Long,
        val fit: Fit,
        val estimatedParams: Long,
        val bitsPerWeight: Double,
    )

    /** Approximate bits-per-weight for common llama.cpp quantizations. */
    val QUANT_BITS: Map<String, Double> = linkedMapOf(
        "IQ1" to 1.7,
        "Q2_K" to 2.9,
        "IQ2" to 2.6,
        "Q3_K_S" to 3.5,
        "Q3_K_M" to 3.9,
        "Q3_K_L" to 4.3,
        "IQ3" to 3.6,
        "Q4_0" to 4.5,
        "Q4_K_S" to 4.6,
        "IQ4_XS" to 4.3,
        "IQ4_NL" to 4.5,
        "Q4_K_M" to 4.85,
        "Q4_1" to 5.0,
        "Q5_0" to 5.4,
        "Q5_K_S" to 5.6,
        "Q5_K_M" to 5.7,
        "Q5_1" to 6.0,
        "Q6_K" to 6.6,
        "Q8_0" to 8.5,
        "F16" to 16.5,
        "BF16" to 16.5,
        "F32" to 32.5,
    )

    private const val BYTES_PER_BIT = 1.0 / 8.0

    /** Reserved for the runtime itself: thread stacks, ggml graph, JNI, allocators. */
    private const val BASE_OVERHEAD_BYTES = 48L * 1024 * 1024

    /** Extra scratch per inference thread (ggml worker buffers). */
    private const val PER_THREAD_BYTES = 8L * 1024 * 1024

    /**
     * Recognises a quantization tag inside a GGUF file name.
     * Matching is case-insensitive and prefers the longest tag so that
     * "q4_k_m" wins over "q4".
     */
    fun detectQuant(fileName: String): String? {
        val upper = fileName.uppercase().replace('.', '_').replace('-', '_')
        return QUANT_BITS.keys
            .map { it.replace('.', '_') }
            .filter { upper.contains(it.uppercase()) }
            .maxByOrNull { it.length }
    }

    fun bitsPerWeightFor(quantTag: String?): Double =
        quantTag?.let { q -> QUANT_BITS[q.uppercase().replace('.', '_')] } ?: 4.85

    /**
     * Estimate parameter count from the on-disk size and the quantization.
     * Subtracts the vocabulary/embedding overhead roughly (a few MB) so the
     * figure is not wildly optimistic for very small models.
     */
    fun estimateParams(fileBytes: Long, bitsPerWeight: Double): Long {
        if (fileBytes <= 0 || bitsPerWeight <= 0) return 0
        val weightBytes = fileBytes - (2L * 1024 * 1024) // metadata + vocab + alignment
        if (weightBytes <= 0) return 0
        return (weightBytes / (bitsPerWeight * BYTES_PER_BIT)).roundToLong()
    }

    /**
     * KV cache bytes for a decoder-only transformer with a 16-bit cache.
     * Mirrors varun::llm::Session::EstimateKvCacheBytes in C++ so the on-device
     * and pre-load numbers agree.
     */
    fun estimateKvBytes(nLayer: Int, nHeadKv: Int, nEmbd: Int, nCtx: Int): Long {
        if (nLayer <= 0 || nHeadKv <= 0 || nEmbd <= 0 || nCtx <= 0) return 0
        val headDim = nEmbd.toDouble() / nHeadKv.toDouble()
        // K and V, 2 bytes per value.
        return ceil(2.0 * nLayer * nHeadKv * headDim * nCtx * 2.0).toLong()
    }

    /**
     * Compute/graph buffers: dominated by the prompt-processing batch.
     * n_batch * n_embd * sizeof(float) * a small constant factor, floored.
     */
    fun estimateComputeBytes(nBatch: Int, nEmbd: Int): Long {
        val b = if (nBatch > 0) nBatch else 512
        val e = if (nEmbd > 0) nEmbd else 2048
        val raw = b.toLong() * e.toLong() * 4L * 8L
        return raw.coerceAtLeast(16L * 1024 * 1024)
    }

    /**
     * Fallback estimate before a model is loaded, using only the file size, a
     * guessed architecture and the requested context length.
     */
    fun estimateFromFile(
        fileBytes: Long,
        quantTag: String?,
        nCtx: Int,
        nBatch: Int,
        nThreads: Int,
        availableBytes: Long,
        assumedParams: Long? = null,
    ): Estimate {
        val bpw = bitsPerWeightFor(quantTag)
        val params = assumedParams ?: estimateParams(fileBytes, bpw)

        // Typical 1B-3B decoder geometry. Used only to size the KV cache before
        // the real header is known; replaced by measured values after load.
        val (layers, headKv, embd) = typicalGeometry(params)
        val kv = estimateKvBytes(layers, headKv, embd, nCtx)
        val compute = estimateComputeBytes(nBatch, embd)
        val overhead = BASE_OVERHEAD_BYTES + PER_THREAD_BYTES * nThreads.coerceIn(1, 16)
        val total = fileBytes + kv + compute + overhead
        return Estimate(
            modelBytes = fileBytes,
            kvCacheBytes = kv,
            computeBytes = compute,
            overheadBytes = overhead,
            totalBytes = total,
            availableBytes = availableBytes,
            fit = fitFor(total, availableBytes),
            estimatedParams = params,
            bitsPerWeight = bpw,
        )
    }

    /** Re-estimate using real geometry read from the loaded GGUF header. */
    fun estimateFromModel(
        modelBytes: Long,
        nLayer: Int,
        nHeadKv: Int,
        nEmbd: Int,
        nCtx: Int,
        nBatch: Int,
        nThreads: Int,
        availableBytes: Long,
    ): Estimate {
        val kv = estimateKvBytes(nLayer, nHeadKv, nEmbd, nCtx)
        val compute = estimateComputeBytes(nBatch, nEmbd)
        val overhead = BASE_OVERHEAD_BYTES + PER_THREAD_BYTES * nThreads.coerceIn(1, 16)
        val total = modelBytes + kv + compute + overhead
        return Estimate(
            modelBytes = modelBytes,
            kvCacheBytes = kv,
            computeBytes = compute,
            overheadBytes = overhead,
            totalBytes = total,
            availableBytes = availableBytes,
            fit = fitFor(total, availableBytes),
            estimatedParams = 0,
            bitsPerWeight = 0.0,
        )
    }

    /**
     * Headroom policy. Android kills processes that push the system into
     * pressure, so we require meaningfully more free RAM than the estimate.
     */
    fun fitFor(totalBytes: Long, availableBytes: Long): Fit {
        if (availableBytes <= 0 || totalBytes <= 0) return Fit.UNLIKELY
        val ratio = totalBytes.toDouble() / availableBytes.toDouble()
        return when {
            ratio <= 0.60 -> Fit.OK
            ratio <= 0.85 -> Fit.TIGHT
            else -> Fit.UNLIKELY
        }
    }

    /**
     * Largest context length that should still fit, given a loaded model's
     * geometry and the RAM currently available. Returns 0 when even the
     * minimum context does not fit.
     */
    fun maxAffordableContext(
        modelBytes: Long,
        nLayer: Int,
        nHeadKv: Int,
        nEmbd: Int,
        nBatch: Int,
        nThreads: Int,
        availableBytes: Long,
        candidates: List<Int> = listOf(512, 1024, 2048, 4096, 8192, 16384),
    ): Int {
        var best = 0
        for (ctx in candidates.sorted()) {
            val e = estimateFromModel(modelBytes, nLayer, nHeadKv, nEmbd, ctx, nBatch, nThreads, availableBytes)
            if (e.fit != Fit.UNLIKELY) best = ctx else break
        }
        return best
    }

    /** Rough (layers, kv-heads, embedding) for common small decoder models. */
    private fun typicalGeometry(params: Long): Triple<Int, Int, Int> = when {
        params <= 0L -> Triple(2, 4, 64)
        params < 300_000_000L -> Triple(16, 4, 512)
        params < 800_000_000L -> Triple(24, 4, 896)     // ~0.5B class
        params < 1_800_000_000L -> Triple(28, 4, 1536)  // ~1.5B class
        params < 3_500_000_000L -> Triple(36, 8, 2048)  // ~3B class
        params < 8_000_000_000L -> Triple(32, 8, 4096)  // ~7-8B class
        else -> Triple(40, 8, 5120)
    }

    fun humanBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024.0
            i++
        }
        return if (i == 0) "${v.roundToLong()} ${units[i]}" else String.format(java.util.Locale.US, "%.1f %s", v, units[i])
    }

    fun humanParams(params: Long): String = when {
        params <= 0 -> "unknown"
        params >= 1_000_000_000L -> String.format(java.util.Locale.US, "%.2fB", params / 1_000_000_000.0)
        params >= 1_000_000L -> String.format(java.util.Locale.US, "%.0fM", params / 1_000_000.0)
        else -> "$params"
    }
}
