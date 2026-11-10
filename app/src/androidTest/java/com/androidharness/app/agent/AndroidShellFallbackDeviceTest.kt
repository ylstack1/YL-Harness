package com.androidharness.app.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.core.*
import com.androidharness.app.data.*
import com.androidharness.app.data.env.ShellTierRouter
import com.androidharness.app.data.env.LinuxEnvironmentManager
import android.content.ContextWrapper
import com.androidharness.app.llm.*
import com.androidharness.app.tools.*
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidShellFallbackDeviceTest {
    private val c get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HarnessApp).container

    @Test fun actualAndroidCommandsRunBeforeMissingToolOffersSetup() = runBlocking {
        val fixture = File(c.appContext.cacheDir, "android-fallback-${System.nanoTime()}").apply { mkdirs() }
        val root = File(fixture, "workspace").apply { mkdirs() }
        val isolatedContext = object : ContextWrapper(c.appContext) {
            override fun getFilesDir(): File = File(fixture, "files").apply { mkdirs() }
        }
        val linux = LinuxEnvironmentManager(isolatedContext)
        assertFalse(linux.isReady)
        val sid = c.sessions.createSession("Android fallback fixture")
        val prompts = mutableListOf<String>()
        val outcomes = mutableListOf<List<ChatMessage>>()
        val commands = listOf("echo git >> native.txt", "echo before >> native.txt; npm --version")
        val provider = object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
                messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                prompts += systemPrompt
                outcomes += messages.toList()
                val index = prompts.size - 1
                if (index == 0) {
                    emit(StreamEvent.ToolCallReady(ToolCallData("write", "write_file", buildJsonObject {
                        put("path", "native.txt"); put("content", "written\n")
                    }.toString())))
                    emit(StreamEvent.Done("tool_calls"))
                } else if (index <= commands.size) {
                    emit(StreamEvent.ToolCallReady(ToolCallData("shell-$index", "shell", buildJsonObject {
                        put("command", commands[index - 1])
                    }.toString())))
                    emit(StreamEvent.Done("tool_calls"))
                } else {
                    emit(StreamEvent.TextDelta("Finished")); emit(StreamEvent.Done("stop"))
                }
            }
        }
        val shell = ShellTool(linux, ShellTierRouter(c.appContext, c.shizuku, linux))
        val engine = AgentEngine({ provider }, ToolRegistry(listOf(shell, WriteFileTool())), c.checkpoints, c.images, linux, c.shizuku, c.skills)
        val config = ProviderConfig("fixture", "Fixture", ProviderType.OPENAI_COMPAT, "https://example.invalid", "fixture")
        var requests = 0
        try {
            engine.run(sid, "android-fallback-turn", config, "", listOf(ChatMessage(Role.USER, "Edit a file")),
                { PermissionMode.FULL_AUTO }, mutableSetOf(), FileFs(root), RequestOptions(), 100_000, AgentMode.ACT,
                maxIterations = 5, repoMapEnabled = false, conversation = ConversationConfig(), searchDepth = SearchDepth.OFF)
                .collect { event ->
                    when (event) {
                        is AgentEvent.EnvironmentNeeded -> {
                            requests++
                            assertEquals("written\ngit\nbefore\n", File(root, "native.txt").readText())
                            assertEquals(listOf("node"), event.request.hints)
                            event.request.response.complete(false)
                        }
                        is AgentEvent.Error -> fail(event.toString())
                        else -> Unit
                    }
                }
            assertEquals(1, requests)
            assertEquals("written\ngit\nbefore\n", File(root, "native.txt").readText())
            assertTrue(prompts.first().contains("Linux environment is not installed or ready"))
            assertTrue(outcomes.last().any { it.text.contains("Linux is not available") && it.text.contains("exit code: 127") })
        } finally {
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            fixture.deleteRecursively()
        }
    }

    @Test fun setupOnlyMatchesActualMissingSupportedCommands() {
        fun result(code: Int, stderr: String) = "exit code: $code\nstderr (JSON string): ${JsonPrimitive(stderr)}"
        assertEquals("python", missingAndroidToolchain(result(127, "/system/bin/sh: python3: inaccessible or not found")))
        assertEquals("node", missingAndroidToolchain(result(127, "/system/bin/sh: npm: not found")))
        assertNull(missingAndroidToolchain(result(1, "npm: not found")))
        assertNull(missingAndroidToolchain(result(127, "vite: not found")))
        assertNull(missingAndroidToolchain("exit code: 127\nstdout (JSON string): \"npm: not found\""))
        assertNull(missingAndroidToolchain(result(127, "npm: permission denied")))
    }
}
