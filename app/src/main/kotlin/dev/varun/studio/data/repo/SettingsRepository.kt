package dev.varun.studio.data.repo

import android.content.Context
import dev.varun.studio.ai.GenerateConfig
import dev.varun.studio.ai.MemoryEstimator
import dev.varun.studio.data.db.VarunDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * User preferences.
 *
 * Non-sensitive values live in SQLite (so they survive a data wipe of shared
 * prefs and are easy to inspect); the GitHub token lives only in [SecureVault].
 * Exposed as a StateFlow so Compose recomposes when a limit changes.
 */
class SettingsRepository(private val context: Context, private val db: VarunDatabase) {

    data class Settings(
        // Inference
        val contextLength: Int = 2048,
        val maxTokens: Int = 384,
        val temperature: Float = 0.2f,
        val topK: Int = 40,
        val topP: Float = 0.95f,
        val repeatPenalty: Float = 1.1f,
        val threads: Int = 3,
        val batch: Int = 256,
        val useMmap: Boolean = true,
        val historyTurns: Int = 6,
        val keepModelLoaded: Boolean = false,
        // Safety limits
        val maxFileKb: Int = 96,
        val maxFilesPerRequest: Int = 4,
        val maxWorkspaceImportMb: Int = 128,
        val requireConfirmBeforePush: Boolean = true,
        val refuseDefaultBranchCommits: Boolean = true,
        val autoTrimHistory: Boolean = true,
        val warnOnThermal: Boolean = true,
        val pauseOnBatterySaver: Boolean = false,
        // Appearance
        val themeMode: String = "system",     // system | light | dark
        val languageMode: String = "system",  // system | en | ta
        val fontSizeScale: Float = 1.0f,
        // Diagnostics
        val verboseLogging: Boolean = false,
        // Last used
        val lastModelId: Long? = null,
        val lastWorkspacePath: String? = null,
        val lastGithubOwner: String? = null,
        val lastGithubRepo: String? = null,
    ) {
        fun generateConfig(maxTokensOverride: Int? = null) = GenerateConfig(
            maxTokens = maxTokensOverride ?: maxTokens,
            temperature = temperature,
            topK = topK,
            topP = topP,
            repeatPenalty = repeatPenalty,
            repeatLastN = 64,
        )

    }

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        val all = db.allSettings()
        _settings.value = Settings(
            contextLength = all.int(K_CTX, 2048),
            maxTokens = all.int(K_MAX_TOKENS, 384),
            temperature = all.float(K_TEMP, 0.2f),
            topK = all.int(K_TOP_K, 40),
            topP = all.float(K_TOP_P, 0.95f),
            repeatPenalty = all.float(K_REPEAT, 1.1f),
            threads = all.int(K_THREADS, defaultThreads()),
            batch = all.int(K_BATCH, 256),
            useMmap = all.bool(K_MMAP, true),
            historyTurns = all.int(K_HISTORY, 6),
            keepModelLoaded = all.bool(K_KEEP_LOADED, false),
            maxFileKb = all.int(K_MAX_FILE_KB, 96),
            maxFilesPerRequest = all.int(K_MAX_FILES, 4),
            maxWorkspaceImportMb = all.int(K_MAX_IMPORT_MB, 128),
            requireConfirmBeforePush = all.bool(K_CONFIRM_PUSH, true),
            refuseDefaultBranchCommits = all.bool(K_REFUSE_DEFAULT, true),
            autoTrimHistory = all.bool(K_AUTO_TRIM, true),
            warnOnThermal = all.bool(K_WARN_THERMAL, true),
            pauseOnBatterySaver = all.bool(K_PAUSE_SAVER, false),
            themeMode = all.string(K_THEME, "system"),
            languageMode = all.string(K_LANG, "system"),
            fontSizeScale = all.float(K_FONT_SCALE, 1.0f),
            verboseLogging = all.bool(K_VERBOSE, false),
            lastModelId = all.longOrNull(K_LAST_MODEL),
            lastWorkspacePath = all.stringOrNull(K_LAST_WS),
            lastGithubOwner = all.stringOrNull(K_LAST_OWNER),
            lastGithubRepo = all.stringOrNull(K_LAST_REPO),
        )
    }

    suspend fun update(transform: (Settings) -> Settings) = withContext(Dispatchers.IO) {
        val next = transform(_settings.value)
        persistDelta(_settings.value, next)
        _settings.value = next
        next
    }

    /** Only write keys that actually changed; keeps SQLite churn low. */
    private fun persistDelta(old: Settings, new: Settings) {
        if (old.contextLength != new.contextLength) db.putSetting(K_CTX, new.contextLength.toString())
        if (old.maxTokens != new.maxTokens) db.putSetting(K_MAX_TOKENS, new.maxTokens.toString())
        if (old.temperature != new.temperature) db.putSetting(K_TEMP, new.temperature.toString())
        if (old.topK != new.topK) db.putSetting(K_TOP_K, new.topK.toString())
        if (old.topP != new.topP) db.putSetting(K_TOP_P, new.topP.toString())
        if (old.repeatPenalty != new.repeatPenalty) db.putSetting(K_REPEAT, new.repeatPenalty.toString())
        if (old.threads != new.threads) db.putSetting(K_THREADS, new.threads.toString())
        if (old.batch != new.batch) db.putSetting(K_BATCH, new.batch.toString())
        if (old.useMmap != new.useMmap) db.putSetting(K_MMAP, new.useMmap.toString())
        if (old.historyTurns != new.historyTurns) db.putSetting(K_HISTORY, new.historyTurns.toString())
        if (old.keepModelLoaded != new.keepModelLoaded) db.putSetting(K_KEEP_LOADED, new.keepModelLoaded.toString())
        if (old.maxFileKb != new.maxFileKb) db.putSetting(K_MAX_FILE_KB, new.maxFileKb.toString())
        if (old.maxFilesPerRequest != new.maxFilesPerRequest) db.putSetting(K_MAX_FILES, new.maxFilesPerRequest.toString())
        if (old.maxWorkspaceImportMb != new.maxWorkspaceImportMb) db.putSetting(K_MAX_IMPORT_MB, new.maxWorkspaceImportMb.toString())
        if (old.requireConfirmBeforePush != new.requireConfirmBeforePush) db.putSetting(K_CONFIRM_PUSH, new.requireConfirmBeforePush.toString())
        if (old.refuseDefaultBranchCommits != new.refuseDefaultBranchCommits) db.putSetting(K_REFUSE_DEFAULT, new.refuseDefaultBranchCommits.toString())
        if (old.autoTrimHistory != new.autoTrimHistory) db.putSetting(K_AUTO_TRIM, new.autoTrimHistory.toString())
        if (old.warnOnThermal != new.warnOnThermal) db.putSetting(K_WARN_THERMAL, new.warnOnThermal.toString())
        if (old.pauseOnBatterySaver != new.pauseOnBatterySaver) db.putSetting(K_PAUSE_SAVER, new.pauseOnBatterySaver.toString())
        if (old.themeMode != new.themeMode) db.putSetting(K_THEME, new.themeMode)
        if (old.languageMode != new.languageMode) db.putSetting(K_LANG, new.languageMode)
        if (old.fontSizeScale != new.fontSizeScale) db.putSetting(K_FONT_SCALE, new.fontSizeScale.toString())
        if (old.verboseLogging != new.verboseLogging) db.putSetting(K_VERBOSE, new.verboseLogging.toString())
        if (old.lastModelId != new.lastModelId) db.putSetting(K_LAST_MODEL, new.lastModelId?.toString() ?: "")
        if (old.lastWorkspacePath != new.lastWorkspacePath) db.putSetting(K_LAST_WS, new.lastWorkspacePath ?: "")
        if (old.lastGithubOwner != new.lastGithubOwner) db.putSetting(K_LAST_OWNER, new.lastGithubOwner ?: "")
        if (old.lastGithubRepo != new.lastGithubRepo) db.putSetting(K_LAST_REPO, new.lastGithubRepo ?: "")
    }

    /**
     * Clamp inference settings to what this device can plausibly do.
     * Called after load and whenever RAM figures change, so the app never
     * hands llama.cpp a context length that will OOM.
     */
    fun saneFor(deviceRamBytes: Long, perAppHeapBytes: Long): Settings {
        val s = _settings.value
        val usable = (deviceRamBytes * 0.55).toLong().coerceAtLeast(768L * 1024 * 1024)
        // Rough KV cost per context token for a 1B-3B model: ~24 KB per 256 ctx.
        val maxCtxByRam = ((usable - 1_200_000_000L).coerceAtLeast(0) / 96).toInt()
            .coerceIn(512, 8192)
        val ctx = s.contextLength.coerceAtMost(maxCtxByRam).coerceAtLeast(512)
        val threads = s.threads.coerceIn(1, 4)
        if (ctx == s.contextLength && threads == s.threads) return s
        return s.copy(contextLength = ctx, threads = threads)
    }

    private fun defaultThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return when {
            cores <= 2 -> 1
            cores <= 4 -> 2
            else -> 3
        }
    }

    private fun Map<String, String>.int(k: String, d: Int) = get(k)?.toIntOrNull() ?: d
    private fun Map<String, String>.long(k: String, d: Long) = get(k)?.toLongOrNull() ?: d
    private fun Map<String, String>.longOrNull(k: String) = get(k)?.takeIf { it.isNotBlank() }?.toLongOrNull()
    private fun Map<String, String>.float(k: String, d: Float) = get(k)?.toFloatOrNull() ?: d
    private fun Map<String, String>.bool(k: String, d: Boolean) = get(k)?.toBooleanStrictOrNull() ?: d
    private fun Map<String, String>.string(k: String, d: String) = get(k)?.takeIf { it.isNotBlank() } ?: d
    private fun Map<String, String>.stringOrNull(k: String) = get(k)?.takeIf { it.isNotBlank() }

    companion object {
        private const val K_CTX = "ctx_len"
        private const val K_MAX_TOKENS = "max_tokens"
        private const val K_TEMP = "temperature"
        private const val K_TOP_K = "top_k"
        private const val K_TOP_P = "top_p"
        private const val K_REPEAT = "repeat_penalty"
        private const val K_THREADS = "threads"
        private const val K_BATCH = "batch"
        private const val K_MMAP = "use_mmap"
        private const val K_HISTORY = "history_turns"
        private const val K_KEEP_LOADED = "keep_model_loaded"
        private const val K_MAX_FILE_KB = "max_file_kb"
        private const val K_MAX_FILES = "max_files"
        private const val K_MAX_IMPORT_MB = "max_import_mb"
        private const val K_CONFIRM_PUSH = "confirm_push"
        private const val K_REFUSE_DEFAULT = "refuse_default_branch"
        private const val K_AUTO_TRIM = "auto_trim_history"
        private const val K_WARN_THERMAL = "warn_thermal"
        private const val K_PAUSE_SAVER = "pause_battery_saver"
        private const val K_THEME = "theme_mode"
        private const val K_LANG = "language_mode"
        private const val K_FONT_SCALE = "font_scale"
        private const val K_VERBOSE = "verbose_logging"
        private const val K_LAST_MODEL = "last_model_id"
        private const val K_LAST_WS = "last_workspace"
        private const val K_LAST_OWNER = "last_gh_owner"
        private const val K_LAST_REPO = "last_gh_repo"

        val ALLOWED_CONTEXTS = listOf(512, 1024, 2048, 3072, 4096, 8192)
    }
}
