package dev.varun.studio.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.varun.studio.R
import dev.varun.studio.ai.MemoryEstimator
import dev.varun.studio.core.DeviceMemory
import dev.varun.studio.ui.components.ErrorBanner
import dev.varun.studio.ui.components.KeyValueRow
import dev.varun.studio.ui.components.SectionCard
import dev.varun.studio.ui.components.WarningBanner
import dev.varun.studio.ui.vm.HomeViewModel

/**
 * Dashboard.
 *
 * The first thing a user needs to know is whether this phone can actually run
 * a model, so RAM, storage, ABI and runtime availability are on the first
 * screen rather than buried in diagnostics.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onOpenModels: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenRepos: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenAudit: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val ui by vm.ui.collectAsState()

    LaunchedEffect(Unit) { vm.refresh() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.home_title)) })

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        ) {
            // ------------------------------------------------ runtime health
            if (!ui.runtimeAvailable) {
                ErrorBanner(
                    "The native inference runtime could not load" +
                        (ui.runtimeProblem?.let { ": $it" } ?: "") +
                        ". GitHub browsing, the editor, diffs and commits still work; " +
                        "on-device AI does not. You need an arm64-v8a build of the APK.",
                )
            } else if (!ui.isArm64) {
                WarningBanner(
                    "This device reports ABIs ${ui.abis.joinToString()}. The APK ships arm64-v8a; " +
                        "on-device inference may be unavailable.",
                )
            }

            // ------------------------------------------------------------ model
            SectionCard(
                title = stringResource(R.string.home_model_card),
                subtitle = if (ui.runtimeAvailable) {
                    stringResource(R.string.settings_runtime_value, ui.runtimeVersion)
                } else null,
            ) {
                val stats = ui.engineStats
                if (stats != null && stats.loaded) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.home_model_ready),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    KeyValueRow("Model", ui.loadedModel?.displayName ?: stats.description)
                    KeyValueRow("Architecture", stats.description)
                    KeyValueRow("Quantization", stats.ftype)
                    KeyValueRow("Parameters", MemoryEstimator.humanParams(stats.nParams))
                    KeyValueRow("Weights", MemoryEstimator.humanBytes(stats.modelBytes))
                    KeyValueRow(stringResource(R.string.models_context), "${stats.nCtx} tokens (trained ${stats.nCtxTrain})")
                    KeyValueRow(stringResource(R.string.models_threads), "${stats.nThreads}")
                    if (stats.lastGeneratedTokens > 0) {
                        KeyValueRow(
                            stringResource(R.string.models_speed),
                            String.format(java.util.Locale.US, "%.1f tok/s (prompt %.1f tok/s)", stats.tokensPerSec, stats.promptTokensPerSec),
                        )
                    }
                    ui.modelEstimate?.let { est ->
                        Spacer(Modifier.height(6.dp))
                        KeyValueRow(stringResource(R.string.models_estimated_ram), MemoryEstimator.humanBytes(est.totalBytes))
                        KeyValueRow("  weights", MemoryEstimator.humanBytes(est.modelBytes))
                        KeyValueRow("  KV cache", MemoryEstimator.humanBytes(est.kvCacheBytes))
                        KeyValueRow("  compute + overhead", MemoryEstimator.humanBytes(est.computeBytes + est.overheadBytes))
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.unloadModel() }) {
                            Text(stringResource(R.string.action_unload))
                        }
                        Button(onClick = onOpenChat) { Text(stringResource(R.string.nav_chat)) }
                    }
                } else {
                    Icon(
                        Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.home_no_model), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.home_no_model_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onOpenModels) { Text(stringResource(R.string.nav_models)) }
                }
            }

            // ---------------------------------------------------------- memory
            ui.memory?.let { mem ->
                SectionCard(title = stringResource(R.string.home_memory)) {
                    KeyValueRow(stringResource(R.string.home_device_ram), MemoryEstimator.humanBytes(mem.totalRamBytes))
                    KeyValueRow(stringResource(R.string.home_free_ram), MemoryEstimator.humanBytes(mem.availableRamBytes))
                    KeyValueRow(
                        "Usable for a model",
                        MemoryEstimator.humanBytes(mem.usableForModelBytes),
                    )
                    KeyValueRow(stringResource(R.string.home_app_limit), MemoryEstimator.humanBytes(mem.perAppHeapBytes))
                    KeyValueRow("Low-RAM device", if (mem.isLowRamDevice) "yes" else "no")
                    Text(
                        "Recommended CPU threads on this device: ${ui.recommendedThreads}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            // --------------------------------------------------------- storage
            ui.storage?.let { st ->
                SectionCard(title = stringResource(R.string.home_storage)) {
                    KeyValueRow("Free", MemoryEstimator.humanBytes(st.freeBytes))
                    KeyValueRow("Total", MemoryEstimator.humanBytes(st.totalBytes))
                    KeyValueRow("Models", MemoryEstimator.humanBytes(st.modelsBytes))
                    KeyValueRow("Workspaces", MemoryEstimator.humanBytes(st.workspacesBytes))
                    KeyValueRow("App data", MemoryEstimator.humanBytes(st.appDirBytes))
                }
            }

            // -------------------------------------------------- thermal/battery
            ui.power?.let { p ->
                SectionCard(title = stringResource(R.string.home_thermal)) {
                    KeyValueRow(stringResource(R.string.home_thermal), p.thermal.name)
                    KeyValueRow(stringResource(R.string.home_battery), if (p.batteryPercent >= 0) "${p.batteryPercent}%" else "unknown")
                    KeyValueRow("Charging", if (p.isCharging) "yes" else "no")
                    KeyValueRow("Battery saver", if (p.isPowerSaver) "yes" else "no")
                    if (p.thermal == DeviceMemory.ThermalLevel.SEVERE || p.thermal == DeviceMemory.ThermalLevel.CRITICAL) {
                        Spacer(Modifier.height(6.dp))
                        WarningBanner(
                            "The device is ${p.thermal.name.lowercase()} hot. Inference will be throttled and may " +
                                "stop. Let it cool before a long generation.",
                        )
                    }
                    if (p.isPowerSaver) {
                        Spacer(Modifier.height(6.dp))
                        WarningBanner(
                            "Battery saver is on. Android may pause a long generation when the screen turns off.",
                        )
                    }
                }
            }

            // -------------------------------------------------------- workspace
            SectionCard(
                title = stringResource(R.string.workspace_title),
                subtitle = ui.workspaceName?.let { "$it · ${ui.workspaceFiles} files" }
                    ?: stringResource(R.string.workspace_empty),
                trailing = {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenRepos) { Text(stringResource(R.string.nav_repos)) }
                }
            }

            // ------------------------------------------------------- quick links
            SectionCard(title = stringResource(R.string.home_quick_actions)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenTasks) { Text(stringResource(R.string.tasks_title)) }
                    OutlinedButton(onClick = onOpenAudit) { Text(stringResource(R.string.audit_title)) }
                    OutlinedButton(onClick = onOpenSettings) { Text(stringResource(R.string.nav_settings)) }
                }
                if (ui.activeTasks > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${ui.activeTasks} task(s) queued or running",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ------------------------------------------------------- honesty box
            SectionCard(title = "How this app works") {
                Text(
                    "AI inference runs entirely on this phone using llama.cpp. No prompt, source file or " +
                        "model output is ever sent to an AI service. The only network destination is " +
                        "github.com, used for repository access, and model downloads you start yourself.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "A 1B-3B model on a phone is useful for explaining code, spotting likely bugs and " +
                        "drafting small changes. It is not a desktop-class coding agent, and it cannot " +
                        "verify its own fixes — every proposed change is shown as a diff and needs your " +
                        "approval.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
