package com.androidharness.app.ui.chat

import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.ui.chat.components.AssistantText
import com.androidharness.app.ui.theme.HarnessTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class MathRenderingDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun mathHeavyStreamingKeepsRenderersStableAndReleasesThemAfterwards() {
        val paragraphs = (1..4).joinToString("\n\n") { i ->
            "Equation $i: \\(x_{$i}^2 + y_{$i}^2\\) and \\(\\frac{a_{$i}}{b_{$i}}\\)."
        } + "\n\n" + (1..2).joinToString("\n\n") { i -> "\$\$\\sum_{k=1}^{${i + 3}} k^2 = ${i * 10}\$\$" }
        val text = mutableStateOf(paragraphs + "\n\nWorking")
        val streaming = mutableStateOf(true)
        val fontScale = mutableStateOf(1f)
        val metricThread = HandlerThread("math-frames").apply { start() }
        val frames = java.util.Collections.synchronizedList(mutableListOf<Double>())
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            frames += metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1e6
        }
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.window.addOnFrameMetricsAvailableListener(listener, Handler(metricThread.looper))
                    activity.setContent {
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                            androidx.compose.ui.unit.Density(density.density, fontScale.value)) {
                        HarnessTheme {
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                                AssistantText(text.value, streaming.value)
                            }
                        }
                    } } }
                }
                SystemClock.sleep(6000)
                fun views(): List<WebView> {
                    val result = mutableListOf<WebView>()
                    scenario.onActivity { activity ->
                        fun visit(view: View) {
                            if (view is WebView) result += view
                            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
                        }
                        visit(activity.window.decorView)
                    }
                    return result
                }
                val initial = views()
                assertTrue("Expected actual equation WebViews", initial.size >= 8)
                repeat(24) { i ->
                    instrumentation.runOnMainSync { text.value = paragraphs + "\n\nWorking " + "more words ".repeat(i + 1) }
                    SystemClock.sleep(80)
                }
                instrumentation.runOnMainSync { streaming.value = false }
                SystemClock.sleep(2000)
                val after = views()
                val snapshot = synchronized(frames) { frames.toList().sorted() }
                instrumentation.sendStatus(0, Bundle().apply { putString("math_frames",
                    "debug=true initial_views=${initial.size} final_views=${after.size} reused=${after.count { it in initial }} " +
                        "frames=${snapshot.size} p95_ms=${snapshot.getOrNull((snapshot.size * .95).toInt())} max_ms=${snapshot.lastOrNull()}") })
                assertEquals("Streaming must retain completed equation views", initial.toSet(), after.toSet())
                val result = AtomicReference<String>()
                val loaded = CountDownLatch(1)
                scenario.onActivity {
                    after.first().evaluateJavascript("JSON.stringify({math:typeof renderMath,mermaid:typeof mermaid})") { value ->
                        result.set(value); loaded.countDown()
                    }
                }
                assertTrue("Math renderer script did not load", loaded.await(5, TimeUnit.SECONDS))
                assertTrue(result.get(), result.get().contains("function") && result.get().contains("undefined"))
                instrumentation.runOnMainSync { fontScale.value = 1.5f }
                SystemClock.sleep(2000)
                val scaled = views()
                val picture = instrumentation.uiAutomation.takeScreenshot()
                if (picture != null) {
                    val folder = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "math-rendering").apply { mkdirs() }
                    java.io.File(folder, "math-large-text.png").outputStream().use {
                        picture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    picture.recycle()
                }
                val settings = AtomicReference<List<android.webkit.WebSettings>>()
                scenario.onActivity {
                    assertTrue("Equation font size must follow accessibility scaling", scaled.all { it.settings.textZoom == 150 })
                    settings.set(scaled.map { it.settings })
                    it.setContent { Text("Math reply removed") }
                }
                val released = AtomicReference(false)
                val releaseDeadline = SystemClock.uptimeMillis() + 5000
                while (!released.get() && SystemClock.uptimeMillis() < releaseDeadline) {
                    scenario.onActivity { released.set(settings.get().none { it.javaScriptEnabled }) }
                    if (!released.get()) SystemClock.sleep(100)
                }
                assertTrue("Removed equation renderers must stop JavaScript", released.get())
            }
        } finally { metricThread.quitSafely(); metricThread.join() }
    }
    @Test fun diagramRendererStillLoadsItsLibraryOnDemand() {
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { HarnessTheme {
                Surface(Modifier.fillMaxSize()) {
                    com.androidharness.app.ui.chat.components.MermaidBlock("graph TD; A[Start] --> B[Done]")
                }
            } } }
            val until = SystemClock.uptimeMillis() + 10_000
            var rendered = false
            while (!rendered && SystemClock.uptimeMillis() < until) {
                val checked = CountDownLatch(1)
                scenario.onActivity { activity ->
                    fun find(view: View): WebView? {
                        if (view is WebView) return view
                        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                        return null
                    }
                    val view = find(activity.window.decorView)
                    if (view == null) checked.countDown()
                    else view.evaluateJavascript("document.querySelectorAll('#root svg').length") {
                        rendered = it == "1"; checked.countDown()
                    }
                }
                checked.await(2, TimeUnit.SECONDS)
                if (!rendered) SystemClock.sleep(100)
            }
            assertTrue("Diagram SVG must still render after loading its library", rendered)
        }
    }

}
