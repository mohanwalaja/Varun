package dev.varun.studio.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.varun.studio.R
import dev.varun.studio.ui.components.CodeBlock
import dev.varun.studio.ui.components.ConfirmDialog
import dev.varun.studio.ui.components.DiffView
import dev.varun.studio.ui.components.EmptyState
import dev.varun.studio.ui.components.ErrorBanner
import dev.varun.studio.ui.components.InfoBanner
import dev.varun.studio.ui.components.KeyValueRow
import dev.varun.studio.ui.components.SectionCard
import dev.varun.studio.ui.components.WarningBanner
import dev.varun.studio.ui.theme.CodeFamily
import dev.varun.studio.ui.vm.ChatViewModel
import dev.varun.studio.ui.vm.WorkspaceViewModel
import dev.varun.studio.workspace.EditJournal

/**
 * Local workspace: file tree, mobile code editor, diff viewer, local search,
 * change journal with rollback, and staging for the GitHub commit workflow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    vm: WorkspaceViewModel,
    chatVm: ChatViewModel,
    onBack: () -> Unit,
    onOpenChat: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val archivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importArchive(uri, "imported-archive")
    }

    LaunchedEffect(Unit) {
        vm.refresh()
        vm.refreshJournal()
        vm.refreshChanged()
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(stringResource(R.string.workspace_title), style = MaterialTheme.typography.titleMedium)
                    ui.current?.let {
                        Text(
                            "${it.name} · ${it.fileCount} files · ${it.totalBytes / 1024} KB",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            },
            actions = { TextButton(onClick = onOpenChat) { Text(stringResource(R.string.nav_chat)) } },
        )

        Column(Modifier.padding(horizontal = 12.dp)) {
            ErrorBanner(ui.error) { vm.dismiss() }
            InfoBanner(ui.info) { vm.dismiss() }
            ui.busy?.let { dev.varun.studio.ui.components.BusyRow(it) }
        }

        if (ui.current == null) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            ) {
                EmptyState(stringResource(R.string.workspace_empty))
                Button(
                    onClick = { archivePicker.launch(arrayOf("application/zip", "application/gzip", "*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.workspace_import_zip)) }
                Spacer(Modifier.height(12.dp))
                if (ui.workspaces.isNotEmpty()) {
                    SectionCard(title = "Existing workspaces") {
                        ui.workspaces.forEach { ws ->
                            Row(
                                Modifier.fillMaxWidth().clickable { vm.openWorkspace(ws.name) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(ws.name, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "${ws.fileCount} files · ${ws.totalBytes / 1024} KB · ${ws.origin ?: "local"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { vm.deleteWorkspace(ws.name) }) {
                                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
            return
        }

        // ---------------------------------------------------------------- tabs
        var tab by remember { mutableStateOf(0) }
        val tabLabels = listOf("Files", "Editor", "Search", "Changes", "Journal")
        androidx.compose.material3.ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            tabLabels.forEachIndexed { i, label ->
                androidx.compose.material3.Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = { Text(label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        when (tab) {
            0 -> FilesTab(vm, ui, archivePicker = { archivePicker.launch(arrayOf("*/*")) })
            1 -> EditorTab(vm, ui)
            2 -> SearchTab(vm, ui)
            3 -> ChangesTab(vm, ui, chatVm)
            4 -> JournalTab(vm, ui)
        }
    }
}

