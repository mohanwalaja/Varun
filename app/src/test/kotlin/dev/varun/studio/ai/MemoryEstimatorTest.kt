package dev.varun.studio.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MemoryEstimator decides whether a model may be loaded at all. Being wrong
 * here is what turns "load model" into an OOM kill on an 8 GB phone, so the
 * arithmetic is pinned by these tests.
 */
class MemoryEstimatorTest {

    private val MB = 1024L * 1024

    // ------------------------------------------------------------- KV cache

    @Test
    fun `kv cache matches the C++ formula exactly`() {
        // C++: 2.0 * n_layer * n_head_kv * (n_embd / n_head_kv) * n_ctx * 2.0
        // 28 layers, 4 kv heads, 1536 embd, 2048 ctx
        val expected = (2.0 * 28 * 4 * (1536.0 / 4.0) * 2048 * 2.0).toLong()
        assertEquals(expected, MemoryEstimator.estimateKvBytes(28, 4, 1536, 2048))
        assertEquals(352_321_536L, MemoryEstimator.estimateKvBytes(28, 4, 1536, 2048))
    }

    @Test
    fun `kv cache is zero for non-positive geometry`() {
        assertEquals(0L, MemoryEstimator.estimateKvBytes(0, 4, 1536, 2048))
        assertEquals(0L, MemoryEstimator.estimateKvBytes(28, 0, 1536, 2048))
        assertEquals(0L, MemoryEstimator.estimateKvBytes(28, 4, 0, 2048))
        assertEquals(0L, MemoryEstimator.estimateKvBytes(28, 4, 1536, 0))
    }

    @Test
    fun `kv cache scales linearly with context length`() {
        val small = MemoryEstimator.estimateKvBytes(28, 4, 1536, 512)
        val big = MemoryEstimator.estimateKvBytes(28, 4, 1536, 1024)
        assertEquals(small * 2, big)
    }

    // ------------------------------------------------------ compute buffers

    @Test
    fun `compute bytes are batch x embd x 4 x 8 with a 16 MB floor`() {
        assertEquals(512L * 2048 * 4 * 8, MemoryEstimator.estimateComputeBytes(512, 2048))
        // tiny inputs still get the floor
        assertEquals(16 * MB, MemoryEstimator.estimateComputeBytes(1, 1))
        // zero inputs fall back to the documented defaults (512, 2048)
        assertEquals(512L * 2048 * 4 * 8, MemoryEstimator.estimateComputeBytes(0, 0))
    }

    // ---------------------------------------------------------- quant tables

    @Test
    fun `bitsPerWeightFor reads the table and defaults to Q4_K_M`() {
        assertEquals(4.85, MemoryEstimator.bitsPerWeightFor("Q4_K_M"), 1e-9)
        assertEquals(8.5, MemoryEstimator.bitsPerWeightFor("Q8_0"), 1e-9)
        assertEquals(4.85, MemoryEstimator.bitsPerWeightFor(null), 1e-9)
        assertEquals(4.85, MemoryEstimator.bitsPerWeightFor("NOT_A_QUANT"), 1e-9)
        // case-insensitive lookup
        assertEquals(4.85, MemoryEstimator.bitsPerWeightFor("q4_k_m"), 1e-9)
    }

    @Test
    fun `detectQuant finds the longest matching tag in a filename`() {
        assertEquals("Q4_K_M", MemoryEstimator.detectQuant("qwen2.5-coder-1.5b-q4_k_m.gguf"))
        assertEquals("Q8_0", MemoryEstimator.detectQuant("model-Q8_0.gguf"))
        assertEquals("Q4_K_S", MemoryEstimator.detectQuant("a.Q4_K_S.gguf"))
        // a filename with no recognisable tag must report null, never a guess
        assertEquals(null, MemoryEstimator.detectQuant("model.gguf"))
        assertEquals(null, MemoryEstimator.detectQuant(""))
    }

