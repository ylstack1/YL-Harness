package com.androidharness.app.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.core.*
import com.androidharness.app.data.AppSettings
import com.androidharness.app.llm.*
import com.androidharness.app.tools.*
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class BackgroundSubagentDeviceTest {
    private val c get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HarnessApp).container

    @Test fun completionWakesAnIdleMainAgentWithoutInventingAnotherUserMessage(): Unit = runBlocking {
        val root = File(c.appContext.cacheDir, "delegate-followup-${System.nanoTime()}").apply { mkdirs() }
        val previous = c.workspace.currentProjectOnce()
        val project = c.workspace.addShellProject(root.path)
        val sid = c.sessions.createSession("Delegate follow-up fixture", project.id)
        val gate = CompletableDeferred<Unit>()
        val rounds = AtomicInteger()
        val config = ProviderConfig("followup-fixture", "Fixture", ProviderType.OPENAI_COMPAT, "https://example.invalid", "main")
        val child = object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
                messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                gate.await()
                emit(StreamEvent.TextDelta("Independent check completed successfully."))
                emit(StreamEvent.Done("stop"))
            }
        }
        val parent = object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
                messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                when (rounds.incrementAndGet()) {
                    1 -> emit(StreamEvent.ToolCallReady(ToolCallData("idle-check", "task",
                        """{"name":"Scout","prompt":"Check independently","background":true}""")))
                    2 -> emit(StreamEvent.TextDelta("The main conversation is free while Scout works."))
                    else -> {
                        assertTrue(messages.any { it.text.contains("Independent check completed successfully.") })
                        emit(StreamEvent.TextDelta("Scout finished; its findings are now available."))
                    }
                }
                emit(StreamEvent.Done("stop"))
            }
        }
        val engine = AgentEngine({ if (it.model == "delegate") child else parent }, ToolRegistry(listOf(TaskTool())),
            c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills,
            cavemanSettings = { AppSettings(backgroundSubagents = true) },
            configuredSubagent = { SubagentConnection(config.copy(model = "delegate"), "") })
        val manager = RunManager(c.appContext, engine, c.sessions, c.checkpoints, c.workspace, c.linuxEnv,
            c.settings, TodoStore(), c.providers)
        try {
            manager.startRun(sid, "Delegate an independent check", emptyList(), config, "", PermissionMode.FULL_AUTO,
                AgentMode.ACT, 4000, 100000, ThinkingLevel.OFF, 5, notifyOnFinish = false)
            withTimeout(15000) { c.sessions.messagesFlow(sid).first { rows -> rows.any { it.text.contains("main conversation is free") } } }
            withTimeout(5000) { manager.live(sid).first { !it.running } }
            assertTrue(manager.subagents.list(sid).single().status.active)
            gate.complete(Unit)
            withTimeout(20000) { c.sessions.messagesFlow(sid).first { rows -> rows.any { it.text == "Scout finished; its findings are now available." } } }
            withTimeout(5000) { manager.live(sid).first { !it.running } }
            val rows = c.sessions.messages(sid)
            assertEquals(1, rows.count { it.role == Role.USER })
            assertEquals(1, rows.count { it.id == "subagent-result-idle-check" })
            assertTrue(manager.subagents.pending(sid).isEmpty())
            assertEquals(3, rounds.get())
        } finally {
            manager.stopAllAndJoin()
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            c.workspace.deleteProject(project); c.workspace.setActiveProject(previous.id); root.deleteRecursively()
        }
    }

    @Test fun mainContinuesBeforeDelegateFinishesAndReceivesItsSavedAnswer(): Unit = runBlocking {
        val root = File(c.appContext.cacheDir, "background-engine-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val config = ProviderConfig("fixture", "Fixture", ProviderType.OPENAI_COMPAT, "https://example.invalid", "main")
        val sid = c.sessions.createSession("Background delegate fixture")
        val gate = CompletableDeferred<Unit>()
        val mainRequests = mutableListOf<List<ChatMessage>>()
        val writes = AtomicInteger()
        val reads = AtomicInteger()
        val childRounds = AtomicInteger()
        val mainRounds = AtomicInteger()
        val call = ToolCallData("delegate-${System.nanoTime()}", "task",
            """{"name":"Build checker","prompt":"Inspect build files","background":true}""")
        val child = object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
                messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                assertFalse(tools.any { it.name == "write_fixture" || it.name.startsWith("subagent_") })
                when (childRounds.incrementAndGet()) {
                    1 -> {
                        gate.await()
                        emit(StreamEvent.ToolCallReady(ToolCallData("read-child", "read_fixture", "{}")))
                        // Even an invented action must be refused by the executor.
                        emit(StreamEvent.ToolCallReady(ToolCallData("write-child", "write_fixture", "{}")))
                    }
                    else -> emit(StreamEvent.TextDelta("Saved finding: the build configuration is present."))
                }
                emit(StreamEvent.Done("stop"))
            }
        }
        val main = object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
                messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                mainRequests += messages.toList()
                assertTrue(tools.any { it.name == "subagent_status" })
                when (mainRounds.incrementAndGet()) {
                    1 -> emit(StreamEvent.ToolCallReady(call))
                    2 -> {
                        assertFalse(gate.isCompleted)
                        assertTrue(messages.any { it.role == Role.TOOL && it.text.contains("task_id=${call.id}") })
                        emit(StreamEvent.TextDelta("Main agent can continue."))
                    }
                    else -> {
                        assertTrue(messages.any { it.text.contains("Saved finding: the build configuration is present.") })
                        emit(StreamEvent.TextDelta("Build checker finished. I have its findings."))
                    }
                }
                emit(StreamEvent.Done("stop"))
            }
        }
        fun fixtureTool(name: String, readOnly: Boolean, count: AtomicInteger) = object : Tool {
            override val name = name
            override val description = "Fixture"
            override val parametersSchema = Schema.obj(emptyMap())
            override val isReadOnly = readOnly
            override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
                count.incrementAndGet(); return ToolResult(true, "Fixture output")
            }
        }
        val registry = ToolRegistry(listOf(TaskTool(), fixtureTool("read_fixture", true, reads), fixtureTool("write_fixture", false, writes)))
        val engine = AgentEngine({ if (it.model == "delegate") child else main }, registry, c.checkpoints, c.images,
            c.linuxEnv, c.shizuku, c.skills, cavemanSettings = { AppSettings(backgroundSubagents = true, subagentFullAccess = true) },
            configuredSubagent = { SubagentConnection(config.copy(model = "delegate"), "") })
        val delegates = SubagentRuns(File(root, "runs.json"), scope, onEvent = { run, event ->
            if (event is AgentEvent.SubagentMessageCommitted) c.sessions.addMessage(sid, event.message, run.turnId)
        })
        val initial = ChatMessage(Role.USER, "Inspect this project")
        try {
            suspend fun run(history: List<ChatMessage>) = engine.run(sid, "fixture-turn", config, "", history,
                { PermissionMode.FULL_AUTO }, mutableSetOf(), FileFs(root), RequestOptions(), 100000,
                AgentMode.ACT, maxIterations = 5, repoMapEnabled = false, subagentRuns = delegates).toList()
            val events = withTimeout(15000) { run(listOf(initial)) }
            assertTrue(events.any { it is AgentEvent.Text && it.delta.contains("Main agent can continue.") })
            assertTrue(delegates.list(sid).single().status.active)
            assertEquals("Build checker", delegates.list(sid).single().name)
            assertFalse(gate.isCompleted)
            gate.complete(Unit)
            assertEquals(SubagentStatus.COMPLETED, delegates.waitFor(sid, call.id, 10000)?.status)
            assertEquals(1, reads.get()); assertEquals(0, writes.get())
            val inner = c.sessions.messages(sid)
            assertTrue(inner.any { it.role == Role.ASSISTANT && it.toolCallId == call.id && it.text.contains("Saved finding") })
            val history = listOf(initial) + events.mapNotNull {
                when (it) { is AgentEvent.AssistantCommitted -> it.message; is AgentEvent.ToolMessageCommitted -> it.message; else -> null }
            }
            withTimeout(15000) { run(history) }
            assertTrue(delegates.pending(sid).isEmpty())
            assertTrue(mainRequests.all { request -> request.none { it.toolCallId == "read-child" || it.toolCallId == "write-child" } })
        } finally {
            delegates.stopAllAndJoin(); scope.cancel()
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            root.deleteRecursively()
        }
    }
}
