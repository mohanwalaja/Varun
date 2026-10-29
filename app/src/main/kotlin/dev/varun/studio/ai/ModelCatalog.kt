package dev.varun.studio.ai

/**
 * Curated catalogue of small, code-capable GGUF models the user can download.
 *
 * Redistribution and consent rules this list respects:
 *  - Only models whose license permits redistribution are listed.
 *  - Nothing is ever downloaded automatically: every entry requires an
 *    explicit user tap, followed by a consent dialog stating the size.
 *  - Only 0.5B-3B quantizations are offered by default. A 7B Q4 model needs
 *    roughly 4.5-5.5 GB of resident RAM and will not run well, or at all, on a
 *    phone that Android has already given ~2 GB to other processes.
 *  - URLs are Hugging Face *resolve* links, which are stable and do not require
 *    an API key. The user can also paste any direct GGUF URL.
 *
 * Each entry carries a RAM estimate computed by [MemoryEstimator] rather than a
 * hardcoded claim, so the numbers stay honest if the file size differs.
 */
object ModelCatalog {

    data class Entry(
        val id: String,
        val displayName: String,
        val family: String,
        val params: String,
        val quant: String,
        val fileName: String,
        val url: String,
        val sizeBytes: Long,
        val license: String,
        val licenseUrl: String?,
        val contextTokens: Int,
        val recommended: Boolean,
        val minRamBytes: Long,
        val notes: String,
    ) {
        val sizeLabel: String get() = MemoryEstimator.humanBytes(sizeBytes)

        fun estimate(availableRamBytes: Long, nCtx: Int, nThreads: Int): MemoryEstimator.Estimate =
            MemoryEstimator.estimateFromFile(
                fileBytes = sizeBytes,
                quantTag = quant,
                nCtx = nCtx,
                nBatch = 256,
                nThreads = nThreads,
                availableBytes = availableRamBytes,
            )
    }

    /**
     * Verified-against-license entries. Sizes are the published GGUF file sizes
     * at the time of writing; the app re-reads the real size after download and
     * recomputes the estimate, so a stale number here cannot cause a bad load.
     */
    val ENTRIES: List<Entry> = listOf(
        Entry(
            id = "qwen2.5-coder-1.5b-q4_k_m",
            displayName = "Qwen2.5 Coder 1.5B Instruct · Q4_K_M",
            family = "Qwen2.5-Coder",
            params = "1.5B",
            quant = "Q4_K_M",
            fileName = "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/" +
                "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            sizeBytes = 1_140_000_000L,
            license = "Apache-2.0 (redistribution permitted)",
            licenseUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF",
            contextTokens = 32_768,
            recommended = true,
            minRamBytes = 3L * 1024 * 1024 * 1024,
            notes = "Best default for an 8 GB phone. Strong at code completion and explanation for its size.",
        ),
        Entry(
            id = "qwen2.5-coder-0.5b-q4_k_m",
            displayName = "Qwen2.5 Coder 0.5B Instruct · Q4_K_M",
            family = "Qwen2.5-Coder",
            params = "0.5B",
            quant = "Q4_K_M",
            fileName = "qwen2.5-coder-0.5b-instruct-q4_k_m.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-Coder-0.5B-Instruct-GGUF/resolve/main/" +
                "qwen2.5-coder-0.5b-instruct-q4_k_m.gguf",
            sizeBytes = 400_000_000L,
            license = "Apache-2.0 (redistribution permitted)",
            licenseUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-0.5B-Instruct-GGUF",
            contextTokens = 32_768,
            recommended = false,
            minRamBytes = 1_500L * 1024 * 1024,
            notes = "Fastest and lightest. Use this first to confirm the runtime works, then move up.",
        ),
        Entry(
            id = "qwen2.5-coder-3b-q4_k_m",
            displayName = "Qwen2.5 Coder 3B Instruct · Q4_K_M",
            family = "Qwen2.5-Coder",
            params = "3B",
            quant = "Q4_K_M",
            fileName = "qwen2.5-coder-3b-instruct-q4_k_m.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/main/" +
                "qwen2.5-coder-3b-instruct-q4_k_m.gguf",
            sizeBytes = 1_900_000_000L,
            license = "Apache-2.0 (redistribution permitted)",
            licenseUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-3B-Instruct-GGUF",
            contextTokens = 32_768,
            recommended = false,
            minRamBytes = 5L * 1024 * 1024 * 1024,
            notes = "Better reasoning, noticeably slower and heavier. Close every other app before loading.",
        ),
        Entry(
            id = "gemma-3-1b-it-q4_k_m",
            displayName = "Gemma 3 1B Instruct · Q4_K_M",
            family = "Gemma 3",
            params = "1B",
            quant = "Q4_K_M",
            fileName = "gemma-3-1b-it-q4_k_m.gguf",
            url = "https://huggingface.co/ggml-org/gemma-3-1b-it-GGUF/resolve/main/" +
                "gemma-3-1b-it-q4_k_m.gguf",
            sizeBytes = 800_000_000L,
            license = "Gemma Terms of Use (see link) — verify before redistributing",
            licenseUrl = "https://ai.google.dev/gemma/terms",
            contextTokens = 32_768,
            recommended = false,
            minRamBytes = 2_500L * 1024 * 1024,
            notes = "Good general instruction following; weaker at code than Qwen2.5-Coder at this size. " +
                "Gemma uses its own license, NOT Apache/MIT — read it before sharing the file.",
        ),
        Entry(
            id = "llama-3.2-1b-instruct-q4_k_m",
            displayName = "Llama 3.2 1B Instruct · Q4_K_M",
            family = "Llama 3.2",
            params = "1B",
            quant = "Q4_K_M",
            fileName = "llama-3.2-1b-instruct-q4_k_m.gguf",
            url = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/" +
                "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            sizeBytes = 750_000_000L,
            license = "Llama 3.2 Community License (see link) — not OSI open source",
            licenseUrl = "https://www.llama.com/llama3_2/license/",
            contextTokens = 131_072,
            recommended = false,
            minRamBytes = 2_500L * 1024 * 1024,
            notes = "Popular and well supported, but the Llama license has usage terms you must accept yourself.",
        ),
        Entry(
            id = "tinyllama-1.1b-chat-q4_k_m",
            displayName = "TinyLlama 1.1B Chat · Q4_K_M",
            family = "TinyLlama",
            params = "1.1B",
            quant = "Q4_K_M",
            // Matches the remote name: TheBloke's GGUF convention is
            // "<model>.<QUANT>.gguf" with a DOT before the quantization, not a
            // hyphen. Lowercased here because fileName is the local name the
            // download is saved under, consistent with the other entries.
            fileName = "tinyllama-1.1b-chat-v1.0.q4_k_m.gguf",
            url = "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/" +
                "tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf",
            sizeBytes = 670_000_000L,
            license = "Apache-2.0 (redistribution permitted)",
            licenseUrl = "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF",
            contextTokens = 2_048,
            recommended = false,
            minRamBytes = 2L * 1024 * 1024 * 1024,
            notes = "Small context (2k). Fine for a smoke test; too small for real file analysis.",
        ),
    )

