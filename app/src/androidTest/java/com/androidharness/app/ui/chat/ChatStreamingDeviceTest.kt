package com.androidharness.app.ui.chat

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.R
import com.androidharness.app.core.*
import com.androidharness.app.data.*
import com.androidharness.app.ui.theme.HarnessTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ChatStreamingDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    private fun exercise(block: suspend (MutableStateFlow<ChatUiState>, ChatUiState) -> Unit): Unit = runBlocking {
        val saved = c.settings.settings.first()
        val sid = c.sessions.createSession("Chat streaming fixture", conversation = ConversationConfig(ConversationKind.CHAT))
        var vm: ChatViewModel? = null
        try {
            c.settings.setChatKeepThinkingCollapsed(true)
            instrumentation.runOnMainSync { vm = ChatViewModel(c, sid) }
            val model = requireNotNull(vm)
            withTimeout(10000) { model.state.first { !it.isLoadingMessages && it.conversation.isChat } }
            instrumentation.runOnMainSync { model.viewModelScope.cancel() }
            @Suppress("UNCHECKED_CAST")
            val state = model.state as MutableStateFlow<ChatUiState>
            val base = state.value.copy(busy = true, currentTurnId = "turn", streamingMessageId = "answer",
                streamingText = null, streamingThinking = null, messages = listOf(
                    ChatMessage(Role.USER, "Explain the results clearly.", id = "question", turnId = "turn")))
            state.value = base
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { HarnessTheme(dynamicColor = false) {
                    ChatScreen(model, {}, { _, _ -> }, {}, {}, onOpenSubagent = {})
                } } }
                block(state, base)
            }
        } finally {
            instrumentation.runOnMainSync { vm?.viewModelScope?.cancel() }
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            c.settings.setChatKeepThinkingCollapsed(saved.chatKeepThinkingCollapsed)
            c.settings.setLastActiveSessionId(saved.lastActiveSessionId)
        }
    }

    @Test fun answerStreamsAfterToolsWithoutBeingHiddenInsideCollapsedThinking() = exercise { state, base ->
        val search = ToolCallData("search", "web_search", """{"query":"example"}""")
        val history = base.messages + listOf(
            ChatMessage(Role.ASSISTANT, "I will search for a source.", thinking = "Use the original report.",
                toolCalls = listOf(search), id = "search-message", turnId = "turn"),
            ChatMessage(Role.TOOL, "RAW_SEARCH_DATA", toolCallId = search.id, toolName = search.name, id = "result", turnId = "turn"))
        state.value = base.copy(messages = history, streamingText = "The first result is visible now.")
        awaitText("The first result is visible now.")
        assertTrue(state.value.busy)
        assertFalse(nodes().any { it.text?.toString() == "Use the original report." })
        state.value = state.value.copy(streamingText = "The first result is visible now. More text arrives before completion.")
        awaitText("The first result is visible now. More text arrives before completion.")
        tap("Writing a reply")
        awaitText("Use the original report.")
        assertEquals(1, nodes().count { it.isVisibleToUser && it.text?.toString() == state.value.streamingText })
        assertFalse(nodes().any { it.text?.toString() == "RAW_SEARCH_DATA" })
        screenshot("live-answer-after-search")
        // The same streamed prose becomes commentary only if its own request calls a tool.
        val fetch = ToolCallData("read", "web_fetch", """{"url":"https://example.com"}""")
        val commentary = ChatMessage(Role.ASSISTANT, state.value.streamingText.orEmpty(), toolCalls = listOf(fetch), id = "answer", turnId = "turn")
        state.value = state.value.copy(messages = history + commentary, runningCalls = listOf(fetch))
        awaitText(commentary.text)
        assertEquals(1, nodes().count { it.isVisibleToUser && it.text?.toString() == commentary.text })
        state.value = state.value.copy(messages = history + commentary + ChatMessage(Role.TOOL, "RAW_PAGE_DATA",
            toolCallId = fetch.id, toolName = fetch.name, turnId = "turn"), runningCalls = emptyList(),
            streamingMessageId = "final", streamingText = "This answer also streams after the second tool.")
        awaitText("This answer also streams after the second tool.")
        assertTrue(state.value.busy)
        screenshot("live-answer-after-second-tool")
    }

    @Test fun completionReturnsToQuestionAndBottomChoiceSurvivesLateUpdatesAndUserScrolls() = exercise { state, base ->
        val answer = (1..45).joinToString("\n\n") { "Paragraph $it explains one part of the result in enough detail to fill the conversation." } + "\n\nAnswer end marker."
        state.value = base.copy(streamingText = "A first streamed sentence.")
        awaitText("A first streamed sentence.")
        state.value = state.value.copy(streamingText = answer)
        awaitText("Paragraph 1 explains one part of the result in enough detail to fill the conversation.")
        state.value = state.value.copy(busy = false, currentTurnId = null, streamingText = null, streamingMessageId = null,
            messages = base.messages + ChatMessage(Role.ASSISTANT, answer, id = "answer", turnId = "turn"))
        SystemClock.sleep(300)
        val question = awaitText("Explain the results clearly.")
        assertTrue(bounds(question).top > 0 && bounds(question).top < instrumentation.targetContext.resources.displayMetrics.heightPixels / 2)
        screenshot("completed-at-question")
        tap(c.appContext.getString(R.string.ui_scroll_to_latest))
        awaitText("Answer end marker.")
        await { find("Explain the results clearly.") == null }
        val late = "Late footer marker."
        state.value = state.value.copy(messages = base.messages + ChatMessage(Role.ASSISTANT, "$answer\n\n$late", id = "answer", turnId = "turn"))
        awaitText(late)
        assertNull(find("Explain the results clearly."))
        screenshot("bottom-choice-preserved")
        swipeTowardOlderMessages()
        await { find(late) == null }
        SystemClock.sleep(350)
        val before = nodes().firstOrNull { it.isVisibleToUser && it.text?.toString()?.startsWith("Paragraph ") == true }
        assertNotNull(before)
        val beforeText = requireNotNull(before).text.toString()
        val beforeTop = bounds(before).top
        state.value = state.value.copy(messages = base.messages + ChatMessage(Role.ASSISTANT, "$answer\n\n$late\n\nAnother delayed footer.", id = "answer", turnId = "turn"))
        SystemClock.sleep(250)
        assertNull(find("Another delayed footer."))
        assertEquals(beforeTop.toFloat(), bounds(awaitText(beforeText)).top.toFloat(), 6f)
    }

    @Test fun explicitBottomChoiceWhileStreamingPreventsCompletionFromJumpingBack() = exercise { state, base ->
        val answer = (1..40).joinToString("\n\n") { "Section $it contains a longer explanation of the answer for this streaming test." } + "\n\nFinal answer marker."
        state.value = base.copy(streamingText = answer)
        awaitText("Section 1 contains a longer explanation of the answer for this streaming test.")
        assertNotNull(find("Explain the results clearly."))
        swipeTowardOlderMessages()
        await { find("Final answer marker.") == null }
        tap(c.appContext.getString(R.string.ui_scroll_to_latest))
        awaitText("Final answer marker.")
        state.value = state.value.copy(busy = false, currentTurnId = null, streamingText = null, streamingMessageId = null,
            messages = base.messages + ChatMessage(Role.ASSISTANT, answer, id = "answer", turnId = "turn"))
        awaitText("Final answer marker.")
        SystemClock.sleep(250)
        assertNull(find("Explain the results clearly."))
    }

    private fun swipeTowardOlderMessages() {
        val metrics = c.appContext.resources.displayMetrics
        val x = metrics.widthPixels * .5f
        val from = metrics.heightPixels * .35f
        val to = metrics.heightPixels * .72f
        val start = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.uiAutomation.injectInputEvent(event, true); event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, from)
        repeat(18) { step -> SystemClock.sleep(16); send(MotionEvent.ACTION_MOVE, from + (to - from) * (step + 1) / 18) }
        send(MotionEvent.ACTION_UP, to)
    }
    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo) { add(node); for (i in 0 until node.childCount) node.getChild(i)?.let(::visit) }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun find(text: String) = nodes().firstOrNull {
        val rect = bounds(it)
        it.isVisibleToUser && !rect.isEmpty && rect.bottom > 0 && rect.top < c.appContext.resources.displayMetrics.heightPixels &&
            (it.text?.toString() == text || it.contentDescription?.toString() == text)
    }
    private fun awaitText(text: String): AccessibilityNodeInfo { await { find(text) != null }; return requireNotNull(find(text)) }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        do { if (condition()) return; SystemClock.sleep(50) } while (SystemClock.uptimeMillis() < deadline)
        screenshot("failure"); error("Condition not met. Visible: ${nodes().filter { it.isVisibleToUser }.mapNotNull { it.text?.toString() }}")
    }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun tap(text: String) {
        var node = awaitText(text)
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        SystemClock.sleep(150) // Infinite UI animations need not become idle.
    }
    private fun screenshot(name: String) {
        val folder = File(c.appContext.getExternalFilesDir(null), "chat-streaming").apply { mkdirs() }
        SystemClock.sleep(150) // Infinite UI animations need not become idle.
        instrumentation.uiAutomation.takeScreenshot()!!.let { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
