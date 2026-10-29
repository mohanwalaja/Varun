package dev.varun.studio.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The download catalogue is the only place the app fetches anything large from
 * the internet, and it is the place a bad URL could leak a credential or pull
 * an unlicensed model. Every entry is validated here, and checkUrl is pinned
 * against the bypasses that matter.
 */
class ModelCatalogTest {

    private val GB = 1024L * 1024 * 1024

    // ------------------------------------------------------------ catalogue

    @Test
    fun `catalogue is small, curated and has exactly one default recommendation`() {
        assertEquals("the catalogue must stay small enough to vet by hand", 6, ModelCatalog.ENTRIES.size)
        val recommended = ModelCatalog.ENTRIES.filter { it.recommended }
        assertEquals("exactly one default recommendation", 1, recommended.size)
        assertEquals("qwen2.5-coder-1.5b-q4_k_m", recommended[0].id)
    }

    @Test
    fun `the recommended model is in the 1B-3B quantized range the brief requires`() {
        val rec = ModelCatalog.ENTRIES.first { it.recommended }
        assertEquals("Q4_K_M", rec.quant)
        assertTrue("should be a ~1.5B model", rec.sizeBytes in 500_000_000L..2_000_000_000L)
        assertEquals("1.5B", rec.params)
    }

    @Test
    fun `entry ids are unique and byId resolves them`() {
        val ids = ModelCatalog.ENTRIES.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        for (id in ids) {
            val e = ModelCatalog.byId(id)
            assertNotNull("byId($id) must resolve", e)
            assertEquals(id, e!!.id)
        }
        assertNull(ModelCatalog.byId("does-not-exist"))
        assertNull(ModelCatalog.byId(""))
    }

    @Test
    fun `every entry is complete enough for the user to give informed consent`() {
        for (e in ModelCatalog.ENTRIES) {
            assertTrue("${e.id}: displayName", e.displayName.isNotBlank())
            assertTrue("${e.id}: params", e.params.isNotBlank())
            assertTrue("${e.id}: quant", e.quant.isNotBlank())
            assertTrue("${e.id}: fileName", e.fileName.isNotBlank())
            assertTrue("${e.id}: license must be stated", e.license.isNotBlank())
            assertTrue("${e.id}: notes", e.notes.isNotBlank())
            assertTrue("${e.id}: size must be positive", e.sizeBytes > 0)
            assertTrue("${e.id}: context must be positive", e.contextTokens > 0)
            assertTrue("${e.id}: minRam must be positive", e.minRamBytes > 0)
            assertTrue("${e.id}: sizeLabel must render", e.sizeLabel.isNotBlank())
        }
    }

    @Test
    fun `every catalogue filename is a gguf`() {
        for (e in ModelCatalog.ENTRIES) {
            assertTrue("${e.id}: ${e.fileName} must be .gguf", e.fileName.lowercase().endsWith(".gguf"))
        }
    }

    @Test
    fun `every catalogue url is https, on an allowed host, and passes checkUrl`() {
        for (e in ModelCatalog.ENTRIES) {
            assertTrue("${e.id}: url must be https", e.url.startsWith("https://"))
            val check = ModelCatalog.checkUrl(e.url)
            assertTrue("${e.id}: url must validate, got $check", check is ModelCatalog.UrlCheck.Ok)
            assertEquals(
                "${e.id}: the url must actually point at the declared filename",
                e.fileName,
                (check as ModelCatalog.UrlCheck.Ok).fileName,
            )
            val host = java.net.URI(e.url).host.lowercase()
            assertTrue(
                "${e.id}: host $host must be on the allowlist",
                ModelCatalog.ALLOWED_HOSTS.any { host == it || host.endsWith(".$it") },
            )
        }
    }

    @Test
    fun `declared minimum RAM is plausible for the target 8 GB phone`() {
        for (e in ModelCatalog.ENTRIES) {
            // The whole point of minRamBytes is to warn BEFORE an OOM, so it
            // must exceed the download size (weights + KV + compute + overhead).
            assertTrue(
                "${e.id}: minRamBytes ${e.minRamBytes} must exceed the ${e.sizeBytes}-byte file",
                e.minRamBytes > e.sizeBytes,
            )
            // and must stay within a device the brief actually targets
            assertTrue(
                "${e.id}: minRamBytes ${e.minRamBytes} exceeds an 8 GB phone",
                e.minRamBytes <= 8 * GB,
            )
        }
    }

