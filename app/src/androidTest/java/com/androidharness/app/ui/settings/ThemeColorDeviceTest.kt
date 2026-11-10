package com.androidharness.app.ui.settings

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.data.*
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.theme.HarnessTheme
import com.androidharness.app.ui.theme.colorScheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ThemeColorDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun paletteAndWallpaperChoicesApplyPersistAndFitLargeText() = runBlocking {
        val repository = SettingsRepository(context)
        val saved = repository.settings.first()
        val observed = AtomicReference<ColorScheme>()
        val expectedWallpaper = AtomicReference<ColorScheme>()
        try {
            repository.setColorPalette(ThemePalette.BLUE)
            for (fontScale in listOf(1f, 1.5f)) {
                repository.setThemeMode(ThemeMode.LIGHT)
                ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                    scenario.onActivity { activity -> activity.setContent {
                        val current by repository.settings.collectAsState(initial = saved)
                        val scope = rememberCoroutineScope()
                        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                            HarnessTheme(current.themeMode, current.dynamicColor, colorPalette = current.colorPalette) {
                                val scheme = MaterialTheme.colorScheme
                                val local = LocalContext.current
                                SideEffect {
                                    observed.set(scheme)
                                    if (android.os.Build.VERSION.SDK_INT >= 31) expectedWallpaper.set(dynamicLightColorScheme(local))
                                }
                                Surface(Modifier.fillMaxSize()) {
                                    Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Text("Appearance", style = MaterialTheme.typography.headlineMedium)
                                        ThemeColorSection(current.colorPalette, current.dynamicColor, current.themeMode,
                                            { scope.launch { repository.setDynamicColor(it) } },
                                            { scope.launch { repository.setColorPalette(it) } })
                                    }
                                }
                            }
                        }
                    } }
                    scrollTo("Ocean")
                    click("Ocean")
                    await { observed.get()?.primary == ThemePalette.OCEAN.colorScheme(false).primary }
                    assertFalse(repository.settings.first().dynamicColor)
                    assertEquals(ThemePalette.OCEAN, SettingsRepository(context).settings.first().colorPalette)
                    scrollTo("Teal")
                    click("Teal")
                    await { observed.get()?.primary == ThemePalette.TEAL.colorScheme(false).primary }
                    await { choice("Teal").let { it.isSelected || it.isChecked } }
                    scrollTo("Wallpaper colors", forward = false)
                    if (android.os.Build.VERSION.SDK_INT >= 31) {
                        click("Wallpaper colors")
                        await { runBlocking { repository.settings.first().dynamicColor } && observed.get()?.primary == expectedWallpaper.get()?.primary }
                        click("Wallpaper colors")
                        await { observed.get()?.primary == ThemePalette.TEAL.colorScheme(false).primary }
                    }
                    for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.AMOLED)) {
                        repository.setThemeMode(mode)
                        await {
                            val scheme = observed.get()
                            scheme?.primary == ThemePalette.TEAL.colorScheme(mode != ThemeMode.LIGHT).primary &&
                                (mode != ThemeMode.AMOLED || scheme.surface == androidx.compose.ui.graphics.Color.Black)
                        }
                        SystemClock.sleep(350)
                        screenshot("theme-colors-${mode.name}-$fontScale")
                    }
                }
            }
            // The same portable restore path used by backups includes both color preferences.
            val selected = repository.settings.first()
            repository.setColorPalette(ThemePalette.CORAL)
            repository.restorePortable(selected)
            assertEquals(ThemePalette.TEAL, repository.settings.first().colorPalette)
            assertFalse(repository.settings.first().dynamicColor)
        } finally {
            repository.setColorPalette(saved.colorPalette)
            repository.setDynamicColor(saved.dynamicColor)
            repository.setThemeMode(saved.themeMode)
        }
    }

    private fun nodes(node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): List<AccessibilityNodeInfo> =
        if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
    private fun choice(label: String): AccessibilityNodeInfo {
        var node = nodes().first { matches(it, label) }
        while (!node.isClickable && !node.isCheckable && node.parent != null) node = node.parent
        return node
    }
    private fun click(label: String) { assertTrue(choice(label).performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
    private fun scrollTo(label: String, forward: Boolean = true) {
        repeat(15) {
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            if (nodes().any { matches(it, label) && it.isVisibleToUser }) return
            nodes().firstOrNull { it.isScrollable }?.performAction(
                if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            SystemClock.sleep(250)
        }
        error("Missing palette choice: $label")
    }
    private fun matches(node: AccessibilityNodeInfo, label: String) = node.text?.toString()?.lineSequence()?.any { it == label } == true
    private fun await(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < until) {
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            if (condition()) return
            SystemClock.sleep(50)
        }
        error("Theme did not update")
    }
    private fun screenshot(name: String) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
