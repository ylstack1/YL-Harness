package com.androidharness.app.ui.settings

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.data.ProgressBarStyle
import com.androidharness.app.data.SettingsRepository
import com.androidharness.app.data.ThemeMode
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.common.AppLinearProgressIndicator
import com.androidharness.app.ui.common.ToolCallProgress
import com.androidharness.app.ui.theme.HarnessTheme
import com.androidharness.app.ui.theme.LocalProgressBarStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ProgressBarStyleDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun choicePersistsPreviewsAnimateAndSwitchingKeepsProgressInPlace() = runBlocking {
        val repository = SettingsRepository(context)
        val saved = repository.settings.first().progressBarStyle
        try {
            for (fontScale in listOf(1f, 1.5f)) {
                repository.setProgressBarStyle(ProgressBarStyle.FLAT)
                val observed = AtomicReference<ProgressBarStyle>()
                val toolBounds = AtomicReference<androidx.compose.ui.geometry.Rect>()
                val measuredBounds = AtomicReference<androidx.compose.ui.geometry.Rect>()
                val host = AtomicReference<ChatListTestActivity>()
                ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        host.set(activity)
                        activity.setContent {
                        val settings by repository.settings.collectAsState(initial = null)
                        val scope = rememberCoroutineScope()
                        settings?.let { current ->
                            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                                HarnessTheme(ThemeMode.DARK, dynamicColor = false, progressBarStyle = current.progressBarStyle) {
                                    val activeStyle = LocalProgressBarStyle.current
                                    SideEffect { observed.set(activeStyle) }
                                    Surface(Modifier.fillMaxSize()) {
                                        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                                            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                            ProgressBarStyleSection(current.progressBarStyle) { style ->
                                                scope.launch { repository.setProgressBarStyle(style) }
                                            }
                                            Text("Measured progress")
                                            AppLinearProgressIndicator(progress = { 0.4f }, modifier = Modifier.fillMaxWidth()
                                                .onGloballyPositioned { measuredBounds.set(it.boundsInWindow()) })
                                            Text("Tool activity")
                                            ToolCallProgress(Modifier.fillMaxWidth()
                                                .onGloballyPositioned { toolBounds.set(it.boundsInWindow()) })
                                        }
                                    }
                                }
                            }
                        }
                    } }
                    try {
                    await { toolBounds.get() != null && measuredBounds.get() != null && observed.get() == ProgressBarStyle.FLAT }
                    val flatTool = toolBounds.get()
                    val flatMeasured = measuredBounds.get()
                    val flat = choice("Flat")
                    val squiggly = choice("Squiggly")
                    assertTrue("Flat must be selected: $flat", flat.isChecked || flat.isSelected)
                    assertPreviewsAnimate(bounds(flat), bounds(squiggly))
                    assertTrue(squiggly.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    await { observed.get() == ProgressBarStyle.SQUIGGLY && choice("Squiggly").let { it.isChecked || it.isSelected } }
                    assertEquals(ProgressBarStyle.SQUIGGLY, SettingsRepository(context).settings.first().progressBarStyle)
                    assertEquals("Tool progress must keep its bounds", flatTool, toolBounds.get())
                    assertEquals("Measured progress must keep its bounds", flatMeasured, measuredBounds.get())
                    screenshot("progress-style-$fontScale")
                    assertTrue(choice("Flat").performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    await { observed.get() == ProgressBarStyle.FLAT && choice("Flat").let { it.isChecked || it.isSelected } }
                    } finally {
                        // Remove infinite preview animations before ActivityScenario waits for idle.
                        instrumentation.runOnMainSync { host.get().setContent {} }
                    }
                }
                assertEquals(ProgressBarStyle.FLAT, SettingsRepository(context).settings.first().progressBarStyle)
            }
        } finally { repository.setProgressBarStyle(saved) }
    }

    private fun choice(label: String): AccessibilityNodeInfo {
        var node = requireNotNull(find(instrumentation.uiAutomation.rootInActiveWindow, label))
        // Android intentionally makes an already-selected radio option non-clickable.
        while (!node.isClickable && !node.isCheckable && !node.isSelected && node.parent != null) node = node.parent
        return node
    }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label) return node
        for (i in 0 until node.childCount) find(node.getChild(i), label)?.let { return it }
        return null
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            if (condition()) return
            SystemClock.sleep(40)
        }
        fail("Progress style did not update")
    }
    private fun assertPreviewsAnimate(vararg regions: Rect) {
        val first = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val changed = BooleanArray(regions.size)
        try {
            repeat(4) {
                SystemClock.sleep(120)
                val next = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                regions.forEachIndexed { index, rect ->
                    for (y in rect.top.coerceAtLeast(0) until rect.bottom.coerceAtMost(first.height) step 2) {
                        for (x in rect.left.coerceAtLeast(0) until rect.right.coerceAtMost(first.width) step 2) {
                            if (first.getPixel(x, y) != next.getPixel(x, y)) changed[index] = true
                        }
                    }
                }
                next.recycle()
            }
            assertTrue("Both previews must animate", changed.all { it })
        } finally { first.recycle() }
    }
    private fun screenshot(name: String) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
