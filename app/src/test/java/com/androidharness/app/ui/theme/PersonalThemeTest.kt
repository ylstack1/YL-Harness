package com.androidharness.app.ui.theme

import androidx.compose.ui.graphics.luminance
import com.androidharness.app.data.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PersonalThemeTest {
    @Test fun `hex input requires a complete opaque RGB color`() {
        assertEquals(0xFF17A2B8, parseThemeHex(" #17a2b8 "))
        assertEquals("#17A2B8", themeHex(0xFF17A2B8))
        listOf("", "abc", "FFFFFFFF", "GG0000", "12345", "##123456").forEach { assertNull(parseThemeHex(it)) }
    }

    @Test fun `custom themes keep readable foregrounds for extreme seeds and independent accents`() {
        for (seed in listOf(0xFF000000, 0xFFFFFFFF, 0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFF17A2B8)) {
            val custom = CustomThemePalette("fixture", "Fixture", seed, 0xFFFA0088, 0xFFFFCC00)
            for (dark in listOf(false, true)) {
                val scheme = custom.colorScheme(dark)
                listOf(scheme.onPrimary to scheme.primary, scheme.onSecondary to scheme.secondary,
                    scheme.onTertiary to scheme.tertiary, scheme.onSurface to scheme.surface,
                    scheme.onPrimaryContainer to scheme.primaryContainer).forEach { (text, background) ->
                    val ratio = (maxOf(text.luminance(), background.luminance()) + .05f) / (minOf(text.luminance(), background.luminance()) + .05f)
                    assertTrue("seed=$seed dark=$dark contrast=$ratio", ratio >= 4.49f)
                }
                assertEquals(scheme.primary, scheme.amoledSurfaces().primary)
            }
        }
    }

    @Test fun `personal themes round trip and older settings keep a plain background`() {
        val old = Json.decodeFromString<AppSettings>("{}")
        assertTrue(old.customPalettes.isEmpty()); assertNull(old.harnessCanvas.imageId)
        val custom = CustomThemePalette("custom", "Sea glass", 0xFF17A2B8)
        val saved = old.copy(customPalettes = listOf(custom), activeCustomPaletteId = custom.id,
            harnessCanvas = HarnessCanvasSettings(dim = .4f, chat = true, code = false))
        assertEquals(saved, Json.decodeFromString<AppSettings>(Json.encodeToString(saved)))
    }

    @Test fun `portable palettes and canvas references are bounded and validated`() {
        val custom = CustomThemePalette("custom", "Sea glass", 0xFF17A2B8)
        SettingsBackupValidation.validate(SettingsBackupFile(settings = AppSettings(customPalettes = listOf(custom), activeCustomPaletteId = custom.id)))
        val bad = listOf(
            AppSettings(customPalettes = List(25) { custom.copy(id = "$it") }),
            AppSettings(customPalettes = listOf(custom, custom)),
            AppSettings(customPalettes = listOf(custom.copy(primary = 0))),
            AppSettings(activeCustomPaletteId = "missing"),
            AppSettings(harnessCanvas = HarnessCanvasSettings(dim = -1f)),
            AppSettings(harnessCanvas = HarnessCanvasSettings(imageId = "../../photo.jpg")),
        )
        bad.forEach { settings ->
            try { SettingsBackupValidation.validate(SettingsBackupFile(settings = settings)); fail("Invalid appearance was accepted") }
            catch (expected: SettingsBackupValidationException) { assertEquals(SettingsBackupProblem.APPEARANCE, expected.problem) }
        }
    }
}
