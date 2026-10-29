package dev.varun.studio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.varun.studio.R
import dev.varun.studio.agent.PromptLibrary
import dev.varun.studio.ui.components.CodeBlock
import dev.varun.studio.ui.components.ConfirmDialog
import dev.varun.studio.ui.components.DiffView
import dev.varun.studio.ui.components.ErrorBanner
import dev.varun.studio.ui.components.InfoBanner
import dev.varun.studio.ui.components.SectionCard
import dev.varun.studio.ui.components.WarningBanner
import dev.varun.studio.ui.theme.CodeFamily
import dev.varun.studio.ui.vm.ChatViewModel
import dev.varun.studio.ui.vm.WorkspaceViewModel

/**
 * Coding assistant.
 *
 * Layout is optimised for a portrait phone: a scrolling transcript, a
 * horizontally-scrolling chip row for task selection (no dropdown hunting with
 * one thumb), and an input bar pinned above the IME.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    workspaceVm: WorkspaceViewModel,
    onOpenModels: () -> Unit,
    onOpenWorkspace: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val wsUi by workspaceVm.ui.collectAsState()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    var showAttach by remember { mutableStateOf(false) }

    // Auto-scroll while streaming.
    LaunchedEffect(ui.messages.size, ui.streaming.length) {
        val target = ui.messages.size - 1
        if (target >= 0) listState.animateScrollToItem(target)
    }

    if (!ui.modelLoaded) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(title = { Text(stringResource(R.string.chat_title)) })
            Column(
                Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.chat_no_model), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.chat_no_model_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onOpenModels) { Text(stringResource(R.string.nav_models)) }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(stringResource(R.string.chat_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        ui.modelName ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            actions = {
                TextButton(onClick = { vm.startNewSession() }) {
                    Text(stringResource(R.string.chat_new_session))
                }
                IconButton(onClick = { showAttach = !showAttach }) {
                    Icon(Icons.Filled.AttachFile, contentDescription = stringResource(R.string.chat_attach_files))
                }
            },
        )

        // ------------------------------------------------------- status strip
        Column(Modifier.padding(horizontal = 12.dp)) {
            ErrorBanner(ui.error) { vm.dismissError() }
            InfoBanner(ui.info) { vm.dismissError() }
            if (ui.question != null) {
                WarningBanner("The assistant needs more context:\n${ui.question}")
            }
            if (ui.untestedMarkers.isNotEmpty()) {
                WarningBanner(
                    "The model marked ${ui.untestedMarkers.size} claim(s) as UNTESTED. It cannot run code, " +
                        "so nothing below has been verified. Review the diff yourself.",
                )
            }
            if (ui.droppedFiles.isNotEmpty()) {
                WarningBanner(
                    "Not everything you selected fit the context:\n" + ui.droppedFiles.joinToString("\n"),
                )
            }
        }

        // --------------------------------------------------- attached files
        if (showAttach) {
            SectionCard(
                title = stringResource(R.string.chat_attach_files),
                subtitle = wsUi.current?.let {
                    "${it.name} · tap to attach · limit ${wsUi.selectedForAi.size}"
                } ?: "Open a workspace first",
                modifier = Modifier.padding(horizontal = 12.dp),
                trailing = {
                    if (wsUi.current == null) {
                        TextButton(onClick = onOpenWorkspace) { Text(stringResource(R.string.workspace_title)) }
                    }
                },
            ) {
                if (wsUi.current == null) {
                    Text(
                        "No workspace is open. Import a repository or an archive from the Repos tab, " +
                            "then attach real files here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        stringResource(R.string.chat_attached, ui.attachedFiles.size),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        wsUi.nodes.filter { !it.isDirectory }.take(60).forEach { node ->
                            val attached = ui.attachedFiles.contains(node.relativePath)
                            AssistChip(
                                onClick = {
                                    if (attached) vm.detachFile(node.relativePath)
                                    else vm.attachFile(node.relativePath)
                                },
                                label = { Text(node.name, style = MaterialTheme.typography.labelSmall) },
                                leadingIcon = {
                                    if (attached) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                },
                            )
                        }
                    }
                    if (ui.attachedFiles.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = { vm.clearAttachments() }) {
                            Text("Detach all (${ui.attachedFiles.size})")
                        }
                    }
                }
            }
        }

        // -------------------------------------------------------- task chips
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TASK_CHIPS.forEach { (kind, labelRes) ->
                AssistChip(
                    onClick = { vm.setTask(kind) },
                    label = { Text(stringResource(labelRes), style = MaterialTheme.typography.labelSmall) },
                    border = if (ui.task == kind) {
                        androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                    } else null,
                )
            }
        }

        // ------------------------------------------------------------ messages
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ui.messages, key = { it.id }) { msg ->
                MessageBubble(msg, onCopy = { clipboard.setText(AnnotatedString(msg.content)) })
            }
        }

        // ------------------------------------------------- proposed changes
        if (ui.proposedChanges.isNotEmpty()) {
            ProposedChangesPanel(ui = ui, vm = vm)
        }
        ui.applyReport?.let { report ->
            SectionCard(
                title = "Apply result",
                modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                if (report.applied.isNotEmpty()) {
                    Text(
                        "Saved with backups: " + report.applied.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (report.refused.isNotEmpty()) {
                    Text(
                        "Refused: " + report.refused.joinToString("; ") { "${it.first} — ${it.second}" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (report.rejected.isNotEmpty()) {
                    Text(
                        "Rejected (originals untouched): " + report.rejected.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "Nothing here has been executed or tested. Review each change in the workspace " +
                        "before committing it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        // ------------------------------------------------------------- input
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).imePadding(),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = ui.input,
                onValueChange = { vm.setInput(it) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 140.dp),
                placeholder = { Text(stringResource(R.string.chat_input_hint)) },
                maxLines = 6,
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.width(8.dp))
            if (ui.generating) {
                IconButton(onClick = { vm.stop() }) {
                    Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.chat_stop_generating),
                        tint = MaterialTheme.colorScheme.error)
                }
            } else {
                IconButton(onClick = { vm.send() }, enabled = ui.input.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.action_send))
                }
            }
        }

        // --------------------------------------------------------- status bar
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                if (ui.generating) stringResource(R.string.chat_generating)
                else stringResource(
                    R.string.chat_tokens_used,
                    ui.contextUsedTokens,
                    ui.contextLimit,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ui.lastTokensPerSec > 0) {
                Text(
                    stringResource(R.string.models_tokens_per_sec, ui.lastTokensPerSec.toFloat()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (ui.generating) {
            androidx.compose.material3.LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
        }
    }
}

private val TASK_CHIPS = listOf(
    PromptLibrary.TaskKind.FREEFORM to R.string.nav_chat,
    PromptLibrary.TaskKind.EXPLAIN to R.string.chat_task_explain,
    PromptLibrary.TaskKind.FIND_BUGS to R.string.chat_task_bugs,
    PromptLibrary.TaskKind.SECURITY_REVIEW to R.string.chat_task_security,
    PromptLibrary.TaskKind.SUGGEST_FIX to R.string.chat_task_fix,
    PromptLibrary.TaskKind.GENERATE_CODE to R.string.chat_task_generate,
    PromptLibrary.TaskKind.REFACTOR to R.string.chat_task_refactor,
    PromptLibrary.TaskKind.WRITE_TESTS to R.string.chat_task_tests,
    PromptLibrary.TaskKind.REVIEW_DEPENDENCIES to R.string.chat_task_deps,
    PromptLibrary.TaskKind.EXPLAIN_BUILD_ERROR to R.string.chat_task_build_error,
    PromptLibrary.TaskKind.REPO_AUDIT to R.string.chat_task_audit,
)

@Composable
private fun MessageBubble(msg: ChatViewModel.UiMessage, onCopy: () -> Unit) {
    val isUser = msg.role == "user"
    val bubble = if (isUser) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
    Card(
        colors = CardDefaults.cardColors(containerColor = bubble),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (isUser) "You" else "Varun (on device)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (msg.pending) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                } else {
                    TextButton(onClick = onCopy) {
                        Text(stringResource(R.string.chat_copy_response), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (msg.content.isBlank() && msg.pending) {
                Text("…", style = MaterialTheme.typography.bodyMedium)
            } else {
                SelectionFriendlyText(msg.content)
            }
            if (msg.resultCode != null && msg.resultCode != "OK" && msg.resultCode != "MAX_TOKENS") {
                Text(
                    "result: ${msg.resultCode}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (msg.tokensPerSec > 0) {
                Text(
                    "${String.format(java.util.Locale.US, "%.1f", msg.tokensPerSec)} tok/s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Renders model output, splitting fenced code blocks into a monospace,
 * horizontally scrollable view. Everything is displayed as text — model output
 * is never interpreted.
 */
