package com.androidharness.app.ui.theme

import com.androidharness.app.data.AppSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class HarnessGlassModeTest {
    @Test fun olderDevicesAndDisabledExperimentsNeverSelectUnsupportedEffects() {
        for (sdk in listOf(26, 30, 31, 32, 33, 36)) {
            assertEquals(HarnessGlassMode.OFF, HarnessGlassMode.resolve(false, true, sdk))
        }
        assertEquals(HarnessGlassMode.OFF, HarnessGlassMode.resolve(true, true, 30))
        assertEquals(HarnessGlassMode.FROSTED, HarnessGlassMode.resolve(true, true, 31))
        assertEquals(HarnessGlassMode.FROSTED, HarnessGlassMode.resolve(true, true, 32))
        assertEquals(HarnessGlassMode.FROSTED, HarnessGlassMode.resolve(true, false, 33))
        assertEquals(HarnessGlassMode.LIQUID, HarnessGlassMode.resolve(true, true, 33))
    }

    @Test fun oldSettingsStayOffAndPortableSettingsRetainBothSwitches() {
        val old = Json.decodeFromString<AppSettings>("{}")
        assertFalse(old.harnessGlassEnabled)
        assertFalse(old.harnessLiquidGlass)
        assertEquals(.5f, old.harnessGlassTransparency)
        for (liquid in listOf(false, true)) {
            val saved = old.copy(harnessGlassEnabled = true, harnessLiquidGlass = liquid, harnessGlassTransparency = .8f)
            val restored = Json.decodeFromString<AppSettings>(Json.encodeToString(saved))
            assertEquals(saved, restored)
        }
    }

    @Test fun transparencyPreservesTheDefaultAndNeverChangesForegroundOpacity() {
        assertEquals(1f, liquidGlassOpacity(.3f, 0f), .001f)
        assertEquals(.3f, liquidGlassOpacity(.3f, .5f), .001f)
        assertEquals(0f, liquidGlassOpacity(.3f, 1f), .001f)
        assertEquals(.3f, liquidGlassOpacity(.3f, Float.NaN), .001f)
        assertEquals(1f, liquidGlassOpacity(.3f, -1f), .001f)
        assertEquals(0f, liquidGlassOpacity(.3f, 2f), .001f)
    }
}
