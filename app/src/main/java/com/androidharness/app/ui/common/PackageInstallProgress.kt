package com.androidharness.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.androidharness.app.data.env.EnvState
import java.util.Locale
import kotlin.math.roundToInt

internal data class PackageProgressPresentation(
    val title: String,
    val detail: String,
    val fraction: Float? = null,
    val percentage: String? = null,
)

/** Download percentage measures bytes; install percentage measures completed packages. */
internal fun EnvState.packageProgress(): PackageProgressPresentation? = when (this) {
    EnvState.Preparing -> PackageProgressPresentation("Preparing installation", "Resolving packages and dependencies…")
    is EnvState.Downloading -> {
        val fraction = totalBytes?.takeIf { it > 0 }?.let { (downloadedBytes.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
        PackageProgressPresentation("Downloading $pkg", buildString {
            append("Package ${index + 1} of $total · ${downloadSize(downloadedBytes)}")
            totalBytes?.takeIf { it > 0 }?.let { append(" / ${downloadSize(it)}") }
        }, fraction, fraction?.let { "${(it * 100).roundToInt()}%" })
    }
    is EnvState.Installing -> {
        val fraction = if (total > 0) (index.toFloat() / total).coerceIn(0f, 1f) else null
        PackageProgressPresentation("Installing $pkg", "${index.coerceAtLeast(0)} of $total packages installed",
            fraction, fraction?.let { "${(it * 100).toInt()}%" })
    }
    else -> null
}

private fun downloadSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> "${bytes.coerceAtLeast(0)} B"
}

@Composable
internal fun PackageInstallProgress(state: EnvState, modifier: Modifier = Modifier) {
    val progress = state.packageProgress() ?: return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(progress.title, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(progress.detail, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            progress.percentage?.let { Text(it, style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary) }
        }
        val fraction = progress.fraction
        if (fraction != null) ThinLinearProgress(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        else ThinLinearProgress(Modifier.fillMaxWidth())
    }
}
