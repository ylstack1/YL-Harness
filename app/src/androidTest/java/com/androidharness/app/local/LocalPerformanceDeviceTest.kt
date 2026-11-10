package com.androidharness.app.local

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.MainActivity
import com.androidharness.app.ui.settings.LocalModelsSection
import com.androidharness.app.ui.theme.HarnessTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalPerformanceDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun nodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::nodes).orEmpty() }
    private fun find(text: String) = instrumentation.uiAutomation.rootInActiveWindow?.let { nodes(it).firstOrNull { n -> n.text?.toString() == text } }
    private fun waitFor(text: String) {
        val end = SystemClock.uptimeMillis() + 12_000
        while (SystemClock.uptimeMillis() < end) { if (find(text) != null) return; SystemClock.sleep(100) }
        error("Missing dashboard UI: $text")
    }
    private fun click(text: String) {
        waitFor(text)
        var node = requireNotNull(find(text))
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    private fun scrollTo(text: String) {
        for (i in 0 until 20) {
            if (find(text) != null) return
            instrumentation.uiAutomation.rootInActiveWindow?.let { root -> nodes(root).firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
            SystemClock.sleep(150)
        }
        waitFor(text)
    }
    private fun capture(name: String) {
        SystemClock.sleep(350)
        val path = File(instrumentation.targetContext.getExternalFilesDir(null), "device-dashboard/$name.png")
        path.parentFile!!.mkdirs()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap -> path.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
    }
    @Test fun telemetryIsRealAndTabsStayReachableWhileScrolling() {
        val context = instrumentation.targetContext
        val reader = DeviceTelemetry(context)
        val hardware = reader.hardware()
        val first = reader.sample()
        SystemClock.sleep(100)
        val second = reader.sample()
        assertTrue(hardware.coreIds.isNotEmpty())
        assertTrue(first.totalRam > 0)
        assertTrue(first.availableRam in 0..first.totalRam)
        assertTrue(first.availableStorage in 0..first.totalStorage)
        assertTrue(second.sampledAt > first.sampledAt)
        assertTrue(second.cores.isNotEmpty())
        assertTrue(second.appCpu == null || second.appCpu in 0f..100f)
        println("DEVICE_HARDWARE chipset=${hardware.chipset} gpu=${hardware.gpu} drivers=${hardware.neuralDrivers} cores=${hardware.coreIds.size} RAM=${second.availableRam}/${second.totalRam}")
        val container = (context.applicationContext as HarnessApp).container
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity.setContent { HarnessTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) { LocalModelsSection(container, scrollContent = true) } } }
            }
            waitFor("My models"); waitFor("Explore"); waitFor("Performance")
            click("Performance"); waitFor("Memory"); capture("memory")
            scrollTo("App CPU share"); capture("cpu")
            scrollTo("Graphics processor"); capture("gpu")
            scrollTo("NPU & accelerator drivers"); capture("accelerators")
            scrollTo("Temperature & battery"); capture("thermal")
            click("Explore"); waitFor("Safetensors"); capture("explore")
            click("My models"); waitFor("On this phone"); capture("models")
            click("Performance"); waitFor("Memory")
        }
    }
}