    @Test
    fun `higher quant means more bits per weight`() {
        val q4 = MemoryEstimator.bitsPerWeightFor("Q4_K_M")
        val q8 = MemoryEstimator.bitsPerWeightFor("Q8_0")
        val f16 = MemoryEstimator.bitsPerWeightFor("F16")
        assertTrue(q4 < q8)
        assertTrue(q8 < f16)
    }

    // ----------------------------------------------------- parameter counts

    @Test
    fun `estimateParams subtracts the metadata allowance and scales with quant`() {
        val fileBytes = 1_140_000_000L
        val q4 = MemoryEstimator.estimateParams(fileBytes, 4.85)
        val q8 = MemoryEstimator.estimateParams(fileBytes, 8.5)
        // coarser quant -> fewer params for the same file size
        assertTrue("q4=$q4 should exceed q8=$q8", q4 > q8)
        // ~1.14 GB at 4.85 bpw is roughly 1.6-1.7 B params
        assertTrue("expected ~1.6B params, got $q4", q4 in 1_500_000_000L..1_800_000_000L)
    }

    @Test
    fun `estimateParams is zero for degenerate input`() {
        assertEquals(0L, MemoryEstimator.estimateParams(0, 4.85))
        assertEquals(0L, MemoryEstimator.estimateParams(-1, 4.85))
        assertEquals(0L, MemoryEstimator.estimateParams(1_000_000, 4.85)) // < 2 MB allowance
        assertEquals(0L, MemoryEstimator.estimateParams(1_000_000_000, 0.0))
    }

    // --------------------------------------------------------------- policy

    @Test
    fun `fitFor enforces the 60 and 85 percent headroom policy`() {
        assertEquals(MemoryEstimator.Fit.OK, MemoryEstimator.fitFor(600, 1000))
        assertEquals(MemoryEstimator.Fit.TIGHT, MemoryEstimator.fitFor(601, 1000))
        assertEquals(MemoryEstimator.Fit.TIGHT, MemoryEstimator.fitFor(850, 1000))
        assertEquals(MemoryEstimator.Fit.UNLIKELY, MemoryEstimator.fitFor(851, 1000))
        // degenerate inputs must never be reported as safe
        assertEquals(MemoryEstimator.Fit.UNLIKELY, MemoryEstimator.fitFor(0, 1000))
        assertEquals(MemoryEstimator.Fit.UNLIKELY, MemoryEstimator.fitFor(100, 0))
        assertEquals(MemoryEstimator.Fit.UNLIKELY, MemoryEstimator.fitFor(-5, 1000))
    }

    @Test
    fun `estimateFromFile adds model, kv, compute and overhead`() {
        val e = MemoryEstimator.estimateFromFile(
            fileBytes = 1_140_000_000L,
            quantTag = "Q4_K_M",
            nCtx = 2048,
            nBatch = 256,
            nThreads = 3,
            availableBytes = 4_000_000_000L,
        )
        assertEquals(1_140_000_000L, e.modelBytes)
        assertEquals(
            e.modelBytes + e.kvCacheBytes + e.computeBytes + e.overheadBytes,
            e.totalBytes,
        )
        // overhead is 48 MB base + 8 MB per thread
        assertEquals(48 * MB + 8 * MB * 3, e.overheadBytes)
        assertEquals(4_000_000_000L, e.availableBytes)
        assertEquals(MemoryEstimator.Fit.OK, e.fit)
        assertTrue(e.estimatedParams > 0)
        assertEquals(4.85, e.bitsPerWeight, 1e-9)
    }

    @Test
    fun `estimateFromFile reports UNLIKELY when the model will not fit`() {
        val e = MemoryEstimator.estimateFromFile(
            fileBytes = 4_000_000_000L,   // ~4 GB model
            quantTag = "Q4_K_M",
            nCtx = 8192,
            nBatch = 512,
            nThreads = 4,
            availableBytes = 2_000_000_000L,  // only 2 GB free
        )
        assertEquals(MemoryEstimator.Fit.UNLIKELY, e.fit)
        assertTrue(e.totalBytes > e.availableBytes)
    }

