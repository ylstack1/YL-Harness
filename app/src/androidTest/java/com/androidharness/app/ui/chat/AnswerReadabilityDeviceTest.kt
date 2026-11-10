package com.androidharness.app.ui.chat

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.R
import com.androidharness.app.data.ThemeMode
import com.androidharness.app.ui.theme.HarnessTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class AnswerReadabilityDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun paragraphsListsAndLiveFormattingKeepTheirSpacingWhenCommitted() {
        val text = mutableStateOf("**Start small.** Build one clear example.\n\nKeep each idea in its own paragraph.\n\n## Next steps\n\n9. Check the first result.\n10. Keep technical identifiers intact.\n\nThe **live answer** stays readable.")
        val streaming = mutableStateOf(true)
        val scale = mutableStateOf(1f)
        val theme = mutableStateOf(ThemeMode.LIGHT)
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale.value)) {
                    HarnessTheme(themeMode = theme.value, dynamicColor = false) {
                        Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                                MarkdownText(text.value, streaming.value)
                            }
                        }
                    }
                }
            } }
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                instrumentation.runOnMainSync { theme.value = mode; scale.value = if (mode == ThemeMode.DARK) 1.5f else 1f; streaming.value = true }
                SystemClock.sleep(300)
                val first = bounds(awaitText("Start small. Build one clear example."))
                val second = bounds(awaitText("Keep each idea in its own paragraph."))
                val heading = bounds(awaitText("Next steps"))
                val item = bounds(awaitText("Check the first result."))
                awaitText("10.")
                assertEquals("Ordered list text shares one aligned column", item.left, bounds(awaitText("Keep technical identifiers intact.")).left)
                awaitText("The live answer stays readable.")
                val density = context.resources.displayMetrics.density
                assertTrue("Paragraphs need breathing room", second.top - first.bottom >= 12 * density)
                assertTrue("Sections need a clear gap", heading.top - second.bottom >= 18 * density)
                assertTrue("Heading stays with its list", item.top - heading.bottom >= 6 * density)
                assertNull("Live bold markers are presentation, not visible text", find { it.text?.contains("**live answer**") == true })
                screenshot("answer-spacing-${mode.name.lowercase()}-live")
                instrumentation.runOnMainSync { streaming.value = false }
                SystemClock.sleep(200)
                assertEquals("Committing must keep paragraph positions", first, bounds(awaitText("Start small. Build one clear example.")))
                assertEquals(second, bounds(awaitText("Keep each idea in its own paragraph.")))
                assertEquals(heading, bounds(awaitText("Next steps")))
            }
            // A replacement with identical blank-line positions must invalidate the cached prefix.
            instrumentation.runOnMainSync { text.value = "Fresh\n\nA different **answer**"; streaming.value = true }
            awaitText("Fresh"); awaitText("A different answer")
            assertNull(find { it.text?.toString() == "Start small. Build one clear example." })
        }
    }

    @Test fun themedTablesScrollWithoutResettingAndCopyCompleteCellValues() {
        instrumentation.runOnMainSync { context.getSystemService(android.content.ClipboardManager::class.java).clearPrimaryClip() }
        val source = "| Plan | Monthly | Storage | Reference |\n| :--- | ---: | ---: | :--- |\n| Basic | 12 | 128 GB | [Docs](http://localhost:8080/docs) |\n| Plus | 24 | 256 GB | a\\|b |"
        val text = mutableStateOf(source)
        val streaming = mutableStateOf(true)
        val theme = mutableStateOf(ThemeMode.LIGHT)
        val scale = mutableStateOf(1f)
        val opened = AtomicReference<String>()
        val headerColor = AtomicInteger()
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale.value)) {
                    HarnessTheme(themeMode = theme.value, dynamicColor = false) {
                        val colors = MaterialTheme.colorScheme
                        headerColor.set(colors.surface.toArgb())
                        Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                            Box(Modifier.fillMaxSize()) {
                            Column(Modifier.width(320.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
                                MarkdownText(text.value, streaming.value, onOpenUrl = { opened.set(it) })
                            }
                            }
                        }
                    }
                }
            } }
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.AMOLED)) {
                instrumentation.runOnMainSync { theme.value = mode; scale.value = if (mode == ThemeMode.AMOLED) 1.5f else 1f; streaming.value = true }
                SystemClock.sleep(300) // Wait for the new font metrics before choosing a scroll action.
                awaitText(context.getString(R.string.answer_table_swipe_hint))
                await { horizontalScroll() != null }
                val scroll = requireNotNull(horizontalScroll())
                if (scroll.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id })
                    assertTrue(scroll.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id))
                SystemClock.sleep(350)
                val header = bounds(awaitText("Reference"))
                assertTrue("Table header exposes its role", awaitText("Reference").isHeading)
                val density = context.resources.displayMetrics.density
                val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                val pixel = image.getPixel((header.right - 3 * density).toInt().coerceIn(0, image.width - 1), (header.top + 3 * density).toInt())
                image.recycle()
                assertRgbNear(headerColor.get(), pixel)
                screenshot("answer-table-${mode.name.lowercase()}")
                instrumentation.runOnMainSync { streaming.value = false }
                SystemClock.sleep(200)
                assertEquals("Finishing must retain the horizontal table position", header, bounds(awaitText("Reference")))
            }
            clickDescription(context.getString(R.string.ui_copy_table))
            instrumentation.runOnMainSync {
                val clip = context.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
                assertEquals("Plan\tMonthly\tStorage\tReference\nBasic\t12\t128 GB\tDocs\nPlus\t24\t256 GB\ta|b", clip)
            }
            clickDescription(context.getString(R.string.answer_table_expand))
            awaitText(context.getString(R.string.answer_table_done))
            clickDescription(context.getString(R.string.rich_table_options))
            val sort = awaitText(context.getString(R.string.rich_sort_rows))
            var sortAction = sort
            while (!sortAction.isClickable && sortAction.parent != null) sortAction = sortAction.parent
            assertTrue(sortAction.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitText("Monthly")
            val done = awaitText(context.getString(R.string.answer_table_done))
            var close = done
            while (!close.isClickable && close.parent != null) close = close.parent
            assertTrue(close.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val docs = bounds(awaitText("Docs"))
            tap(docs.left + 20 * context.resources.displayMetrics.density, docs.exactCenterY())
            await { opened.get() == "http://localhost:8080/docs" }
            instrumentation.runOnMainSync {
                text.value = "| Setting | Explanation |\n| --- | --- |\n| Memory | Keep useful facts available across future conversations without repeating the same details. |"
                theme.value = ThemeMode.DARK; scale.value = 1f
                context.getSystemService(android.content.ClipboardManager::class.java).clearPrimaryClip()
            }
            awaitText("Explanation")
            SystemClock.sleep(250)
            assertNull("A two-column explanation should wrap within the phone", horizontalScroll())
            assertNull(find { it.text?.toString() == context.getString(R.string.answer_table_swipe_hint) })
            screenshot("answer-table-two-columns")
        }
    }

    @Test fun longLiveCodeKeepsIntroAndCopiesTheCompleteSource() {
        val code = (1..90).joinToString("\n") { "val value$it = $it // keep this exact source" }
        val text = mutableStateOf("Keep this introduction.\n\n```kotlin\n$code")
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                HarnessTheme {
                    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        MarkdownText(text.value, streaming = true)
                    }
                }
            } }
            awaitText("Keep this introduction.")
            clickDescription(context.getString(R.string.settings_telegram_copy_code))
            instrumentation.runOnMainSync {
                val clip = context.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
                assertEquals(code, clip)
            }
            instrumentation.runOnMainSync { text.value += "\n```" }
            SystemClock.sleep(200)
            clickDescription(context.getString(R.string.settings_telegram_copy_code))
            instrumentation.runOnMainSync {
                val clip = context.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
                assertEquals("Copy excludes the closing delimiter before the stream commits", code, clip)
            }
        }
    }

    private fun horizontalScroll() = find { node -> node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id || it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id } }
    private fun awaitText(text: String): AccessibilityNodeInfo {
        await { find { it.text?.toString() == text } != null }
        return requireNotNull(find { it.text?.toString() == text })
    }
    private fun clickDescription(description: String) {
        await { find { it.contentDescription?.toString() == description } != null }
        var node = requireNotNull(find { it.contentDescription?.toString() == description })
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun tap(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            if (condition()) return
            SystemClock.sleep(40)
        }
        screenshot("answer-rendering-failure")
        val visible = mutableListOf<String>()
        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null) return
            node.text?.toString()?.let { visible += it }
            for (index in 0 until node.childCount) collect(node.getChild(index))
        }
        collect(instrumentation.uiAutomation.rootInActiveWindow)
        fail("Answer rendering did not reach the expected state. Text: $visible")
    }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node)) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        return walk(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also { node.getBoundsInScreen(it) }
    private fun assertRgbNear(expected: Int, actual: Int) {
        for (shift in listOf(0, 8, 16)) assertTrue("Header must follow the active theme", kotlin.math.abs((expected shr shift and 255) - (actual shr shift and 255)) <= 3)
    }
    private fun screenshot(name: String) {
        SystemClock.sleep(250)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
