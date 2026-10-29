package dev.varun.studio.ui.vm

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.varun.studio.ai.LlamaEngine
import dev.varun.studio.ai.MemoryEstimator
import dev.varun.studio.ai.ModelCatalog
import dev.varun.studio.core.AppLog
import dev.varun.studio.core.DeviceMemory
import dev.varun.studio.data.db.ModelRecord
import dev.varun.studio.data.repo.ModelRepository
import dev.varun.studio.data.repo.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Model manager: import, download (with consent), load, unload, delete.
 *
 * The download job is held so the UI can cancel it; cancelling deletes the
 * partial file so a half-downloaded GGUF is never mistaken for a real model.
 */
class ModelsViewModel(
    private val models: ModelRepository,
    private val engine: LlamaEngine,
    private val settings: SettingsRepository,
    private val device: DeviceMemory,
) : ViewModel() {

    data class CatalogRow(
        val entry: ModelCatalog.Entry,
        val estimate: MemoryEstimator.Estimate,
        val affordable: Boolean,
        val alreadyImported: Boolean,
    )

    data class UiState(
        val models: List<ModelRecord> = emptyList(),
        val catalog: List<CatalogRow> = emptyList(),
        val loadState: ModelRepository.LoadUiState = ModelRepository.LoadUiState.Idle,
        val download: ModelRepository.DownloadState = ModelRepository.DownloadState(null, null, 0, 0, false, null),
        val loadedModelId: Long? = null,
        val availableRamBytes: Long = 0,
        val totalRamBytes: Long = 0,
        val freeStorageBytes: Long = 0,
        val message: String? = null,
        val busy: Boolean = false,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var downloadJob: Job? = null

    init {
        refresh()
        viewModelScope.launch {
            models.models.collect { list ->
                _ui.value = _ui.value.copy(models = list)
                rebuildCatalog()
            }
        }
        viewModelScope.launch { models.loadState.collect { _ui.value = _ui.value.copy(loadState = it) } }
        viewModelScope.launch { models.downloadState.collect { _ui.value = _ui.value.copy(download = it) } }
        viewModelScope.launch { models.loadedModelId.collect { _ui.value = _ui.value.copy(loadedModelId = it) } }
    }

    fun refresh() {
        viewModelScope.launch {
            models.refresh()
            val mem = device.memory()
            val store = device.storage(models.modelsDir())
            _ui.value = _ui.value.copy(
                availableRamBytes = mem.usableForModelBytes,
                totalRamBytes = mem.totalRamBytes,
                freeStorageBytes = store.freeBytes,
            )
            rebuildCatalog()
        }
    }

    /** Previews what each catalog entry would cost on THIS device right now. */
    private fun rebuildCatalog() {
        val s = _ui.value
        val mem = device.memory()
        val available = mem.usableForModelBytes
        val importedPaths = s.models.map { it.origin }.toSet()
        _ui.value = s.copy(
            catalog = ModelCatalog.ENTRIES.map { entry ->
                val est = entry.estimate(
                    availableRamBytes = available,
                    nCtx = settings.settings.value.contextLength.coerceAtMost(entry.contextTokens),
                    nThreads = settings.settings.value.threads,
                )
                CatalogRow(
                    entry = entry,
                    estimate = est,
                    affordable = est.fit != MemoryEstimator.Fit.UNLIKELY,
                    alreadyImported = importedPaths.contains("catalog:${entry.id}"),
                )
            },
        )
    }

    /** Import a GGUF chosen with ACTION_OPEN_DOCUMENT. */
    fun importFromUri(uri: Uri) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true, message = null)
            val r = models.importFromUri(uri)
            _ui.value = _ui.value.copy(
                busy = false,
                message = r.fold(
                    { "Imported ${it.fileName}" },
                    { "Import failed: ${it.message ?: AppLog.describeError(it)}" },
                ),
            )
        }
    }

    /**
     * Start a download. [userConsented] must be true; it is wired to a dialog
     * confirmation, never to a default.
     */
    fun download(entry: ModelCatalog.Entry, userConsented: Boolean) {
        if (!userConsented) {
            _ui.value = _ui.value.copy(message = "Downloads need your explicit confirmation.")
            return
        }
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true, message = null)
            val r = models.download(entry, userConsented = true)
            _ui.value = _ui.value.copy(
                busy = false,
                message = r.fold(
                    { "Downloaded ${it.fileName}" },
                    { "Download failed: ${it.message ?: AppLog.describeError(it)}" },
                ),
            )
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _ui.value = _ui.value.copy(busy = false, message = "Download cancelled")
    }

    fun load(id: Long) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true)
            val ok = models.load(id)
            _ui.value = _ui.value.copy(busy = false)
            if (ok) settings.update { it.copy(lastModelId = id) }
        }
    }

    fun unload() {
        viewModelScope.launch {
            models.unload()
            _ui.value = _ui.value.copy(message = "Model unloaded; RAM released")
            refresh()
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            val ok = models.delete(id)
            _ui.value = _ui.value.copy(message = if (ok) "Model deleted" else "Delete failed")
        }
    }

    /** Estimate for an arbitrary file already on disk (used before loading). */
    fun estimateFor(record: ModelRecord): MemoryEstimator.Estimate =
        models.estimateForFile(record.sizeBytes, record.fileName, device.memory().usableForModelBytes)

    fun clearMessage() { _ui.value = _ui.value.copy(message = null) }
}
