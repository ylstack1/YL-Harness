package com.androidharness.app.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.agent.*
import com.androidharness.app.core.*
import com.androidharness.app.llm.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run prepare, force-stop the app, then run resume to exercise actual process recreation. */
@RunWith(AndroidJUnit4::class)
class RuntimeRecoveryDeviceTest {
    private val c get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HarnessApp).container
    private val fixture get() = File(c.appContext.cacheDir, "runtime-recovery-fixture.json")
    private val sources = linkedMapOf("site/index.html" to "<html>saved</html>\n",
        "site/base.css" to "body{color:red}\n", "site/site.js" to "console.log('saved');\n")
    private fun runs(provider: LlmProvider, registry: ToolRegistry = c.registry): RunManager {
        val engine = AgentEngine({ provider }, registry, c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills)
        return RunManager(c.appContext, engine, c.sessions, c.checkpoints, c.workspace, c.linuxEnv, c.settings, TodoStore(), c.providers)
    }
    private val config get() = ProviderConfig("runtime-fixture", "Runtime fixture", ProviderType.OPENAI_COMPAT, "https://example.invalid", "fixture-model")
    private suspend fun start(runs: RunManager, sid: String) = runs.startRun(sid, "Build fixture", emptyList(), config,
        "fixture", PermissionMode.FULL_AUTO, AgentMode.ACT, 1024, 32000, ThinkingLevel.OFF, 10, notifyOnFinish = false)

    @Test fun chatResumeKeepsContinuationInModelContextWithoutShowingItInSearch() = runBlocking {
        val sid = c.sessions.createSession("Continuation fixture", conversation = com.androidharness.app.data.ConversationConfig(com.androidharness.app.data.ConversationKind.CHAT))
        var calls = 0
        var resumedHistory = emptyList<ChatMessage>()
        val provider = object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
                messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                calls++
                if (calls == 1) {
                    emit(StreamEvent.TextDelta("Partial answer"))
                    emit(StreamEvent.Failure("Connection interrupted", code = 503))
                } else {
                    resumedHistory = messages
                    emit(StreamEvent.TextDelta("Continued answer")); emit(StreamEvent.Done("stop"))
                }
            }
        }
        val runs = runs(provider)
        try {
            start(runs, sid)
            withTimeout(30_000) { while (runs.isRunning(sid)) delay(50) }
            assertTrue(runs.controls.flow(sid).value.resumable)
            runs.resumeTask(sid, "fixture", notifyOnFinish = false)
            withTimeout(30_000) { while (runs.isRunning(sid)) delay(50) }
            assertTrue(resumedHistory.any { it.text == RunRecovery.CONTINUE_PROMPT })
            assertTrue(c.sessions.messages(sid).any { RunRecovery.isInternalChatContinuation(it.id) })
            assertTrue(c.sessions.searchMessagesInSession(sid, "authoritative").isEmpty())
        } finally {
            runs.stopAndJoin(sid)
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
        }
    }

    @Test fun prepareInterruptedFiles() = runBlocking {
        check(!fixture.exists()) { "Finish or remove the previous isolated recovery fixture first" }
        val prior = c.workspace.currentProjectOnce()
        val permission = c.settings.settings.first().permissionMode
        val root = File(c.appContext.getExternalFilesDir(null) ?: c.appContext.filesDir,
            "recovery-${System.nanoTime()}").apply { mkdirs() }
        val project = c.workspace.addShellProject(root.path)
        val sid = c.sessions.createSession("Interrupted file fixture", project.id)
        fixture.writeText(buildJsonObject {
            put("sid", sid); put("project", project.id); put("root", root.path)
            put("prior", prior.id); put("permission", permission.name)
        }.toString())
        c.settings.setPermissionMode(PermissionMode.FULL_AUTO)
        val paused = CompletableDeferred<Unit>()
        var calls = 0
        val provider = object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
                messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                if (++calls == 1) {
                    sources.entries.forEachIndexed { i, entry ->
                        emit(StreamEvent.ToolCallReady(ToolCallData("create-$i", "write_file", buildJsonObject {
                            put("path", entry.key); put("content", entry.value)
                        }.toString())))
                    }
                    emit(StreamEvent.Done("tool_calls"))
                } else { paused.complete(Unit); awaitCancellation() }
            }
        }
        val runs = runs(provider)
        start(runs, sid)
        withTimeout(30_000) { paused.await() }
        sources.forEach { (path, text) -> assertEquals(text, File(root, path).readText()) }
        assertEquals("running", runs.controls.flow(sid).value.status)
        assertEquals(1, c.checkpoints.turnsWithCheckpoints(sid).size)
        assertTrue(sources.keys.all { c.fileHistory.versions(sid, c.workspace.forSession(sid), it).isNotEmpty() })
    }

    @Test fun resumeAfterProcessDeathKeepsFilesAndUndoHasDurableRecovery() = runBlocking {
        val saved = Json.parseToJsonElement(fixture.readText()).jsonObject
        fun value(key: String) = saved.getValue(key).jsonPrimitive.content
        val sid = value("sid")
        val root = File(value("root"))
        var providerCalls = 0
        val provider = object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
                messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                providerCalls++
                emit(StreamEvent.TextDelta("Kept completed work")); emit(StreamEvent.Done("stop"))
            }
        }
        val runs = runs(provider)
        val fs = c.workspace.forSession(sid)
        try {
            assertEquals("interrupted", runs.controls.flow(sid).value.status)
            val turn = c.checkpoints.turnsWithCheckpoints(sid).single()
            val originalIds = c.sessions.messages(sid).map { it.id }
            runs.resumeTask(sid, "fixture", notifyOnFinish = false)
            withTimeout(30_000) { while (runs.isRunning(sid)) delay(50) }
            assertEquals(1, providerCalls)
            assertTrue(c.sessions.messages(sid).map { it.id }.containsAll(originalIds))
            assertEquals(3, c.sessions.messages(sid).count { it.role == Role.TOOL && it.toolName == "write_file" })
            sources.forEach { (path, text) -> assertEquals(text, File(root, path).readText()) }
            assertEquals(setOf(turn), c.checkpoints.turnsWithCheckpoints(sid))

            // Reproduce an externally missing file; resume must report it and
            // the saved original must be recoverable without replaying a write.
            File(root, "site/index.html").delete()
            runs.controls.update(sid) { it.copy(status = "interrupted") }
            runs.resumeTask(sid, "fixture", notifyOnFinish = false)
            withTimeout(30_000) { while (runs.isRunning(sid)) delay(50) }
            assertTrue(c.sessions.messages(sid).any { it.role == Role.SYSTEM && it.text.contains("Saved workspace files are missing") })
            val ctx = ToolContext(fs, sessionId = sid)
            suspend fun recover(path: String) {
                val version = c.fileHistory.versions(sid, fs, path).first { !it.deleted }
                val result = FileRestoreTool(c.fileHistory).execute(buildJsonObject {
                    put("path", path); put("version", version.id)
                }, ctx)
                assertTrue(result.output, result.ok)
            }
            recover("site/index.html")
            assertEquals(0, runs.rewindFromTurn(sid, turn).filesFailed)
            assertTrue(sources.keys.all { !File(root, it).exists() })
            for ((path, text) in sources) { recover(path); assertEquals(text, File(root, path).readText()) }
        } finally {
            runs.stopAllAndJoin()
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            c.workspace.projects.first().firstOrNull { it.id == value("project") }?.let { c.workspace.deleteProject(it) }
            c.workspace.setActiveProject(value("prior"))
            c.settings.setPermissionMode(PermissionMode.valueOf(value("permission")))
            fixture.delete(); root.deleteRecursively()
        }
    }

    @Test fun repairedFilesAllowTheSameBuildCommandAgain() = runBlocking {
        val prior = c.workspace.currentProjectOnce()
        val permission = c.settings.settings.first().permissionMode
        val root = File(c.appContext.cacheDir, "retry-${System.nanoTime()}").apply { mkdirs() }
        val project = c.workspace.addShellProject(root.path)
        val sid = c.sessions.createSession("Retry fixture", project.id)
        var executions = 0
        val shell = object : Tool {
            override val name = "shell"
            override val description = "Fixture compiler"
            override val parametersSchema = Schema.obj(mapOf("command" to Schema.string("Command")))
            override val isReadOnly = false
            override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
                executions++
                return if (File(root, "base.css").exists()) ToolResult(true, "exit code: 0")
                    else ToolResult(false, "exit code: 1\nNo such file or directory: base.css")
            }
        }
        var round = 0
        val provider = object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
                messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                val call = when (++round) {
                    1, 3 -> ToolCallData("build-$round", "shell", """{"command":"fixture-build"}""")
                    2 -> ToolCallData("repair", "write_file", """{"path":"base.css","content":"body{}"}""")
                    else -> null
                }
                if (call == null) emit(StreamEvent.TextDelta("Built")) else emit(StreamEvent.ToolCallReady(call))
                emit(StreamEvent.Done(if (call == null) "stop" else "tool_calls"))
            }
        }
        val runs = runs(provider, ToolRegistry(listOf(shell, WriteFileTool())))
        try {
            c.settings.setPermissionMode(PermissionMode.FULL_AUTO)
            start(runs, sid)
            withTimeout(30_000) { while (runs.isRunning(sid)) delay(50) }
            assertEquals(2, executions)
            assertFalse(c.sessions.messages(sid).any { it.text.contains("Blocked unchanged retry") })
            assertTrue(runs.controls.flow(sid).value.completed)
        } finally {
            runs.stopAllAndJoin()
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            c.workspace.deleteProject(project); c.workspace.setActiveProject(prior.id)
            c.settings.setPermissionMode(permission); root.deleteRecursively()
        }
    }
}