@Composable
private fun FilesTab(vm: WorkspaceViewModel, ui: WorkspaceViewModel.UiState, archivePicker: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (ui.pathStack.isEmpty()) "/" else "/" + ui.pathStack.joinToString("/"),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFamily),
                modifier = Modifier.weight(1f),
            )
            if (ui.pathStack.isNotEmpty()) TextButton(onClick = { vm.upOneLevel() }) { Text("Up") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = archivePicker) { Text(stringResource(R.string.workspace_import_zip)) }
            OutlinedButton(onClick = { vm.refresh() }) { Text(stringResource(R.string.action_refresh)) }
        }
        Text(
            "Tap a file to open it. Tick files to attach them to an AI request " +
                "(${ui.selectedForAi.size} selected, ~${ui.tokenEstimate} tokens).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        LazyColumn {
            items(ui.nodes, key = { it.relativePath }) { node ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!node.isDirectory) {
                        Checkbox(
                            checked = ui.selectedForAi.contains(node.relativePath),
                            onCheckedChange = { vm.toggleSelectionForAi(node.relativePath) },
                        )
                    } else {
                        Spacer(Modifier.width(48.dp))
                    }
                    Icon(
                        if (node.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp).clickable { vm.openFile(node) },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(
                        Modifier.weight(1f).clickable { vm.openFile(node) },
                    ) {
                        Text(node.name, style = MaterialTheme.typography.bodyMedium)
                        if (!node.isDirectory) {
                            Text(
                                "${node.sizeBytes / 1024} KB · ${node.relativePath}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun EditorTab(vm: WorkspaceViewModel, ui: WorkspaceViewModel.UiState) {
    val file = ui.openFile
    if (file == null) {
        EmptyState("Open a file from the Files tab.")
        return
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(file.relativePath, style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFamily))
                Text(
                    stringResource(R.string.editor_lines, file.lineCount) +
                        if (file.dirty) " · " + stringResource(R.string.editor_unsaved) else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (file.dirty) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { vm.toggleDiff() }) {
                Text(if (ui.showDiff) "Code" else stringResource(R.string.diff_title))
            }
            TextButton(onClick = { vm.closeFile() }) { Text(stringResource(R.string.action_close)) }
        }
        file.error?.let { ErrorBanner(it) }
        if (file.readOnly) {
            WarningBanner(stringResource(R.string.editor_read_only))
            CodeBlock(file.original, fontSize = 11, maxLines = 80)
            return
        }
        if (ui.showDiff && ui.diff != null) {
            DiffView(ui.diff!!, modifier = Modifier.weight(1f), fontSize = 11)
        } else {
            OutlinedTextField(
                value = file.draft,
                onValueChange = { vm.onDraftChange(it) },
                modifier = Modifier.fillMaxWidth().weight(1f).imePadding(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFamily),
                // Single line would be wrong for code; allow scrolling instead of wrapping
                // so indentation stays readable on a narrow screen.
                singleLine = false,
                maxLines = Int.MAX_VALUE,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            Button(onClick = { vm.saveFile() }, enabled = file.dirty) {
                Text(stringResource(R.string.action_save))
            }
            OutlinedButton(onClick = { vm.revertDraft() }, enabled = file.dirty) {
                Text("Revert")
            }
            OutlinedButton(
                onClick = { vm.rollback(file.relativePath) },
            ) { Text("Roll back to backup") }
        }
        Text(
            "Saving keeps a timestamped backup of the previous version and records the change in the " +
                "journal, so you can roll back even after several edits.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SearchTab(vm: WorkspaceViewModel, ui: WorkspaceViewModel.UiState) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = ui.searchQuery,
                onValueChange = { vm.setSearchQuery(it) },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.github_search_placeholder)) },
                singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { vm.search() }) { Text(stringResource(R.string.action_search)) }
        }
        if (ui.searching) dev.varun.studio.ui.components.BusyRow(stringResource(R.string.state_working))
        ui.searchResult?.let { r ->
            Text(
                "${r.matches.size} match(es) in ${r.filesScanned} file(s)" +
                    if (r.filesSkipped > 0) ", ${r.filesSkipped} skipped (binary or too large)" else "" +
                    if (r.truncated) " — truncated at the match limit" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            LazyColumn {
                items(r.matches.take(500), key = { "${it.relativePath}:${it.lineNumber}" }) { m ->
                    Column(
                        Modifier.fillMaxWidth().clickable {
                            vm.enterDir(m.relativePath.substringBeforeLast('/', ""))
                        }.padding(vertical = 4.dp),
                    ) {
                        Text(
                            "${m.relativePath}:${m.lineNumber}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFamily),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            m.line,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFamily),
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChangesTab(vm: WorkspaceViewModel, ui: WorkspaceViewModel.UiState, chatVm: ChatViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        SectionCard(
            title = "Local changes",
            subtitle = "${ui.changed.size} file(s) differ from the imported snapshot",
            trailing = { TextButton(onClick = { vm.refreshChanged() }) { Text(stringResource(R.string.action_refresh)) } },
        ) {
            if (ui.changed.isEmpty()) {
                EmptyState("No local changes yet. Edit a file, or ask the assistant to propose one.")
            } else {
                ui.changed.forEach { (path, text) ->
                    val diff = vm.diffAgainstBackup(path)
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        elevation = CardDefaults.cardElevation(0.dp),
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(path, style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFamily))
                            if (diff != null) {
                                Text(
                                    "+${diff.added} / -${diff.removed} vs backup",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                DiffView(diff, fontSize = 11)
                            } else {
                                Text(
                                    "No backup to compare against (this file may be new).",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                CodeBlock(text.take(2000), fontSize = 11, maxLines = 20)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { vm.rollback(path) }) { Text("Roll back") }
                            }
                        }
                    }
                }
            }
        }
        SectionCard(
            title = "Send to GitHub",
            subtitle = "Branch → commit → push, each step confirmed separately",
        ) {
            Text(
                "Open the Repos tab, select the repository, create a branch, then use " +
                    "“Prepare commit from workspace”. Varun shows the exact file list and message and " +
                    "will not push without your explicit confirmation. It never force-pushes, never " +
                    "deletes a branch, and refuses the default branch unless you override that in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun JournalTab(vm: WorkspaceViewModel, ui: WorkspaceViewModel.UiState) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        SectionCard(
            title = "Change journal",
            subtitle = "${ui.journal.size} recorded event(s) · append-only",
            trailing = { TextButton(onClick = { vm.refreshJournal() }) { Text(stringResource(R.string.action_refresh)) } },
        ) {
            if (ui.journal.isEmpty()) {
                EmptyState("Nothing journaled yet.")
            } else {
                LazyColumn(Modifier.heightIn(max = 520.dp)) {
                    items(ui.journal, key = { it.id }) { entry ->
                        JournalRow(entry) { vm.rollback(entry.relativePath) }
                    }
                }
            }
        }
        Text(
            "The journal is append-only plain text inside the workspace. A crash mid-write can only " +
                "lose the last line, never corrupt earlier records.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
}

@Composable
private fun JournalRow(entry: EditJournal.Entry, onRollback: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            when (entry.kind) {
                EditJournal.EntryKind.APPLIED -> Icons.Filled.Check
                EditJournal.EntryKind.REJECTED -> Icons.Filled.Close
                EditJournal.EntryKind.ROLLED_BACK -> Icons.AutoMirrored.Filled.ArrowBack
                else -> Icons.Filled.InsertDriveFile
            },
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = when (entry.kind) {
                EditJournal.EntryKind.APPLIED -> MaterialTheme.colorScheme.tertiary
                EditJournal.EntryKind.REJECTED -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${entry.kind.name} · ${entry.relativePath}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
            )
            Text(
                buildString {
                    append(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                        .format(java.util.Date(entry.timestamp)))
                    if (entry.bytes > 0) append(" · ${entry.bytes / 1024} KB")
                    entry.backupPath?.let { append(" · backup: $it") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (entry.kind == EditJournal.EntryKind.APPLIED) {
            TextButton(onClick = onRollback) { Text("Roll back") }
        }
    }
}
