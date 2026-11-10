package com.androidharness.app.ui.chat

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.data.*
import com.androidharness.app.ui.chat.components.ChatAgentEditor
import com.androidharness.app.ui.chat.components.ChatAgentsSheet
import com.androidharness.app.ui.theme.HarnessTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ChatAgentsDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun profileSnapshotModelAndPrivateMemorySurvivePresetEditsAndDeletion(): Unit = runBlocking {
        val prefs = c.settings.settings.first()
        val agent = ChatAgentProfile(name = "Agent integration fixture", model = ChatModelChoice("harness", "fixture-model"),
            knowledge = listOf(AgentKnowledge(name = "facts.txt", text = "The fixture bird is blue.")))
        val created = mutableListOf<String>()
        val before = c.memory.memories.first().map { it.id }.toSet()
        try {
            c.settings.setModelSelectionScope(true)
            c.settings.setMemoryEnabled(true); c.settings.setSectionMemoryEnabled(MemorySection.CHAT, true)
            c.settings.saveChatAgent(agent)
            val a = c.startChatAgent(agent).also(created::add)
            val b = c.startChatAgent(agent).also(created::add)
            val foreign = c.startChatAgent(agent.copy(id = "other-${agent.id}")).also(created::add)
            val regular = c.sessions.createSession("Regular fixture", conversation = ConversationConfig(ConversationKind.CHAT)).also(created::add)
            assertEquals(agent.model, c.chatModels.observe(a).first().selection.active)
            assertTrue(c.chatModels.observe(a).first().agentChat)
            assertTrue(runCatching { c.chatModels.setShared(a, true) }.isFailure)
            c.settings.saveChatAgent(agent.copy(name = "Changed", knowledge = emptyList()))
            c.settings.deleteChatAgent(agent.id)
            assertEquals(agent, ConversationConfig.decode(c.sessions.session(a)?.conversationJson).agent)
            val saved = c.memory.rememberResult("A fixture prefers orbital examples", MemoryCategory.PREFERENCE, MemoryScope.GLOBAL, a).memory
            assertEquals(agent.id, saved.agentId)
            assertEquals(saved.id, c.memory.recallById(saved.id, b).id)
            assertTrue(c.memory.promptBlock(b).orEmpty().contains("orbital examples"))
            for (id in listOf(foreign, regular)) {
                assertFalse(c.memory.promptBlock(id).orEmpty().contains("orbital examples"))
                assertTrue(runCatching { c.memory.recallById(saved.id, id) }.isFailure)
            }
            val shared = c.startChatAgent(agent.copy(memory = AgentMemory.SHARED)).also(created::add)
            val sharedFact = c.memory.rememberResult("A fixture enjoys shared astronomy", MemoryCategory.FACT, MemoryScope.GLOBAL, shared).memory
            assertNull(sharedFact.agentId)
            assertTrue(c.memory.promptBlock(regular).orEmpty().contains("shared astronomy"))
            val off = c.startChatAgent(agent.copy(memory = AgentMemory.OFF)).also(created::add)
            assertFalse(c.memory.isEnabled(off))
            assertTrue(runCatching { c.memory.recallById(sharedFact.id, off) }.isFailure)
        } finally {
            (c.memory.memories.first().filter { it.id !in before }).forEach { c.memory.remove(it.id) }
            created.forEach { id -> c.sessions.session(id)?.let { c.sessions.deleteSession(it) } }
            c.settings.restorePortable(prefs)
        }
    }

    @Test fun changingAgentConversationModelNeverChangesSharedDefaults(): Unit = runBlocking {
        val original = c.settings.settings.first()
        val ids = mutableListOf<String>()
        try {
            c.settings.setModelSelectionScope(true)
            val provider = c.providers.providers.first().first()
            val a = c.startChatAgent(ChatAgentProfile(name = "Model A", model = ChatModelChoice(provider.id, "agent-a"))).also(ids::add)
            val b = c.startChatAgent(ChatAgentProfile(name = "Model B", model = ChatModelChoice(provider.id, "agent-b"))).also(ids::add)
            val shared = c.settings.settings.first().activeModel
            c.chatModels.select(a, ChatModelTarget.ACTIVE, provider.id, "agent-a-updated")
            assertEquals("agent-a-updated", c.chatModels.observe(a).first().selection.active?.model)
            assertEquals("agent-b", c.chatModels.observe(b).first().selection.active?.model)
            assertEquals(shared, c.settings.settings.first().activeModel)
            assertTrue(c.settings.settings.first().sameModelAcrossChats)
        } finally {
            ids.forEach { id -> c.sessions.session(id)?.let { c.sessions.deleteSession(it) } }
            c.settings.restorePortable(original)
        }
    }

    @Test fun libraryEditorSavesAndLargeTextKeepsActionsReachable(): Unit = runBlocking {
        val original = c.settings.settings.first()
        val initial = ChatAgentProfile(name = "Study partner", description = "A patient guide for curious questions", avatar = "study",
            starters = listOf("Explain something with an example", "Help me make a study plan"),
            knowledge = listOf(AgentKnowledge(name = "study-notes.md", text = "Plan short study sessions.")))
        try {
            c.settings.saveChatAgent(initial)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                val editing = mutableStateOf(false)
                val large = mutableStateOf(false)
                val dark = mutableStateOf(false)
                scenario.onActivity { host -> host.setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, if (large.value) 1.5f else 1f)) {
                        HarnessTheme(themeMode = if (dark.value) ThemeMode.DARK else ThemeMode.LIGHT) {
                            if (editing.value) ChatAgentEditor(c, initial, onDismiss = { editing.value = false }, onSaved = { editing.value = false })
                            else ChatAgentsSheet(c, onDismiss = {}, onStart = {})
                        }
                    }
                } }
                awaitText("Study partner"); screenshot("library-phone")
                scenario.onActivity { editing.value = true }
                awaitText("Identity"); screenshot("editor-phone")
                assertNull(find { it.isEditable })
                tap("Identity")
                for (icon in listOf("Study", "Writing", "Research", "Ideas", "Travel", "Spark")) {
                    tap("Agent icon: $icon")
                }
                tap("Identity")
                scrollTo("Knowledge"); tap("Knowledge")
                scrollTo("study-notes.md"); screenshot("editor-knowledge")
                scenario.onActivity { large.value = true }
                instrumentation.waitForIdleSync(); SystemClock.sleep(700)
                awaitText("Save agent"); screenshot("editor-large-text")
                scenario.onActivity { dark.value = true }
                instrumentation.waitForIdleSync(); SystemClock.sleep(500)
                screenshot("editor-dark-large-text")
                tap("Save agent")
                awaitText("Chat agents")
                assertEquals(initial.knowledge, c.settings.settings.first().chatAgents.first { it.id == initial.id }.knowledge)
            }
        } finally { c.settings.restorePortable(original) }
    }

    @Test fun newChatUsesAgentHeaderAndConversationStarters(): Unit = runBlocking {
        val agent = ChatAgentProfile(name = "Writing companion", description = "Find the words you need", avatar = "write",
            starters = listOf("Help me shape this idea"))
        val id = c.startChatAgent(agent)
        val store = ViewModelStore()
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { host ->
                    val vm = ChatViewModel(c, id)
                    store.put("agent-chat", vm)
                    host.setContent { HarnessTheme { ChatScreen(vm, {}, { _, _ -> }, {}, {}, onOpenSubagent = {}) } }
                }
                awaitText("Help me shape this idea")
                assertNotNull(find { it.text?.toString() == "Writing companion" })
                screenshot("agent-conversation")
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            c.sessions.session(id)?.let { c.sessions.deleteSession(it) }
        }
    }

    @Test fun engineUsesAgentInstructionsKnowledgeAndSkillsWhileRejectingCodeTools(): Unit = runBlocking {
        val agent = ChatAgentProfile(name = "Runtime tutor", instructions = "Explain orbital motion.", persona = "Patient and precise",
            capabilities = emptySet(), memory = AgentMemory.OFF,
            knowledge = listOf(AgentKnowledge("source", "orbit.md", "The Moon orbits Earth.")),
            skills = listOf(AgentSkill("explain", "Start with a small example.")))
        val conversation = ConversationConfig(ConversationKind.CHAT, agent = agent)
        val id = c.startChatAgent(agent)
        val root = File(c.appContext.cacheDir, "agent-engine-${System.nanoTime()}").apply { mkdirs() }
        var codeExecuted = false
        val forbidden = object : com.androidharness.app.tools.Tool {
            override val name = "shell"
            override val description = "Forbidden code tool"
            override val parametersSchema = com.androidharness.app.tools.Schema.obj(emptyMap())
            override val isReadOnly = false
            override suspend fun execute(args: kotlinx.serialization.json.JsonObject, ctx: com.androidharness.app.tools.ToolContext): com.androidharness.app.tools.ToolResult {
                codeExecuted = true
                return com.androidharness.app.tools.ToolResult(true, "should not run")
            }
        }
        var round = 0
        val provider = object : com.androidharness.app.llm.LlmProvider {
            override fun streamChat(config: com.androidharness.app.llm.ProviderConfig, apiKey: String, systemPrompt: String,
                messages: List<com.androidharness.app.core.ChatMessage>, tools: List<com.androidharness.app.llm.ToolSchema>,
                options: com.androidharness.app.llm.RequestOptions) = kotlinx.coroutines.flow.flow {
                assertTrue(systemPrompt.contains("Patient and precise"))
                assertTrue(systemPrompt.contains("Explain orbital motion"))
                assertTrue(tools.any { it.name == "search_agent_knowledge" })
                assertTrue(tools.any { it.name == "skill_view" })
                assertFalse(tools.any { it.name in setOf("shell", "web_search", "remember", "recall") })
                if (round++ == 0) {
                    emit(com.androidharness.app.llm.StreamEvent.ToolCallReady(com.androidharness.app.core.ToolCallData("knowledge", "search_agent_knowledge", "{\"query\":\"Moon\"}")))
                    emit(com.androidharness.app.llm.StreamEvent.ToolCallReady(com.androidharness.app.core.ToolCallData("skill", "skill_view", "{\"name\":\"explain\"}")))
                    emit(com.androidharness.app.llm.StreamEvent.ToolCallReady(com.androidharness.app.core.ToolCallData("denied", "shell", "{}")))
                    emit(com.androidharness.app.llm.StreamEvent.Done("tool_calls"))
                } else {
                    assertTrue(messages.any { it.role == com.androidharness.app.core.Role.TOOL && it.text.contains("Moon orbits Earth") })
                    assertTrue(messages.any { it.role == com.androidharness.app.core.Role.TOOL && it.text.contains("Start with a small example") })
                    assertTrue(messages.any { it.role == com.androidharness.app.core.Role.TOOL && it.isError && it.toolName == "shell" })
                    emit(com.androidharness.app.llm.StreamEvent.TextDelta("Explained from orbit.md."))
                    emit(com.androidharness.app.llm.StreamEvent.Done("stop"))
                }
            }
        }
        try {
            val engine = com.androidharness.app.agent.AgentEngine({ provider }, com.androidharness.app.tools.ToolRegistry(listOf(forbidden)),
                c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills)
            engine.run(id, "agent-fixture", com.androidharness.app.llm.ProviderConfig("fixture", "Fixture", com.androidharness.app.llm.ProviderType.OPENAI_COMPAT,
                "https://example.com", "fixture"), "", listOf(com.androidharness.app.core.ChatMessage(com.androidharness.app.core.Role.USER, "Explain the Moon")),
                { com.androidharness.app.agent.PermissionMode.FULL_ACCESS }, mutableSetOf(), com.androidharness.app.workspace.FileFs(root),
                com.androidharness.app.llm.RequestOptions(), 32_000, com.androidharness.app.agent.AgentMode.ACT, conversation = conversation).toList()
            assertEquals(2, round)
            assertFalse(codeExecuted)
        } finally {
            root.deleteRecursively()
            c.sessions.session(id)?.let { c.sessions.deleteSession(it) }
        }
    }

    @Test fun migrationKeepsLegacyMemoryAndAddsNullableAgentOwnership() {
        val name = "agent-migration-${System.nanoTime()}.db"
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(c.appContext).name(name)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(23) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE memories (id TEXT PRIMARY KEY, text TEXT, section TEXT)")
                    }
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build())
        try {
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO memories VALUES ('legacy', 'Keep this exact text', 'CHAT')")
            com.androidharness.app.data.db.AppDatabase.MIGRATION_23_24.migrate(db)
            db.query("SELECT text, section, agentId FROM memories WHERE id = 'legacy'").use { row ->
                assertTrue(row.moveToFirst()); assertEquals("Keep this exact text", row.getString(0))
                assertEquals("CHAT", row.getString(1)); assertTrue(row.isNull(2))
            }
        } finally { helper.close(); c.appContext.deleteDatabase(name) }
    }

    @Test fun systemLargeTextKeepsEditorScrollableAndSaveVisible() {
        val resolver = c.appContext.contentResolver
        val oldScale = android.provider.Settings.System.getFloat(resolver, android.provider.Settings.System.FONT_SCALE, 1f)
        try {
            shell("settings put system font_scale 1.5")
            SystemClock.sleep(700)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { host -> host.setContent { HarnessTheme {
                    ChatAgentEditor(c, ChatAgentProfile(name = "Study partner", description = "A patient guide for curious questions"), onDismiss = {}, onSaved = {})
                } } }
                awaitText("Save agent")
                screenshot("editor-system-large-text")
                scrollTo("Knowledge"); tap("Knowledge")
                scrollTo("Add knowledge file")
                assertNotNull(find { it.text?.toString() == "Save agent" })
                screenshot("knowledge-system-large-text")
            }
        } finally { shell("settings put system font_scale $oldScale") }
    }

    @Test fun agentSetupSitsAbovePromptsAndSelectionMovesRightOfPersonalization(): Unit = runBlocking {
        val original = c.settings.settings.first()
        val before = c.sessions.sessions.first().map { it.id }.toSet()
        val store = ViewModelStore()
        val session = c.sessions.createSession("Chooser layout fixture", conversation = ConversationConfig(ConversationKind.CHAT))
        val current = mutableStateOf(session)
        val scale = mutableStateOf(1f)
        try {
            c.settings.restorePortable(original.copy(chatAgents = emptyList(), searchDepth = SearchDepth.AUTO))
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { host ->
                    host.setContent { HarnessTheme {
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value)) {
                            key(current.value) {
                                val vm = remember(current.value) { ChatViewModel(c, current.value).also { store.put(current.value, it) } }
                                ChatScreen(vm, {}, { _, _ -> }, { current.value = session }, {}, onOpenSubagent = {},
                                    onNavigateToSession = { current.value = it })
                            }
                        }
                    } }
                }
                val setup = Rect().also(awaitText("Set a chat agent")::getBoundsInScreen)
                val firstPrompt = Rect().also(awaitText("Explain a tricky topic simply")::getBoundsInScreen)
                assertTrue("Agent setup goes before starter prompts", setup.bottom < firstPrompt.top)
                awaitText("Auto")
                assertNull(find { it.text?.toString() == "Agent" })
                screenshot("agent-setup-above-prompts")
                val agent = ChatAgentProfile(name = "Layout tutor", description = "A patient guide", starters = listOf("Start a lesson"))
                val other = ChatAgentProfile(name = "Writing partner", description = "A helpful editor", starters = listOf("Shape my draft"))
                c.settings.saveChatAgent(agent); c.settings.saveChatAgent(other)
                // Saving a profile alone does not add another header control.
                awaitText("Set a chat agent")
                tap("Set a chat agent"); awaitText("Regular Chat"); tap("Layout tutor")
                awaitText("Start a lesson")
                assertNull(find { it.text?.toString() == "Set a chat agent" })
                val personal = Rect().also(awaitChoice("Personalized")::getBoundsInScreen)
                val selected = Rect().also(awaitChoice("Layout tutor")::getBoundsInScreen)
                screenshot("agent-right-of-personalization")
                assertTrue("Agent must be right of personalization: $selected / $personal", selected.left >= personal.right - 1)
                assertTrue("The dropdowns share a row: $selected / $personal", kotlin.math.abs(selected.centerY() - personal.centerY()) < 12)
                tap("Layout tutor"); awaitText("Regular Chat"); awaitText("Create agent")
                screenshot("agent-matching-dropdown")
                tap("Writing partner"); awaitText("Shape my draft")
                tap("Personalized"); tap("Unpersonalized"); awaitText("Unpersonalized")
                scenario.onActivity { scale.value = 1.5f }
                awaitText("Writing partner")
                screenshot("agent-start-controls-large-text")
                scenario.onActivity { scale.value = 1f }
                tap("Writing partner"); tap("Regular Chat"); awaitText("Set a chat agent")
                tap("More options"); tap("Chat settings"); awaitText("Chat agents"); tap("Chat agents")
                awaitText("Start a conversation")
                screenshot("agents-from-chat-settings")
                instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                awaitText("Set a chat agent")
                tap("Set a chat agent"); tap("Create agent"); tap("Identity")
                val name = requireNotNull(find { it.isEditable })
                assertTrue(name.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Fresh helper")
                }))
                tap("Save agent"); awaitText("Fresh helper")
                val createdDeadline = SystemClock.uptimeMillis() + 10_000
                while (current.value == session && SystemClock.uptimeMillis() < createdDeadline) SystemClock.sleep(100)
                assertNotEquals("Creating an agent opens its conversation", session, current.value)
                assertNull(find { it.text?.toString() == "Set a chat agent" })
                c.sessions.addMessage(current.value, com.androidharness.app.core.ChatMessage(com.androidharness.app.core.Role.USER, "First sent message"), "chooser-turn")
                awaitText("First sent message")
                instrumentation.waitForIdleSync(); SystemClock.sleep(300)
                assertNull(find { it.text?.toString() == "Personalized" })
                assertNull(find { it.text?.toString() == "Set a chat agent" })
                screenshot("agent-controls-hidden-after-send")
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            c.sessions.sessions.first().filter { it.id !in before }.forEach { c.sessions.deleteSession(it) }
            c.settings.restorePortable(original)
        }
    }

    @Test fun agentEditorOnlyShowsTheSelectedModelsExistingThinkingAndKeepsCustomChoice(): Unit = runBlocking {
        val original = c.settings.settings.first()
        val provider = c.providers.add("Agent reasoning fixture", com.androidharness.app.llm.ProviderType.OPENAI_COMPAT,
            "https://example.com/v1", "agent-reasoning-fixture", "")
        val config = provider.copy(model = "agent-reasoning-fixture")
        val key = com.androidharness.app.agent.CustomReasoning.key(config)
        val initial = ChatAgentProfile(name = "Thinking fixture", model = ChatModelChoice(config.id, config.model),
            customThinkingLevels = mapOf(key to "quiet"))
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { host -> host.setContent { HarnessTheme {
                    ChatAgentEditor(c, initial, onDismiss = {}, onSaved = {})
                } } }
                tap("Model and thinking")
                instrumentation.waitForIdleSync(); SystemClock.sleep(300)
                assertNull(find { it.text?.toString() == "Thinking" })
                assertNull(find { it.text?.toString() == "Minimal" })
            }
            c.settings.setReasoningConfig(key, com.androidharness.app.agent.CustomReasoningConfig(listOf("Quiet", "Focused"),
                "{\"reasoning_effort\": level}"))
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                var saved = false
                scenario.onActivity { host -> host.setContent { HarnessTheme {
                    ChatAgentEditor(c, initial, onDismiss = {}, onSaved = { saved = true })
                } } }
                tap("Model and thinking"); awaitText("Thinking"); tap("Quiet")
                tap("Focused"); tap("Save agent")
                val deadline = SystemClock.uptimeMillis() + 10_000
                while (!saved && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
                assertTrue(saved)
                val agent = c.settings.settings.first().chatAgents.first { it.id == initial.id }
                assertEquals("focused", agent.customThinkingLevels[key])
                val session = c.startChatAgent(agent)
                try { assertEquals("focused", c.chatModels.observe(session).first().selection.customThinkingLevels[key]) }
                finally { c.sessions.session(session)?.let { c.sessions.deleteSession(it) } }
            }
        } finally {
            c.providers.delete(provider.id)
            c.settings.restorePortable(original)
        }
    }

    private fun root(): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        return instrumentation.uiAutomation.rootInActiveWindow
    }
    private fun find(node: AccessibilityNodeInfo? = root(), predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && predicate(node)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
        return null
    }
    private fun awaitText(text: String): AccessibilityNodeInfo {
        val until = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < until) {
            find { it.text?.toString() == text || it.contentDescription?.toString() == text }?.let { return it }
            SystemClock.sleep(100)
        }
        screenshot("missing-${text.take(18)}")
        error("Missing UI text: $text")
    }
    private fun tap(text: String) {
        var node = find { it.contentDescription?.toString() == text } ?: awaitText(text)
        val rect = Rect().also(node::getBoundsInScreen)
        while (!node.isClickable && node.parent != null) node = node.parent
        if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) shell("input tap ${rect.centerX()} ${rect.centerY()}")
        SystemClock.sleep(300)
    }
    private fun awaitChoice(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            find { it.contentDescription?.toString() == text }?.let { return it }
            SystemClock.sleep(100)
        }
        error("Missing dropdown: $text")
    }
    private fun scrollTo(text: String) {
        repeat(18) {
            if (find { it.text?.toString() == text } != null) return
            find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(250)
        }
        awaitText(text)
    }
    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync(); SystemClock.sleep(350)
        val dir = File(c.appContext.getExternalFilesDir(null), "chat-agents-evidence").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
