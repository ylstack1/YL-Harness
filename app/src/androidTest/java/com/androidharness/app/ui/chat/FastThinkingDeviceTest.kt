package com.androidharness.app.ui.chat

import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.agent.*
import com.androidharness.app.core.*
import com.androidharness.app.data.*
import com.androidharness.app.llm.*
import com.androidharness.app.ui.chat.components.ChatActivity
import com.androidharness.app.ui.theme.HarnessTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FastThinkingDeviceTest {
    @Test fun expandedThinkingKeepsUpWithFastProviderBurstsWithoutLosingTokens(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val c = (instrumentation.targetContext.applicationContext as HarnessApp).container
        val sid = c.sessions.createSession("Fast thinking fixture", conversation = ConversationConfig(ConversationKind.CHAT))
        val start = CompletableDeferred<Unit>()
        val chunks = (0 until 160).map { index -> "Step $index: " + "token ".repeat(64) + "\n" }
        val expected = chunks.joinToString("")
        var generationMs = 0L
        val metrics = mutableListOf<Double>()
        val layoutMetrics = mutableListOf<Double>()
        val provider = object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
                messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                start.await()
                val begun = SystemClock.elapsedRealtime()
                for ((index, chunk) in chunks.withIndex()) {
                    // Exclude cold window/font/shader setup from sustained-stream frame timings.
                    if (index == 32) synchronized(metrics) { metrics.clear(); layoutMetrics.clear() }
                    emit(StreamEvent.ThinkingDelta(chunk))
                    delay(16)
                }
                generationMs = SystemClock.elapsedRealtime() - begun
                emit(StreamEvent.TextDelta("All tokens received."))
                emit(StreamEvent.Done("stop"))
            }
        }
        val engine = AgentEngine({ provider }, c.registry, c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills)
        val runs = RunManager(c.appContext, engine, c.sessions, c.checkpoints, c.workspace, c.linuxEnv, c.settings, TodoStore(), c.providers)
        val thread = HandlerThread("fast-thinking-frames").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, frame, _ ->
            synchronized(metrics) {
                metrics += frame.getMetric(FrameMetrics.TOTAL_DURATION) / 1e6
                layoutMetrics += frame.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) / 1e6
            }
        }
        var publications = 0
        val observer = launch(Dispatchers.Default) { runs.live(sid).collect { if (!it.streamingThinking.isNullOrEmpty()) publications++ } }
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
                    activity.setContent { HarnessTheme(dynamicColor = false) {
                        val live by runs.live(sid).collectAsState()
                        val saved by c.sessions.messagesFlow(sid).collectAsState(initial = emptyList())
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
                                ChatActivity(saved, live = live.running, streamingThinking = live.streamingThinking.orEmpty(), autoExpand = true)
                            }
                        }
                    } }
                }
                runs.startRun(sid, "Think quickly", emptyList(), ProviderConfig("fast-fixture", "Fixture", ProviderType.OPENAI_COMPAT,
                    "https://example.invalid", "fixture-model"), "fixture", PermissionMode.FULL_AUTO, AgentMode.ACT,
                    32000, 100000, ThinkingLevel.MEDIUM, 10, notifyOnFinish = false)
                instrumentation.waitForIdleSync()
                start.complete(Unit)
                withTimeout(30_000) { runs.live(sid).first { !it.running } }
                val saved = c.sessions.messages(sid).filter { it.role == Role.ASSISTANT }
                assertEquals(expected, saved.joinToString("") { it.thinking })
                assertEquals("All tokens received.", saved.last().text)
                assertTrue("Expected frequent live publication, got $publications", publications > 30)
                val rate = 160 * 64 * 1000L / generationMs.coerceAtLeast(1)
                assertTrue("Synthetic input should exceed 1000 tokens/s, got $rate", rate > 1000)
                scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
                val samples = synchronized(metrics) { metrics.sorted() }
                val layoutSamples = synchronized(metrics) { layoutMetrics.sorted() }
                assertTrue(samples.isNotEmpty())
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("fast_thinking", "synthetic_tokens_per_second=$rate publications=$publications frames=${samples.size} p95_ms=${samples[((samples.size - 1) * .95).toInt()]} layout_p95_ms=${layoutSamples[((layoutSamples.size - 1) * .95).toInt()]}")
                })
            }
        } finally {
            observer.cancelAndJoin()
            runs.stopAndJoin(sid)
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            thread.quitSafely()
        }
    }
}