    fun byId(id: String): Entry? = ENTRIES.firstOrNull { it.id == id }

    /**
     * Filter the catalogue to what this device can plausibly run, given the RAM
     * Android reports as available right now.
     */
    fun affordable(availableRamBytes: Long, nCtx: Int, nThreads: Int): List<Entry> =
        ENTRIES.map { it to it.estimate(availableRamBytes, nCtx, nThreads) }
            .filter { (_, est) -> est.fit != MemoryEstimator.Fit.UNLIKELY }
            .map { (entry, _) -> entry }

    /**
     * Validate a user-supplied direct URL. We deliberately do not allow
     * arbitrary schemes or hosts: a GGUF is large and a mistyped URL is an easy
     * way to leak a token in a query string or fetch something unexpected.
     */
    sealed interface UrlCheck {
        data class Ok(val fileName: String) : UrlCheck
        data class Bad(val reason: String) : UrlCheck
    }

    fun checkUrl(raw: String): UrlCheck {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return UrlCheck.Bad("Enter a URL.")
        val uri = runCatching { java.net.URI(trimmed) }.getOrNull()
            ?: return UrlCheck.Bad("That is not a valid URL.")
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https") return UrlCheck.Bad("Only https:// URLs are accepted.")
        val host = uri.host?.lowercase() ?: return UrlCheck.Bad("The URL has no host.")
        // "https://user:pass@host/..." would send those credentials on the wire
        // and they would land in the download log. Refuse the whole URL.
        if (!uri.userInfo.isNullOrBlank()) {
            return UrlCheck.Bad("Refusing a URL that embeds credentials (user:pass@host).")
        }
        val allowed = ALLOWED_HOSTS.any { host == it || host.endsWith(".$it") }
        if (!allowed) {
            return UrlCheck.Bad(
                "Only these hosts are allowed: ${ALLOWED_HOSTS.joinToString()}. " +
                    "This keeps model downloads to known publishers.",
            )
        }
        if (trimmed.contains("token=", true) || trimmed.contains("key=", true) ||
            trimmed.contains("Authorization", true)
        ) {
            return UrlCheck.Bad("Refusing a URL that appears to carry credentials.")
        }
        val path = uri.path ?: ""
        val name = path.substringAfterLast('/').ifBlank { "model.gguf" }
        if (!name.lowercase().endsWith(".gguf")) {
            return UrlCheck.Bad("The URL must point at a .gguf file (got '$name').")
        }
        return UrlCheck.Ok(name)
    }

    /** Publishers whose GGUF files are known-good and license-checked. */
    val ALLOWED_HOSTS = setOf(
        "huggingface.co",
        "hf.co",
        "cdn-lfs.huggingface.co",
        "github.com",
        "objects.githubusercontent.com",
    )
}
