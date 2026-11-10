package com.androidharness.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.ThemePalette
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ThemePaletteTest {
    @Test fun `palette text keeps readable contrast in light dark and AMOLED modes`() {
        ThemePalette.entries.forEach { palette ->
            listOf(false, true).forEach { dark ->
                val base = palette.colorScheme(dark)
                (if (dark) listOf(base, base.amoledSurfaces()) else listOf(base)).forEach { scheme ->
                    listOf(
                        scheme.onPrimary to scheme.primary,
                        scheme.onPrimaryContainer to scheme.primaryContainer,
                        scheme.onSecondary to scheme.secondary,
                        scheme.onSecondaryContainer to scheme.secondaryContainer,
                        scheme.onTertiary to scheme.tertiary,
                        scheme.onTertiaryContainer to scheme.tertiaryContainer,
                        scheme.onSurface to scheme.surface,
                        scheme.onSurfaceVariant to scheme.surfaceContainerHighest,
                    ).forEach { (foreground, background) ->
                        val light = maxOf(foreground.luminance(), background.luminance())
                        val dim = minOf(foreground.luminance(), background.luminance())
                        assertTrue("$palette dark=$dark: $foreground on $background", (light + .05f) / (dim + .05f) >= 4.49f)
                    }
                }
            }
        }
    }

    @Test fun `every palette has a distinct accent and preserves it on AMOLED`() {
        assertEquals(ThemePalette.entries.size, ThemePalette.entries.map { it.colorScheme(false).primary }.distinct().size)
        ThemePalette.entries.forEach { palette ->
            val dark = palette.colorScheme(true)
            assertEquals(dark.primary, dark.amoledSurfaces().primary)
            assertEquals(Color.Black, dark.amoledSurfaces().surface)
        }
    }

    @Test fun `old settings retain their defaults and new palette survives portable backup`() {
        assertEquals(ThemePalette.BLUE, Json.decodeFromString<AppSettings>("{}").colorPalette)
        assertEquals(ThemePalette.BLUE, ThemePalette.fromStored("future-palette"))
        val settings = AppSettings(colorPalette = ThemePalette.ROSE, dynamicColor = false)
        assertEquals(settings, Json.decodeFromString<AppSettings>(Json.encodeToString(settings)))
    }
}
