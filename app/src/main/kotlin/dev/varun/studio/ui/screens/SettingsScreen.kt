package dev.varun.studio.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import dev.varun.studio.ai.MemoryEstimator
import dev.varun.studio.data.repo.SettingsRepository
import dev.varun.studio.ui.components.CodeBlock
import dev.varun.studio.ui.components.ConfirmDialog
import dev.varun.studio.ui.components.InfoBanner
import dev.varun.studio.ui.components.KeyValueRow
import dev.varun.studio.ui.components.SectionCard
import dev.varun.studio.ui.components.WarningBanner
import dev.varun.studio.ui.vm.SettingsViewModel

/** Settings, safety limits, security controls, diagnostics and licensing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel) {
    val ui by vm.ui.collectAsState()
    val s = ui.settings
    val clipboard = LocalClipboardManager.current
    var showDiagnostics by remember { mutableStateOf(false) }
    var confirmErase by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refresh() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.settings_title)) })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        ) {
            InfoBanner(ui.message) { vm.setMessage(null) }

            // ------------------------------------------------------ inference
            SectionCard(
                title = stringResource(R.string.settings_inference),
                subtitle = "Lower values are safer on a phone",
            ) {
                DropdownChoice(
                    label = stringResource(R.string.settings_context_length),
                    options = SettingsRepository.ALLOWED_CONTEXTS.map { it.toString() },
                    selected = s.contextLength.toString(),
                    onSelected = { vm.setContextLength(it.toInt()) },
                    hint = "Prompt + answer must fit inside this. Raising it costs RAM for the KV cache.",
                )
                IntSliderRow(
                    label = stringResource(R.string.settings_max_tokens),
                    value = s.maxTokens,
                    range = 32..1024,
                    step = 32,
                    onChange = { vm.setMaxTokens(it) },
                )
                FloatSliderRow(
                    label = stringResource(R.string.settings_temperature),
                    value = s.temperature,
                    range = 0f..1.5f,
                    onChange = { vm.setTemperature(it) },
                    hint = "0 = deterministic. Low values (0.1-0.3) suit code.",
                )
                IntSliderRow(
                    label = stringResource(R.string.settings_threads),
                    value = s.threads,
                    range = 1..8,
                    step = 1,
                    onChange = { vm.setThreads(it) },
                    hint = stringResource(R.string.settings_threads_hint) +
                        " Recommended here: ${ui.recommendedThreads}.",
                )
                IntSliderRow(
                    label = "Prompt batch size",
                    value = s.batch,
                    range = 64..2048,
                    step = 64,
                    onChange = { vm.setBatch(it) },
                    hint = "Larger batches read a file faster but use more RAM.",
                )
                SwitchRow(
                    label = stringResource(R.string.settings_mmap),
                    checked = s.useMmap,
                    onChange = { vm.setMmap(it) },
                    hint = stringResource(R.string.settings_mmap_hint),
                )
                IntSliderRow(
                    label = stringResource(R.string.settings_history),
                    value = s.historyTurns,
                    range = 0..16,
                    step = 1,
                    onChange = { vm.setHistoryTurns(it) },
                    hint = stringResource(R.string.settings_history_hint),
                )
                SwitchRow(
                    label = "Auto-trim history to fit",
                    checked = s.autoTrimHistory,
                    onChange = { vm.setAutoTrim(it) },
                    hint = "Drops the oldest turns from the prompt instead of failing when the context fills.",
                )
                SwitchRow(
                    label = "Keep model loaded in background",
                    checked = s.keepModelLoaded,
                    onChange = { vm.setKeepModelLoaded(it) },
                    hint = "Off (recommended): the model is released when Android reports memory pressure, " +
                        "so the app survives instead of being killed.",
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { vm.applyConservativeProfile() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Apply a conservative profile for this device")
                }
            }

            // --------------------------------------------------------- limits
            SectionCard(
                title = stringResource(R.string.settings_limits),
                subtitle = "Stops the model being handed more than it can use",
            ) {
                IntSliderRow(
                    label = stringResource(R.string.settings_max_file_kb),
                    value = s.maxFileKb,
                    range = 4..512,
                    step = 4,
                    onChange = { vm.setMaxFileKb(it) },
                )
                IntSliderRow(
                    label = stringResource(R.string.settings_max_files),
                    value = s.maxFilesPerRequest,
                    range = 1..12,
                    step = 1,
                    onChange = { vm.setMaxFiles(it) },
                )
                IntSliderRow(
                    label = "Max repository import (MB)",
                    value = s.maxWorkspaceImportMb,
                    range = 16..512,
                    step = 16,
                    onChange = { vm.setMaxImportMb(it) },
                )
                SwitchRow(
                    label = "Require confirmation before push",
                    checked = s.requireConfirmBeforePush,
                    onChange = { vm.setConfirmPush(it) },
                    hint = "Always shows the exact file list and commit message first.",
                )
                SwitchRow(
                    label = "Refuse commits to the default branch",
                    checked = s.refuseDefaultBranchCommits,
                    onChange = { vm.setRefuseDefaultBranch(it) },
                    hint = "On (recommended): Varun insists on a dedicated branch. Varun never force-pushes " +
                        "and never deletes a branch, regardless of this setting.",
                )
                SwitchRow(
                    label = "Warn when the device is hot",
                    checked = s.warnOnThermal,
                    onChange = { vm.setWarnThermal(it) },
                )
                SwitchRow(
                    label = "Pause generation in battery saver",
                    checked = s.pauseOnBatterySaver,
                    onChange = { vm.setPauseOnSaver(it) },
                )
            }

            // ----------------------------------------------------- appearance
            SectionCard(title = stringResource(R.string.settings_appearance)) {
                DropdownChoice(
                    label = stringResource(R.string.settings_theme),
                    options = listOf("system", "light", "dark"),
                    selected = s.themeMode,
                    onSelected = { vm.setThemeMode(it) },
                    labels = listOf(
                        stringResource(R.string.settings_theme_system),
                        stringResource(R.string.settings_theme_light),
                        stringResource(R.string.settings_theme_dark),
                    ),
                )
                DropdownChoice(
                    label = stringResource(R.string.settings_language),
                    options = listOf("system", "en", "ta"),
                    selected = s.languageMode,
                    onSelected = { vm.setLanguage(it) },
                    labels = listOf(
                        stringResource(R.string.settings_lang_system),
                        stringResource(R.string.settings_lang_en),
                        stringResource(R.string.settings_lang_ta),
                    ),
                    hint = "English and தமிழ். Below Android 13 the change applies after restarting the app.",
                )
                FloatSliderRow(
                    label = "Text size",
                    value = s.fontSizeScale,
                    range = 0.85f..1.4f,
                    onChange = { vm.setFontScale(it) },
                )
            }

            // ------------------------------------------------------- security
            SectionCard(
                title = stringResource(R.string.settings_security),
                subtitle = stringResource(R.string.settings_security_body),
            ) {
                KeyValueRow("Keystore encryption", if (ui.keystoreAvailable) "available" else "UNAVAILABLE")
                KeyValueRow("GitHub token stored", if (ui.tokenStored) "yes (encrypted)" else "no")
                if (!ui.keystoreAvailable) {
                    WarningBanner(
                        "The Android Keystore is not usable on this device, so the GitHub token cannot be " +
                            "encrypted. Do not connect GitHub here.",
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "What leaves this phone: only requests to github.com when you browse or push, and model " +
                        "downloads you start yourself. Prompts, source files and model output never leave the " +
                        "device. Backup of tokens, models and workspaces is disabled at the manifest level.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SwitchRow(
                    label = "Verbose logging",
                    checked = s.verboseLogging,
                    onChange = { vm.setVerbose(it) },
                    hint = "Off by default. Even when on, every message is passed through a redactor that " +
                        "strips tokens, bearer headers, private keys and JWTs.",
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { vm.unloadModel() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.action_unload) + " model") }
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = { confirmErase = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.settings_erase_all)) }
            }

            // --------------------------------------------------- diagnostics
            SectionCard(title = stringResource(R.string.settings_diagnostics)) {
                KeyValueRow(stringResource(R.string.settings_version), ui.appVersion)
                KeyValueRow(stringResource(R.string.settings_runtime), ui.runtimeVersion)
                KeyValueRow("Native runtime", if (ui.runtimeAvailable) "loaded" else "NOT available")
                ui.runtimeProblem?.let { WarningBanner(it) }
                KeyValueRow("Device ABIs", ui.abis.joinToString(", "))
                KeyValueRow("arm64-v8a present", if (ui.isArm64) "yes" else "no")
                ui.memory?.let {
                    KeyValueRow(stringResource(R.string.home_device_ram), MemoryEstimator.humanBytes(it.totalRamBytes))
                    KeyValueRow(stringResource(R.string.home_free_ram), MemoryEstimator.humanBytes(it.availableRamBytes))
                }
                ui.storage?.let {
                    KeyValueRow("Free storage", MemoryEstimator.humanBytes(it.freeBytes))
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { showDiagnostics = !showDiagnostics }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (showDiagnostics) "Hide diagnostics" else "Show diagnostics")
                }
                if (showDiagnostics) {
                    Spacer(Modifier.height(6.dp))
                    CodeBlock(vm.diagnostics(), fontSize = 10)
                    TextButton(onClick = { clipboard.setText(AnnotatedString(vm.diagnostics())) }) {
                        Text(stringResource(R.string.action_copy))
                    }
                }
            }

            // ------------------------------------------------------- licenses
            SectionCard(title = stringResource(R.string.settings_licenses)) {
                Text(
                    "Varun Studio — see LICENSE in the repository.\n" +
                        "llama.cpp — MIT License. Built from source at a pinned commit; no prebuilt binary " +
                        "is redistributed inside this app.\n" +
                        "ggml — MIT License (part of llama.cpp).\n" +
                        "Jetpack Compose, AndroidX, Kotlin — Apache-2.0.\n" +
                        "OkHttp — Apache-2.0.\n" +
                        "Models — each GGUF keeps its own license (Apache-2.0 for Qwen2.5-Coder; Gemma and " +
                        "Llama have separate publisher terms). Varun does not bundle or redistribute any model.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // ----------------------------------------------------- limitations
            SectionCard(title = "Known limitations") {
                Text(
                    "• Inference has not been benchmarked on a physical device by this build pipeline; " +
                        "verify tokens/sec yourself before relying on it.\n" +
                        "• No local .git object store: history is read from GitHub and commits are " +
                        "constructed through the Git Data API.\n" +
                        "• No code execution, so no test can actually be run. All model fixes are UNTESTED.\n" +
                        "• Large repositories must be narrowed to specific files; importing everything is " +
                        "capped by the limit above.\n" +
                        "• Long generations may be paused by Doze when the screen is off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(20.dp))
        }
    }

    if (confirmErase) {
        ConfirmDialog(
            title = stringResource(R.string.settings_erase_all),
            body = stringResource(R.string.settings_erase_confirm),
            confirmLabel = stringResource(R.string.action_delete),
            dismissLabel = stringResource(R.string.action_cancel),
            destructive = true,
            onConfirm = {
                vm.eraseEverything()
                confirmErase = false
            },
            onDismiss = { confirmErase = false },
        )
    }
}

// ------------------------------------------------------------------ widgets

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, hint: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun IntSliderRow(
    label: String,
    value: Int,
    range: IntRange,
    step: Int,
    onChange: (Int) -> Unit,
    hint: String? = null,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("$value", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val steps = ((range.last - range.first) / step - 1).coerceAtLeast(0)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = steps,
        )
    }
}

@Composable
private fun FloatSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    hint: String? = null,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(String.format(java.util.Locale.US, "%.2f", value), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun DropdownChoice(
    label: String,
    options: List<String>,
    selected: String,
    onSelected: (String) -> Unit,
    labels: List<String> = options,
    hint: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        labels.getOrElse(options.indexOf(selected)) { selected },
                        modifier = Modifier.weight(1f),
                    )
                    Text("▾")
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEachIndexed { i, option ->
                    DropdownMenuItem(
                        text = { Text(labels.getOrElse(i) { option }) },
                        onClick = {
                            onSelected(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
