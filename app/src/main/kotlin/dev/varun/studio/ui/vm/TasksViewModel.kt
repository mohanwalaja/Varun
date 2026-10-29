package dev.varun.studio.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.varun.studio.data.db.AuditReportRecord
import dev.varun.studio.data.db.TaskRecord
import dev.varun.studio.data.repo.ChatRepository
import dev.varun.studio.data.repo.TaskRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Task queue + audit report browsing. */
class TasksViewModel(
    private val tasks: TaskRepository,
    private val chat: ChatRepository,
) : ViewModel() {

    data class UiState(
        val tasks: List<TaskRecord> = emptyList(),
        val reports: List<AuditReportRecord> = emptyList(),
        val openReport: AuditReportRecord? = null,
        val active: Int = 0,
        val finished: Int = 0,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            tasks.tasks.collect { list ->
                _ui.value = _ui.value.copy(
                    tasks = list,
                    active = list.count { it.state == "QUEUED" || it.state == "RUNNING" },
                    finished = list.count { it.state != "QUEUED" && it.state != "RUNNING" },
                )
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            tasks.refresh()
            _ui.value = _ui.value.copy(reports = chat.reports())
        }
    }

    fun openReport(id: Long) {
        viewModelScope.launch { _ui.value = _ui.value.copy(openReport = chat.report(id)) }
    }

    fun closeReport() { _ui.value = _ui.value.copy(openReport = null) }

    fun clearFinished() {
        viewModelScope.launch { tasks.clearFinished() }
    }
}