    @Test
    fun `thread count is clamped so a hostile value cannot shrink overhead`() {
        val lo = MemoryEstimator.estimateFromFile(500 * MB, "Q4_K_M", 2048, 256, 1, 8_000_000_000L)
        val zero = MemoryEstimator.estimateFromFile(500 * MB, "Q4_K_M", 2048, 256, 0, 8_000_000_000L)
        val huge = MemoryEstimator.estimateFromFile(500 * MB, "Q4_K_M", 2048, 256, 9999, 8_000_000_000L)
        // 0 clamps to 1; 9999 clamps to 16
        assertEquals(lo.overheadBytes, zero.overheadBytes)
        assertEquals(48 * MB + 8 * MB * 16, huge.overheadBytes)
    }

    @Test
    fun `estimateFromModel uses measured geometry and reports zero params`() {
        val e = MemoryEstimator.estimateFromModel(
            modelBytes = 900 * MB, nLayer = 28, nHeadKv = 4, nEmbd = 1536,
            nCtx = 2048, nBatch = 256, nThreads = 2, availableBytes = 4_000_000_000L,
        )
        assertEquals(900 * MB, e.modelBytes)
        assertEquals(MemoryEstimator.estimateKvBytes(28, 4, 1536, 2048), e.kvCacheBytes)
        // real geometry means the param estimate is no longer a guess
        assertEquals(0L, e.estimatedParams)
        assertEquals(0.0, e.bitsPerWeight, 1e-9)
    }

    @Test
    fun `maxAffordableContext returns the largest context that still fits`() {
        val ctx = MemoryEstimator.maxAffordableContext(
            modelBytes = 900 * MB, nLayer = 28, nHeadKv = 4, nEmbd = 1536,
            nBatch = 256, nThreads = 2, availableBytes = 1_600 * MB,
        )
        assertTrue("expected a usable context, got $ctx", ctx > 0)
        // and 0 when nothing can fit
        val none = MemoryEstimator.maxAffordableContext(
            modelBytes = 8_000 * MB, nLayer = 40, nHeadKv = 8, nEmbd = 5120,
            nBatch = 512, nThreads = 4, availableBytes = 200 * MB,
        )
        assertEquals(0, none)
    }

    @Test
    fun `maxAffordableContext grows as more RAM becomes available`() {
        val tight = MemoryEstimator.maxAffordableContext(900 * MB, 28, 4, 1536, 256, 2, 1_400 * MB)
        val roomy = MemoryEstimator.maxAffordableContext(900 * MB, 28, 4, 1536, 256, 2, 8_000 * MB)
        assertTrue("roomy=$roomy should be >= tight=$tight", roomy >= tight)
    }

    // -------------------------------------------------------- human strings

    @Test
    fun `humanBytes formats without losing the unit`() {
        assertEquals("0 B", MemoryEstimator.humanBytes(0))
        assertEquals("0 B", MemoryEstimator.humanBytes(-1))
        assertEquals("500 B", MemoryEstimator.humanBytes(500))
        assertEquals("1.0 KB", MemoryEstimator.humanBytes(1024))
        assertEquals("1.0 MB", MemoryEstimator.humanBytes(1024 * 1024))
        assertEquals("1.5 GB", MemoryEstimator.humanBytes((1.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `humanParams formats billions, millions and unknown`() {
        assertEquals("unknown", MemoryEstimator.humanParams(0))
        assertEquals("unknown", MemoryEstimator.humanParams(-1))
        assertEquals("1.50B", MemoryEstimator.humanParams(1_500_000_000))
        assertEquals("150M", MemoryEstimator.humanParams(150_000_000))
        assertEquals("999", MemoryEstimator.humanParams(999))
    }

    @Test
    fun `every catalogue quant tag is present in the bits-per-weight table`() {
        for (tag in listOf("Q2_K", "Q3_K_M", "Q4_K_M", "Q5_K_M", "Q6_K", "Q8_0")) {
            assertTrue("$tag missing from QUANT_BITS", MemoryEstimator.QUANT_BITS.containsKey(tag))
        }
    }
}
