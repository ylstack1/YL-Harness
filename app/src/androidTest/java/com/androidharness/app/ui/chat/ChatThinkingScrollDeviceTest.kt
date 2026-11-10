package com.androidharness.app.ui.chat

import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.agent.RunManager
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.data.ConversationConfig
import com.androidharness.app.data.ConversationKind
import com.androidharness.app.ui.theme.HarnessTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatThinkingScrollDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun readingThinkingDoesNotMoveTheQuestionAndShortCompletionKeepsItsAnchor() = thinkingScroll(ConversationKind.CHAT)
    @Test fun codeThinkingContainsEdgeFlingsWhileStreaming() = thinkingScroll(ConversationKind.CODE)

    private fun thinkingScroll(kind: ConversationKind): Unit = runBlocking {
        val saved = c.settings.settings.first()
        val sid = c.sessions.createSession("Thinking scroll fixture", conversation = ConversationConfig(kind))
        val store = ViewModelStore()
        @Suppress("UNCHECKED_CAST")
        val live = c.runManager.live(sid) as MutableStateFlow<RunManager.LiveRunState>
        val thought = (1..100).joinToString("\n\n") { "Reasoning line $it: inspect this part of the problem carefully before answering." }
        val turn = "thinking-scroll"
        try {
            c.settings.setChatKeepThinkingCollapsed(true)
            c.sessions.addMessage(sid, ChatMessage(Role.USER, "Keep this question visible"), turn)
            live.value = RunManager.LiveRunState(sid, running = true, turnId = turn, liveMessageId = "thinking-live", streamingThinking = thought, thinkingTiming = com.androidharness.app.agent.ThinkingTiming().start(com.androidharness.app.agent.ThinkingTiming.now()))
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { host ->
                    val vm = ChatViewModel(c, sid); store.put("chat", vm)
                    host.setContent { HarnessTheme(dynamicColor = false) { ChatScreen(vm, {}, { _, _ -> }, {}, {}, onOpenSubagent = {}) } }
                }
                await("Keep this question visible"); await("Thinking"); frames()
                var original = bounds("Keep this question visible")
                tap("Thinking"); frames()
                waitFor("Latest thinking should appear") { nodes().any { it.text?.toString()?.contains("Reasoning line 100") == true } }
                var pane: AccessibilityNodeInfo? = find("Reasoning line 100", contains = true)
                while (pane != null && !pane.isScrollable) pane = pane.parent
                requireNotNull(pane)
                val area = Rect().also(requireNotNull(pane)::getBoundsInScreen)
                if (kind == ConversationKind.CODE) original = bounds("Keep this question visible")
                // Both ends of the nested list, including repeated attempts to scroll past them.
                repeat(3) { swipe(area, towardEnd = true) }
                assertNear("Thinking end must not drag the transcript", original.top, bounds("Keep this question visible").top)
                repeat(3) { swipe(area, towardEnd = false) }
                assertNear("Reading earlier thinking must not drag the transcript", original.top, bounds("Keep this question visible").top)
                repeat(8) { index ->
                    live.value = live.value.copy(streamingThinking = thought + "\n\nMore reasoning $index", streamingText = "A short answer $index.")
                    SystemClock.sleep(60)
                }
                assertNear("Streaming keeps the question anchored", original.top, bounds("Keep this question visible").top)
                val readingBeforeCommit = await("Reasoning line", contains = true).text.toString().take(80)
                c.sessions.addMessage(sid, ChatMessage(Role.ASSISTANT, "A short final answer.", thinking = thought, thinkingMs = 2000, id = "thinking-live"), turn)
                live.value = RunManager.LiveRunState(sid, lastCommittedId = "thinking-live")
                await("A short final answer."); frames()
                if (kind == ConversationKind.CHAT) {
                    assertNear("Completion keeps enough room to anchor a short turn", original.top, bounds("Keep this question visible").top)
                    assertEquals("Saving the reply must retain the position inside thinking", readingBeforeCommit,
                        await("Reasoning line", contains = true).text.toString().take(80))
                    tap("Thoughts"); frames()
                    assertNear("Closing thinking must not drop the question", original.top, bounds("Keep this question visible").top)
                } else {
                    // Code deliberately folds finished reasoning into the work summary.
                    await("Work summary")
                }
            }
        } finally {
            live.value = RunManager.LiveRunState(sid)
            instrumentation.runOnMainSync { store.clear() }
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            c.settings.setChatKeepThinkingCollapsed(saved.chatKeepThinkingCollapsed)
            c.settings.setLastActiveSessionId(saved.lastActiveSessionId)
        }
    }

    @Test fun longStreamingReplyKeepsQuestionAtTopButManualTranscriptScrollWinsAtCompletion() = longReply(ConversationKind.CHAT)
    @Test fun codeCompletionKeepsTheParagraphBeingRead() = longReply(ConversationKind.CODE)

    private fun longReply(kind: ConversationKind): Unit = runBlocking {
        val saved = c.settings.settings.first()
        val sid = c.sessions.createSession("Answer anchor fixture", conversation = ConversationConfig(kind))
        val store = ViewModelStore()
        @Suppress("UNCHECKED_CAST") val live = c.runManager.live(sid) as MutableStateFlow<RunManager.LiveRunState>
        val turn = "answer-anchor"
        val reply = (1..45).joinToString("\n\n") { "Answer paragraph $it. " + "Read this detail carefully. ".repeat(6) } + "\n\nEND OF ANSWER"
        try {
            c.sessions.addMessage(sid, ChatMessage(Role.USER, "Start this answer below my question"), turn)
            live.value = RunManager.LiveRunState(sid, running = true, turnId = turn, liveMessageId = "answer-live", streamingText = "Starting the answer.")
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { host -> val vm = ChatViewModel(c, sid); store.put("chat", vm)
                    host.setContent { HarnessTheme(dynamicColor = false) { ChatScreen(vm, {}, { _, _ -> }, {}, {}, onOpenSubagent = {}) } } }
                await("Start this answer below my question"); frames()
                val top = bounds("Start this answer below my question").top
                live.value = live.value.copy(streamingText = reply)
                frames()
                if (kind == ConversationKind.CHAT) {
                    assertNear("A growing answer should not pull the question upward", top, bounds("Start this answer below my question").top)
                    tap("Scroll to latest")
                }
                await("END OF ANSWER", contains = true)
                val display = instrumentation.targetContext.resources.displayMetrics
                val area = Rect(0, (display.heightPixels * .35f).toInt(), display.widthPixels, (display.heightPixels * .7f).toInt())
                repeat(2) { swipe(area, towardEnd = false) }
                waitFor("Manual reading leaves the answer end") { find("Scroll to latest") != null && find("END OF ANSWER", true) == null }
                val reading = nodes().first { node ->
                    node.text?.toString()?.startsWith("Answer paragraph") == true &&
                        Rect().also(node::getBoundsInScreen).top > area.top
                }.text.toString()
                val readingTop = bounds(reading).top
                c.sessions.addMessage(sid, ChatMessage(Role.ASSISTANT, reply, id = "answer-live"), turn)
                live.value = RunManager.LiveRunState(sid, lastCommittedId = "answer-live")
                frames(); SystemClock.sleep(500)
                assertNull("Finishing must not override manual reading", find("Start this answer below my question"))
                assertNull("Finishing must not force the bottom", find("END OF ANSWER", true))
                if (kind == ConversationKind.CHAT) assertNear("Saving the streamed answer must retain the paragraph being read", readingTop, bounds(reading).top)
                else assertTrue("Code completion should keep the paragraph in place, allowing its saved answer label",
                    kotlin.math.abs(readingTop - bounds(reading).top) < 48 * display.density)
                c.sessions.addMessage(sid, ChatMessage(Role.USER, "Anchor the next question"), "next-question")
                live.value = RunManager.LiveRunState(sid, running = true, turnId = "next-question", liveMessageId = "next-live", streamingText = "The next answer starts here.")
                await("Anchor the next question"); frames()
                if (kind == ConversationKind.CHAT) assertNear("A newly sent turn anchors after earlier history", top, bounds("Anchor the next question").top)
                else await("The next answer starts here.")
            }
        } finally {
            live.value = RunManager.LiveRunState(sid)
            instrumentation.runOnMainSync { store.clear() }
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            c.settings.setLastActiveSessionId(saved.lastActiveSessionId)
        }
    }

    @Test fun codeReturnsToFollowingAfterDraggingBackToTheGrowingEnd(): Unit = runBlocking {
        val saved = c.settings.settings.first()
        val sid = c.sessions.createSession("Code follow fixture", conversation = ConversationConfig(ConversationKind.CODE))
        val store = ViewModelStore()
        @Suppress("UNCHECKED_CAST") val live = c.runManager.live(sid) as MutableStateFlow<RunManager.LiveRunState>
        var reply = (1..20).joinToString("\n\n") { "Follow paragraph $it. " + "Read the code details. ".repeat(4) }
        try {
            c.sessions.addMessage(sid, ChatMessage(Role.USER, "Follow the code answer"), "follow-turn")
            live.value = RunManager.LiveRunState(sid, running = true, turnId = "follow-turn", liveMessageId = "follow-live", streamingText = reply + "\n\nFOLLOW END 0")
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { host -> val vm = ChatViewModel(c, sid); store.put("chat", vm)
                    host.setContent { HarnessTheme(dynamicColor = false) { ChatScreen(vm, {}, { _, _ -> }, {}, {}, onOpenSubagent = {}) } } }
                await("FOLLOW END 0", true); frames()
                val display = instrumentation.targetContext.resources.displayMetrics
                val area = Rect(0, (display.heightPixels * .3f).toInt(), display.widthPixels, (display.heightPixels * .7f).toInt())
                swipe(area, towardEnd = false)
                waitFor("Manual reading must leave the end") { find("FOLLOW END 0", true) == null }
                repeat(4) { index ->
                    reply += "\n\nAdded code detail $index. " + "New streaming content. ".repeat(3)
                    live.value = live.value.copy(streamingText = reply + "\n\nFOLLOW END 1")
                    SystemClock.sleep(80)
                }
                assertNull("Stream growth must preserve history reading", find("FOLLOW END 1", true))
                // Return using only real finger drags, without the jump button.
                repeat(8) { if (find("FOLLOW END 1", true) == null) swipe(area, towardEnd = true) }
                await("FOLLOW END 1", true)
                swipe(area, towardEnd = true) {
                    // Extend the end while the finger is still down, before the drag settles.
                    reply += "\n\nArrived while dragging. ".repeat(12)
                    live.value = live.value.copy(streamingText = reply + "\n\nFOLLOW END 1")
                    frames(); SystemClock.sleep(100)
                }
                repeat(6) { index ->
                    reply += "\n\nNew following detail $index. " + "More live code output. ".repeat(4)
                    live.value = live.value.copy(streamingText = reply + "\n\nFOLLOW END ${index + 2}")
                    SystemClock.sleep(150)
                }
                await("FOLLOW END 7", true)
                assertNull("Following should hide the jump button", find("Scroll to latest"))
            }
        } finally {
            live.value = RunManager.LiveRunState(sid)
            instrumentation.runOnMainSync { store.clear() }
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            c.settings.setLastActiveSessionId(saved.lastActiveSessionId)
        }
    }

    private fun descendants(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
        add(node); for (i in 0 until node.childCount) node.getChild(i)?.let { addAll(descendants(it)) }
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::descendants).orEmpty().filter { it.isVisibleToUser }
    }
    private fun find(label: String, contains: Boolean = false) = nodes().firstOrNull { listOfNotNull(it.text?.toString(), it.contentDescription?.toString()).any { value -> if (contains) value.contains(label) else value == label } }
    private fun waitFor(reason: String, check: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 12_000
        while (SystemClock.uptimeMillis() < until) { if (check()) return; SystemClock.sleep(100) }
        assertTrue("$reason; visible: ${nodes().mapNotNull { it.text?.toString() }}", check())
    }
    private fun await(label: String, contains: Boolean = false): AccessibilityNodeInfo { waitFor("Missing $label") { find(label, contains) != null }; return requireNotNull(find(label, contains)) }
    private fun bounds(label: String) = Rect().also { await(label).getBoundsInScreen(it) }
    private fun assertNear(reason: String, expected: Int, actual: Int) { assertTrue("$reason: $expected vs $actual", kotlin.math.abs(expected - actual) < 12) }
    private fun frames() {
        val latch = java.util.concurrent.CountDownLatch(1)
        instrumentation.runOnMainSync { android.view.Choreographer.getInstance().postFrameCallback { android.view.Choreographer.getInstance().postFrameCallback { latch.countDown() } } }
        assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
    }
    private fun tap(label: String) {
        waitFor("Tap $label") { var node = find(label); while (node != null && !node.isClickable) node = node.parent; node?.isEnabled == true && node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }; frames()
    }
    private fun swipe(area: Rect, towardEnd: Boolean, beforeRelease: () -> Unit = {}) {
        val x = area.centerX().toFloat(); val from = area.top + area.height() * if (towardEnd) .8f else .2f
        val to = area.top + area.height() * if (towardEnd) .2f else .8f
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float) { val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0); try { instrumentation.sendPointerSync(event) } finally { event.recycle() } }
        send(MotionEvent.ACTION_DOWN, from)
        repeat(10) { i -> SystemClock.sleep(20); send(MotionEvent.ACTION_MOVE, from + (to - from) * (i + 1) / 10) }
        beforeRelease()
        send(MotionEvent.ACTION_UP, to); SystemClock.sleep(350); frames()
    }
}
