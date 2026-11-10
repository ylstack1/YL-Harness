package com.androidharness.app.ui.chat

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.agent.CodeDraftStage
import com.androidharness.app.agent.LiveCodePreview
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.ui.theme.HarnessTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production chat list, including provider-ID reuse and commit races. */
@RunWith(AndroidJUnit4::class)
class LiveCodeHandoffDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    private fun call(id: String, path: String) = ToolCallData(id, "write_file", buildJsonObject {
        put("path", path); put("content", "fixture line\n")
    }.toString())

    private fun exercise(block: suspend (MutableStateFlow<ChatUiState>, ChatUiState) -> Unit) = runBlocking {
        val sid = c.sessions.createSession("Live code handoff regression")
        var vm: ChatViewModel? = null
        try {
            instrumentation.runOnMainSync { vm = ChatViewModel(c, sid) }
            val model = requireNotNull(vm)
            withTimeout(10000) { model.state.first { !it.isLoadingMessages } }
            instrumentation.runOnMainSync { model.viewModelScope.cancel() }
            @Suppress("UNCHECKED_CAST")
            val state = model.state as MutableStateFlow<ChatUiState>
            val base = state.value.copy(busy = true, showLiveCode = true, currentTurnId = "fixture-turn",
                streamingText = null, streamingThinking = null, streamingMessageId = null,
                runningCalls = emptyList(), codeDrafts = emptyMap(),
                messages = listOf(ChatMessage(Role.USER, "Check live code previews", id = "user", turnId = "fixture-turn")))
            state.value = base
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { HarnessTheme {
                    ChatScreen(model, {}, { _, _ -> }, {}, {}, onOpenSubagent = {})
                } } }
                block(state, base)
            }
        } finally {
            instrumentation.runOnMainSync { vm?.viewModelScope?.cancel() }
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
        }
    }

    @Test fun repeatedProviderIdsAcrossMessagesAndWithinABatchCannotCollide() = exercise { state, base ->
        val first = call("reused", "first.kt")
        val second = call("reused", "second.kt")
        val third = call("reused", "third.kt")
        state.value = base.copy(messages = base.messages + listOf(
            ChatMessage(Role.ASSISTANT, toolCalls = listOf(first, second), id = "response-a", turnId = "fixture-turn"),
            ChatMessage(Role.ASSISTANT, toolCalls = listOf(third), id = "response-b", turnId = "fixture-turn"),
        ))
        awaitText("first.kt")
        awaitText("second.kt")
        awaitText("third.kt")
    }

    @Test fun streamedDraftsCommitWithoutDuplicatedOrMissingPreviewRows() = exercise { state, base ->
        repeat(30) { index ->
            val tool = call("handoff-$index", "handoff.kt")
            val draft = requireNotNull(LiveCodePreview.build(tool.id, tool.name, tool.argumentsJson, true))
            val generating = base.copy(streamingMessageId = "response-$index", streamingText = "Preparing file $index",
                codeDrafts = mapOf(tool.id to draft))
            state.value = generating
            awaitText("handoff.kt")
            // Room can publish a saved assistant row while the live preview is still present.
            state.value = generating.copy(messages = base.messages + ChatMessage(Role.ASSISTANT,
                toolCalls = listOf(tool), id = "response-$index", turnId = "fixture-turn"),
                codeDrafts = mapOf(tool.id to draft.copy(stage = CodeDraftStage.WRITING)))
            instrumentation.waitForIdleSync()
            SystemClock.sleep(16)
            state.value = state.value.copy(streamingText = null, streamingMessageId = null,
                codeDrafts = mapOf(tool.id to draft.copy(stage = CodeDraftStage.SAVED)))
            awaitText("handoff.kt")
        }
    }

    private fun awaitText(text: String) {
        fun contains(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.text?.toString()?.contains(text) == true || node.contentDescription?.toString()?.contains(text) == true) return true
            return (0 until node.childCount).any { contains(node.getChild(it)) }
        }
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            if (contains(instrumentation.uiAutomation.rootInActiveWindow)) return
            SystemClock.sleep(30)
        }
        fail("Production chat did not render $text")
    }
}
