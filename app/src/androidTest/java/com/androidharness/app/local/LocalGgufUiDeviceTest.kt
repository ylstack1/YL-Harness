package com.androidharness.app.local

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.MainActivity
import com.androidharness.app.ui.settings.LocalModelsSection
import com.androidharness.app.ui.theme.HarnessTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalGgufUiDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun nodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::nodes).orEmpty() }
    private fun waitFor(text: String): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis()+10_000
        while (SystemClock.uptimeMillis()<end) {
            instrumentation.uiAutomation.rootInActiveWindow?.let { root -> nodes(root).firstOrNull { it.text?.toString()?.lineSequence()?.any { line -> line == text }==true || it.contentDescription?.toString()==text }?.let { return it } }
            SystemClock.sleep(100)
        }
        error("Missing UI: $text")
    }
    private fun click(node: AccessibilityNodeInfo) {
        var target=node
        while (!target.isClickable && target.parent != null) target=target.parent
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(1000)
        val path=File(instrumentation.targetContext.getExternalFilesDir(null),"acceleration-validation/$name.png")
        path.parentFile!!.mkdirs()
        requireNotNull(instrumentation.uiAutomation.takeScreenshot()).let { image ->
            path.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }; image.recycle()
        }
    }
    @Test fun experimentalWarningGatesGpuControls() = runBlocking {
        val c=(instrumentation.targetContext.applicationContext as HarnessApp).container
        c.settings.setLocalModelExperimental(false)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    activity.setContent { HarnessTheme {
                        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,1.5f)) {
                            Surface(Modifier.fillMaxSize()) { LocalModelsSection(c, scrollContent = true) }
                        }
                    } }
                }
                click(waitFor("Performance"))
                for (i in 0 until 20) {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    if (root != null && nodes(root).any { it.text?.toString() == "Experimental features" }) break
                    root?.let { nodes(it).firstOrNull { n -> n.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
                    SystemClock.sleep(100)
                }
                waitFor("Experimental features")
                screenshot("gguf-default")
                click(waitFor("Experimental features"))
                waitFor("Enable experimental features?")
                assertFalse(c.settings.settings.first().localModelExperimental)
                click(waitFor("Cancel"))
                assertFalse(c.settings.settings.first().localModelExperimental)
                click(waitFor("Experimental features")); click(waitFor("Enable"))
                withTimeout(5_000) { c.settings.settings.first { it.localModelExperimental } }
                click(waitFor("Explore"))
                waitFor("Safetensors");screenshot("gguf-explore")
                click(waitFor("Local model options"))
                waitFor("GGUF runs directly on compatible Vulkan GPUs.")
                screenshot("gguf-gpu-options")
                println("GGUF_EXPRESSIVE_UI_150_PERCENT_OK")
            }
        } finally { c.settings.setLocalModelExperimental(false) }
    }
}
