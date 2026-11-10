package com.androidharness.app.ui.settings

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.data.env.AccessMode
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.theme.HarnessTheme
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootAccessUiDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Test fun accessModesAndDiagnosticsRemainReachableAtPhoneSize() = runBlocking {
        val context = instrumentation.targetContext
        val container = (context.applicationContext as HarnessApp).container
        val saved = container.deviceAccess.store.preferences.first()
        try {
            container.deviceAccess.store.setMode(AccessMode.AUTO)
            // The project picker is disabled unless temporary mode is on, and the
            // device may arrive with it off from manual testing.
            container.deviceAccess.store.setTemporaryRoot(true)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { host -> host.setContent { HarnessTheme {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        RootAccessSection(container)
                    }
                } } }
                awaitText("Root access")
                val bounds = Rect()
                for (label in listOf("Auto", "Normal", "Shizuku / Shevery", "Root")) {
                    scrollTo(label)
                    val node = awaitText(label)
                    node.getBoundsInScreen(bounds)
                    assertTrue("Mode label $label has no usable bounds: $bounds", bounds.width() > 20 && bounds.height() > 20)
                }
                screenshot("root-access-modes")
                scrollTo("Normal"); tap("Normal")
                assertEquals("Normal selection", AccessMode.NORMAL, kotlinx.coroutines.withTimeoutOrNull(5_000) { container.deviceAccess.store.preferences.first { it.mode == AccessMode.NORMAL } }?.mode)
                scrollTo("Auto"); tap("Auto")
                assertEquals("Auto selection", AccessMode.AUTO, kotlinx.coroutines.withTimeoutOrNull(5_000) { container.deviceAccess.store.preferences.first { it.mode == AccessMode.AUTO } }?.mode)
                scrollTo("Diagnostics")
                screenshot("root-access-controls")
                tap("Diagnostics")
                awaitText("Device access diagnostics")
                assertNotNull(awaitTextContaining("Mode: Auto"))
                tap("Done")
                scrollTo("Project root permissions"); tap("Project root permissions")
                awaitTextContaining("Full access mode does not override")
                tap("Done")
            }
        } finally {
            container.deviceAccess.store.setMode(saved.mode)
            container.deviceAccess.store.setFallback(saved.autoFallback)
            container.deviceAccess.store.setTemporaryRoot(saved.temporaryRoot)
        }
    }
    private fun screenshot(name: String) {
        val image = instrumentation.uiAutomation.takeScreenshot()
        val scale = android.provider.Settings.System.getString(instrumentation.targetContext.contentResolver, "font_scale")
        File(instrumentation.targetContext.getExternalFilesDir(null), "$name-$scale.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    private fun find(node: AccessibilityNodeInfo?, text: String, contains: Boolean = false): AccessibilityNodeInfo? {
        node ?: return null
        if (node.isVisibleToUser && (if (contains) node.text?.toString()?.contains(text) == true else node.text?.toString() == text)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), text, contains)?.let { return it }
        return null
    }
    private fun awaitText(text: String): AccessibilityNodeInfo = await(text, false)
    private fun awaitTextContaining(text: String): AccessibilityNodeInfo = await(text, true)
    private fun await(text: String, contains: Boolean): AccessibilityNodeInfo {
        val end = SystemClock.elapsedRealtime() + 8_000
        while (SystemClock.elapsedRealtime() < end) {
            find(instrumentation.uiAutomation.rootInActiveWindow, text, contains)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Missing text: $text")
    }
    private fun tap(text: String) {
        val end = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < end) {
            var node: AccessibilityNodeInfo? = find(instrumentation.uiAutomation.rootInActiveWindow, text)
            while (node != null && !node.isClickable) node = node.parent
            if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                SystemClock.sleep(350)
                return
            }
            SystemClock.sleep(100)
        }
        error("Cannot activate $text")
    }
    private fun scrollTo(text: String) {
        repeat(20) { attempt ->
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root == null) { SystemClock.sleep(100); return@repeat }
            if (find(root, text) != null) return
            val bounds = Rect().also { root.getBoundsInScreen(it) }
            val x = bounds.exactCenterX()
            val top = bounds.top + bounds.height() * 0.25f
            val bottom = bounds.top + bounds.height() * 0.8f
            val from = if (attempt < 10) bottom else top
            val to = if (attempt < 10) top else bottom
            val down = SystemClock.uptimeMillis()
            fun event(action: Int, y: Float, time: Long) {
                val motion = android.view.MotionEvent.obtain(down, time, action, x, y, 0)
                try { instrumentation.uiAutomation.injectInputEvent(motion, true) } finally { motion.recycle() }
            }
            event(android.view.MotionEvent.ACTION_DOWN, from, down)
            for (step in 1..12) {
                SystemClock.sleep(20)
                event(android.view.MotionEvent.ACTION_MOVE, from + (to - from) * step / 12, SystemClock.uptimeMillis())
            }
            event(android.view.MotionEvent.ACTION_UP, to, SystemClock.uptimeMillis())
            SystemClock.sleep(250)
        }
        error("Cannot reach $text")
    }
}
