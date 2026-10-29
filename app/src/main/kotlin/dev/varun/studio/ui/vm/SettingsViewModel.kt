package dev.varun.studio.ui.vm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.varun.studio.BuildConfig
import dev.varun.studio.ai.LlamaEngine
import dev.varun.studio.ai.MemoryEstimator
import dev.varun.studio.core.AppLog
import dev.varun.studio.core.DeviceMemory
import dev.varun.studio.core.security.SecureVault
import dev.varun.studio.data.db.VarunDatabase
import dev.varun.studio.data.repo.ModelRepository
import dev.varun.studio.data.repo.SettingsRepository
import dev.varun.studio.data.repo.WorkspaceRepository
import dev.varun.studio.ui.theme.LanguageMode
import dev.varun.studio.ui.theme.applyLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Settings, security controls and diagnostics.
 *
 * Also the place where the app is honest about itself: the runtime version, the
 * ABI actually present, RAM figures and a plain statement of what was and was
 * not verified.
 */
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val device: DeviceMemory,
    private val engine: LlamaEngine,
    private val vault: SecureVault,
    private val models: ModelRepository,
    private val workspace: WorkspaceRepository,
    private val db: VarunDatabase,
    private val context: Context,
) : ViewModel() {

    data class UiState(
        val settings: SettingsRepository.Settings = SettingsRepository.Settings(),
        val memory: DeviceMemory.MemoryInfo? = null,
        val storage: DeviceMemory.StorageInfo? = null,
        val power: DeviceMemory.PowerInfo? = null,
        val runtimeVersion: String = "",
        val runtimeAvailable: Boolean = false,
        val runtimeProblem: String? = null,
        val abis: List<String> = emptyList(),
        val isArm64: Boolean = false,
        val keystoreAvailable: Boolean = false,
        val tokenStored: Boolean = false,
        val appVersion: String = "",
        val recommendedThreads: Int = 2,
        val message: String? = null,
        val busy: Boolean = false,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            settings.settings.collect { s ->
                AppLog.verbose = s.verboseLogging
                _ui.value = _ui.value.copy(settings = s)
            }
        }
        refresh()
    }

    fun refresh() {
        val mem = device.memory()
        val store = device.storage(models.modelsDir(), workspace.workspacesRoot())
        _ui.value = _ui.value.copy(
            memory = mem,
            storage = store,
            power = device.power(),
            runtimeVersion = engine.runtimeVersion,
            runtimeAvailable = engine.isRuntimeAvailable,
            runtimeProblem = engine.runtimeUnavailableReason,
            abis = device.supportedAbis(),
            isArm64 = device.isArm64(),
            keystoreAvailable = vault.isHardwareBackedStorageAvailable,
            tokenStored = !vault.getString(SecureVault.KEY_GITHUB_TOKEN).isNullOrBlank(),
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            recommendedThreads = device.recommendedThreads(),
        )
    }

    fun <T> change(selector: (SettingsRepository.Settings) -> T, value: T, apply: (SettingsRepository.Settings, T) -> SettingsRepository.Settings) {
        viewModelScope.launch {
            settings.update { s -> if (selector(s) != value) apply(s, value) else s }
        }
    }

    fun setContextLength(v: Int) = viewModelScope.launch {
        settings.update { it.copy(contextLength = v.coerceIn(512, 32_768)) }
    }

    fun setMaxTokens(v: Int) = viewModelScope.launch {
        settings.update { it.copy(maxTokens = v.coerceIn(16, 4096)) }
    }

    fun setTemperature(v: Float) = viewModelScope.launch {
        settings.update { it.copy(temperature = v.coerceIn(0f, 2f)) }
    }

    fun setTopK(v: Int) = viewModelScope.launch {
        settings.update { it.copy(topK = v.coerceIn(0, 200)) }
    }

    fun setThreads(v: Int) = viewModelScope.launch {
        settings.update { it.copy(threads = v.coerceIn(1, 8)) }
    }

    fun setBatch(v: Int) = viewModelScope.launch {
        settings.update { it.copy(batch = v.coerceIn(32, 2048)) }
    }

    fun setMmap(v: Boolean) = viewModelScope.launch { settings.update { it.copy(useMmap = v) } }
    fun setHistoryTurns(v: Int) = viewModelScope.launch {
        settings.update { it.copy(historyTurns = v.coerceIn(0, 24)) }
    }
    fun setKeepModelLoaded(v: Boolean) = viewModelScope.launch {
        settings.update { it.copy(keepModelLoaded = v) }
    }
    fun setMaxFileKb(v: Int) = viewModelScope.launch {
        settings.update { it.copy(maxFileKb = v.coerceIn(4, 2048)) }
    }
    fun setMaxFiles(v: Int) = viewModelScope.launch {
        settings.update { it.copy(maxFilesPerRequest = v.coerceIn(1, 20)) }
    }
    fun setMaxImportMb(v: Int) = viewModelScope.launch {
        settings.update { it.copy(maxWorkspaceImportMb = v.coerceIn(8, 1024)) }
    }
    fun setConfirmPush(v: Boolean) = viewModelScope.launch {
        // This guard is deliberately not turnable-off from a hidden path: the
        // switch only relaxes the default-branch rule, never the confirmation.
        settings.update { it.copy(requireConfirmBeforePush = v) }
    }
    fun setRefuseDefaultBranch(v: Boolean) = viewModelScope.launch {
        settings.update { it.copy(refuseDefaultBranchCommits = v) }
    }
    fun setAutoTrim(v: Boolean) = viewModelScope.launch { settings.update { it.copy(autoTrimHistory = v) } }
    fun setWarnThermal(v: Boolean) = viewModelScope.launch { settings.update { it.copy(warnOnThermal = v) } }
    fun setPauseOnSaver(v: Boolean) = viewModelScope.launch { settings.update { it.copy(pauseOnBatterySaver = v) } }
    fun setVerbose(v: Boolean) = viewModelScope.launch {
        settings.update { it.copy(verboseLogging = v) }
        AppLog.verbose = v
    }
    fun setFontScale(v: Float) = viewModelScope.launch {
        settings.update { it.copy(fontSizeScale = v.coerceIn(0.85f, 1.4f)) }
    }

    fun setThemeMode(mode: String) = viewModelScope.launch { settings.update { it.copy(themeMode = mode) } }

    fun setLanguage(mode: String) = viewModelScope.launch {
        settings.update { it.copy(languageMode = mode) }
        applyLanguage(context, LanguageMode.valueOf(mode.uppercase()))
    }

    /**
     * Apply the RAM-derived sane defaults. Useful after an OOM: one tap moves
     * context, threads and file limits to something this device can actually do.
     */
    fun applyConservativeProfile() = viewModelScope.launch {
        val mem = device.memory()
        val usable = mem.usableForModelBytes
        val threads = device.recommendedThreads().coerceAtMost(2)
        settings.update {
            it.copy(
                contextLength = when {
                    usable < 1_500L * 1024 * 1024 -> 512
                    usable < 3_000L * 1024 * 1024 -> 1024
                    else -> 2048
                },
                maxTokens = 256,
                threads = threads,
                batch = 128,
                useMmap = true,
                maxFileKb = 48,
                maxFilesPerRequest = 2,
                historyTurns = 3,
                autoTrimHistory = true,
            )
        }
        _ui.value = _ui.value.copy(message = "Applied a conservative profile for this device's current free RAM.")
    }

    fun unloadModel() = viewModelScope.launch {
        models.unload()
        _ui.value = _ui.value.copy(message = "Model unloaded; RAM released.")
    }

    fun eraseEverything() = viewModelScope.launch {
        _ui.value = _ui.value.copy(busy = true)
        runCatching { engine.unload() }
        runCatching { db.wipe() }
        runCatching { vault.clearAll() }
        runCatching { models.modelsDir().deleteRecursively(); models.modelsDir().mkdirs() }
        runCatching { workspace.workspacesRoot().deleteRecursively(); workspace.workspacesRoot().mkdirs() }
        runCatching { context.cacheDir.deleteRecursively() }
        runCatching { settings.load() }
        _ui.value = _ui.value.copy(
            busy = false,
            message = "All local data erased: models, workspaces, chats, token and settings.",
        )
        refresh()
    }

    /**
     * Diagnostics text the user can copy when asking for help. Contains no
     * secrets by construction (AppLog.redact is applied as a second pass).
     */
    fun diagnostics(): String {
        val s = _ui.value
        val mem = s.memory
        val store = s.storage
        val st = engine.stats()
        return AppLog.redact(
            buildString {
                appendLine("Varun Studio ${s.appVersion}")
                appendLine("Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
                appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                appendLine("ABIs: ${s.abis.joinToString()}  arm64=${s.isArm64}")
                appendLine("Native runtime available: ${s.runtimeAvailable} (${s.runtimeVersion})")
                s.runtimeProblem?.let { appendLine("Runtime problem: $it") }
                appendLine("RAM total: ${mem?.let { MemoryEstimator.humanBytes(it.totalRamBytes) }}")
                appendLine("RAM available: ${mem?.let { MemoryEstimator.humanBytes(it.availableRamBytes) }}")
                appendLine("RAM usable for model: ${mem?.let { MemoryEstimator.humanBytes(it.usableForModelBytes) }}")
                appendLine("Java heap limit: ${mem?.let { MemoryEstimator.humanBytes(it.perAppHeapBytes) }}")
                appendLine("Low-RAM device flag: ${mem?.isLowRamDevice}")
                appendLine("Storage free: ${store?.let { MemoryEstimator.humanBytes(it.freeBytes) }} of ${store?.let { MemoryEstimator.humanBytes(it.totalBytes) }}")
                appendLine("Models on disk: ${store?.let { MemoryEstimator.humanBytes(it.modelsBytes) }}")
                appendLine("Workspaces on disk: ${store?.let { MemoryEstimator.humanBytes(it.workspacesBytes) }}")
                appendLine("Keystore encryption available: ${s.keystoreAvailable}")
                appendLine("GitHub token stored: ${s.tokenStored}")
                appendLine("Thermal: ${s.power?.thermal}  battery saver: ${s.power?.isPowerSaver}")
                appendLine("--- inference settings ---")
                appendLine("context=${s.settings.contextLength} maxTokens=${s.settings.maxTokens} threads=${s.settings.threads} batch=${s.settings.batch} mmap=${s.settings.useMmap}")
                appendLine("maxFileKb=${s.settings.maxFileKb} maxFiles=${s.settings.maxFilesPerRequest} historyTurns=${s.settings.historyTurns}")
                appendLine("--- loaded model ---")
                if (st.loaded) {
                    appendLine("${st.description} ftype=${st.ftype}")
                    appendLine("n_vocab=${st.nVocab} n_ctx=${st.nCtx} (train ${st.nCtxTrain}) n_embd=${st.nEmbd} n_layer=${st.nLayer}")
                    appendLine("params=${MemoryEstimator.humanParams(st.nParams)} size=${MemoryEstimator.humanBytes(st.modelBytes)}")
                    appendLine("last run: prompt ${st.lastPromptTokens} tok @ ${String.format(java.util.Locale.US, "%.1f", st.promptTokensPerSec)} t/s, " +
                        "gen ${st.lastGeneratedTokens} tok @ ${String.format(java.util.Locale.US, "%.1f", st.tokensPerSec)} t/s")
                } else {
                    appendLine("(none)")
                }
            },
        )
    }

    fun setMessage(m: String?) { _ui.value = _ui.value.copy(message = m) }
}
