package dev.varun.studio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.varun.studio.R
import dev.varun.studio.ui.theme.CodeFamily
import dev.varun.studio.ui.theme.LocalCodeColors
import dev.varun.studio.workspace.DiffEngine

// ------------------------------------------------------------------ banners

@Composable
fun ErrorBanner(message: String?, onDismiss: (() -> Unit)? = null) {
    if (message.isNullOrBlank()) return
    Banner(
        text = message,
        container = MaterialTheme.colorScheme.errorContainer,
        content = MaterialTheme.colorScheme.onErrorContainer,
        icon = Icons.Filled.Error,
        onDismiss = onDismiss,
    )
}

@Composable
fun InfoBanner(message: String?, onDismiss: (() -> Unit)? = null) {
    if (message.isNullOrBlank()) return
    Banner(
        text = message,
        container = MaterialTheme.colorScheme.secondaryContainer,
        content = MaterialTheme.colorScheme.onSecondaryContainer,
        icon = Icons.Filled.Info,
        onDismiss = onDismiss,
    )
}

@Composable
fun WarningBanner(message: String?, onDismiss: (() -> Unit)? = null) {
    if (message.isNullOrBlank()) return
    Banner(
        text = message,
        container = MaterialTheme.colorScheme.tertiaryContainer,
        content = MaterialTheme.colorScheme.onTertiaryContainer,
        icon = Icons.Filled.Warning,
        onDismiss = onDismiss,
    )
}

@Composable
private fun Banner(
    text: String,
    container: Color,
    content: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onDismiss: (() -> Unit)?,
) {
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (onDismiss != null) {
                TextButton(onClick = onDismiss, modifier = Modifier.height(28.dp)) {
                    Text(androidx.compose.ui.res.stringResource(R.string.action_close))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ progress

@Composable
fun BusyRow(label: String, progress: Float? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
        if (progress == null) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

// -------------------------------------------------------------------- cards

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 5.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                trailing?.invoke()
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun KeyValueRow(label: String, value: String, monospace: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(132.dp),
        )
        Text(
            value,
            style = if (monospace) MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFamily)
            else MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}

// -------------------------------------------------------------- code & diff

/**
 * A horizontally scrollable monospace code block. Horizontal scrolling matters
 * on a narrow phone: wrapping code destroys the indentation that makes it
 * readable.
 */
@Composable
fun CodeBlock(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    fontSize: Int = 12,
) {
    val scroll = rememberScrollState()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = CodeFamily,
                fontSize = fontSize.sp(),
                lineHeight = (fontSize + 5).sp(),
            ),
            maxLines = maxLines,
            overflow = TextOverflow.Visible,
            softWrap = false,
            modifier = Modifier.horizontalScroll(scroll),
        )
    }
}

private fun Int.sp() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)

/**
 * Rendered unified diff. Colours come from the theme's CodeColors so light and
 * dark both stay legible.
 */
@Composable
fun DiffView(result: DiffEngine.DiffResult, modifier: Modifier = Modifier, fontSize: Int = 12) {
    val code = LocalCodeColors.current
    if (result.identical) {
        Text(
            androidx.compose.ui.res.stringResource(R.string.diff_no_changes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(8.dp),
        )
        return
    }
    val scroll = rememberScrollState()
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Text(
                androidx.compose.ui.res.stringResource(R.string.diff_added, result.added),
                style = MaterialTheme.typography.labelSmall,
                color = code.addedFg,
                modifier = Modifier.background(code.addedBg, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                androidx.compose.ui.res.stringResource(R.string.diff_removed, result.removed),
                style = MaterialTheme.typography.labelSmall,
                color = code.removedFg,
                modifier = Modifier.background(code.removedBg, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Column(Modifier.horizontalScroll(scroll)) {
            for (line in result.lines) {
                val (bg, fg, prefix) = when (line.kind) {
                    DiffEngine.LineKind.ADDED -> Triple(code.addedBg, code.addedFg, "+")
                    DiffEngine.LineKind.REMOVED -> Triple(code.removedBg, code.removedFg, "-")
                    DiffEngine.LineKind.CONTEXT -> Triple(Color.Transparent, MaterialTheme.colorScheme.onSurface, " ")
                }
                Row(
                    Modifier.fillMaxWidth().background(bg).padding(vertical = 0.5.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = line.oldNumber.takeIf { it > 0 }?.toString() ?: "",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFamily),
                        color = code.lineNumber,
                        modifier = Modifier.width(34.dp),
                        maxLines = 1,
                    )
                    Text(
                        text = line.newNumber.takeIf { it > 0 }?.toString() ?: "",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFamily),
                        color = code.lineNumber,
                        modifier = Modifier.width(34.dp),
                        maxLines = 1,
                    )
                    Text(
                        text = "$prefix${line.text}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = CodeFamily,
                            fontSize = fontSize.sp(),
                        ),
                        color = fg,
                        softWrap = false,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ dialogs

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmLabel,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
