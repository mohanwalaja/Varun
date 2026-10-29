package dev.varun.studio.agent

import dev.varun.studio.ai.ChatTurn
import dev.varun.studio.ai.EngineStats
import dev.varun.studio.ai.GenerateConfig
import dev.varun.studio.ai.GenerationResultCode
import dev.varun.studio.ai.LlamaEngine
import dev.varun.studio.core.AppLog
import dev.varun.studio.workspace.CodeSearch
import dev.varun.studio.workspace.DiffEngine
import dev.varun.studio.workspace.EditJournal
import dev.varun.studio.workspace.PathGuard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * The controlled, tool-based coding workflow required by Phase 6.
 *
 * A 1B-3B on-device model cannot be trusted to edit a repository autonomously,
 * so it is never given write access. The sequence is fixed:
 *
 *   1. INSPECT  - resolve real file paths from the workspace (never from the
 *                 model), read them, and budget them into the context.
 *   2. ASK      - if the selection is ambiguous or empty, the agent reports what
 *                 it needs instead of guessing.
 *   3. PROPOSE  - run the model, capture output, parse proposed edits.
 *   4. DIFF     - compute a real diff against the on-disk original.
 *   5. APPROVE  - the user approves or rejects each file individually.
 *   6. APPLY    - only approved files are written, through EditJournal, which
 *                 backs up the original first and journals the change.
 *   7. REPORT   - state honestly what was applied and what is UNTESTED.
 *
 * Nothing in steps 3-4 touches disk. Step 6 is the only write path.
 */