    @Test
    fun `the default recommendation is sized for an 8 GB phone, the 3B model is not assumed to fit`() {
        val rec = ModelCatalog.ENTRIES.first { it.recommended }
        assertTrue(
            "the recommendation must fit comfortably: ${rec.minRamBytes}",
            rec.minRamBytes <= 4 * GB,
        )
        val big = ModelCatalog.byId("qwen2.5-coder-3b-q4_k_m")
        assertNotNull(big)
        // 3B is offered but explicitly gated behind a larger RAM warning.
        assertTrue("3B must demand more RAM than the recommendation", big!!.minRamBytes > rec.minRamBytes)
        assertTrue(big.notes.contains("Close every other app", ignoreCase = true))
    }

    @Test
    fun `catalogue prefers permissively licensed models`() {
        val permissive = ModelCatalog.ENTRIES.count {
            it.license.contains("Apache", ignoreCase = true) || it.license.contains("MIT", ignoreCase = true)
        }
        assertTrue("expected most entries to be Apache/MIT, got $permissive", permissive >= 4)
        // the default recommendation must be redistribution-friendly
        assertTrue(ModelCatalog.ENTRIES.first { it.recommended }.license.contains("Apache"))
    }

    // ------------------------------------------------------------- checkUrl

    @Test
    fun `checkUrl accepts a well-formed https gguf url on an allowed host`() {
        val r = ModelCatalog.checkUrl(
            "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/model-q4_k_m.gguf",
        )
        assertTrue(r is ModelCatalog.UrlCheck.Ok)
        assertEquals("model-q4_k_m.gguf", (r as ModelCatalog.UrlCheck.Ok).fileName)
    }

    @Test
    fun `checkUrl accepts every host on the allowlist`() {
        for (host in ModelCatalog.ALLOWED_HOSTS) {
            val r = ModelCatalog.checkUrl("https://$host/some/path/model.gguf")
            assertTrue("$host must be accepted, got $r", r is ModelCatalog.UrlCheck.Ok)
        }
    }

    @Test
    fun `checkUrl accepts a subdomain of an allowed host`() {
        val r = ModelCatalog.checkUrl("https://cdn-lfs.huggingface.co/x/y/model.gguf")
        assertTrue(r is ModelCatalog.UrlCheck.Ok)
    }

    @Test
    fun `checkUrl rejects anything that is not https`() {
        for (u in listOf(
            "http://huggingface.co/a/b/model.gguf",
            "ftp://huggingface.co/a/b/model.gguf",
            "file:///sdcard/model.gguf",
            "huggingface.co/a/b/model.gguf",
        )) {
            val r = ModelCatalog.checkUrl(u)
            assertTrue("$u must be rejected, got $r", r is ModelCatalog.UrlCheck.Bad)
        }
    }

    @Test
    fun `checkUrl rejects hosts outside the allowlist`() {
        for (u in listOf(
            "https://evil.example.com/model.gguf",
            "https://huggingface.co.evil.com/model.gguf",   // suffix spoof
            "https://nothuggingface.co/model.gguf",
        )) {
            val r = ModelCatalog.checkUrl(u)
            assertTrue("$u must be rejected, got $r", r is ModelCatalog.UrlCheck.Bad)
        }
    }

    @Test
    fun `checkUrl rejects urls that appear to carry credentials`() {
        for (u in listOf(
            "https://huggingface.co/a/b/model.gguf?token=hf_secret123",
            "https://huggingface.co/a/b/model.gguf?api_key=abc",
            "https://huggingface.co/a/b/model.gguf?Authorization=Bearer%20xyz",
            "https://user:pass@huggingface.co/a/b/model.gguf",
        )) {
            val r = ModelCatalog.checkUrl(u)
            assertTrue("$u must be rejected as credential-bearing, got $r", r is ModelCatalog.UrlCheck.Bad)
            if (r is ModelCatalog.UrlCheck.Bad) {
                assertTrue(
                    "reason must mention credentials: ${r.reason}",
                    r.reason.contains("credential", ignoreCase = true) ||
                        r.reason.contains("host", ignoreCase = true) ||
                        r.reason.contains("gguf", ignoreCase = true),
                )
            }
        }
    }

