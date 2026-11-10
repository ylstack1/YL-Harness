package com.androidharness.app.ui.chat

import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.ui.chat.components.AssistantText
import com.androidharness.app.ui.chat.components.ThinkingBlock
import com.androidharness.app.ui.chat.components.ToolGroupCard
import com.androidharness.app.ui.chat.components.TurnActivityCard
import com.androidharness.app.ui.theme.HarnessTheme
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real window/layout/draw timings, without changing animation scales or streaming behavior.
 * Run with -PcardBenchmark for the non-debuggable release-equivalent variant. Results are
 * instrumentation status fields so before/after runs can be retained outside the APK.
 */
@RunWith(AndroidJUnit4::class)
class ChatCardFrameDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun measureExpansionsAndHistory() {
        assumeTrue("Run frame measurements with -PcardBenchmark", instrumentation.targetContext.packageName.endsWith(".benchmark"))
        assertTrue("Frame measurements require a non-debuggable APK",
            (instrumentation.targetContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0)
        val arguments = InstrumentationRegistry.getArguments()
        val scenes = arguments.getString("scene")?.let(::listOf)
            ?: listOf("thinking", "tools", "summary", "history")
        val calls = List(40) { index ->
            ToolCallData("frame-$index", "read_file", "{\"path\":\"src/Example$index.kt\",\"offset\":1,\"limit\":200}")
        }
        val results = calls.associate { it.id to ChatMessage(Role.TOOL, "Saved file contents.\n".repeat(120), toolCallId = it.id) }
        val thinking = (1..600).joinToString("\n") { "Step $it: inspect the implementation and verify the resulting behavior." }
        val activity = calls.map { call -> ChatMessage(Role.ASSISTANT,
            text = "Checked `${call.id}`.\n\nThe source and expected result agree.", toolCalls = listOf(call)) }
        for (scene in scenes) {
            val thread = HandlerThread("card-frame-metrics").apply { start() }
            val frames = mutableListOf<Pair<Double, Double>>()
            var collecting = false
            var dropped = 0
            val listener = Window.OnFrameMetricsAvailableListener { _, metrics, lost ->
                synchronized(frames) {
                    if (collecting) {
                        frames += metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1e6 to
                            metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) / 1e6
                        dropped += lost
                    }
                }
            }
            try {
                ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                    scenario.onActivity { host ->
                        host.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
                        host.setContent {
                            HarnessTheme {
                                Surface(Modifier.fillMaxSize()) {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        when (scene) {
                                            "thinking" -> item { ThinkingBlock(thinking) }
                                            "tools" -> item { ToolGroupCard(calls, results, emptySet(), { _, _ -> }) }
                                            "summary" -> item { TurnActivityCard(calls, results, emptyList(), "12s", activity, { _, _ -> }) }
                                            "history" -> items(120, key = { it }) { index ->
                                                AssistantText("## Saved message $index\n\n" + "Verified history text. ".repeat(50))
                                            }
                                            else -> error("Unknown scene $scene")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(800)
                    val bounds = Rect()
                    scenario.onActivity { it.window.decorView.getGlobalVisibleRect(bounds) }
                    assertTrue("Missing window bounds", !bounds.isEmpty)
                    val header = if (scene == "history") bounds else awaitToggle(scene)
                    synchronized(frames) { collecting = true }
                    if (scene == "history") repeat(12) { swipe(bounds) } else repeat(8) {
                        tap(header.exactCenterX(), header.exactCenterY())
                        SystemClock.sleep(700)
                    }
                    synchronized(frames) { collecting = false }
                    scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
                    val sample = synchronized(frames) { frames.toList() }
                    assertTrue("No frame samples for $scene", sample.isNotEmpty())
                    fun percentile(values: List<Double>, p: Double) = values.sorted()[((values.size - 1) * p).toInt()]
                    val totals = sample.map { it.first }
                    val layout = sample.map { it.second }
                    val report = "scene=$scene frames=${sample.size} p50_ms=${percentile(totals, .5)} " +
                        "p95_ms=${percentile(totals, .95)} max_ms=${totals.max()} " +
                        "over_16ms=${totals.count { it > 16.667 }} layout_p95_ms=${percentile(layout, .95)} dropped=$dropped"
                    instrumentation.sendStatus(0, Bundle().apply { putString("card_frames", report) })
                }
            } finally {
                thread.quitSafely()
                thread.join()
            }
        }
    }

    private fun awaitToggle(scene: String): Rect {
        val description = if (scene == "summary") "Expand activity" else "Expand"
        val deadline = SystemClock.uptimeMillis() + 5_000
        fun find(node: AccessibilityNodeInfo?): Rect? {
            node ?: return null
            if (node.contentDescription?.toString() == description) return Rect().also(node::getBoundsInScreen)
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        while (SystemClock.uptimeMillis() < deadline) {
            find(instrumentation.uiAutomation.rootInActiveWindow)?.let { return it }
            SystemClock.sleep(50)
        }
        error("Missing $scene toggle")
    }

    private fun tap(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        send(down, MotionEvent.ACTION_DOWN, x, y)
        send(down, MotionEvent.ACTION_UP, x, y)
    }

    private fun swipe(bounds: Rect) {
        val down = SystemClock.uptimeMillis()
        val x = bounds.exactCenterX()
        val from = bounds.top + bounds.height() * .75f
        val to = bounds.top + bounds.height() * .3f
        send(down, MotionEvent.ACTION_DOWN, x, from)
        repeat(20) { step ->
            SystemClock.sleep(16)
            send(down, MotionEvent.ACTION_MOVE, x, from + (to - from) * (step + 1) / 20)
        }
        send(down, MotionEvent.ACTION_UP, x, to)
        SystemClock.sleep(200)
    }

    private fun send(down: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
    }
}