class CodingAgent(
    private val engine: LlamaEngine,
    private val workspaceRoot: File,
    private val journal: EditJournal,
) {

    enum class Phase { IDLE, INSPECTING, ASKING, PROPOSING, AWAITING_APPROVAL, APPLYING, DONE, FAILED }

    /** A file the model wants to change, paired with its verified original. */
    data class ProposedChange(
        val relativePath: String,
        val originalText: String?,
        val proposedText: String,
        val isNewFile: Boolean,
        val diff: DiffEngine.DiffResult?,
        val pathSafe: Boolean,
        val pathReason: String?,
        var approved: Boolean = false,
        var applied: Boolean = false,
        var rejected: Boolean = false,
        var applyError: String? = null,
    ) {
        val canApply: Boolean get() = pathSafe && !applied && !rejected
    }

    data class RunState(
        val phase: Phase = Phase.IDLE,
        val progress: Float = 0f,
        val message: String = "",
        val selectedFiles: List<String> = emptyList(),
        val droppedFiles: List<String> = emptyList(),
        val approximatePromptTokens: Int = 0,
        val overBudget: Boolean = false,
        val rawResponse: String = "",
        val streamingResponse: String = "",
        val changes: List<ProposedChange> = emptyList(),
        val untestedMarkers: List<String> = emptyList(),
        val resultCode: GenerationResultCode? = null,
        val stats: EngineStats? = null,
        val error: String? = null,
        val question: String? = null,
        val generationActive: Boolean = false,
    ) {
        val hasPendingChanges: Boolean get() = changes.any { it.canApply }
    }

    data class Request(
        val task: PromptLibrary.TaskKind,
        val userText: String,
        val files: List<String>,           // workspace-relative paths, chosen by the user
        val history: List<ChatTurn> = emptyList(),
        val budget: PromptLibrary.Budget,
        val generate: GenerateConfig,
        val searchQuery: String? = null,   // optional: auto-select files by search
        val maxAutoFiles: Int = 4,
    )

    data class ApplyReport(
        val applied: List<String>,
        val refused: List<Pair<String, String>>,
        val rejected: List<String>,
        val backups: List<String>,
    ) {
        val anyApplied: Boolean get() = applied.isNotEmpty()
    }

    private val _state = MutableStateFlow(RunState())
    val state: StateFlow<RunState> = _state.asStateFlow()

    private fun update(block: (RunState) -> RunState) {
        _state.value = block(_state.value)
    }

    fun reset() {
        update { RunState() }
    }

    /** Stop an in-flight generation. Safe from any thread. */
    fun cancel() {
        engine.cancel()
        update { it.copy(generationActive = false, message = "Cancelled") }
    }

    // ---------------------------------------------------------------- step 1

    /**
     * Resolve the files to inspect. Paths come from the workspace and the user,
     * never from model output. Anything that fails validation is reported.
     */
    private fun inspect(request: Request): InspectOutcome {
        update { it.copy(phase = Phase.INSPECTING, progress = 0.05f, message = "Inspecting workspace…") }

        val wanted = LinkedHashSet<String>()
        request.files.forEach { if (it.isNotBlank()) wanted.add(it.trim()) }

        if (wanted.isEmpty() && !request.searchQuery.isNullOrBlank()) {
            val ranked = CodeSearch.rankForContext(workspaceRoot, request.searchQuery, request.maxAutoFiles)
            ranked.forEach { f ->
                PathGuard.relativize(workspaceRoot, f)?.let { wanted.add(it) }
            }
            if (wanted.isEmpty()) {
                return InspectOutcome.NeedMoreContext(
                    "No files matched \"${request.searchQuery}\" in this workspace. " +
                        "Pick the files you want analysed, or check that the right workspace is open.",
                )
            }
        }

        if (wanted.isEmpty() && request.task != PromptLibrary.TaskKind.FREEFORM &&
            request.task != PromptLibrary.TaskKind.GENERATE_CODE &&
            request.task != PromptLibrary.TaskKind.EXPLAIN_BUILD_ERROR
        ) {
            return InspectOutcome.NeedMoreContext(
                "Select at least one source file for a ${request.task.id.replace('_', ' ')} task. " +
                    "The model only sees files you choose.",
            )
        }

        val kept = ArrayList<PromptLibrary.CodeFile>()
        val dropped = ArrayList<Pair<String, String>>()
        var n = 0
        for (relative in wanted) {
            n++
            update { it.copy(progress = 0.05f + 0.15f * n / wanted.size.coerceAtLeast(1)) }
            val decision = PathGuard.resolve(workspaceRoot, relative)
            when (decision) {
                is PathGuard.Decision.Denied -> {
                    dropped.add(relative to "Refused: ${decision.reason}")
                    AppLog.w("Agent: refused to read $relative (${decision.reason})")
                }
                is PathGuard.Decision.Allowed -> {
                    val f = decision.file
                    if (!f.exists()) {
                        dropped.add(relative to "File does not exist in this workspace")
                        continue
                    }
                    if (f.isDirectory) {
                        dropped.add(relative to "Path is a directory")
                        continue
                    }
                    if (f.length() > MAX_READ_BYTES) {
                        dropped.add(relative to "Larger than ${MAX_READ_BYTES / 1024} KB; open it in the editor and select a region instead")
                        continue
                    }
                    if (!CodeSearch.isProbablyText(f)) {
                        dropped.add(relative to "Binary file; not useful as model context")
                        continue
                    }
                    val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull()
                    if (text == null) {
                        dropped.add(relative to "Could not be decoded as UTF-8 text")
                        continue
                    }
                    kept.add(PromptLibrary.CodeFile(relative, text))
                }
            }
        }

        val budgeted = PromptLibrary.applyBudget(kept, request.budget)
        val allDropped = dropped + budgeted.dropped
        return InspectOutcome.Ready(budgeted, allDropped)
    }

    private sealed interface InspectOutcome {
        data class Ready(
            val budgeted: PromptLibrary.BudgetedPrompt,
            val dropped: List<Pair<String, String>>,
        ) : InspectOutcome

        data class NeedMoreContext(val question: String) : InspectOutcome
    }

    // ------------------------------------------------------------- steps 3-4

    /**
     * Run the full pipeline up to AWAITING_APPROVAL. Disk is only read here.
     *
     * @param onToken optional streaming sink for the chat UI.
     */
    suspend fun run(request: Request, onToken: (String) -> Unit = {}): RunState {
        if (!engine.isLoaded) {
            update {
                it.copy(
                    phase = Phase.FAILED,
                    error = "No local model is loaded. Open Models and load a GGUF first.",
                )
            }
            return _state.value
        }

        when (val outcome = inspect(request)) {
            is InspectOutcome.NeedMoreContext -> {
                update {
                    it.copy(
                        phase = Phase.ASKING,
                        question = outcome.question,
                        message = "Need more context",
                        progress = 1f,
                    )
                }
                return _state.value
            }
            is InspectOutcome.Ready -> {
                update {
                    it.copy(
                        selectedFiles = outcome.budgeted.files.map { f -> f.path },
                        droppedFiles = outcome.dropped.map { d -> "${d.first}: ${d.second}" },
                        approximatePromptTokens = outcome.budgeted.approximateTokens,
                        overBudget = outcome.budgeted.overBudget,
                    )
                }
                return propose(request, outcome.budgeted, onToken)
            }
        }
    }

    private suspend fun propose(
        request: Request,
        budgeted: PromptLibrary.BudgetedPrompt,
        onToken: (String) -> Unit,
    ): RunState {
        val notes = ArrayList<String>()
        if (budgeted.dropped.isNotEmpty()) {
            notes.add(
                "Some files were omitted for budget reasons: " +
                    budgeted.dropped.joinToString(", ") { it.first } +
                    ". Say so in your answer.",
            )
        }
        if (budgeted.files.any { it.truncated }) {
            notes.add("Some files were truncated. Do not comment on code you cannot see.")
        }

        val userPrompt = PromptLibrary.build(request.task, request.userText, budgeted, notes)

        // Build chat turns so llama.cpp applies the model's own template.
        val turns = ArrayList<ChatTurn>()
        turns.add(ChatTurn("system", PromptLibrary.systemPrompt()))
        request.history.takeLast(HISTORY_TURNS).forEach { turns.add(it) }
        turns.add(ChatTurn("user", userPrompt))

        val rendered = if (turns.size > 0) {
            engine.applyChatTemplate(turns, addAssistantSuffix = true).ifBlank { fallbackRender(turns) }
        } else userPrompt

        update {
            it.copy(
                phase = Phase.PROPOSING,
                progress = 0.25f,
                message = "Generating on device…",
                generationActive = true,
                rawResponse = "",
                streamingResponse = "",
                changes = emptyList(),
                question = null,
                error = null,
            )
        }

        val config = request.generate.copy(
            stopStrings = (request.generate.stopStrings + PromptLibrary.defaultStopStrings()).distinct(),
            useChatTemplate = false, // we already rendered it
        )

        val builder = StringBuilder()
        val outcome = engine.generate(rendered, config) { piece, _, generated ->
            builder.append(piece)
            onToken(piece)
            if (generated % 8 == 0 || piece.contains('\n')) {
                update { it.copy(streamingResponse = builder.toString(), progress = progressFor(generated, config.maxTokens)) }
            }
            true
        }

        val response = builder.toString()
        val edits = PromptLibrary.parseProposedEdits(response)
        val changes = edits.map { e -> toProposedChange(e) }
        val untested = PromptLibrary.extractUntestedMarkers(response)

        update {
            it.copy(
                phase = if (outcome.code == GenerationResultCode.CANCELLED) Phase.DONE
                else if (changes.isEmpty()) Phase.DONE else Phase.AWAITING_APPROVAL,
                progress = 1f,
                message = when (outcome.code) {
                    GenerationResultCode.CANCELLED -> "Cancelled"
                    GenerationResultCode.CONTEXT_FULL -> "Stopped: context full"
                    GenerationResultCode.OUT_OF_MEMORY -> "Stopped: out of memory"
                    GenerationResultCode.DECODE_FAILED -> "Stopped: decode error"
                    GenerationResultCode.NOT_LOADED -> "Stopped: model unloaded"
                    // Braced: an unbraced multi-line if/else as the last `when`
                    // branch makes the parser read the second `else` as a new
                    // entry, which is illegal after `else`.
                    else -> if (changes.isEmpty()) {
                        "Complete (no file edits proposed)"
                    } else {
                        "${changes.size} proposed change(s) await your approval"
                    }
                },
                generationActive = false,
                rawResponse = response,
                streamingResponse = response,
                changes = changes,
                untestedMarkers = untested,
                resultCode = outcome.code,
                stats = outcome.stats,
                error = outcome.error ?: errorFor(outcome.code),
            )
        }
        return _state.value
    }

    private fun errorFor(code: GenerationResultCode): String? = when (code) {
        GenerationResultCode.CONTEXT_FULL ->
            "The selected files do not fit the model's context. Deselect some files, lower the max file size, or raise the context length in Settings."
        GenerationResultCode.OUT_OF_MEMORY ->
            "The device ran out of memory while generating. Try a smaller model or a smaller context length."
        GenerationResultCode.DECODE_FAILED -> "The inference engine failed to decode. The model may be incompatible."
        GenerationResultCode.NOT_LOADED -> "The model was unloaded during generation."
        GenerationResultCode.INVALID_SAMPLER -> "Invalid sampling parameters."
        GenerationResultCode.UNKNOWN -> "Generation failed."
        else -> null
    }

    private fun progressFor(generated: Int, max: Int): Float {
        if (max <= 0) return 0.5f
        return (0.25f + 0.7f * generated.toFloat() / max.toFloat()).coerceIn(0.25f, 0.95f)
    }

    /**
     * Turn a parsed edit into a reviewable change, validating the path against
     * the workspace and reading the CURRENT on-disk original for the diff.
     */
    private fun toProposedChange(edit: PromptLibrary.ProposedEdit): ProposedChange {
        val cleaned = edit.path.removePrefix("./").trim()
        val decision = PathGuard.resolve(workspaceRoot, cleaned)
        return when (decision) {
            is PathGuard.Decision.Denied -> ProposedChange(
                relativePath = cleaned,
                originalText = null,
                proposedText = edit.body,
                isNewFile = false,
                diff = null,
                pathSafe = false,
                pathReason = "${decision.reason}: ${decision.detail}",
            )
            is PathGuard.Decision.Allowed -> {
                val file = decision.file
                val exists = file.exists() && file.isFile
                val original = if (exists) runCatching { file.readText(Charsets.UTF_8) }.getOrNull() else null
                ProposedChange(
                    relativePath = decision.relativePath,
                    originalText = original,
                    proposedText = edit.body,
                    isNewFile = !exists,
                    diff = original?.let { DiffEngine.diff(it, edit.body) },
                    pathSafe = true,
                    pathReason = null,
                )
            }
        }
    }

    // ------------------------------------------------------------- steps 5-6

    fun setApproved(index: Int, approved: Boolean) {
        update { s ->
            val list = s.changes.toMutableList()
            val i = index.coerceIn(0, list.size - 1)
            if (list.indices.contains(i)) {
                list[i] = list[i].copy(approved = approved, rejected = !approved && list[i].rejected)
            }
            s.copy(changes = list)
        }
    }

    fun approveAll() = setAll(true)
    fun rejectAll() = setAll(false)

    private fun setAll(approved: Boolean) {
        update { s ->
            s.copy(changes = s.changes.map { if (it.pathSafe) it.copy(approved = approved) else it })
        }
    }

    /**
     * Step 6: apply ONLY approved changes, through the journal.
     *
     * Originals are backed up before any write. A refusal (bad path, symlink,
     * failed backup) leaves that file untouched and is reported.
     */
    suspend fun applyApproved(): ApplyReport {
        val snapshot = _state.value
        val applied = ArrayList<String>()
        val refused = ArrayList<Pair<String, String>>()
        val rejected = ArrayList<String>()
        val backups = ArrayList<String>()

        update { it.copy(phase = Phase.APPLYING, progress = 0.96f, message = "Applying approved changes…") }

        val targets = snapshot.changes.withIndex().filter { (_, c) -> c.approved && !c.applied }
        var n = 0
        for ((index, change) in targets) {
            n++
            if (!change.pathSafe) {
                refused.add(change.relativePath to (change.pathReason ?: "Path is not inside the workspace"))
                continue
            }
            val proposal = EditJournal.Proposal(
                relativePath = change.relativePath,
                originalText = change.originalText,
                proposedText = change.proposedText,
                reason = "Approved from ${snapshot.phase.name.lowercase()} agent run",
                isNewFile = change.isNewFile,
            )
            // Journal the proposal first so an interrupted apply is still traceable.
            runCatching { journal.recordProposal(proposal) }

            when (val result = journal.applyApproved(proposal)) {
                is EditJournal.ApplyResult.Applied -> {
                    applied.add(change.relativePath)
                    result.backupFile?.let { backups.add(it.name) }
                    update { s ->
                        val list = s.changes.toMutableList()
                        if (list.indices.contains(index)) list[index] = list[index].copy(applied = true, applyError = null)
                        s.copy(changes = list)
                    }
                }
                is EditJournal.ApplyResult.Refused -> {
                    refused.add(change.relativePath to result.reason)
                    update { s ->
                        val list = s.changes.toMutableList()
                        if (list.indices.contains(index)) list[index] = list[index].copy(applyError = result.reason)
                        s.copy(changes = list)
                    }
                }
            }
            update { it.copy(progress = 0.96f + 0.04f * n / targets.size.coerceAtLeast(1)) }
        }

        for ((index, change) in snapshot.changes.withIndex()) {
            if (!change.approved && !change.applied && change.pathSafe) {
                rejected.add(change.relativePath)
                runCatching { journal.recordRejection(EditJournal.Proposal(
                    relativePath = change.relativePath,
                    originalText = change.originalText,
                    proposedText = change.proposedText,
                    reason = "Rejected by user",
                    isNewFile = change.isNewFile,
                )) }
                update { s ->
                    val list = s.changes.toMutableList()
                    if (list.indices.contains(index)) list[index] = list[index].copy(rejected = true)
                    s.copy(changes = list)
                }
            }
        }

        update {
            it.copy(
                phase = Phase.DONE,
                progress = 1f,
                message = buildString {
                    append(if (applied.isEmpty()) "No changes applied." else "${applied.size} file(s) applied.")
                    if (refused.isNotEmpty()) append(" ${refused.size} refused.")
                    if (rejected.isNotEmpty()) append(" ${rejected.size} rejected (originals untouched).")
                    if (it.untestedMarkers.isNotEmpty()) append(" The model marked ${it.untestedMarkers.size} claim(s) as UNTESTED.")
                },
            )
        }
        return ApplyReport(applied, refused, rejected, backups)
    }

    /**
     * Roll back one applied file using the journal's backup.
     */
    fun rollback(relativePath: String): String = when (val r = journal.rollback(relativePath)) {
        is EditJournal.ApplyResult.Applied -> "Restored $relativePath from backup."
        is EditJournal.ApplyResult.Refused -> "Rollback refused: ${r.reason}"
    }

    /**
     * Render a chat prompt without llama.cpp's template. Only used when the
     * model ships no template and llama.cpp returns nothing.
     */
    private fun fallbackRender(turns: List<ChatTurn>): String = buildString {
        for (t in turns) {
            append("<|").append(t.role).append("|>\n").append(t.content).append("\n")
        }
        append("<|assistant|>\n")
    }

    companion object {
        /** Hard cap on a single file read into context. */
        const val MAX_READ_BYTES = 512L * 1024
        /** How many earlier turns are replayed to the model. */
        const val HISTORY_TURNS = 6
    }
}
