package com.androidharness.app.ui.chat

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
import com.androidharness.app.ui.AppNav
import com.androidharness.app.ui.theme.HarnessTheme
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ConversationSectionsDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun chatPolicyIsEnforcedInTheEngineEvenWithFullAccessAndRememberedGrants() = runBlocking {
        val root = File(c.appContext.cacheDir, "chat-policy-${System.nanoTime()}").apply { mkdirs() }
        val session = c.sessions.createSession("Chat policy fixture")
        val executed = java.util.concurrent.CopyOnWriteArrayList<String>()
        fun tool(name: String) = object : Tool {
            override val name = name
            override val description = name
            override val parametersSchema = JsonObject(emptyMap())
            override val isReadOnly = true
            override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
                executed += name
                return ToolResult(true, "fixture result")
            }
        }
        try {
            for (policy in listOf(ConversationConfig(ConversationKind.CHAT),
                ConversationConfig(ConversationKind.CHAT, true, setOf(ChatTool.WEB_SEARCH)), ConversationConfig())) {
                executed.clear()
                var round = 0
                val requested = listOf("web_search", "fixture_code_action", "task", "mcp_fixture")
                var advertised = emptyList<String>()
                var prompt = ""
                val provider = object : LlmProvider {
                    override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
                        messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                        advertised = tools.map { it.name }; prompt = systemPrompt
                        if (round++ == 0) {
                            emit(StreamEvent.ToolCallBatch(requested.filter { policy.isChat || it != "task" }
                                .mapIndexed { index, name -> ToolCallData("call-$index", name, "{}") }))
                            emit(StreamEvent.Done("tool_calls"))
                        } else { emit(StreamEvent.TextDelta("Fixture answer")); emit(StreamEvent.Done("stop")) }
                    }
                }
                val engine = AgentEngine({ provider }, ToolRegistry(listOf(tool("web_search"), tool("fixture_code_action"), tool("task"))),
                    c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills)
                val events = engine.run(session, "policy-turn", ProviderConfig("fixture", "Fixture", ProviderType.OPENAI_COMPAT,
                    "http://localhost", "fixture"), "", emptyList(), { PermissionMode.FULL_ACCESS }, requested.toMutableSet(),
                    FileFs(root), RequestOptions(), 100_000, AgentMode.ACT,
                    extraTools = listOf(tool("mcp_fixture")), repoMapEnabled = false, conversation = policy).toList()
                assertFalse(events.any { it is AgentEvent.ApprovalNeeded || it is AgentEvent.Error })
                if (policy.isChat) {
                    assertEquals(listOf("web_search", "zoom_chat_image"), advertised)
                    assertEquals(listOf("web_search"), executed)
                    assertTrue(prompt.startsWith(com.androidharness.app.core.ChatExperience.SYSTEM_PROMPT))
                    assertTrue(prompt.contains("Current local date: ${java.time.LocalDate.now()}"))
                    assertFalse(prompt.contains(root.absolutePath))
                    assertTrue(events.filterIsInstance<AgentEvent.ToolMessageCommitted>().any {
                        it.message.toolName == "fixture_code_action" && it.message.isError
                    })
                } else {
                    assertTrue(advertised.containsAll(requested))
                    assertEquals(setOf("web_search", "fixture_code_action", "mcp_fixture"), executed.toSet())
                }
            }
        } finally { c.sessions.session(session)?.let { c.sessions.deleteSession(it) }; root.deleteRecursively() }
    }

    @Test fun savedChatPreferencesAndHandoffPreserveHistoryWithoutStartingCode() = runBlocking {
        val project = c.workspace.currentProjectOnce()
        val config = ConversationConfig(ConversationKind.CHAT, true, setOf(ChatTool.WEB_PAGES), draftText = "Keep my draft")
        val chat = c.sessions.createSession("Handoff fixture", project.id, config)
        var code: String? = null
        try {
            c.sessions.addMessage(chat, ChatMessage(Role.USER, "Make a compact search screen"))
            c.sessions.addMessage(chat, ChatMessage(Role.ASSISTANT, "Use the current layout"))
            c.sessions.addMessage(chat, ChatMessage(Role.SYSTEM, "system-only-context"))
            c.sessions.addMessage(chat, ChatMessage(Role.TOOL, "private-tool-output", toolCallId = "old-call", toolName = "web_fetch"))
            assertEquals(config, ConversationConfig.decode(c.sessions.session(chat)?.conversationJson))
            code = c.sessions.continueInCode(chat, project.id)
            val destination = requireNotNull(c.sessions.session(code))
            val codeConfig = ConversationConfig.decode(destination.conversationJson)
            assertEquals(ConversationKind.CODE, codeConfig.kind)
            assertNull(codeConfig.allowedTools)
            assertEquals(chat, codeConfig.sourceChatId)
            assertEquals(project.id, destination.projectId)
            assertTrue(destination.compactionSummary.contains("Make a compact search screen"))
            assertFalse(destination.compactionSummary.contains("system-only-context"))
            assertFalse(destination.compactionSummary.contains("private-tool-output"))
            assertEquals(4, c.sessions.messages(chat).size)
            assertFalse(c.runManager.isRunning(code))
            assertFalse(c.runManager.controls.flow(code).value.resumable)
            assertEquals(1, c.sessions.messages(code).size)
        } finally {
            code?.let { c.sessions.session(it)?.let { s -> c.sessions.deleteSession(s) } }
            c.sessions.session(chat)?.let { c.sessions.deleteSession(it) }
        }
    }

    @Test fun modeLabelPreservesExistingHeaderAtPhoneDesktopAndLargeText() = runBlocking {
        val saved = c.settings.settings.first()
        val project = c.workspace.currentProjectOnce()
        val code = c.sessions.createSession("Code section fixture", project.id)
        val chat = c.sessions.createSession("Chat section fixture", project.id, ConversationConfig(ConversationKind.CHAT))
        val stores = mutableListOf<ViewModelStore>()
        val section = mutableStateOf(ConversationKind.CODE)
        val scale = mutableStateOf(1f)
        val desktop = mutableStateOf(false)
        val theme = mutableStateOf(ThemeMode.LIGHT)
        val current = AtomicReference<ChatViewModel>()
        try {
            val codeVm = AtomicReference<ChatViewModel>(); val chatVm = AtomicReference<ChatViewModel>()
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val a = ChatViewModel(c, code); val b = ChatViewModel(c, chat)
                    codeVm.set(a); chatVm.set(b)
                    stores += ViewModelStore().apply { put("code", a); put("chat", b) }
                    activity.setContent { HarnessTheme(themeMode = theme.value, dynamicColor = false) {
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value),
                            com.androidharness.app.ui.LocalDesktopLayout provides desktop.value) {
                            Box(Modifier.width(if (desktop.value) 1000.dp else 320.dp).fillMaxHeight()) {
                                val vm = if (section.value == ConversationKind.CODE) a else b
                                current.set(vm)
                                key(section.value) { ChatScreen(vm, {}, { _, _ -> }, {}, {}, onOpenSubagent = {},
                                    onSelectSection = { section.value = it }) }
                            }
                        }
                    } }
                }
                await("Code · ${project.name}"); await("Workspace files")
                assertNull("No standalone Chat switch in the header", find(instrumentation.uiAutomation.rootInActiveWindow, "Chat"))
                screenshot("code-phone")
                scenario.onActivity { section.value = ConversationKind.CHAT }
                await("Chat")
                assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "Workspace files"))
                assertNull("No tools toggle in Chat", find(instrumentation.uiAutomation.rootInActiveWindow, "Chat tools off"))
                screenshot("chat-phone")
                scenario.onActivity { scale.value = 1.5f; theme.value = ThemeMode.DARK }
                await("Chat")
                screenshot("chat-large-dark")
                scenario.onActivity { section.value = ConversationKind.CODE }
                await("Workspace files"); await("Code · ${project.name}")
                screenshot("code-large-dark")
                scenario.onActivity { scale.value = 1f; desktop.value = true; section.value = ConversationKind.CHAT }
                await("Chat"); await("Open navigation")
                screenshot("chat-desktop")
                scenario.onActivity { section.value = ConversationKind.CODE }
                await("Conversation actions"); await("Code · ${project.name}")
                screenshot("code-desktop")
            }
        } finally {
            instrumentation.runOnMainSync { stores.forEach { it.clear() } }
            c.sessions.session(code)?.let { c.sessions.deleteSession(it) }
            c.sessions.session(chat)?.let { c.sessions.deleteSession(it) }
            c.settings.setLastActiveSessionId(saved.lastActiveSessionId)
        }
    }

    @Test fun appNavigationStartsInCodeAndRetainsDraftWhenSwitchingSections(): Unit = runBlocking {
        val saved = c.settings.settings.first()
        val before = c.sessions.sessions.first().map { it.id }.toSet()
        val project = c.workspace.currentProjectOnce()
        val chat = c.sessions.createSession("Navigation Chat fixture", project.id, ConversationConfig(ConversationKind.CHAT))
        c.settings.setStartScreen(com.androidharness.app.data.StartScreen.CODE)
        c.settings.setOnboardingDone(true)
        c.settings.setResumeLastChat(true)
        c.settings.setChatStart(com.androidharness.app.data.ChatStartMode.NEW_CHAT)
        c.settings.setWorkspaceLayout(WorkspaceLayoutMode.PHONE)
        c.settings.setLastActiveSessionId(chat)
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                val scale = mutableStateOf(1f)
                scenario.onActivity { activity -> activity.setContent { HarnessTheme {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value)) { AppNav(c) }
                } } }
                // Even a previously active normal Chat does not change the default section.
                await("Workspace files")
                await("Message input")
                val input = nodes().last { it.isEditable && it.isVisibleToUser }
                assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Keep my unsent Code draft")
                }))
                repeat(3) {
                    tap("Open navigation"); await("New Code chat")
                    tap("Chat"); awaitSelected("Chat"); await("New chat")
                    assertNull("Code histories are filtered from Chat", find(instrumentation.uiAutomation.rootInActiveWindow, "Recent Code chats"))
                    tap("Collapse sidebar"); await("Auto")
                    tap("Open navigation"); tap("Code"); await("New Code chat")
                    tap("Collapse sidebar"); await("Workspace files")
                }
                val deadline = SystemClock.uptimeMillis() + 10_000
                var restored = false
                while (SystemClock.uptimeMillis() < deadline) {
                    val value = nodes().lastOrNull { it.isEditable && it.isVisibleToUser }?.text?.toString().orEmpty()
                    if (value.contains("Keep my unsent Code draft")) { restored = true; break }
                    SystemClock.sleep(100)
                }
                assertTrue("Section switching preserves the Code draft", restored)
                screenshot("app-navigation-code")
                tap("Open navigation"); tap("Chat"); awaitSelected("Chat"); await("New chat")
                assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "Chat tools off"))
                screenshot("sidebar-chat")
                tap("Collapse sidebar"); await("Auto")
                assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "Continue in Code"))
                scenario.onActivity { scale.value = 1.5f }
                await("Message input")
                screenshot("chat-large")
                tap("Open navigation"); tap("Code"); await("New Code chat")
                screenshot("sidebar-code-large")
                tap("Collapse sidebar"); await("Workspace files")
            }
        } finally {
            c.sessions.sessions.first().filter { it.id !in before }.forEach { c.sessions.deleteSession(it) }
            c.settings.setOnboardingDone(saved.onboardingDone)
            c.settings.setResumeLastChat(saved.resumeLastChat)
            c.settings.setChatStart(saved.chatStart)
            c.settings.setWorkspaceLayout(saved.workspaceLayout)
            c.settings.setLastActiveSessionId(saved.lastActiveSessionId)
            c.settings.setStartScreen(saved.startScreen)
            c.settings.setLastConversationKind(saved.lastConversationKind)
        }
    }

    @Test fun desktopSidebarKeepsChatSelectedOnOtherPagesAndCanReopenChat(): Unit = runBlocking {
        val configuration = instrumentation.targetContext.resources.configuration
        org.junit.Assume.assumeTrue("Run this case in a desktop-sized emulator window",
            configuration.screenWidthDp >= 720 * configuration.fontScale.coerceAtLeast(1f))
        val saved = c.settings.settings.first()
        val before = c.sessions.sessions.first().map { it.id }.toSet()
        val chat = c.sessions.createSession("Desktop sidebar Chat fixture", c.workspace.currentProjectOnce().id,
            ConversationConfig(ConversationKind.CHAT))
        c.settings.setOnboardingDone(true)
        c.settings.setStartScreen(StartScreen.CODE)
        c.settings.setResumeLastChat(true)
        c.settings.setChatStart(com.androidharness.app.data.ChatStartMode.NEW_CHAT)
        c.settings.setWorkspaceLayout(WorkspaceLayoutMode.DESKTOP)
        c.settings.setLastActiveSessionId(chat)
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { HarnessTheme { AppNav(c) } } }
                await("New Code chat")
                tap("Chat"); awaitSelected("Chat"); await("New chat")
                tap("Providers")
                val deadline = SystemClock.uptimeMillis() + 10_000
                var selected = false
                while (SystemClock.uptimeMillis() < deadline) {
                    var node = find(instrumentation.uiAutomation.rootInActiveWindow, "Chat")
                    while (node != null) {
                        if (node.isSelected) { selected = true; break }
                        node = node.parent
                    }
                    if (selected) break
                    SystemClock.sleep(100)
                }
                screenshot("desktop-sidebar-providers")
                assertTrue("Chat stays selected when opening Providers", selected)
                tap("Open Desktop sidebar Chat fixture"); await("Message input"); await("Chat")
                screenshot("desktop-sidebar-chat")
                tap("Collapse sidebar"); await("Open navigation")
                tap("Open navigation"); await("New chat")
            }
        } finally {
            c.sessions.sessions.first().filter { it.id !in before }.forEach { c.sessions.deleteSession(it) }
            c.settings.setOnboardingDone(saved.onboardingDone)
            c.settings.setResumeLastChat(saved.resumeLastChat)
            c.settings.setChatStart(saved.chatStart)
            c.settings.setWorkspaceLayout(saved.workspaceLayout)
            c.settings.setLastActiveSessionId(saved.lastActiveSessionId)
            c.settings.setStartScreen(saved.startScreen)
            c.settings.setLastConversationKind(saved.lastConversationKind)
        }
    }

    @Test fun version19MigrationKeepsOldSessionsInCode() {
        val name = "conversation-migration-${System.nanoTime()}.db"
        val callback = object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(19) {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE sessions (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL)")
                db.execSQL("INSERT INTO sessions VALUES ('legacy', 'Original title')")
            }
            override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(c.appContext).name(name).callback(callback).build())
        try {
            val db = helper.writableDatabase
            com.androidharness.app.data.db.AppDatabase.MIGRATION_19_20.migrate(db)
            db.query("SELECT title, conversationJson FROM sessions WHERE id = 'legacy'").use {
                assertTrue(it.moveToFirst()); assertEquals("Original title", it.getString(0))
                assertTrue(it.isNull(1)); assertEquals(ConversationKind.CODE, ConversationConfig.decode(null).kind)
            }
        } finally { helper.close(); c.appContext.deleteDatabase(name) }
    }

    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            add(node)
            for (index in 0 until node.childCount) visit(node.getChild(index))
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
        for (i in 0 until node.childCount) find(node.getChild(i), text)?.let { return it }
        return null
    }
    private fun await(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 15_000
        do { find(instrumentation.uiAutomation.rootInActiveWindow, text)?.let { return it }; SystemClock.sleep(100) }
        while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Expected visible: $text")
    }
    private fun awaitSelected(text: String) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            var node = find(instrumentation.uiAutomation.rootInActiveWindow, text)
            while (node != null) {
                if (node.isSelected) return
                node = node.parent
            }
            SystemClock.sleep(100)
        }
        throw AssertionError("Expected selected: $text")
    }
    private fun tap(text: String) {
        await(text)
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            var node = find(instrumentation.uiAutomation.rootInActiveWindow, text)
            while (node != null && !node.isClickable && node.parent != null) node = node.parent
            if (node?.isEnabled == true && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Click $text")
    }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun screenshot(name: String) {
        val dir = File(c.appContext.getExternalFilesDir(null), "conversation-sections").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()!!.let { bitmap ->
            File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
