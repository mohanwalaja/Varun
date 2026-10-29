package dev.varun.studio.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.varun.studio.ai.EngineStats
import dev.varun.studio.ai.LlamaEngine
import dev.varun.studio.ai.MemoryEstimator
import dev.varun.studio.core.DeviceMemory
import dev.varun.studio.data.db.ModelRecord
import dev.varun.studio.data.repo.ModelRepository
import dev.varun.studio.data.repo.SettingsRepository
import dev.varun.studio.data.repo.TaskRepository
import dev.varun.studio.data.repo.WorkspaceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Dashboard state: what is loaded, what the device can do, what is running. */
class HomeViewModel(
    private val device: DeviceMemory,
    private val engine: LlamaEngine,
    private val models: ModelRepository,
    private val settings: SettingsRepository,
    private val tasks: TaskRepository,
    private val workspace: WorkspaceRepository,
) : ViewModel() {

    data class UiState(
        val memory: DeviceMemory.MemoryInfo? = null,
        val storage: DeviceMemory.StorageInfo? = null,
        val power: DeviceMemory.PowerInfo? = null,
        val engineStats: EngineStats? = null,
        val loadedModel: ModelRecord? = null,
        val modelEstimate: MemoryEstimator.Estimate? = null,
        val runtimeVersion: String = "",
        val runtimeAvailable: Boolean = false,
        val runtimeProblem: String? = null,
        val isArm64: Boolean = true,
        val abis: List<String> = emptyList(),
        val workspaceName: String? = null,
        val workspaceFiles: Int = 0,
        val githubConnected: Boolean = false,
        val githubLogin: String? = null,
        val activeTasks: Int = 0,
        val recommendedThreads: Int = 2,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            combine(models.loadState, models.loadedModelId, workspace.current, tasks.tasks) { load, id, ws, t ->
                Triple(load to id, ws, t)
            }.collect { (loadAndId, ws, t) ->
                val (load, id) = loadAndId
                val loadedRecord = id?.let { wanted -> models.models.value.firstOrNull { it.id == wanted } }
                _ui.value = _ui.value.copy(
                    workspaceName = ws?.name,
                    workspaceFiles = ws?.fileCount ?: 0,
                    activeTasks = t.count { it.state == "QUEUED" || it.state == "RUNNING" },
                    loadedModel = loadedRecord,
                    engineStats = if (load is ModelRepository.LoadUiState.Loaded) engine.stats() else null,
                )
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val mem = device.memory()
            val store = device.storage(models.modelsDir(), workspace.workspacesRoot())
            val power = device.power()
            val stats = engine.stats()
            val estimate = if (stats.loaded) {
                MemoryEstimator.estimateFromModel(
                    modelBytes = stats.modelBytes,
                    nLayer = stats.nLayer,
                    nHeadKv = (stats.nEmbd / 64).coerceAtLeast(1),
                    nEmbd = stats.nEmbd,
                    nCtx = stats.nCtx,
                    nBatch = settings.settings.value.batch,
                    nThreads = settings.settings.value.threads,
                    availableBytes = mem.usableForModelBytes,
                )
            } else null
            _ui.value = _ui.value.copy(
                memory = mem,
                storage = store,
                power = power,
                engineStats = stats.takeIf { it.loaded },
                modelEstimate = estimate,
                runtimeVersion = engine.runtimeVersion,
                runtimeAvailable = engine.isRuntimeAvailable,
                runtimeProblem = engine.runtimeUnavailableReason,
                isArm64 = device.isArm64(),
                abis = device.supportedAbis(),
                recommendedThreads = device.recommendedThreads(),
            )
            models.refresh()
            tasks.refresh()
        }
    }

    fun unloadModel() {
        viewModelScope.launch {
            models.unload()
            refresh()
        }
    }
}