    @Test
    fun `checkUrl rejects urls that do not point at a gguf`() {
        for (u in listOf(
            "https://huggingface.co/a/b/model.bin",
            "https://huggingface.co/a/b/model.safetensors",
            "https://huggingface.co/a/b/archive.zip",
            "https://huggingface.co/a/b/noextension",
        )) {
            val r = ModelCatalog.checkUrl(u)
            assertTrue("$u must be rejected, got $r", r is ModelCatalog.UrlCheck.Bad)
            assertTrue((r as ModelCatalog.UrlCheck.Bad).reason.contains(".gguf"))
        }
    }

    @Test
    fun `checkUrl rejects empty and malformed input without throwing`() {
        assertTrue(ModelCatalog.checkUrl("") is ModelCatalog.UrlCheck.Bad)
        assertTrue(ModelCatalog.checkUrl("   ") is ModelCatalog.UrlCheck.Bad)
        assertTrue(ModelCatalog.checkUrl("not a url at all") is ModelCatalog.UrlCheck.Bad)
        assertTrue(ModelCatalog.checkUrl("https://") is ModelCatalog.UrlCheck.Bad)
    }

    @Test
    fun `checkUrl trims surrounding whitespace`() {
        val r = ModelCatalog.checkUrl("  https://huggingface.co/a/b/model.gguf  \n")
        assertTrue("leading and trailing whitespace must be tolerated, got $r", r is ModelCatalog.UrlCheck.Ok)
    }

    @Test
    fun `checkUrl is case-insensitive about the gguf extension`() {
        assertTrue(ModelCatalog.checkUrl("https://huggingface.co/a/b/MODEL.GGUF") is ModelCatalog.UrlCheck.Ok)
    }

    @Test
    fun `every rejection carries a human-readable reason`() {
        val bad = ModelCatalog.checkUrl("http://evil.com/x.gguf") as ModelCatalog.UrlCheck.Bad
        assertTrue("reason must be a real sentence: ${bad.reason}", bad.reason.length > 10)
    }

    // ------------------------------------------------------------ affordability

    @Test
    fun `affordable includes the recommendation on a roomy 8 GB phone`() {
        val list = ModelCatalog.affordable(availableRamBytes = 4 * GB, nCtx = 2048, nThreads = 3)
        assertTrue("the 0.5B model must be affordable", list.any { it.id.contains("0.5b") })
        for (e in list) {
            val fit = e.estimate(4 * GB, 2048, 3).fit
            assertFalse("$e must not be UNLIKELY", fit == MemoryEstimator.Fit.UNLIKELY)
        }
    }

    @Test
    fun `affordable excludes large models when very little RAM is free`() {
        val list = ModelCatalog.affordable(availableRamBytes = 300L * 1024 * 1024, nCtx = 2048, nThreads = 2)
        assertTrue("a 300 MB budget cannot run any catalogue model", list.isEmpty())
    }

    @Test
    fun `affordable grows as more RAM becomes available`() {
        val tight = ModelCatalog.affordable(1_200L * 1024 * 1024, 2048, 2).size
        val roomy = ModelCatalog.affordable(6L * GB, 2048, 3).size
        assertTrue("roomy=$roomy must be >= tight=$tight", roomy >= tight)
    }

    @Test
    fun `entry estimate matches a direct call to MemoryEstimator`() {
        val e = ModelCatalog.ENTRIES.first()
        val viaEntry = e.estimate(3 * GB, 2048, 3)
        val direct = MemoryEstimator.estimateFromFile(
            fileBytes = e.sizeBytes,
            quantTag = e.quant,
            nCtx = 2048,
            nBatch = 256,
            nThreads = 3,
            availableBytes = 3 * GB,
        )
        assertEquals(direct.totalBytes, viaEntry.totalBytes)
        assertEquals(direct.kvCacheBytes, viaEntry.kvCacheBytes)
        assertEquals(direct.fit, viaEntry.fit)
    }

    @Test
    fun `sizeLabel is a readable human size, not a raw byte count`() {
        val label = ModelCatalog.ENTRIES.first { it.recommended }.sizeLabel
        assertTrue("expected a GB-scale label, got '$label'", label.endsWith("GB") || label.endsWith("MB"))
        assertFalse(label == ModelCatalog.ENTRIES.first { it.recommended }.sizeBytes.toString())
    }
}
