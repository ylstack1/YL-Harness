package com.androidharness.app.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.agent.packageInstallPresentation
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.data.env.PackageInstallStatus
import com.androidharness.app.ui.common.ThinLinearProgress
import com.androidharness.app.ui.theme.LocalStatusColors

/** Progress stays visible when collapsed. Only a committed tool result can say "Installed". */
@Composable
internal fun PackageInstallCard(call: ToolCallData, result: ChatMessage?, running: Boolean,
    awaitingApproval: Boolean, progress: PackageInstallStatus?) {
    var expanded by rememberSaveable(call.id) { mutableStateOf(false) }
    val info = remember(call, result, running, awaitingApproval, progress) {
        packageInstallPresentation(call, result, running, awaitingApproval, progress)
    }
    val scheme = MaterialTheme.colorScheme
    val active = running && result == null && !awaitingApproval
    Surface(onClick = { expanded = !expanded }, shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainerLow, border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = .5f)),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(32.dp).background(scheme.surfaceContainerHigh, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                    Icon(toolIcon("pkg_install"), null, Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                }
                Text(info.title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                result?.let {
                    Icon(if (it.isError) Icons.Filled.Close else Icons.Filled.CheckCircle,
                        if (it.isError) "Installation failed" else "Installation complete", Modifier.size(18.dp),
                        tint = if (it.isError) scheme.error else LocalStatusColors.current.success)
                }
                Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    if (expanded) "Hide installation details" else "Show installation details", Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
            }
            info.phase?.let { phase ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(phase, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)
                        .semantics { liveRegion = LiveRegionMode.Polite })
                    info.percentage?.let { Text("$it%", style = MaterialTheme.typography.labelLarge, color = scheme.primary) }
                }
            }
            if (active) {
                if (info.fraction != null) ThinLinearProgress(progress = { info.fraction }, modifier = Modifier.fillMaxWidth())
                else ThinLinearProgress(Modifier.fillMaxWidth())
                info.bytes?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
                info.count?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant) }
                if (info.recent.isNotEmpty()) {
                    Text("Recently unpacked · ${info.recent.joinToString(", ")}", style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (result?.isError == true) Text(result.text.lineSequence().firstOrNull().orEmpty().take(240),
                style = MaterialTheme.typography.bodySmall, color = scheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis)
            AnimatedVisibility(expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = .5f))
                    info.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
                    progress?.takeIf { active && it.packages.isNotEmpty() }?.let {
                        Text("Packages · ${it.packages.joinToString(", ")}", style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant)
                    }
                    result?.let { MonoBlock(it.text.take(4000)) }
                }
            }
        }
    }
}
