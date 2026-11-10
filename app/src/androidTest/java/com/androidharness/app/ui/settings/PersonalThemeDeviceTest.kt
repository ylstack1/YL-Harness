package com.androidharness.app.ui.settings

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.data.*
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.chat.ChatScreen
import com.androidharness.app.ui.chat.ChatViewModel
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
class PersonalThemeDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val c get() = (context.applicationContext as HarnessApp).container

    @Test fun customPickerCreatesEditsAndDeletesPalettesAndRetainsTheLastManualChoice() = runBlocking {
        val repository = c.settings
        val saved = repository.settings.first()
        val font = mutableFloatStateOf(1f)
        val observed = AtomicReference<ColorScheme>()
        try {
            repository.setThemeMode(ThemeMode.LIGHT)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    val current by repository.settings.collectAsState(initial = saved)
                    val scope = rememberCoroutineScope()
                    val custom = current.customPalettes.firstOrNull { it.id == current.activeCustomPaletteId }
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, font.floatValue)) {
                        HarnessTheme(current.themeMode, current.dynamicColor, customPalette = custom.takeUnless { current.dynamicColor }) {
                            val scheme = MaterialTheme.colorScheme
                            SideEffect { observed.set(scheme) }
                            Surface(Modifier.fillMaxSize()) {
                                Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    CustomPaletteSection(current.customPalettes, current.activeCustomPaletteId.takeUnless { current.dynamicColor },
                                        { scope.launch { repository.saveCustomPalette(it) } },
                                        { scope.launch { repository.selectCustomPalette(it) } },
                                        { scope.launch { repository.removeCustomPalette(it) } })
                                }
                            }
                        }
                    }
                } }
                await { nodes().any { matches(it, "Create palette") } }
                click("Create palette")
                setField("Palette name", "Sea glass fixture")
                reveal("Hex color")
                setField("Hex color", "000000")
                reveal("Hue", forward = false)
                setProgress("Hue", 180f)
                reveal("Saturation")
                setProgress("Saturation", 1f)
                reveal("Brightness")
                setProgress("Brightness", 1f)
                reveal("Hex color")
                await { nodes().any { it.isEditable && it.text?.toString()?.contains("00FFFF") == true } }
                setField("Hex color", "17A2B8")
                screenshot("personal-picker-light")
                click("Save and apply")
                await { runBlocking { repository.settings.first().customPalettes.any { it.name == "Sea glass fixture" } } }
                var created = repository.settings.first().customPalettes.single { it.name == "Sea glass fixture" }
                assertEquals(0xFF17A2B8, created.primary)
                await { observed.get()?.primary == created.colorScheme(false).primary }
                click("Edit palette")
                reveal("Accent", forward = false)
                click("Accent")
                reveal("Hex color")
                setField("Hex color", "FA0088")
                click("Save and apply")
                await { runBlocking { repository.settings.first().customPalettes.first { it.id == created.id }.secondary == 0xFFFA0088 } }
                created = repository.settings.first().customPalettes.first { it.id == created.id }
                repository.setDynamicColor(true)
                assertEquals(created.id, repository.settings.first().activeCustomPaletteId)
                repository.setDynamicColor(false)
                await { observed.get()?.secondary == created.colorScheme(false).secondary }
                repository.setThemeMode(ThemeMode.AMOLED)
                instrumentation.runOnMainSync { font.floatValue = 1.5f }
                click("Edit palette")
                await { nodes().any { matches(it, "Save and apply") && it.isVisibleToUser } }
                screenshot("personal-picker-amoled-large")
                reveal("Delete palette")
                click("Delete palette")
                click("Delete palette")
                await { runBlocking { repository.settings.first().customPalettes.none { it.id == created.id } } }
            }
        } finally { repository.restorePortable(saved) }
    }

    @Test fun canvasImportsSafelyRendersInBothSectionsDimsAndRemovesItsOwnCopy() = runBlocking {
        val repository = c.settings
        val saved = repository.settings.first()
        val previousImage = HarnessCanvasStore.image(context, saved.harnessCanvas.imageId)
        val previousBytes = previousImage?.readBytes()
        val store = HarnessCanvasStore(context, repository)
        val source = File(context.cacheDir, "canvas-source-fixture.png")
        val bad = File(context.cacheDir, "canvas-invalid-fixture.png").apply { writeText("invalid image") }
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888).apply { eraseColor(AndroidColor.rgb(17, 133, 91)); setHasAlpha(false) }
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val sourceBytes = source.readBytes()
        val models = ViewModelStore()
        try {
            store.import(Uri.fromFile(source))
            val first = repository.settings.first().harnessCanvas
            val firstFile = requireNotNull(HarnessCanvasStore.image(context, first.imageId))
            assertNotNull(first.seed)
            assertTrue(firstFile.isFile)
            assertTrue(runCatching { store.import(Uri.fromFile(bad)) }.isFailure)
            assertEquals(first.imageId, repository.settings.first().harnessCanvas.imageId)
            assertTrue(firstFile.isFile)
            repository.saveCustomPalette(CustomThemePalette("canvas-fixture", "Canvas fixture", 0xFF6750A4))
            repository.updateHarnessCanvas { it.copy(adaptColors = true, dim = 0f, chat = true, code = true) }
            assertFalse(repository.settings.first().dynamicColor)
            assertEquals("canvas-fixture", repository.settings.first().activeCustomPaletteId)
            for (kind in listOf(ConversationKind.CODE, ConversationKind.CHAT)) {
                ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        val vm = ChatViewModel(c, null, kind)
                        models.put(kind.name, vm)
                        activity.setContent {
                            val current by repository.settings.collectAsState(initial = saved)
                            HarnessTheme(ThemeMode.DARK, dynamicColor = false,
                                canvasSeed = current.harnessCanvas.seed.takeIf { current.harnessCanvas.adaptColors }) {
                                ChatScreen(vm, {}, { _, _ -> }, {}, {}, onOpenSubagent = {})
                            }
                        }
                    }
                    await { greenPixels() > 100 }
                    screenshot("canvas-${kind.name.lowercase()}-visible")
                    repository.updateHarnessCanvas { it.copy(dim = 1f) }
                    await { greenPixels() < 10 }
                    screenshot("canvas-${kind.name.lowercase()}-dimmed")
                    repository.updateHarnessCanvas { it.copy(dim = 0f, chat = kind != ConversationKind.CHAT, code = kind != ConversationKind.CODE) }
                    await { greenPixels() < 10 }
                    repository.updateHarnessCanvas { it.copy(chat = true, code = true) }
                    await { greenPixels() > 100 }
                }
            }
            // Preview and all Canvas controls also render at large text sizes.
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    val current by repository.settings.collectAsState(initial = saved)
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                        HarnessTheme(ThemeMode.DARK, false) { Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                                HarnessCanvasSection(current.harnessCanvas, repository)
                            }
                        } }
                    }
                } }
                await { nodes().any { matches(it, "Harness Canvas") } }
                screenshot("canvas-settings-large")
                reveal("Image dimming")
                setProgress("Image dimming", .4f)
                await { runBlocking { kotlin.math.abs(repository.settings.first().harnessCanvas.dim - .4f) < .01f } }
                reveal("Match Canvas colors")
                click("Match Canvas colors")
                await { runBlocking { !repository.settings.first().harnessCanvas.adaptColors } }
            }
            store.import(Uri.fromFile(source))
            assertFalse(firstFile.exists())
            val secondFile = requireNotNull(HarnessCanvasStore.image(context, repository.settings.first().harnessCanvas.imageId))
            store.remove()
            assertFalse(secondFile.exists())
            assertNull(repository.settings.first().harnessCanvas.imageId)
            assertFalse(repository.settings.first().harnessCanvas.adaptColors)
            assertArrayEquals(sourceBytes, source.readBytes())
        } finally {
            instrumentation.runOnMainSync { models.clear() }
            store.remove()
            if (previousImage != null && previousBytes != null) { previousImage.parentFile!!.mkdirs(); previousImage.writeBytes(previousBytes) }
            repository.restorePortable(saved)
            source.delete(); bad.delete()
        }
    }

    private fun nodes(node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): List<AccessibilityNodeInfo> =
        if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
    private fun matches(node: AccessibilityNodeInfo, text: String): Boolean =
        listOfNotNull(node.text?.toString(), node.contentDescription?.toString(), node.hintText?.toString()).any { it.lines().contains(text) }
    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) { if (current.isClickable) return current; current = current.parent }
        return null
    }
    private fun click(text: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        val target = nodes().filter { matches(it, text) }.firstNotNullOfOrNull { clickable(it) }
        assertNotNull("Missing button: $text", target)
        assertTrue(target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        SystemClock.sleep(150)
    }
    private fun setField(label: String, value: String) {
        val field = nodes().first { it.isEditable && nodes(it).any { descendant -> matches(descendant, label) } }
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        SystemClock.sleep(150)
    }
    private fun setProgress(label: String, value: Float) {
        val all = nodes()
        val control = all.firstOrNull { node -> node.rangeInfo != null && nodes(node).any { matches(it, label) } }
        if (control == null) {
            screenshot("personal-missing-slider")
            error("Missing slider $label: " + all.take(80).joinToString(" | ") { "${it.text}/${it.contentDescription}/range=${it.rangeInfo}/visible=${it.isVisibleToUser}" })
        }
        assertTrue(control.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, android.os.Bundle().apply {
            putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value)
        }))
        SystemClock.sleep(150)
    }
    private fun reveal(label: String, forward: Boolean = true) {
        repeat(20) {
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            if (nodes().any { matches(it, label) && it.isVisibleToUser }) return
            nodes().firstOrNull { it.isScrollable }?.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            SystemClock.sleep(200)
        }
        error("Could not reveal $label")
    }
    private fun await(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 12_000
        while (SystemClock.uptimeMillis() < until) {
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            if (condition()) return
            SystemClock.sleep(100)
        }
        error("Personal theme did not reach expected state")
    }
    private fun greenPixels(): Int {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return 0
        var count = 0
        try { for (y in bitmap.height / 5 until bitmap.height * 4 / 5 step 16) for (x in 16 until bitmap.width - 16 step 16) {
            val pixel = bitmap.getPixel(x, y)
            if (kotlin.math.abs(AndroidColor.red(pixel) - 17) < 12 && kotlin.math.abs(AndroidColor.green(pixel) - 133) < 12 && kotlin.math.abs(AndroidColor.blue(pixel) - 91) < 12) count++
        } } finally { bitmap.recycle() }
        return count
    }
    private fun screenshot(name: String) {
        SystemClock.sleep(300)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
}
