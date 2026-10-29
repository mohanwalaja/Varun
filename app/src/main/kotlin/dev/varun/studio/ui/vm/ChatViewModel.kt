package dev.varun.studio.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.varun.studio.agent.CodingAgent
import dev.varun.studio.agent.PromptLibrary
import dev.varun.studio.ai.ChatTurn
import dev.varun.studio.ai.EngineStats
import dev.varun.studio.ai.GenerationResultCode
import dev.varun.studio.ai.LlamaEngine
import dev.varun.studio.ai.MemoryEstimator
import dev.varun.studio.core.AppLog
import dev.varun.studio.core.DeviceMemory
import dev.varun.studio.data.db.ChatMessageRecord
import dev.varun.studio.data.db.ChatSessionRecord
import dev.varun.studio.data.repo.ChatRepository
import dev.varun.studio.data.repo.GitHubRepository
import dev.varun.studio.data.repo.ModelRepository
import dev.varun.studio.data.repo.SettingsRepository
import dev.varun.studio.data.repo.TaskRepository
import dev.varun.studio.data.repo.WorkspaceRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Chat + coding-agent ViewModel.
 *
 * Two modes share one screen:
 *  - FREEFORM chat with the local model (optionally with attached files),
 *  - a structured task (explain, find bugs, security review, ...) which runs
 *    the full inspect -> propose -> diff -> approve -> apply pipeline.
 *
 * Generation always runs on Dispatchers.IO inside LlamaEngine and can be
 * stopped at any time.
 */
