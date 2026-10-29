package dev.varun.studio.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.varun.studio.R
import dev.varun.studio.data.db.TaskRecord
import dev.varun.studio.ui.components.CodeBlock
import dev.varun.studio.ui.components.EmptyState
import dev.varun.studio.ui.components.SectionCard
import dev.varun.studio.ui.theme.CodeFamily
import dev.varun.studio.ui.vm.TasksViewModel

/** Task queue: every long-running operation, its progress and its outcome. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(vm: TasksViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(stringResource(R.string.tasks_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${ui.active} active · ${ui.finished} finished",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            },
            actions = {
                TextButton(onClick = { vm.clearFinished() }) { Text("Clear finished") }
            },
        )
        if (ui.tasks.isEmpty()) {
            EmptyState(stringResource(R.string.tasks_empty))
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(ui.tasks, key = { it.id }) { task -> TaskRow(task) }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun TaskRow(task: TaskRecord) {
    val (label, color) = when (task.state) {
        "RUNNING" -> stringResource(R.string.tasks_running) to MaterialTheme.colorScheme.primary
        "QUEUED" -> stringResource(R.string.tasks_queued) to MaterialTheme.colorScheme.onSurfaceVariant
        "DONE" -> stringResource(R.string.tasks_done) to MaterialTheme.colorScheme.tertiary
        "FAILED" -> stringResource(R.string.tasks_failed) to MaterialTheme.colorScheme.error
        "CANCELLED" -> stringResource(R.string.tasks_cancelled) to MaterialTheme.colorScheme.onSurfaceVariant
        else -> task.state to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(task.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                    Text(
                        "${task.kind} · ${formatTime(task.updatedAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(label, style = MaterialTheme.typography.labelSmall, color = color)
            }
            if (task.state == "RUNNING" || task.state == "QUEUED") {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { task.progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            task.detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            task.errorMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

private fun formatTime(epochMs: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
        .format(java.util.Date(epochMs))

/** Audit reports produced by the repository-audit task. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditScreen(vm: TasksViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.audit_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            },
        )
        val report = ui.openReport
        if (report != null) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            ) {
                Text(report.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${formatTime(report.createdAt)} · ${stringResource(R.string.audit_findings, report.findings)} · " +
                        if (report.testedLocally) "locally tested" else stringResource(R.string.audit_untested),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                report.modelName?.let {
                    Text("Model: $it", style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(10.dp))
                SectionCard(title = "Report") {
                    CodeBlock(report.markdown, fontSize = 11)
                }
                SectionCard(title = "Honesty notice") {
                    Text(
                        "This report was produced by a small on-device model reading only the files you " +
                            "selected. No build, test suite, linter or vulnerability scanner was executed, " +
                            "because none can run inside this app. Treat every finding as a lead to verify, " +
                            "not as a confirmed result.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { vm.closeReport() }) { Text(stringResource(R.string.action_close)) }
                Spacer(Modifier.height(16.dp))
            }
            return
        }
        if (ui.reports.isEmpty()) {
            EmptyState(stringResource(R.string.audit_empty))
            Column(Modifier.padding(16.dp)) {
                Text(
                    "To create one: open a workspace, attach the files you want reviewed, then choose " +
                        "“Repository audit” in the assistant and send. The result is saved here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return
        }
        LazyColumn(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(ui.reports, key = { it.id }) { r ->
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(1.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                            Text(
                                "${formatTime(r.createdAt)} · ${stringResource(R.string.audit_findings, r.findings)}" +
                                    if (r.testedLocally) "" else " · ${stringResource(R.string.audit_untested)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { vm.openReport(r.id) }) { Text("Open") }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
