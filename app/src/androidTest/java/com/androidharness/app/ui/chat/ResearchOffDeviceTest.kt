package com.androidharness.app.ui.chat

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.agent.*
import com.androidharness.app.core.*
import com.androidharness.app.data.*
import com.androidharness.app.llm.*
import com.androidharness.app.tools.*
import com.androidharness.app.ui.theme.HarnessTheme
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ResearchOffDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun offRejectsSearchEvenWithGrantsButKeepsReadsAndOtherToolsAndCode(): Unit = runBlocking {
        val root = File(c.appContext.cacheDir, "research-off-${System.nanoTime()}").apply { mkdirs() }
        val session = c.sessions.createSession("Research Off fixture")
        val requested = listOf("web_search", "web_fetch", "browser_read", "github_api", "read_chat_text")
        val executed = java.util.concurrent.CopyOnWriteArrayList<String>()
        fun tool(name: String) = object : Tool {
            override val name = name
            override val description = name
            override val parametersSchema = JsonObject(emptyMap())
            override val isReadOnly = true
            override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
                executed += name
                return ToolResult(true, "Fixture result")
            }
        }
        try {
            for ((kind, depth) in listOf(ConversationKind.CHAT to SearchDepth.OFF,
                ConversationKind.CHAT to SearchDepth.AUTO, ConversationKind.CODE to SearchDepth.OFF)) {
                executed.clear()
                var round = 0
                var advertised = emptyList<String>()
                val provider = object : LlmProvider {
                    override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
                        messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                        advertised = tools.map { it.name }
                        if (round++ == 0) {
                            emit(StreamEvent.ToolCallBatch(requested.mapIndexed { i, name -> ToolCallData("call-$i", name, "{}") }))
                            emit(StreamEvent.Done("tool_calls"))
                        } else { emit(StreamEvent.TextDelta("Fixture answer")); emit(StreamEvent.Done("stop")) }
                    }
                }
                val engine = AgentEngine({ provider }, ToolRegistry(requested.map(::tool)),
                    c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills)
                val events = engine.run(session, "off-$kind-$depth", ProviderConfig("fixture", "Fixture", ProviderType.OPENAI_COMPAT,
                    "http://localhost", "fixture"), "", emptyList(), { PermissionMode.FULL_ACCESS }, requested.toMutableSet(),
                    FileFs(root), RequestOptions(), 100_000, AgentMode.ACT, repoMapEnabled = false,
                    conversation = ConversationConfig(kind), searchDepth = depth).toList()
                val blocked = kind == ConversationKind.CHAT && depth == SearchDepth.OFF
                val expected = requested.filterNot { blocked && it == "web_search" }.toSet()
                assertEquals(expected, advertised.toSet())
                assertEquals(expected, executed.toSet())
                assertFalse(events.any { it is AgentEvent.Error || it is AgentEvent.ApprovalNeeded })
                val results = events.filterIsInstance<AgentEvent.ToolMessageCommitted>().map { it.message }
                assertEquals(blocked, results.single { it.toolName == "web_search" }.isError)
                assertTrue(results.filterNot { it.toolName == "web_search" }.none { it.isError })
            }
        } finally { c.sessions.session(session)?.let { c.sessions.deleteSession(it) }; root.deleteRecursively() }
    }

    @Test fun headerSelectsOffPersistsAndCanRestoreAuto(): Unit = runBlocking {
        val saved = c.settings.settings.first()
        val session = c.sessions.createSession("Research menu fixture", conversation = ConversationConfig(ConversationKind.CHAT))
        val store = ViewModelStore()
        fun display(scenario: ActivityScenario<ChatListTestActivity>) {
            scenario.onActivity { host ->
                store.clear()
                val vm = ChatViewModel(c, session)
                store.put("chat", vm)
                host.setContent { HarnessTheme { ChatScreen(vm, {}, { _, _ -> }, {}, {}, onOpenSubagent = {}) } }
            }
        }
        try {
            c.settings.setSearchDepth(SearchDepth.AUTO)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                display(scenario)
                tap("Search depth: Auto")
                await("Search disabled · links can still be read")
                tap("Off")
                await("Search depth: Off")
                waitFor { runBlocking { c.settings.settings.first().searchDepth == SearchDepth.OFF } }
                display(scenario)
                await("Search depth: Off")
                tap("Search depth: Off")
                tap("Auto")
                await("Search depth: Auto")
                waitFor { runBlocking { c.settings.settings.first().searchDepth == SearchDepth.AUTO } }
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            c.sessions.session(session)?.let { c.sessions.deleteSession(it) }
            c.settings.setSearchDepth(saved.searchDepth)
            c.settings.setLastActiveSessionId(saved.lastActiveSessionId)
        }
    }

    private fun find(label: String): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label)) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        return walk(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun waitFor(check: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 12_000
        while (SystemClock.uptimeMillis() < until) { if (check()) return; SystemClock.sleep(80) }
        assertTrue("Condition timed out", check())
    }
    private fun await(label: String): AccessibilityNodeInfo {
        waitFor { find(label) != null }
        return requireNotNull(find(label))
    }
    private fun tap(label: String) {
        var node: AccessibilityNodeInfo? = await(label)
        while (node != null && !node.isClickable) node = node.parent
        assertTrue("Clickable $label", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }
}
