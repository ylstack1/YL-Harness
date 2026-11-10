package com.androidharness.app.ui.chat

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.ui.chat.components.ThinkingBlock
import com.androidharness.app.ui.chat.components.ToolGroupCard
import com.androidharness.app.ui.chat.components.TurnActivityCard
import com.androidharness.app.ui.theme.HarnessTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatCardHistoryDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val output = "Inspect the real source first.\nThen verify the result."

    @Test fun thinkingShowsOnlyProviderOutputAndOmitsEmptySavedBlocks() {
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            fun render(host: ChatListTestActivity) { host.setContent { HarnessTheme {
                Surface(Modifier.fillMaxSize()) { Column(Modifier.padding(16.dp)) {
                    ThinkingBlock("")
                    ThinkingBlock(output)
                    Text("End of fixture")
                } }
            } } }
            scenario.onActivity(::render)
            await { texts().contains("End of fixture") }
            assertTrue(texts().count { it == "Thinking" } == 1)
            click("Expand")
            await { texts().any { it.contains(output) } }
            val visible = texts().joinToString("\n")
            listOf("tokens", "Duration", "Thinking: Max", "summary.").forEach {
                assertFalse("Unexpected diagnostic: $visible", visible.contains(it))
            }
            screenshot("thinking-only")
            scenario.recreate()
            scenario.onActivity(::render)
            await { texts().any { it.contains(output) } }
        }
    }

    @Test fun longSavedThinkingKeepsItsFinalParagraphAccessible() {
        val paragraphs = List(120) { "Reasoning paragraph $it: " + "Check the implementation and verify the result. ".repeat(3) }
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { host -> host.setContent { HarnessTheme {
                Surface(Modifier.fillMaxSize()) { Column(Modifier.padding(16.dp)) {
                    ThinkingBlock(paragraphs.joinToString("\n"))
                } }
            } } }
            click("Expand")
            await { texts().any { it.contains(paragraphs.first()) } }
            repeat(150) {
                if (texts().none { it.contains(paragraphs.last()) }) {
                    val scroller = nodes().firstOrNull { it.isScrollable }
                        ?: error("Thinking output is missing a scroll action")
                    assertTrue(scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                    SystemClock.sleep(180)
                }
            }
            await { texts().any { it.contains(paragraphs.last()) } }
            screenshot("thinking-tail")
        }
    }

    @Test fun largeToolGroupKeepsLastToolAndItsResultAccessible() = exerciseHistory(summary = false)
    @Test fun largeWorkSummaryKeepsLastMessageAndItsToolAccessible() = exerciseHistory(summary = true)

    private fun exerciseHistory(summary: Boolean) {
        val calls = List(40) { ToolCallData("history-$it", "read_file", "{\"path\":\"Fixture$it.kt\"}") }
        val results = calls.associate { it.id to ChatMessage(Role.TOOL, "Full result for ${it.id}", toolCallId = it.id) }
        val activity = calls.mapIndexed { index, call ->
            ChatMessage(Role.ASSISTANT, "Saved explanation $index", toolCalls = listOf(call), id = "message-$index")
        }
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { host -> host.setContent { HarnessTheme {
                Surface(Modifier.fillMaxSize()) { Column(Modifier.padding(16.dp)) {
                    if (summary) TurnActivityCard(calls, results, emptyList(), "10s", activity, { _, _ -> })
                    else ToolGroupCard(calls, results, emptySet(), { _, _ -> })
                } }
            } } }
            click(if (summary) "Expand activity" else "Expand")
            await { texts().any { it.contains("Read Fixture0.kt") } }
            // The final entries are retained and become visible through the bounded history.
            repeat(150) {
                if (find("Read Fixture39.kt") == null) {
                    val scroller = nodes().firstOrNull { it.isScrollable }
                        ?: error("History is missing a scroll action")
                    assertTrue(scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                    SystemClock.sleep(180)
                }
            }
            await { find("Read Fixture39.kt") != null }
            click("Read Fixture39.kt")
            // Opening the bottom row adds content below the viewport; scroll to its body.
            repeat(6) {
                if (texts().none { it.contains("Full result for history-39") }) {
                    nodes().firstOrNull { it.isScrollable }
                        ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    SystemClock.sleep(250)
                }
            }
            await { texts().any { it.contains("Full result for history-39") } }
            screenshot(if (summary) "summary-tail" else "tools-tail")
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun walk(node: AccessibilityNodeInfo?, result: MutableList<AccessibilityNodeInfo>) {
            node ?: return
            result += node
            for (index in 0 until node.childCount) walk(node.getChild(index), result)
        }
        return mutableListOf<AccessibilityNodeInfo>().also { walk(instrumentation.uiAutomation.rootInActiveWindow, it) }
    }
    private fun texts() = nodes().mapNotNull { it.text?.toString() }
    private fun find(value: String) = nodes().firstOrNull {
        it.text?.toString()?.lineSequence()?.any { line -> line == value } == true || it.contentDescription?.toString() == value
    }
    private fun click(value: String) {
        await { find(value) != null }
        var node = find(value)
        while (node != null) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            node = node.parent
        }
        error("Missing click action: $value")
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(60)
        }
        error("Expected UI not found: ${texts()}")
    }
    private fun screenshot(name: String) {
        val context = instrumentation.targetContext
        val file = java.io.File(context.getExternalFilesDir(null), "card-$name.png")
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