class ChatViewModel(
    private val chat: ChatRepository,
    private val engine: LlamaEngine,
    private val models: ModelRepository,
    private val settings: SettingsRepository,
    private val device: DeviceMemory,
    private val tasks: TaskRepository,
    private val workspace: WorkspaceRepository,
    private val github: GitHubRepository,
) : ViewModel() {

    data class UiMessage(
        val id: Long,
        val role: String,
        val content: String,
        val pending: Boolean = false,
        val resultCode: String? = null,
        val tokensPerSec: Double = 0.0,
    )

    data class UiState(
        val sessions: List<ChatSessionRecord> = emptyList(),
        val currentSessionId: Long? = null,
        val messages: List<UiMessage> = emptyList(),
        val streaming: String = "",
        val generating: Boolean = false,
        val modelLoaded: Boolean = false,
        val modelName: String? = null,
        val engineStats: EngineStats? = null,
        val attachedFiles: List<String> = emptyList(),
        val task: PromptLibrary.TaskKind = PromptLibrary.TaskKind.FREEFORM,
        val input: String = "",
        val error: String? = null,
        val info: String? = null,
        val contextUsedTokens: Int = 0,
        val contextLimit: Int = 0,
        val droppedFiles: List<String> = emptyList(),
        val proposedChanges: List<CodingAgent.ProposedChange> = emptyList(),
        val untestedMarkers: List<String> = emptyList(),
        val question: String? = null,
        val applyReport: CodingAgent.ApplyReport? = null,
        val lastTokensPerSec: Double = 0.0,
        val workspaceName: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var generateJob: Job? = null
    private var placeholderMessageId: Long? = null
    private var agent: CodingAgent? = null

    init {
        viewModelScope.launch {
            chat.refreshSessions()
            val s = settings.settings.value
            val loaded = engine.isLoaded
            _ui.value = _ui.value.copy(
                sessions = chat.sessions.value,
                modelLoaded = loaded,
                modelName = models.models.value.firstOrNull { it.id == models.loadedModelId.value }?.displayName,
                contextLimit = s.contextLength,
                workspaceName = workspace.current.value?.name,
            )
            if (chat.sessions.value.isEmpty()) startNewSession() else openSession(chat.sessions.value.first().id)
        }
        viewModelScope.launch {
            models.loadState.collect { st ->
                _ui.value = _ui.value.copy(
                    modelLoaded = st is ModelRepository.LoadUiState.Loaded,
                    modelName = models.loadedModelId.value?.let { id ->
                        models.models.value.firstOrNull { it.id == id }?.displayName
                    },
                )
            }
        }
        viewModelScope.launch { workspace.current.collect { ws -> _ui.value = _ui.value.copy(workspaceName = ws?.name) } }
    }

    fun startNewSession() {
        viewModelScope.launch {
            val id = chat.newSession(
                title = "New conversation",
                modelName = _ui.value.modelName,
                workspacePath = workspace.current.value?.root?.absolutePath,
            )
            openSession(id)
        }
    }

    fun openSession(id: Long) {
        viewModelScope.launch {
            val msgs = chat.loadMessages(id)
            _ui.value = _ui.value.copy(
                currentSessionId = id,
                messages = msgs.map { UiMessage(it.id, it.role, it.content, resultCode = it.resultCode) },
                streaming = "",
                proposedChanges = emptyList(),
                applyReport = null,
                question = null,
                untestedMarkers = emptyList(),
            )
            recomputeContextUsage()
        }
    }

    fun setInput(text: String) { _ui.value = _ui.value.copy(input = text) }

    fun setTask(kind: PromptLibrary.TaskKind) { _ui.value = _ui.value.copy(task = kind) }

    fun attachFile(relativePath: String) {
        _ui.value = _ui.value.copy(
            attachedFiles = (_ui.value.attachedFiles + relativePath).distinct(),
        )
        recomputeContextUsage()
    }

    fun detachFile(relativePath: String) {
        _ui.value = _ui.value.copy(attachedFiles = _ui.value.attachedFiles - relativePath)
        recomputeContextUsage()
    }

    fun clearAttachments() {
        _ui.value = _ui.value.copy(attachedFiles = emptyList())
        recomputeContextUsage()
    }

    private fun recomputeContextUsage() {
        val s = settings.settings.value
        val ws = workspace.current.value
        val chars = buildString {
            append(PromptLibrary.systemPrompt())
            _ui.value.messages.takeLast(s.historyTurns * 2).forEach { append(it.content) }
            append(_ui.value.input)
            if (ws != null) {
                for (path in _ui.value.attachedFiles) {
                    runCatching {
                        val f = dev.varun.studio.workspace.PathGuard.requireSafe(ws.root, path)
                        if (f.isFile) append(f.readText().take(s.maxFileKb * 1024))
                    }
                }
            }
        }.length
        _ui.value = _ui.value.copy(
            contextUsedTokens = chars / 4,
            contextLimit = s.contextLength,
        )
    }

    /** Trim what will be replayed to the model (history only; nothing is deleted). */
    fun trimHistoryForModel(): Int {
        val s = settings.settings.value
        val kept = chat.historyForModel(
            _ui.value.messages.map { ChatMessageRecord(role = it.role, content = it.content, sessionId = 0, createdAt = 0) },
            s.historyTurns,
        )
        return kept.size
    }

    fun send() {
        val text = _ui.value.input.trim()
        if (text.isEmpty()) return
        if (!engine.isLoaded) {
            _ui.value = _ui.value.copy(error = "Load a local model first (Models tab).")
            return
        }
        _ui.value = _ui.value.copy(input = "", error = null, info = null)
        runTask(PromptLibrary.TaskKind.from(_ui.value.task.id), text)
    }

    /** Run a structured task. This is the Phase 6 pipeline entry point. */
    fun runTask(kind: PromptLibrary.TaskKind, userText: String) {
        val sessionId = _ui.value.currentSessionId ?: return
        val s = settings.settings.value
        val ws = workspace.current.value

        generateJob?.cancel()
        generateJob = viewModelScope.launch {
            _ui.value = _ui.value.copy(generating = true, streaming = "", error = null, applyReport = null, question = null)

            // Persist the user turn.
            val userMsgId = chat.appendMessage(sessionId, "user", userText)
            _ui.value = _ui.value.copy(
                messages = _ui.value.messages + UiMessage(userMsgId, "user", userText),
            )
            // A placeholder row that streaming updates.
            val placeholderId = chat.appendMessage(sessionId, "assistant", "")
            placeholderMessageId = placeholderId
            _ui.value = _ui.value.copy(
                messages = _ui.value.messages + UiMessage(placeholderId, "assistant", "", pending = true),
            )

            val taskId = tasks.enqueue(kind.id, userText.take(60).ifBlank { kind.id }, ws?.root?.absolutePath)
            tasks.markRunning(taskId)

            val history = chat.historyForModel(
                _ui.value.messages.filter { !it.pending }.map {
                    ChatMessageRecord(role = it.role, content = it.content, sessionId = sessionId, createdAt = 0)
                },
                s.historyTurns,
            ).map { ChatTurn(it.role, it.content) }

            val budget = PromptLibrary.Budget.fromContext(
                nCtx = s.contextLength,
                maxFiles = s.maxFilesPerRequest,
                maxFileKb = s.maxFileKb,
                answerTokens = s.maxTokens,
            )

            val result = if (ws == null && kind != PromptLibrary.TaskKind.FREEFORM &&
                kind != PromptLibrary.TaskKind.EXPLAIN_BUILD_ERROR &&
                kind != PromptLibrary.TaskKind.GENERATE_CODE
            ) {
                // No workspace: structured tasks that need files cannot run.
                _ui.value = _ui.value.copy(
                    generating = false,
                    error = "Open a workspace first (Repos tab) so the assistant can read real files. " +
                        "Without files, ask a free-form question instead.",
                )
                tasks.markFailed(taskId, "No workspace open")
                return@launch
            } else if (ws != null) {
                val a = agent ?: dev.varun.studio.agent.CodingAgent(
                    engine, ws.root, dev.varun.studio.workspace.EditJournal(ws.root),
                ).also { agent = it }
                val request = CodingAgent.Request(
                    task = kind,
                    userText = userText,
                    files = _ui.value.attachedFiles,
                    history = history,
                    budget = budget,
                    generate = s.generateConfig(),
                )
                a.run(request) { piece ->
                    val current = _ui.value.streaming
                    _ui.value = _ui.value.copy(streaming = current + piece)
                    updatePlaceholder(current + piece)
                }
                a.state.value
            } else {
                // Free-form with no workspace: talk to the model directly.
                runFreeForm(userText, history, budget)
            }

            finishRun(sessionId, taskId, kind, result, placeholderId)
        }
    }

    private suspend fun runFreeForm(
        userText: String,
        history: List<ChatTurn>,
        budget: PromptLibrary.Budget,
    ): CodingAgent.RunState {
        val turns = listOf(ChatTurn("system", PromptLibrary.systemPrompt())) + history +
            ChatTurn("user", userText)
        val rendered = engine.applyChatTemplate(turns, true).ifBlank {
            turns.joinToString("\n") { "<|${it.role}|>\n${it.content}" } + "\n<|assistant|>\n"
        }
        val s = settings.settings.value
        val builder = StringBuilder()
        val outcome = engine.generate(
            rendered,
            s.generateConfig().copy(
                stopStrings = PromptLibrary.defaultStopStrings(),
                useChatTemplate = false,
            ),
        ) { piece, _, _ ->
            builder.append(piece)
            val text = builder.toString()
            _ui.value = _ui.value.copy(streaming = text)
            updatePlaceholder(text)
            true
        }
        val edits = PromptLibrary.parseProposedEdits(builder.toString())
        return CodingAgent.RunState(
            phase = CodingAgent.Phase.DONE,
            rawResponse = builder.toString(),
            streamingResponse = builder.toString(),
            resultCode = outcome.code,
            stats = outcome.stats,
            error = outcome.error,
            approximatePromptTokens = rendered.length / 4,
            untestedMarkers = PromptLibrary.extractUntestedMarkers(builder.toString()),
            changes = edits.map { e ->
                CodingAgent.ProposedChange(
                    relativePath = e.path,
                    originalText = null,
                    proposedText = e.body,
                    isNewFile = true,
                    diff = null,
                    pathSafe = false,
                    pathReason = "No workspace is open, so this cannot be applied. Open a workspace to review edits.",
                )
            },
        )
    }

    private suspend fun finishRun(
        sessionId: Long,
        taskId: Long,
        kind: PromptLibrary.TaskKind,
        state: CodingAgent.RunState,
        placeholderId: Long,
    ) {
        val response = state.streamingResponse.ifBlank { state.rawResponse }
        val tps = state.stats?.tokensPerSec ?: 0.0
        val finalContent = buildString {
            append(response)
            if (state.droppedFiles.isNotEmpty()) {
                append("\n\n---\nContext limits: ")
                append(state.droppedFiles.joinToString("; "))
            }
            if (state.question != null) {
                append("\n\nThe assistant needs more context: ").append(state.question)
            }
        }
        chat.updateAssistantMessage(
            placeholderId,
            finalContent,
            state.approximatePromptTokens,
            state.stats?.lastGeneratedTokens,
            state.stats?.lastGenerateMs,
            state.resultCode?.name,
        )
        // Replace the placeholder row content in the UI list.
        _ui.value = _ui.value.copy(
            generating = false,
            messages = _ui.value.messages.map {
                if (it.id == placeholderId) {
                    it.copy(content = finalContent, pending = false, resultCode = state.resultCode?.name, tokensPerSec = tps)
                } else it
            },
            streaming = "",
            lastTokensPerSec = tps,
            engineStats = state.stats,
            proposedChanges = state.changes,
            untestedMarkers = state.untestedMarkers,
            question = state.question,
            droppedFiles = state.droppedFiles,
            error = state.error ?: errorForCode(state.resultCode),
            info = when {
                state.resultCode == GenerationResultCode.CANCELLED -> "Stopped by you."
                state.resultCode == GenerationResultCode.CONTEXT_FULL ->
                    "Stopped: the context filled up. Deselect files or raise the context length."
                else -> null
            },
        )
        recomputeContextUsage()

        when (state.resultCode) {
            GenerationResultCode.CANCELLED -> tasks.markCancelled(taskId)
            GenerationResultCode.OUT_OF_MEMORY, GenerationResultCode.DECODE_FAILED,
            GenerationResultCode.NOT_LOADED, GenerationResultCode.INVALID_SAMPLER,
            GenerationResultCode.UNKNOWN ->
                tasks.markFailed(taskId, state.error ?: errorForCode(state.resultCode) ?: "Failed")
            else -> tasks.markDone(
                taskId,
                "${kind.id}: ${state.stats?.lastGeneratedTokens ?: 0} tokens, " +
                    String.format(java.util.Locale.US, "%.1f tok/s", tps),
            )
        }
        chat.refreshSessions()
        _ui.value = _ui.value.copy(sessions = chat.sessions.value)
    }

    private fun errorForCode(code: GenerationResultCode?): String? = when (code) {
        GenerationResultCode.OUT_OF_MEMORY ->
            "Out of memory. Try a smaller model, a smaller context length, or close other apps."
        GenerationResultCode.CONTEXT_FULL ->
            "The prompt does not fit the model context. Deselect files or raise the context length."
        GenerationResultCode.DECODE_FAILED -> "The inference engine hit a decode error."
        GenerationResultCode.NOT_LOADED -> "The model was unloaded during generation."
        else -> null
    }

    private fun updatePlaceholder(text: String) {
        val id = placeholderMessageId ?: return
        _ui.value = _ui.value.copy(
            messages = _ui.value.messages.map { if (it.id == id) it.copy(content = text) else it },
        )
    }

    fun stop() {
        engine.cancel()
        agent?.cancel()
        generateJob?.cancel()
        generateJob = null
        _ui.value = _ui.value.copy(generating = false, info = "Stopped.")
    }

    // ------------------------------------------------------- review & apply

    fun setApproved(index: Int, approved: Boolean) {
        agent?.setApproved(index, approved)
        _ui.value = _ui.value.copy(proposedChanges = agent?.state?.value?.changes ?: _ui.value.proposedChanges)
    }

    fun approveAll() {
        agent?.approveAll()
        _ui.value = _ui.value.copy(proposedChanges = agent?.state?.value?.changes ?: _ui.value.proposedChanges)
    }

    fun rejectAll() {
        agent?.rejectAll()
        _ui.value = _ui.value.copy(proposedChanges = agent?.state?.value?.changes ?: _ui.value.proposedChanges)
    }

    /** Apply ONLY approved changes, through the journal (backups first). */
    fun applyApproved() {
        viewModelScope.launch {
            val a = agent ?: return@launch
            val report = a.applyApproved()
            _ui.value = _ui.value.copy(
                applyReport = report,
                proposedChanges = a.state.value.changes,
                info = buildString {
                    append(if (report.applied.isEmpty()) "Nothing applied." else "${report.applied.size} file(s) saved with backups.")
                    if (report.refused.isNotEmpty()) append(" ${report.refused.size} refused.")
                    if (report.rejected.isNotEmpty()) append(" ${report.rejected.size} rejected; originals untouched.")
                },
            )
        }
    }

    fun rollback(relativePath: String) {
        viewModelScope.launch {
            val a = agent ?: return@launch
            val msg = a.rollback(relativePath)
            _ui.value = _ui.value.copy(info = msg)
        }
    }

    fun dismissError() { _ui.value = _ui.value.copy(error = null, info = null) }

    override fun onCleared() {
        super.onCleared()
        generateJob?.cancel()
        engine.cancel()
    }

    /** Exposed for the diagnostics sheet. */
    fun memorySnapshot(): DeviceMemory.MemoryInfo = device.memory()

    fun formatBytes(b: Long) = MemoryEstimator.humanBytes(b)

    fun logDebug(msg: String) = AppLog.d(msg)
}
