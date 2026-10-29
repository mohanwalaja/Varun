package dev.varun.studio.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.varun.studio.R
import dev.varun.studio.ai.MemoryEstimator
import dev.varun.studio.ai.ModelCatalog
import dev.varun.studio.data.db.ModelRecord
import dev.varun.studio.data.repo.ModelRepository
import dev.varun.studio.ui.components.ConfirmDialog
import dev.varun.studio.ui.components.EmptyState
import dev.varun.studio.ui.components.ErrorBanner
import dev.varun.studio.ui.components.InfoBanner
import dev.varun.studio.ui.components.KeyValueRow
import dev.varun.studio.ui.components.SectionCard
import dev.varun.studio.ui.components.WarningBanner
import dev.varun.studio.ui.vm.ModelsViewModel

/**
 * Model manager: import from storage, download with explicit consent, load,
 * unload, delete — always showing size, format and a real RAM estimate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(vm: ModelsViewModel) {
    val ui by vm.ui.collectAsState()
    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFromUri(uri)
    }

    var pendingDownload by remember { mutableStateOf<ModelCatalog.Entry?>(null) }
    var pendingDelete by remember { mutableStateOf<ModelRecord?>(null) }
    var pendingUnload by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refresh() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.models_title)) })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        ) {
            InfoBanner(ui.message) { vm.clearMessage() }

            if (ui.loadState is ModelRepository.LoadUiState.Failed) {
                val f = ui.loadState as ModelRepository.LoadUiState.Failed
                ErrorBanner("${f.message}\n\n${f.advice}")
            }
            if (ui.loadState is ModelRepository.LoadUiState.Progress) {
                val p = ui.loadState as ModelRepository.LoadUiState.Progress
                Column(Modifier.padding(vertical = 6.dp)) {
                    LinearProgressIndicator(
                        progress = { p.fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(p.message, style = MaterialTheme.typography.bodySmall)
                }
            }

            // ------------------------------------------------------- device RAM
            SectionCard(
                title = "Device budget",
                subtitle = "Computed from RAM Android reports as available right now",
            ) {
                KeyValueRow("Total RAM", MemoryEstimator.humanBytes(ui.totalRamBytes))
                KeyValueRow("Usable for a model", MemoryEstimator.humanBytes(ui.availableRamBytes))
                KeyValueRow("Free storage", MemoryEstimator.humanBytes(ui.freeStorageBytes))
                Text(
                    "Never assume 8 GB of RAM means an 8 GB model fits: Android reserves a large " +
                        "share, and weights + KV cache + compute buffers are all resident at once.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            // ------------------------------------------------------ import SAF
            SectionCard(
                title = stringResource(R.string.models_import),
                subtitle = stringResource(R.string.models_import_hint),
            ) {
                Button(
                    onClick = { pickModel.launch(arrayOf("*/*")) },
                    enabled = !ui.busy,
                ) { Text(stringResource(R.string.action_import)) }
            }

            // --------------------------------------------------- installed list
            SectionCard(title = "On this phone", subtitle = "${ui.models.size} model file(s)") {
                if (ui.models.isEmpty()) {
                    EmptyState(stringResource(R.string.state_empty))
                } else {
                    ui.models.forEach { record ->
                        ModelRow(
                            record = record,
                            isLoaded = ui.loadedModelId == record.id,
                            estimate = vm.estimateFor(record),
                            availableRam = ui.availableRamBytes,
                            busy = ui.busy,
                            onLoad = { vm.load(record.id) },
                            onUnload = { pendingUnload = true },
                            onDelete = { pendingDelete = record },
                        )
                    }
                }
            }

            // --------------------------------------------------------- catalog
            SectionCard(
                title = stringResource(R.string.models_catalog),
                subtitle = stringResource(R.string.models_catalog_hint),
            ) {
                ui.catalog.forEach { row ->
                    CatalogRow(
                        row = row,
                        busy = ui.busy,
                        downloading = ui.download.active && ui.download.entryId == row.entry.id,
                        downloadFraction = if (ui.download.entryId == row.entry.id) ui.download.fraction else 0f,
                        onDownload = { pendingDownload = row.entry },
                        onCancel = { vm.cancelDownload() },
                    )
                }
                if (ui.download.error != null) ErrorBanner(ui.download.error)
            }

            SectionCard(title = stringResource(R.string.settings_licenses)) {
                Text(
                    "Runtime: llama.cpp (MIT), built from source at a pinned commit.\n" +
                        "Models keep their own licenses. Qwen2.5-Coder is Apache-2.0. Gemma and Llama " +
                        "have their own terms which YOU must accept — Varun does not redistribute any " +
                        "model file; it only downloads from the publisher when you ask it to.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    pendingDownload?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.models_consent_title),
            body = stringResource(
                R.string.models_consent_body,
                entry.displayName,
                entry.sizeLabel,
            ) + "\n\nLicense: ${entry.license}\n\n${entry.notes}",
            confirmLabel = stringResource(R.string.action_download),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = {
                vm.download(entry, userConsented = true)
                pendingDownload = null
            },
            onDismiss = { pendingDownload = null },
        )
    }

    pendingDelete?.let { record ->
        ConfirmDialog(
            title = stringResource(R.string.models_delete_title),
            body = stringResource(R.string.models_delete_body) + "\n\n${record.fileName} · " +
                MemoryEstimator.humanBytes(record.sizeBytes),
            confirmLabel = stringResource(R.string.action_delete),
            dismissLabel = stringResource(R.string.action_cancel),
            destructive = true,
            onConfirm = {
                vm.delete(record.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    if (pendingUnload) {
        ConfirmDialog(
            title = stringResource(R.string.models_unload_confirm),
            body = "This frees the RAM the model is holding. You can load it again at any time.",
            confirmLabel = stringResource(R.string.action_unload),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = {
                vm.unload()
                pendingUnload = false
            },
            onDismiss = { pendingUnload = false },
        )
    }
}

@Composable
private fun ModelRow(
    record: ModelRecord,
    isLoaded: Boolean,
    estimate: MemoryEstimator.Estimate,
    availableRam: Long,
    busy: Boolean,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(record.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    record.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isLoaded) {
                Text(
                    stringResource(R.string.models_loaded_now),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        KeyValueRow(stringResource(R.string.models_size), MemoryEstimator.humanBytes(record.sizeBytes))
        KeyValueRow(stringResource(R.string.models_format), record.format)
        KeyValueRow(stringResource(R.string.models_quant), record.quantTag ?: "unknown")
        KeyValueRow(
            "Estimated params",
            MemoryEstimator.humanParams(estimate.estimatedParams) +
                if (estimate.bitsPerWeight > 0) " @ ${String.format(java.util.Locale.US, "%.2f", estimate.bitsPerWeight)} bits/weight" else "",
        )
        KeyValueRow(stringResource(R.string.models_estimated_ram), MemoryEstimator.humanBytes(estimate.totalBytes))
        KeyValueRow("  of which KV cache", MemoryEstimator.humanBytes(estimate.kvCacheBytes))
        KeyValueRow("Available RAM", MemoryEstimator.humanBytes(availableRam))

        when (estimate.fit) {
            MemoryEstimator.Fit.OK -> Text(
                stringResource(R.string.models_fit_ok),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            MemoryEstimator.Fit.TIGHT -> WarningBanner(stringResource(R.string.models_fit_tight))
            MemoryEstimator.Fit.UNLIKELY -> ErrorBanner(stringResource(R.string.models_fit_warning))
        }
        if (record.origin.startsWith("catalog:")) {
            Text(
                "Source: catalogue download",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isLoaded) {
                OutlinedButton(onClick = onUnload, enabled = !busy) { Text(stringResource(R.string.action_unload)) }
            } else {
                Button(onClick = onLoad, enabled = !busy) { Text(stringResource(R.string.action_load)) }
            }
            TextButton(onClick = onDelete, enabled = !busy) {
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun CatalogRow(
    row: ModelsViewModel.CatalogRow,
    busy: Boolean,
    downloading: Boolean,
    downloadFraction: Float,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    val entry = row.entry
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.displayName, style = MaterialTheme.typography.titleSmall)
                    if (entry.recommended) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "RECOMMENDED",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    "${entry.params} · ${entry.quant} · ${entry.sizeLabel} · ctx ${entry.contextTokens}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(entry.notes, style = MaterialTheme.typography.bodySmall)
        Text(
            "License: ${entry.license}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        KeyValueRow(stringResource(R.string.models_estimated_ram), MemoryEstimator.humanBytes(row.estimate.totalBytes))
        when (row.estimate.fit) {
            MemoryEstimator.Fit.OK -> Text(
                stringResource(R.string.models_fit_ok),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            MemoryEstimator.Fit.TIGHT -> WarningBanner(stringResource(R.string.models_fit_tight))
            MemoryEstimator.Fit.UNLIKELY -> ErrorBanner(stringResource(R.string.models_fit_warning))
        }
        if (downloading) {
            LinearProgressIndicator(
                progress = { downloadFraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${(downloadFraction * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            }
        } else {
            Button(
                onClick = onDownload,
                enabled = !busy,
            ) {
                Text(
                    if (row.alreadyImported) "Download again" else stringResource(R.string.action_download),
                )
            }
        }
    }
}
