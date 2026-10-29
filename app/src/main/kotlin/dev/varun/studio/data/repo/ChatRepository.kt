package dev.varun.studio.data.repo

import android.content.ContentValues
import dev.varun.studio.data.db.AuditReportRecord
import dev.varun.studio.data.db.ChatMessageRecord
import dev.varun.studio.data.db.ChatSessionRecord
import dev.varun.studio.data.db.TaskRecord
import dev.varun.studio.data.db.VarunDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Chat history persistence. */
class ChatRepository(private val db: VarunDatabase) {

    private val _sessions = MutableStateFlow<List<ChatSessionRecord>>(emptyList())
    val sessions: StateFlow<List<ChatSessionRecord>> = _sessions.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessageRecord>>(emptyList())
    val messages: StateFlow<List<ChatMessageRecord>> = _messages.asStateFlow()

    suspend fun refreshSessions() = withContext(Dispatchers.IO) { _sessions.value = db.sessions() }

    suspend fun newSession(title: String, modelName: String?, workspacePath: String?): Long =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val id = db.insertSession(
                ChatSessionRecord(
                    title = title.take(80).ifBlank { "Conversation" },
                    modelName = modelName,
                    createdAt = now,
                    updatedAt = now,
                    workspacePath = workspacePath,
                ),
            )
            refreshSessions()
            id
        }

    suspend fun loadMessages(sessionId: Long): List<ChatMessageRecord> = withContext(Dispatchers.IO) {
        val msgs = db.messages(sessionId)
        _messages.value = msgs
        msgs
    }

    suspend fun appendMessage(sessionId: Long, role: String, content: String): Long =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val id = db.insertMessage(
                ChatMessageRecord(
                    sessionId = sessionId,
                    role = role,
                    content = content,
                    createdAt = now,
                    promptTokens = null,
                    completionTokens = null,
                    elapsedMs = null,
                    resultCode = null,
                ),
            )
            db.touchSession(sessionId, now, null)
            _messages.value = db.messages(sessionId)
            id
        }

    suspend fun updateAssistantMessage(
        id: Long,
        content: String,
        promptTokens: Int?,
        completionTokens: Int?,
        elapsedMs: Long?,
        resultCode: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        // Replace the streamed placeholder row in place. This must be an UPDATE:
        // inserting a second row would either duplicate the reply or violate the
        // session_id foreign key, and the original created_at ordering is what
        // keeps the transcript readable after a restart.
        val cv = ContentValues().apply {
            put("content", content)
            put("prompt_tokens", promptTokens)
            put("completion_tokens", completionTokens)
            put("elapsed_ms", elapsedMs)
            put("result_code", resultCode)
        }
        val rows = db.writableDatabase.update(
            VarunDatabase.T_MESSAGES,
            cv,
            "id = ?",
            arrayOf(id.toString()),
        )
        if (rows > 0) {
            // Keep the session's "last activity" accurate without reordering the
            // transcript: touch the session, not the message's created_at.
            db.messageSessionId(id)?.let { sid ->
                db.touchSession(sid, System.currentTimeMillis(), null)
                _messages.value = db.messages(sid)
            }
        }
        rows > 0
    }

    suspend fun deleteMessage(id: Long) = withContext(Dispatchers.IO) {
        db.writableDatabase.delete(VarunDatabase.T_MESSAGES, "id = ?", arrayOf(id.toString()))
    }

    suspend fun deleteSession(id: Long) = withContext(Dispatchers.IO) {
        db.deleteSession(id)
        refreshSessions()
    }

    /**
     * Trim a conversation to the last [keep] turns for the model's context.
     * Deletes nothing from history; it only selects what to send.
     */
    fun historyForModel(messages: List<ChatMessageRecord>, keepTurns: Int): List<ChatMessageRecord> {
        if (keepTurns <= 0) return emptyList()
        return messages.filter { it.role == "user" || it.role == "assistant" }.takeLast(keepTurns * 2)
    }

    suspend fun saveReport(
        workspacePath: String,
        title: String,
        markdown: String,
        findings: Int,
        testedLocally: Boolean,
        modelName: String?,
    ): Long = withContext(Dispatchers.IO) {
        db.insertReport(
            AuditReportRecord(
                workspacePath = workspacePath,
                title = title,
                markdown = markdown,
                findings = findings,
                testedLocally = testedLocally,
                createdAt = System.currentTimeMillis(),
                modelName = modelName,
            ),
        )
    }

    suspend fun reports(): List<AuditReportRecord> = withContext(Dispatchers.IO) { db.reports() }
    suspend fun report(id: Long): AuditReportRecord? = withContext(Dispatchers.IO) { db.reportById(id) }
}

/** Task queue persistence and observable state. */
class TaskRepository(private val db: VarunDatabase) {

    enum class State { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

    private val _tasks = MutableStateFlow<List<TaskRecord>>(emptyList())
    val tasks: StateFlow<List<TaskRecord>> = _tasks.asStateFlow()

    val activeCount: Int get() = _tasks.value.count { it.state == State.QUEUED.name || it.state == State.RUNNING.name }

    suspend fun refresh() = withContext(Dispatchers.IO) { _tasks.value = db.tasks() }

    suspend fun enqueue(kind: String, title: String, workspacePath: String?, detail: String? = null): Long =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val id = db.insertTask(
                TaskRecord(
                    kind = kind,
                    title = title,
                    state = State.QUEUED.name,
                    progress = 0,
                    createdAt = now,
                    updatedAt = now,
                    detail = detail,
                    errorMessage = null,
                    workspacePath = workspacePath,
                ),
            )
            refresh()
            id
        }

    suspend fun markRunning(id: Long, progress: Int = 0) = withContext(Dispatchers.IO) {
        db.updateTaskState(id, State.RUNNING.name, progress)
        refresh()
    }

    suspend fun updateProgress(id: Long, progress: Int, detail: String? = null) = withContext(Dispatchers.IO) {
        db.updateTaskState(id, State.RUNNING.name, progress.coerceIn(0, 100), null, detail)
        refresh()
    }

    suspend fun markDone(id: Long, detail: String? = null) = withContext(Dispatchers.IO) {
        db.updateTaskState(id, State.DONE.name, 100, null, detail)
        refresh()
    }

    suspend fun markFailed(id: Long, error: String) = withContext(Dispatchers.IO) {
        db.updateTaskState(id, State.FAILED.name, 100, error)
        refresh()
    }

    suspend fun markCancelled(id: Long) = withContext(Dispatchers.IO) {
        db.updateTaskState(id, State.CANCELLED.name, 100, "Cancelled by user")
        refresh()
    }

    suspend fun clearFinished() = withContext(Dispatchers.IO) {
        db.deleteFinishedTasks()
        refresh()
    }
}