@Composable
private fun SelectionFriendlyText(text: String) {
    val parts = remember(text) { splitCodeFences(text) }
    Column {
        for (p in parts) {
            if (p.second) CodeBlock(p.first, modifier = Modifier.padding(vertical = 4.dp), fontSize = 11)
            else SelectionContainer {
                Text(p.first, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun splitCodeFences(text: String): List<Pair<String, Boolean>> {
    val out = ArrayList<Pair<String, Boolean>>()
    val lines = text.split('\n')
    val sb = StringBuilder()
    var inCode = false
    for (line in lines) {
        if (line.trimStart().startsWith("```")) {
            if (sb.isNotEmpty()) {
                out.add(sb.toString() to inCode)
                sb.setLength(0)
            }
            inCode = !inCode
            continue
        }
        sb.append(line).append('\n')
    }
    if (sb.isNotEmpty()) out.add(sb.toString().trimEnd('\n') to inCode)
    return out
}

/**
 * Step 5 of the agent pipeline: per-file approval with a real diff.
 *
 * The original file is never modified until the user approves that specific
 * file, and approval only marks it — the write happens in applyApproved().
 */
@Composable
private fun ProposedChangesPanel(ui: ChatViewModel.UiState, vm: ChatViewModel) {
    var confirmApplyAll by remember { mutableStateOf(false) }
    var expandedIndex by remember { mutableStateOf<Int?>(null) }

    SectionCard(
        title = stringResource(R.string.diff_title),
        subtitle = "${ui.proposedChanges.size} proposed file change(s) · nothing written yet",
        modifier = Modifier.padding(horizontal = 12.dp),
        trailing = {
            Row {
                TextButton(onClick = { vm.approveAll() }) { Text(stringResource(R.string.diff_approve_all)) }
                TextButton(onClick = { vm.rejectAll() }) { Text(stringResource(R.string.diff_reject_all)) }
            }
        },
    ) {
        ui.proposedChanges.forEachIndexed { index, change ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (change.applied) MaterialTheme.colorScheme.tertiaryContainer
                    else if (change.rejected) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.surface,
                ),
                elevation = CardDefaults.cardElevation(0.dp),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(change.relativePath, style = MaterialTheme.typography.titleSmall, fontFamily = CodeFamily)
                            Text(
                                when {
                                    change.applied -> "Applied (backup kept)"
                                    change.rejected -> "Rejected — original untouched"
                                    !change.pathSafe -> "REFUSED: ${change.pathReason}"
                                    change.isNewFile -> "New file"
                                    else -> "Pending your approval"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (change.pathSafe) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error,
                            )
                        }
                        TextButton(onClick = { expandedIndex = if (expandedIndex == index) null else index }) {
                            Text(if (expandedIndex == index) "Hide" else "Diff")
                        }
                    }
                    if (!change.pathSafe) {
                        WarningBanner(
                            "This path was refused and cannot be applied: ${change.pathReason}. " +
                                "Model output is untrusted; paths are validated against the workspace root.",
                        )
                    }
                    if (expandedIndex == index) {
                        Spacer(Modifier.height(6.dp))
                        val diff = change.diff
                        if (diff != null) {
                            DiffView(diff, fontSize = 11)
                        } else {
                            Text(
                                stringResource(R.string.diff_proposed),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            CodeBlock(change.proposedText, fontSize = 11, maxLines = 40)
                        }
                    }
                    if (change.canApply) {
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.setApproved(index, true) }) {
                                Text(if (change.approved) "✓ Approved" else stringResource(R.string.action_approve))
                            }
                            OutlinedButton(onClick = { vm.setApproved(index, false) }) {
                                Text(stringResource(R.string.action_reject))
                            }
                        }
                    }
                    change.applyError?.let { ErrorBanner(it) }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { confirmApplyAll = true },
            enabled = ui.proposedChanges.any { it.approved && !it.applied },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Apply ${ui.proposedChanges.count { it.approved && !it.applied }} approved change(s)",
            )
        }
        Text(
            "Approved files are backed up before being written, and every change is recorded in the " +
                "journal so you can roll back.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    if (confirmApplyAll) {
        ConfirmDialog(
            title = "Apply approved changes?",
            body = "Only the files you ticked will be written. A backup of each original is kept first. " +
                "Nothing is executed or tested.",
            confirmLabel = stringResource(R.string.action_apply),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = {
                vm.applyApproved()
                confirmApplyAll = false
            },
            onDismiss = { confirmApplyAll = false },
        )
    }
}
