package com.androidharness.app.llm

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.agent.*
import com.androidharness.app.core.*
import com.androidharness.app.core.response.ChatRichResponse
import com.androidharness.app.core.response.RichResponse
import com.androidharness.app.data.*
import com.androidharness.app.data.db.*
import com.androidharness.app.tools.*
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Explicitly opt in with -e liveKilo true; uses only catalog models marked free and fixture data. */
@RunWith(AndroidJUnit4::class)
class LiveKiloWorkflowDeviceTest {
    private val c get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HarnessApp).container
    private suspend fun connection(): ProviderConfig {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveKilo") == "true")
        val result = ModelCatalog.listModels(HarnessProvider.config, HarnessProvider.KEYLESS)
        assertTrue("Live Kilo catalog unavailable: $result", result is ModelCatalog.Result.Models)
        val models = (result as ModelCatalog.Result.Models).models
        assertTrue(models.any { it.id == HarnessProvider.DEFAULT_MODEL })
        HarnessProvider.restoreModels(models)
        return HarnessProvider.config.copy(model = HarnessProvider.DEFAULT_MODEL)
    }
    private fun provider(prompts: MutableList<String>? = null): (ProviderConfig) -> LlmProvider = { config ->
        val actual = ProviderFactory.create(config)
        object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String, messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                prompts?.add(systemPrompt)
                actual.streamChat(config, apiKey, systemPrompt, messages, tools, options).collect { emit(it) }
            }
        }
    }
    private suspend fun runChat(engine: AgentEngine, config: ProviderConfig, session: String, root: File,
        prompt: String, conversation: ConversationConfig, global: String = "",
        history: List<ChatMessage> = emptyList()): List<AgentEvent> = withTimeout(180_000) {
        engine.run(session, UUID.randomUUID().toString(), config, HarnessProvider.KEYLESS,
            history + ChatMessage(Role.USER, prompt), { PermissionMode.FULL_AUTO }, mutableSetOf(), FileFs(root),
            RequestOptions(maxOutputTokens = 4096), 100_000, AgentMode.ACT, maxIterations = 4,
            conversation = conversation, chatInstructions = global, searchDepth = SearchDepth.OFF,
            chatImageFrequency = ImageFrequency.NONE).toList().also { events ->
            assertTrue(events.filterIsInstance<AgentEvent.Error>().joinToString(), events.none { it is AgentEvent.Error })
        }
    }
    private fun List<AgentEvent>.answer() = filterIsInstance<AgentEvent.Text>().joinToString("") { it.delta }.trim()
    private fun evidence(name: String, text: String) {
        val directory = File(c.appContext.getExternalFilesDir(null), "regression-live-kilo").apply { mkdirs() }
        File(directory, "$name.json").writeText(org.json.JSONObject().put("scenario", name).put("answer", text).toString(2))
    }
    @Test fun liveRichAnswersUseTablesAndChartsOnlyWhenTheyFitTheRequest() = runBlocking<Unit> {
        val config = connection()
        val root = File(c.appContext.cacheDir, "live-kilo-presentation-${UUID.randomUUID()}").apply { mkdirs() }
        val engine = AgentEngine(provider(), ToolRegistry(emptyList()), c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills)
        val scenarios = listOf(
            Triple("explanation", "Explain why breaking a big task into small steps makes it easier to start.", "non-tabular"),
            Triple("qualitative-comparison", "Compare a paper notebook with a notes app for keeping a daily journal. Help me choose.", "non-tabular"),
            Triple("routine-after-table", "Help me make a simple, calmer morning routine.", "non-tabular"),
            Triple("rich-choices", "Help me choose between reading, drawing, and taking a walk tonight. Give me interactive choices in your reply.", "choices"),
            Triple("requested-table", "Show a table of these supplied book counts: Jan 10, Feb 15, Mar 12.", "table"),
            Triple("requested-chart", "Show a native interactive line chart of these supplied book counts: Jan 10, Feb 15, Mar 12.", "chart"),
        )
        val precedingTable = listOf(
            ChatMessage(Role.USER, "Show these measured times in a table: A 12 ms, B 18 ms."),
            ChatMessage(Role.ASSISTANT, "| Run | Latency (ms) |\n| --- | --- |\n| A | 12 |\n| B | 18 |"),
        )
        fun descendants(node: RichResponse.Node): List<RichResponse.Node> = listOf(node) + when (node) {
            is RichResponse.Node.Card -> node.children.flatMap(::descendants)
            is RichResponse.Node.Layout -> node.children.flatMap(::descendants)
            is RichResponse.Node.Tabs -> node.tabs.flatMap { it.children }.flatMap(::descendants)
            else -> emptyList()
        }
        val markdownTable = Regex("(?m)^\\s*\\|?\\s*:?-{3,}:?\\s*\\|")
        val failures = mutableListOf<String>()
        try {
            for ((name, query, expected) in scenarios) {
                val answer = runChat(engine, config, "presentation-${UUID.randomUUID()}", root, query,
                    ConversationConfig(ConversationKind.CHAT, personalizationEnabled = false),
                    history = if (name == "routine-after-table") precedingTable else emptyList()).answer()
                evidence("presentation-$name", answer)
                val normalized = ChatRichResponse.normalize(answer)
                val nodes = RichResponse.parse(normalized).filterIsInstance<RichResponse.Part.Content>().flatMap { descendants(it.node) }
                val table = nodes.any { it is RichResponse.Node.Table || it is RichResponse.Node.Preview && it.html.contains("<table", true) } ||
                    markdownTable.containsMatchIn(RichResponse.plainText(normalized))
                val charts = nodes.filterIsInstance<RichResponse.Node.Chart>()
                val matches = when (expected) {
                    "non-tabular" -> !table && charts.isEmpty()
                    "choices" -> !table && charts.isEmpty() && nodes.any { it is RichResponse.Node.Choices }
                    "table" -> table && listOf("Jan", "Feb", "Mar", "10", "15", "12").all(answer::contains)
                    "chart" -> charts.any { chart ->
                        chart.kind == "line" && (chart.series.flatMap { it.points } + chart.points).map { it.value }.containsAll(listOf(10.0, 15.0, 12.0))
                    }
                    else -> false
                }
                if (answer.isBlank() || !matches) failures += "$name expected $expected: $answer"
            }
            assertTrue(failures.joinToString("\n\n"), failures.isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun liveChatHonorsGlobalOverridesAndPersonalizationWithoutUnnecessaryTables() = runBlocking<Unit> {
        val config = connection()
        val settings = c.settings.settings.first()
        val db = Room.inMemoryDatabaseBuilder(c.appContext, AppDatabase::class.java).build()
        val root = File(c.appContext.cacheDir, "live-kilo-chat-${UUID.randomUUID()}").apply { mkdirs() }
        val id = "live-${UUID.randomUUID()}"
        try {
            c.settings.setMemoryEnabled(true); c.settings.setSectionMemoryEnabled(MemorySection.CHAT, true)
            db.dao().insertSession(SessionEntity(id, "Live fixture", 1, 1, conversationJson = ConversationConfig.encode(ConversationConfig(ConversationKind.CHAT))))
            val memory = MemoryRepository(db, c.settings)
            memory.remember("The regression identifier is MAPLE42.", MemoryCategory.FACT, MemoryScope.GLOBAL, id)
            val prompts = mutableListOf<String>()
            val engine = AgentEngine(provider(prompts), ToolRegistry(listOf(RememberTool(memory), RecallTool(memory))),
                c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills, universalMemory = memory)
            val query = "What is my regression identifier? Answer in two short paragraphs, without a table. Do not call tools."
            val global = "Begin every final answer with GLOBAL_PASS."
            val first = runChat(engine, config, id, root, query, ConversationConfig(ConversationKind.CHAT), global).answer()
            assertTrue(first, first.contains("GLOBAL_PASS") && first.contains("MAPLE42")); assertTrue(first, first.contains("\n\n"))
            assertFalse(first, first.lineSequence().any { it.trim().startsWith('|') })
            val local = "Begin every final answer with LOCAL_PASS."
            val second = runChat(engine, config, id, root, query, ConversationConfig(ConversationKind.CHAT, instructions = local, useGlobalInstructions = false), global).answer()
            assertTrue(second, second.contains("LOCAL_PASS") && second.contains("MAPLE42") && !second.contains("GLOBAL_PASS"))
            val third = runChat(engine, config, id, root, "Say PLAIN_PASS and one brief sentence. No other identifier or prefix.",
                ConversationConfig(ConversationKind.CHAT, instructions = local, personalizationEnabled = false), global).answer()
            assertTrue(third, third.contains("PLAIN_PASS")); assertFalse(third, third.contains("MAPLE42") || third.contains("GLOBAL_PASS") || third.contains("LOCAL_PASS"))
            assertFalse(prompts.last().contains("The regression identifier is MAPLE42."))
            evidence("instructions-and-memory", listOf(first, second, third).joinToString("\n\n---\n\n"))
        } finally { db.close(); root.deleteRecursively(); c.settings.restorePortable(settings) }
    }
    @Test fun liveModelEditsAnExistingMemoryAndSeesTheUpdatedFactOnItsNextRequest() = runBlocking<Unit> {
        val config = connection()
        val settings = c.settings.settings.first()
        val db = Room.inMemoryDatabaseBuilder(c.appContext, AppDatabase::class.java).build()
        val root = File(c.appContext.cacheDir, "live-kilo-memory-${UUID.randomUUID()}").apply { mkdirs() }
        val id = "live-${UUID.randomUUID()}"
        try {
            c.settings.setMemoryEnabled(true); c.settings.setSectionMemoryEnabled(MemorySection.CHAT, true)
            db.dao().insertSession(SessionEntity(id, "Live fixture", 1, 1, conversationJson = ConversationConfig.encode(ConversationConfig(ConversationKind.CHAT))))
            val memory = MemoryRepository(db, c.settings)
            val original = requireNotNull(memory.remember("Regression display name is Mira.", MemoryCategory.PROFILE, MemoryScope.GLOBAL, id))
            val prompts = mutableListOf<String>()
            val engine = AgentEngine(provider(prompts), ToolRegistry(listOf(RememberTool(memory), RecallTool(memory))),
                c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills, universalMemory = memory)
            val events = runChat(engine, config, id, root,
                "Update my saved regression display name. Call remember to edit memory_id ${original.id}, previous_text exactly \"${original.text}\", new text exactly \"Regression display name is Mina.\". Then confirm in one sentence.",
                ConversationConfig(ConversationKind.CHAT))
            assertTrue(events.any { it is AgentEvent.ToolStarted && it.call.name == "remember" })
            assertEquals("Regression display name is Mina.", db.dao().memory(original.id)!!.text)
            assertEquals(1, db.dao().allMemories().size)
            assertTrue(prompts.size >= 2); assertTrue(prompts.last().contains("Regression display name is Mina."))
            evidence("edited-memory", events.answer())
        } finally { db.close(); root.deleteRecursively(); c.settings.restorePortable(settings) }
    }
    @Test fun liveCodeWritesARealFileAndReturnsAnEditorReference() = runBlocking<Unit> {
        val config = connection()
        val root = File(c.appContext.cacheDir, "live-kilo-code-${UUID.randomUUID()}").apply { mkdirs() }
        val project = c.workspace.addShellProject(root.path, activate = false)
        val sid = c.sessions.createSession("Live Kilo code fixture", project.id)
        try {
            val engine = AgentEngine(provider(), ToolRegistry(listOf(WriteFileTool(), ReadFileTool())), c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills)
            val events = runChat(engine, config, sid, root,
                "Create src/Greeting.kt using write_file with exactly: package fixture\\nfun greeting() = \"Hello\"\\n (the \\n sequences mean line breaks). Then reply only with the Markdown link [Greeting.kt](src/Greeting.kt:2).",
                ConversationConfig(ConversationKind.CODE))
            assertEquals("package fixture\nfun greeting() = \"Hello\"\n", File(root, "src/Greeting.kt").readText())
            assertTrue(events.any { it is AgentEvent.ToolStarted && it.call.name == "write_file" })
            val answer = events.answer()
            assertTrue(answer, answer.contains("src/Greeting.kt") && answer.contains(":2"))
            evidence("file-and-editor-link", answer)
        } finally { c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }; c.workspace.deleteProject(project); root.deleteRecursively() }
    }
    @Test fun liveStreamingCanBeCancelledAndTheNextRequestStillCompletes() = runBlocking<Unit> {
        val config = connection()
        val actual = ProviderFactory.create(config)
        val firstChunk = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) {
            actual.streamChat(config, HarnessProvider.KEYLESS, "Write the requested content without tools.",
                listOf(ChatMessage(Role.USER, "Write a long numbered list of 300 Android UI testing ideas, one sentence each.")), emptyList(), RequestOptions(maxOutputTokens = 4096)).collect { event ->
                if ((event is StreamEvent.TextDelta && event.text.isNotEmpty()) ||
                    (event is StreamEvent.ThinkingDelta && event.text.isNotEmpty())) firstChunk.complete(Unit)
                if (event is StreamEvent.Failure) throw AssertionError(event.message)
            }
            firstChunk.completeExceptionally(AssertionError("Stream ended without model output"))
        }
        try {
            withTimeout(120_000) { firstChunk.await() }
            assertTrue("Cancel while the model is still generating", job.isActive)
            withTimeout(5000) { job.cancelAndJoin() }
            val events = withTimeout(120_000) {
                actual.streamChat(config, HarnessProvider.KEYLESS, "Reply only with the token the user requests.",
                    listOf(ChatMessage(Role.USER, "RECOVERED_AFTER_CANCEL")), emptyList(), RequestOptions(maxOutputTokens = 2048)).toList()
            }
            assertTrue(events.none { it is StreamEvent.Failure })
            fun flatten(event: StreamEvent): List<StreamEvent> = if (event is StreamEvent.Batch) event.events.flatMap(::flatten) else listOf(event)
            val text = events.flatMap(::flatten).filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.text }
            assertTrue(text, text.contains("RECOVERED_AFTER_CANCEL")); evidence("cancel-and-recover", text)
        } finally { job.cancelAndJoin() }
    }
}
