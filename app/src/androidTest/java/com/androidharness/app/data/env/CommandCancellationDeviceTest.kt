package com.androidharness.app.data.env

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.agent.*
import com.androidharness.app.core.*
import com.androidharness.app.data.TerminalManager
import com.androidharness.app.llm.*
import com.androidharness.app.tools.*
import com.androidharness.app.workspace.FileFs
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommandCancellationDeviceTest {
    private val c get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HarnessApp).container
    private fun directory() = File(c.appContext.cacheDir, "cancel-${System.nanoTime()}").apply { mkdirs() }
    private val command = "sleep 60 & child=\$!; printf '%s %s' \"\$\$\" \"\$child\" > pids; wait"

    private suspend fun pids(root: File): List<Int> = withTimeout(10_000) {
        val file = File(root, "pids")
        while (!file.exists() || file.readText().trim().split(' ').size != 2) delay(25)
        file.readText().trim().split(' ').map(String::toInt)
    }

    private suspend fun assertStopped(pids: List<Int>) = withTimeout(3_000) {
        while (pids.any { pid ->
            runCatching { android.system.Os.kill(pid, 0) }.isSuccess &&
                runCatching { File("/proc/$pid/stat").readText().substringAfterLast(") ").first() != 'Z' }.getOrDefault(true)
        }) delay(25)
    }

    @Test fun normalShellCancellationKillsTheShellAndChildAndAllowsTheNextCommand() = runBlocking {
        val root = directory()
        val manager = ShizukuManager(c.appContext)
        val router = ShellTierRouter(c.appContext, manager, c.linuxEnv)
        val job = launch(Dispatchers.Default) { router.run(command, root, 120_000, 1024) }
        try {
            val children = pids(root)
            withTimeout(3_000) { job.cancelAndJoin() }
            assertStopped(children)
            val next = router.run("echo ready", root, 3_000, 1024)
            assertEquals(next.rawStderr, 0, next.exitCode)
            assertEquals("ready", next.rawOutput.trim())
        } finally { job.cancelAndJoin(); manager.close(); root.deleteRecursively() }
    }

    @Test fun serviceCancellationIsScopedAndCancelledReservationsNeverExecute() = runBlocking {
        val root = directory()
        val runner = HarnessUserService()
        val job = launch(Dispatchers.Default) {
            runner.execCancellable(arrayOf("/system/bin/sh", "-c", command), null, root.path, 1024, 120_000)
        }
        val independent = async(Dispatchers.IO) {
            runner.execCancellable(arrayOf("/system/bin/sh", "-c", "sleep 1; echo independent"), null, root.path, 1024, 10_000)
        }
        try {
            val children = pids(root)
            withTimeout(3_000) { job.cancelAndJoin() }
            assertStopped(children)
            assertTrue(withTimeout(3_000) { independent.await() }.contains("independent"))
            val id = runner.registerCommand()
            runner.cancelCommand(id)
            assertTrue(runner.execRegistered(id, arrayOf("/system/bin/sh", "-c", "touch should-not-exist"), null, root.path, 1024, 3_000).startsWith("exit=-130"))
            assertFalse(File(root, "should-not-exist").exists())
            assertEquals(0, runner.activeCommandCount())
        } finally { job.cancelAndJoin(); independent.cancelAndJoin(); root.deleteRecursively() }
    }

    @Test fun agentStopFinishesCleanupAndCanStartASteeredTurn() = runBlocking {
        val root = directory()
        val prior = c.workspace.currentProjectOnce()
        val permission = c.settings.settings.first().permissionMode
        val project = c.workspace.addShellProject(root.path)
        val sid = c.sessions.createSession("Stop command fixture", project.id)
        var requests = 0
        val provider = object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
                messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                if (++requests == 1) {
                    emit(StreamEvent.ToolCallReady(ToolCallData("waiting-shell", "shell",
                        kotlinx.serialization.json.buildJsonObject { put("command", kotlinx.serialization.json.JsonPrimitive(command)) }.toString())))
                    emit(StreamEvent.Done("tool_calls"))
                } else {
                    emit(StreamEvent.TextDelta("Steered successfully")); emit(StreamEvent.Done("stop"))
                }
            }
        }
        val router = ShellTierRouter(c.appContext, c.shizuku, c.linuxEnv)
        val engine = AgentEngine({ provider }, ToolRegistry(listOf(ShellTool(c.linuxEnv, router))), c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills)
        val runs = RunManager(c.appContext, engine, c.sessions, c.checkpoints, c.workspace, c.linuxEnv, c.settings, TodoStore(), c.providers)
        val config = ProviderConfig("cancel-fixture", "Fixture", ProviderType.OPENAI_COMPAT, "https://example.invalid", "fixture")
        suspend fun start(text: String) = runs.startRun(sid, text, emptyList(), config, "fixture", PermissionMode.FULL_AUTO,
            AgentMode.ACT, 1024, 32_000, ThinkingLevel.OFF, 10, notifyOnFinish = false)
        try {
            c.settings.setPermissionMode(PermissionMode.FULL_AUTO)
            start("Run command")
            val children = pids(root)
            runs.inject(sid, "Queued message")
            runs.stop(sid)
            withTimeout(3_000) { runs.stopAndJoin(sid) }
            assertStopped(children)
            assertFalse(runs.live(sid).value.running)
            assertTrue(runs.live(sid).value.cancelled)
            assertEquals("idle", runs.controls.flow(sid).value.status)
            assertEquals(1, requests)
            start("Change direction")
            withTimeout(10_000) { runs.live(sid).first { !it.running } }
            assertTrue(c.sessions.messages(sid).any { it.text == "Steered successfully" })
        } finally {
            runs.stopAllAndJoin()
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            c.workspace.deleteProject(project); c.workspace.setActiveProject(prior.id)
            c.settings.setPermissionMode(permission); root.deleteRecursively()
        }
    }

    @Test fun terminalStopKillsItsCommandAndCanRunAgain() = checkTerminalStop(routed = false)

    @Test fun routedTerminalStopKillsItsCommandAndCanRunAgain() = checkTerminalStop(routed = true)

    private fun checkTerminalStop(routed: Boolean) = runBlocking {
        val root = directory()
        val access = DeviceAccessManager(c.appContext, c.shizuku, c.linuxEnv)
        val mode = access.store.preferences.first().mode
        val router = if (routed) ShellTierRouter(c.appContext, c.shizuku, c.linuxEnv, access) else null
        val terminal = TerminalManager(c.appContext, c.linuxEnv, c.shizuku, c.runManager, router)
        try {
            if (routed) access.store.setMode(AccessMode.NORMAL)
            terminal.send(command, FileFs(root))
            val children = pids(root)
            terminal.interrupt()
            withTimeout(3_000) { terminal.state.first { !it.busy && !it.stopping } }
            assertStopped(children)
            terminal.send("echo terminal-ready", FileFs(root))
            withTimeout(5_000) { terminal.state.first { !it.busy && it.lines.any { line -> line == "terminal-ready" } } }
            assertEquals(0, terminal.state.value.lastExitCode)
            assertTrue(terminal.state.value.lines.any { it == "\$ $command" })
        } finally { terminal.stopTerminal(); if (routed) access.store.setMode(mode); root.deleteRecursively() }
    }
}
