package com.androidharness.app.ui.chat

import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.data.ThemeMode
import com.androidharness.app.ui.chat.components.ChatActivity
import com.androidharness.app.ui.theme.HarnessTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ActivityReaderDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val call = ToolCallData("reader-tool", "read_file", "{\"path\":\"Fixture.kt\",\"limit\":500}")
    private val output = (0 until 350).joinToString("\n") { "OUTPUT_$it: inspect this line and verify the implementation." } + "\nEND_OF_TOOL_OUTPUT"
    private fun history() = listOf(
        ChatMessage(Role.ASSISTANT, thinking = "INITIAL_REASONING: inspect the source before making a change.", toolCalls = listOf(call), id = "reader-thought"),
        ChatMessage(Role.TOOL, output, toolCallId = call.id, id = "reader-result"),
        ChatMessage(Role.ASSISTANT, thinking = "AFTER_TOOL_REASONING", id = "reader-after"),
    )

    @Test fun expandedToolUsesOneActivityScrollerAndFullReaderKeepsTheCompleteOutput() {
        lateinit var transcript: LazyListState
        val owner = ChatDetailScroll { }
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { HarnessTheme(ThemeMode.DARK, false) {
                CompositionLocalProvider(LocalChatDetailScroll provides owner) {
                    Surface(Modifier.fillMaxSize()) {
                        transcript = rememberChatListState()
                        LazyColumn(state = transcript, modifier = Modifier.fillMaxSize().safeDrawingPadding(), contentPadding = PaddingValues(20.dp)) {
                            item { Text("Transcript question") }
                            item { ChatActivity(history(), autoExpand = false) }
                            items(20) { Text("Later transcript message $it\n" + "Keep the chat in place. ".repeat(8), Modifier.padding(vertical = 12.dp)) }
                        }
                    }
                }
            } } }
            click("Expand")
            await { nodes().any { text(it).contains("INITIAL_REASONING") } }
            click("Expand")
            await { nodes().any { text(it).contains("OUTPUT_0") } }
            assertEquals("Only transcript and activity should scroll", 2, scrollers().size)
            val pane = bounds(scrollers().minBy { bounds(it).height() })
            val before = transcript.firstVisibleItemIndex to transcript.firstVisibleItemScrollOffset
            repeat(3) { swipe(pane, up = true) }
            await { nodes().none { text(it).contains("INITIAL_REASONING") } }
            assertEquals("Dragging output must not move the transcript", before, transcript.firstVisibleItemIndex to transcript.firstVisibleItemScrollOffset)
            screenshot("activity-tool-inline-dark")
            click("Open full view")
            await { owner.reading && nodes().any { text(it).contains("INITIAL_REASONING") } }
            click("Expand")
            await { nodes().any { text(it).contains("OUTPUT_0") } }
            assertEquals("Expanded full activity must have one scroll owner", 1, scrollers().size)
            click("Open tool details")
            await { nodes().any { text(it) == "Output" } && nodes().any { text(it).contains("OUTPUT_0") } }
            click("Latest")
            await { nodes().any { text(it).contains("END_OF_TOOL_OUTPUT") } }
            screenshot("activity-tool-full-tail")
            click("Copy text")
            assertEquals(output, context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString())
            click("Input")
            await { nodes().any { text(it).contains("Fixture.kt") } }
            assertTrue(nodes().none { text(it).contains("END_OF_TOOL_OUTPUT") })
            click("Output")
            await { nodes().any { text(it).contains("OUTPUT_0") } }
            click("Close")
            assertTrue("The activity reader still owns reading", owner.reading)
            click("Close")
            await { !owner.reading }
            assertEquals(before, transcript.firstVisibleItemIndex to transcript.firstVisibleItemScrollOffset)
        }
    }

    @Test fun openingToolOutputPausesLiveFollowAndReadingSurvivesNewThinking() {
        val streaming = mutableStateOf("Live thought")
        lateinit var transcript: LazyListState
        val owner = ChatDetailScroll { }
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { HarnessTheme(ThemeMode.LIGHT, false) {
                CompositionLocalProvider(LocalChatDetailScroll provides owner) {
                    Surface(Modifier.fillMaxSize()) {
                        transcript = rememberChatListState()
                        LazyColumn(state = transcript, modifier = Modifier.fillMaxSize().safeDrawingPadding(), contentPadding = PaddingValues(20.dp)) {
                            item { Text("Live question") }
                            item { ChatActivity(history().dropLast(1), live = true, streamingThinking = streaming.value, autoExpand = false) }
                            items(10) { Text("Transcript after $it", Modifier.padding(20.dp)) }
                        }
                    }
                }
            } } }
            click("Expand")
            await { nodes().any { text(it).contains("Live thought") } }
            click("Expand")
            await { nodes().any { text(it).contains("OUTPUT_0") } }
            val pane = bounds(scrollers().minBy { bounds(it).height() })
            val before = transcript.firstVisibleItemIndex to transcript.firstVisibleItemScrollOffset
            swipe(pane, up = true)
            scenario.onActivity { streaming.value = "NEW_STREAM_MARKER\n" + "New thinking must not steal the reader’s position.\n".repeat(100) }
            SystemClock.sleep(500)
            assertTrue("Live updates must not jump to the new tail", nodes().none { text(it).contains("NEW_STREAM_MARKER") })
            assertEquals(before, transcript.firstVisibleItemIndex to transcript.firstVisibleItemScrollOffset)
            screenshot("activity-tool-live-reading")
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun walk(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) emptyList()
            else listOf(node) + (0 until node.childCount).flatMap { walk(node.getChild(it)) }
        val root = instrumentation.uiAutomation.rootInActiveWindow
        if (root?.packageName?.toString() != context.packageName) return emptyList()
        return walk(root)
    }
    private fun text(node: AccessibilityNodeInfo) = node.text?.toString().orEmpty()
    private fun scrollers() = nodes().filter { it.isScrollable }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun click(label: String) {
        await { nodes().any { text(it).lines().contains(label) || it.contentDescription?.toString() == label } }
        val targets = nodes().filter { text(it).lines().contains(label) || it.contentDescription?.toString() == label }
        val target = targets.firstNotNullOfOrNull { node -> generateSequence(node) { it.parent }.firstOrNull { it.isClickable } }
        assertNotNull("Missing button $label", target)
        assertTrue(target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        SystemClock.sleep(250)
    }
    private fun await(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 12_000
        while (SystemClock.uptimeMillis() < until) { if (condition()) return; SystemClock.sleep(100) }
        screenshot("activity-reader-failure")
        error("Activity reader did not reach the expected state")
    }
    private fun swipe(rect: Rect, up: Boolean) {
        val from = rect.top + rect.height() * if (up) .8f else .25f
        val to = rect.top + rect.height() * if (up) .25f else .8f
        val start = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float) {
            MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, rect.exactCenterX(), y, 0).let { event ->
                try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            }
        }
        send(MotionEvent.ACTION_DOWN, from)
        for (step in 1..16) { SystemClock.sleep(18); send(MotionEvent.ACTION_MOVE, from + (to - from) * step / 16) }
        send(MotionEvent.ACTION_UP, to); SystemClock.sleep(250)
    }
    private fun screenshot(name: String) {
        if (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() != context.packageName) return
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
}
