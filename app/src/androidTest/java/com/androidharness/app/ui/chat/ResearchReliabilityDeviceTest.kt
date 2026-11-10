package com.androidharness.app.ui.chat

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.agent.*
import com.androidharness.app.browser.BrowserController
import com.androidharness.app.core.*
import com.androidharness.app.data.*
import com.androidharness.app.llm.*
import com.androidharness.app.tools.*
import com.androidharness.app.ui.chat.components.ChatActivity
import com.androidharness.app.ui.theme.HarnessTheme
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ResearchReliabilityDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun readCounterAdvancesWithoutAnotherSearchAndIgnoresFailedPages() {
        val search = ToolCallData("s", "web_search", "{}")
        val rows = mutableStateOf(listOf(ChatMessage(Role.ASSISTANT, toolCalls = listOf(search)),
            ChatMessage(Role.TOOL, "Search results", toolCallId = "s", generation = GenerationMetadata(
                research = ResearchReceipt(88, 100, 2, 8)))))
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                HarnessTheme(dynamicColor = false) { Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
                    ChatActivity(rows.value, live = true, autoExpand = false)
                } }
            } }
            await("88"); await("2")
            for (count in 3..4) instrumentation.runOnMainSync {
                val call = ToolCallData("read-$count", if (count == 3) "web_fetch" else "browser_read", """{"url":"https://read.test/$count"}""")
                rows.value += listOf(ChatMessage(Role.ASSISTANT, toolCalls = listOf(call)),
                    ChatMessage(Role.TOOL, "Article", toolCallId = call.id, generation = GenerationMetadata(
                        research = ResearchReceipt(88, 100, count, 8, "https://read.test/$count", true))))
            }.also { await(count.toString()) }
            instrumentation.runOnMainSync {
                rows.value += listOf(ChatMessage(Role.ASSISTANT, toolCalls = listOf(ToolCallData("failed", "web_fetch", "{}"))),
                    ChatMessage(Role.TOOL, "HTTP 429", toolCallId = "failed", isError = true))
            }
            await("4"); assertNull(find("5"))
        }
    }

    @Test fun agentAutomaticallySeesTheFreshlySelectedTavilyKeyInChatAndCode(): Unit = runBlocking {
        val saved = c.settings.settings.first()
        val previousKey = c.keys.searchApiKey("tavily")
        val key = "tvly-fixture-${System.nanoTime()}"
        val root = File(c.appContext.cacheDir, "research-config-${System.nanoTime()}").apply { mkdirs() }
        val sessions = mutableListOf<String>()
        try {
            c.keys.putSearchApiKey("tavily", key)
            c.settings.setWebSearchProvider("tavily")
            val config = ProviderConfig("research-fixture", "Fixture", ProviderType.OPENAI_COMPAT, "https://example.invalid", "fixture")
            val captured = mutableListOf<String>()
            val provider = object : LlmProvider {
                override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
                    messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                    captured += systemPrompt
                    assertTrue("Selected Tavily backend is missing from the agent context", systemPrompt.contains("Tavily Search API"))
                    assertTrue(systemPrompt.contains("saved API key is available"))
                    assertTrue(systemPrompt.contains("web_fetch also uses Tavily Extract"))
                    assertFalse(systemPrompt.contains(key))
                    assertTrue(tools.any { it.name == "web_search" })
                    emit(StreamEvent.TextDelta("Search is configured.")); emit(StreamEvent.Done("stop"))
                }
            }
            val engine = AgentEngine({ provider }, c.registry, c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills)
            for (kind in listOf(ConversationKind.CHAT, ConversationKind.CODE)) {
                val conversation = ConversationConfig(kind)
                val sid = c.sessions.createSession("Research config fixture", conversation = conversation).also(sessions::add)
                val events = engine.run(sid, "research", config, "", listOf(ChatMessage(Role.USER, "Check research configuration")),
                    { PermissionMode.FULL_AUTO }, mutableSetOf(), FileFs(root), RequestOptions(), 100000,
                    AgentMode.ACT, maxIterations = 2, repoMapEnabled = false, conversation = conversation).toList()
                assertFalse(events.any { it is AgentEvent.Error })
            }
            assertEquals(2, captured.size)
        } finally {
            if (previousKey == null) c.keys.removeSearchApiKey("tavily") else c.keys.putSearchApiKey("tavily", previousKey)
            c.settings.setWebSearchProvider(saved.webSearchProvider)
            sessions.forEach { sid -> c.sessions.session(sid)?.let { c.sessions.deleteSession(it) } }
            root.deleteRecursively()
        }
    }

    @Test fun legacyTavilyKeyIsNotLostInTheKeylessSlotOrAllowedToOverwriteAReplacement() {
        val prefix = "research-migration-${System.nanoTime()}-"
        val base = instrumentation.targetContext
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefix + name, mode)
            override fun deleteSharedPreferences(name: String) = base.deleteSharedPreferences(prefix + name)
        }
        val keys = KeyStoreManager(context)
        val field = KeyStoreManager::class.java.getDeclaredField("prefs").apply { isAccessible = true }
        val preferences = field.get(keys) as android.content.SharedPreferences
        try {
            preferences.edit().putString("search_api_key", "tvly-legacy-fixture").commit()
            keys.migrateLegacySearchKey("keyless")
            assertEquals("tvly-legacy-fixture", keys.searchApiKey("tavily"))
            assertNull(keys.searchApiKey("keyless"))
            keys.putSearchApiKey("tavily", "tvly-replacement-fixture")
            preferences.edit().putString("search_api_key", "tvly-old-fixture").commit()
            keys.migrateLegacySearchKey("tavily")
            assertEquals("tvly-replacement-fixture", keys.searchApiKey("tavily"))
        } finally { context.deleteSharedPreferences("api_keys") }
    }

    @Test fun renderedJavaScriptCountsOnceAndBlankPagesDoNotConsumeTheReadBudget(): Unit = runBlocking {
        val root = File(c.appContext.cacheDir, "rendered-research-${System.nanoTime()}").apply { mkdirs() }
        File(root, "article.html").writeText("<html><title>Article fixture</title><main id='text'></main><script>document.getElementById('text').innerText='Actual article text rendered by JavaScript.'</script></html>")
        File(root, "blank.html").writeText("<html><title>Blank fixture</title><body></body></html>")
        val controller = BrowserController(c.appContext, c.images)
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use {
                val budget = SearchSourceBudget(100, 40, 8)
                val tool = BrowserReadTool(controller).forBudget(budget)
                val article = BrowserController.localFileUrl("article.html")
                val args = buildJsonObject { put("url", article) }
                val ctx = ToolContext(FileFs(root))
                val read = tool.execute(args, ctx)
                assertTrue(read.output, read.ok); assertEquals(1, budget.pagesRead)
                assertTrue(read.output.contains("rendered by JavaScript"))
                assertTrue(tool.execute(args, ctx).ok); assertEquals(1, budget.pagesRead)
                val empty = tool.execute(buildJsonObject { put("url", BrowserController.localFileUrl("blank.html")) }, ctx)
                assertFalse(empty.output, empty.ok); assertEquals(1, budget.pagesRead)
            }
        } finally { controller.close(); root.deleteRecursively() }
    }

    private fun find(text: String): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && node.text?.toString() == text) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun await(text: String) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) { if (find(text) != null) return; SystemClock.sleep(80) }
        error("Missing research counter: $text")
    }
}
