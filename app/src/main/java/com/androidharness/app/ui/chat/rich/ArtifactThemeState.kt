package com.androidharness.app.ui.chat.rich

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.androidharness.app.artifacts.ArtifactTheme
import com.androidharness.app.ui.theme.LocalStatusColors

private fun Color.hex(): String = String.format("#%06X", 0xFFFFFF and toArgb())

/** The live Material scheme as preview CSS variables, so visuals follow presets, custom palettes and Canvas colors. */
@Composable
internal fun rememberArtifactTheme(): ArtifactTheme {
    val scheme = MaterialTheme.colorScheme
    val success = LocalStatusColors.current.success
    return remember(scheme, success) {
        val dark = scheme.surface.luminance() < .5f
        val warning = if (dark) Color(0xFFF0C36B) else Color(0xFFA86B00)
        ArtifactTheme(
            dark = dark,
            background = scheme.surface.hex(),
            foreground = scheme.onSurface.hex(),
            muted = scheme.onSurfaceVariant.hex(),
            surface = scheme.surfaceContainer.hex(),
            surface2 = scheme.surfaceContainerHighest.hex(),
            border = scheme.outlineVariant.hex(),
            accent = scheme.primary.hex(),
            onAccent = scheme.onPrimary.hex(),
            accentSoft = scheme.primaryContainer.hex(),
            success = success.hex(),
            warning = warning.hex(),
            danger = scheme.error.hex(),
            palette = listOf(scheme.primary, scheme.tertiary, success, warning, scheme.secondary, scheme.error).map { it.hex() },
        ).safe()
    }
}
