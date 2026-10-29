package dev.varun.studio.ui.vm

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.varun.studio.ai.LlamaEngine
import dev.varun.studio.core.DeviceMemory
import dev.varun.studio.data.repo.ChatRepository
import dev.varun.studio.data.repo.SettingsRepository
import dev.varun.studio.data.repo.TaskRepository
import dev.varun.studio.data.repo.WorkspaceRepository
import dev.varun.studio.workspace.CodeSearch
import dev.varun.studio.workspace.DiffEngine
import dev.varun.studio.workspace.EditJournal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Local workspace: browse, read, edit, diff, search, journal, rollback.
 *
 * Editing here is manual (the user types). AI-proposed edits arrive through
 * ChatViewModel and still land on disk via the same EditJournal, so both paths
 * produce backups and journal entries.
 */
class WorkspaceViewModel(
    private val workspace: WorkspaceRepository,
    private val settings: SettingsRepository,
    private val engine: LlamaEngine,
    private val tasks: TaskRepository,
    private val chat: ChatRepository,
    private val device: DeviceMemory,
) : ViewModel() {

    data class OpenFile(
        val relativePath: String,
        val original: String,
        val draft: String,
        val dirty: Boolean,
        val lineCount: Int,
        val readOnly: Boolean,
        val error: String? = null,
    )

    data class UiState(
        val workspaces: List<WorkspaceRepository.Workspace> = emptyList(),
        val current: WorkspaceRepository.Workspace? = null,
        val nodes: List<WorkspaceRepository.FileNode> = emptyList(),
        val pathStack: List<String> = emptyList(),
        val openFile: OpenFile? = null,
        val diff: DiffEngine.DiffResult? = null,
        val showDiff: Boolean = false,
        val searchQuery: String = "",
        val searchResult: CodeSearch.Result? = null,
        val searching: Boolean = false,
        val journal: List<EditJournal.Entry> = emptyList(),
        val changed: List<Pair<String, String>> = emptyList(),
        val selectedForAi: Set<String> = emptySet(),
        val busy: String? = null,
        val error: String? = null,
        val info: String? = null,
        val tokenEstimate: Int = 0,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            workspace.current.collect { ws ->
                _ui.value = _ui.value.copy(current = ws, nodes = workspace.nodes.value)
                refreshJournal()
            }
        }
        viewModelScope.launch {
            workspace.nodes.collect { _ui.value = _ui.value.copy(nodes = it) }
        }
        viewModelScope.launch {
            workspace.busy.collect { _ui.value = _ui.value.copy(busy = it) }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val list = workspace.listWorkspaces()
            _ui.value = _ui.value.copy(workspaces = list)
            if (_ui.value.current == null && list.isNotEmpty()) workspace.open(list.first().name)
        }
    }

    fun openWorkspace(name: String) {
        viewModelScope.launch {
            workspace.open(name)
            _ui.value = _ui.value.copy(pathStack = emptyList(), openFile = null, showDiff = false)
            refreshJournal()
        }
    }

    fun deleteWorkspace(name: String) {
        viewModelScope.launch {
            val ok = workspace.deleteWorkspace(name)
            refresh()
            _ui.value = _ui.value.copy(info = if (ok) "Deleted $name" else "Could not delete $name")
        }
    }

    fun importArchive(uri: Uri, displayName: String?) {
        viewModelScope.launch {
            val taskId = tasks.enqueue("import", displayName ?: "archive", null)
            tasks.markRunning(taskId)
            when (val r = workspace.importArchive(uri, displayName)) {
                is WorkspaceRepository.ImportResult.Success -> {
                    tasks.markDone(taskId, "${r.filesWritten} files")
                    _ui.value = _ui.value.copy(
                        info = "Imported ${r.filesWritten} file(s)" +
                            if (r.skipped > 0) "; ${r.skipped} skipped (unsafe or oversized)" else "",
                        pathStack = emptyList(),
                    )
                    refresh()
                    refreshJournal()
                }
                is WorkspaceRepository.ImportResult.Failure -> {
                    tasks.markFailed(taskId, r.message)
                    _ui.value = _ui.value.copy(error = r.message)
                }
            }
        }
    }

    // ------------------------------------------------------------- browsing

    fun enterDir(name: String) {
        viewModelScope.launch {
            val stack = _ui.value.pathStack + name
            _ui.value = _ui.value.copy(pathStack = stack, openFile = null, showDiff = false)
            workspace.loadNodes(stack.joinToString("/"))
        }
    }

    fun upOneLevel() {
        viewModelScope.launch {
            val stack = _ui.value.pathStack.dropLast(1)
            _ui.value = _ui.value.copy(pathStack = stack)
            workspace.loadNodes(stack.joinToString("/"))
        }
    }

    fun openFile(node: WorkspaceRepository.FileNode) {
        if (node.isDirectory) { enterDir(node.name); return }
        viewModelScope.launch {
            val r = workspace.readText(node.relativePath)
            r.fold(
                { text ->
                    _ui.value = _ui.value.copy(
                        openFile = OpenFile(
                            relativePath = node.relativePath,
                            original = text,
                            draft = text,
                            dirty = false,
                            lineCount = DiffEngine.splitLines(text).size,
                            readOnly = false,
                        ),
                        diff = null,
                        showDiff = false,
                        error = null,
                    )
                },
                { err ->
                    _ui.value = _ui.value.copy(
                        openFile = OpenFile(
                            relativePath = node.relativePath,
                            original = "",
                            draft = "",
                            dirty = false,
                            lineCount = 0,
                            readOnly = true,
                            error = err.message ?: "Could not open this file.",
                        ),
                    )
                },
            )
        }
    }

    fun closeFile() {
        _ui.value = _ui.value.copy(openFile = null, diff = null, showDiff = false)
    }

    fun onDraftChange(text: String) {
        val f = _ui.value.openFile ?: return
        _ui.value = _ui.value.copy(
            openFile = f.copy(
                draft = text,
                dirty = text != f.original,
                lineCount = DiffEngine.splitLines(text).size,
            ),
            diff = if (text != f.original) DiffEngine.diff(f.original, text) else null,
        )
    }

    fun saveFile() {
        val f = _ui.value.openFile ?: return
        if (!f.dirty) return
        viewModelScope.launch {
            workspace.writeText(f.relativePath, f.draft, "Manual edit in Varun editor").fold(
                {
                    _ui.value = _ui.value.copy(
                        openFile = f.copy(original = f.draft, dirty = false),
                        info = "Saved ${f.relativePath}. A backup of the previous version was kept.",
                        error = null,
                    )
                    refreshJournal()
                    refreshChanged()
                },
                { err -> _ui.value = _ui.value.copy(error = err.message ?: "Save failed") },
            )
        }
    }

    fun revertDraft() {
        val f = _ui.value.openFile ?: return
        _ui.value = _ui.value.copy(
            openFile = f.copy(draft = f.original, dirty = false),
            diff = null,
            showDiff = false,
            info = "Reverted unsaved changes.",
        )
    }

    fun toggleDiff() {
        _ui.value = _ui.value.copy(showDiff = !_ui.value.showDiff)
    }

    // --------------------------------------------------------------- search

    fun setSearchQuery(q: String) { _ui.value = _ui.value.copy(searchQuery = q) }

    fun search() {
        val q = _ui.value.searchQuery.trim()
        if (q.isEmpty()) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(searching = true)
            val r = workspace.search(q)
            _ui.value = _ui.value.copy(searching = false, searchResult = r)
        }
    }

    // --------------------------------------------------- selection for AI

    fun toggleSelectionForAi(path: String) {
        val cur = _ui.value.selectedForAi
        val maxFiles = settings.settings.value.maxFilesPerRequest
        val next = if (cur.contains(path)) cur - path else cur + path
        if (next.size > maxFiles) {
            _ui.value = _ui.value.copy(
                error = "Limit is $maxFiles files per request. Deselect one first, or raise the limit in Settings.",
            )
            return
        }
        _ui.value = _ui.value.copy(selectedForAi = next, error = null)
        estimateTokens()
    }

    fun clearSelection() {
        _ui.value = _ui.value.copy(selectedForAi = emptySet(), tokenEstimate = 0)
    }

    fun selectedPaths(): List<String> = _ui.value.selectedForAi.toList()

    private fun estimateTokens() {
        val ws = _ui.value.current ?: return
        val maxKb = settings.settings.value.maxFileKb
        var chars = 0
        for (p in _ui.value.selectedForAi) {
            runCatching {
                val f = dev.varun.studio.workspace.PathGuard.requireSafe(ws.root, p)
                if (f.isFile) chars += f.readText().take(maxKb * 1024).length
            }
        }
        _ui.value = _ui.value.copy(tokenEstimate = chars / 4)
    }

    // ------------------------------------------------------- journal/changes

    fun refreshJournal() {
        _ui.value = _ui.value.copy(journal = workspace.journalEntries())
    }

    fun refreshChanged() {
        viewModelScope.launch {
            val changed = workspace.changedFiles()
            _ui.value = _ui.value.copy(changed = changed)
        }
    }

    fun rollback(relativePath: String) {
        viewModelScope.launch {
            val msg = workspace.rollback(relativePath)
            _ui.value = _ui.value.copy(info = msg)
            refreshJournal()
            refreshChanged()
        }
    }

    /** Diff a working file against its most recent backup. */
    fun diffAgainstBackup(relativePath: String): DiffEngine.DiffResult? {
        val ws = _ui.value.current ?: return null
        val j = workspace.journalFor(ws)
        val entry = j.entriesFor(relativePath).lastOrNull { it.kind == EditJournal.EntryKind.BACKUP }
            ?: return null
        val backupRelative = entry.backupPath ?: return null
        val backup = dev.varun.studio.workspace.PathGuard.resolve(ws.root, backupRelative)
        val backupFile = (backup as? dev.varun.studio.workspace.PathGuard.Decision.Allowed)?.file ?: return null
        val current = dev.varun.studio.workspace.PathGuard.resolve(ws.root, relativePath)
        val currentFile = (current as? dev.varun.studio.workspace.PathGuard.Decision.Allowed)?.file ?: return null
        val oldText = runCatching { backupFile.readText() }.getOrNull() ?: return null
        val newText = runCatching { currentFile.readText() }.getOrNull() ?: return null
        return DiffEngine.diff(oldText, newText)
    }

    fun dismiss() { _ui.value = _ui.value.copy(error = null, info = null) }

    fun memory(): DeviceMemory.MemoryInfo = device.memory()

    fun isModelLoaded(): Boolean = engine.isLoaded
}
