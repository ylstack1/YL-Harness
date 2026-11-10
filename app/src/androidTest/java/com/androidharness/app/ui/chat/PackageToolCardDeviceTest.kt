package com.androidharness.app.ui.chat

import android.content.ContextWrapper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.agent.*
import com.androidharness.app.core.*
import com.androidharness.app.data.ThemeMode
import com.androidharness.app.data.env.*
import com.androidharness.app.llm.*
import com.androidharness.app.tools.*
import com.androidharness.app.ui.theme.HarnessTheme
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PackageToolCardDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun engineProgressReachesTheChatCardAndSuccessWaitsForTheToolResult(): Unit = runBlocking {
        val saved = c.settings.settings.first()
        val sid = c.sessions.createSession("Package progress fixture")
        val root = File(c.appContext.cacheDir, "package-card-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(c.appContext) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getDataDir() = root
            override fun getExternalFilesDir(type: String?) = File(root, "external").apply { mkdirs() }
        }
        val env = LinuxEnvironmentManager(context, packageIndexLoader = {
            mapOf("ffmpeg" to PkgMeta("ffmpeg", "1", emptyList(), "pool/ffmpeg.deb", 8192, ""))
        })
        val stages = Channel<PackageInstallStatus>()
        val installed = mutableListOf<String>()
        val call = ToolCallData("install-ffmpeg", "pkg_install", """{"packages":["ffmpeg"],"reason":"Convert the video to H.264."}""")
        val tool = PkgInstallTool({ true }, { installed.toList() }, {}, installWithProgress = { names, _, publish ->
            for (stage in stages) publish(stage)
            installed += names
        })
        var round = 0
        val provider = object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
                messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                if (round++ == 0) emit(StreamEvent.ToolCallBatch(listOf(call)))
                else emit(StreamEvent.TextDelta("The packages are ready."))
                emit(StreamEvent.Done(if (round == 1) "tool_calls" else "stop"))
            }
        }
        val engine = AgentEngine({ provider }, ToolRegistry(listOf(tool)), c.checkpoints, c.images, env, c.shizuku, c.skills)
        var vm: ChatViewModel? = null
        var job: Job? = null
        val scale = mutableStateOf(1f)
        try {
            instrumentation.runOnMainSync { vm = ChatViewModel(c, sid) }
            val model = requireNotNull(vm)
            withTimeout(10_000) { model.state.first { !it.isLoadingMessages } }
            instrumentation.runOnMainSync { model.viewModelScope.cancel() }
            @Suppress("UNCHECKED_CAST")
            val state = model.state as MutableStateFlow<ChatUiState>
            val user = ChatMessage(Role.USER, "Convert the video to H.264.", turnId = "package-turn")
            state.value = state.value.copy(busy = true, currentTurnId = "package-turn", messages = listOf(user))
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { HarnessTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value)) {
                        ChatScreen(model, {}, { _, _ -> }, {}, {}, onOpenSubagent = {})
                    }
                } } }
                val events = mutableListOf<AgentEvent>()
                job = launch(Dispatchers.Default) {
                    engine.run(sid, "package-turn", ProviderConfig("fixture", "Fixture", ProviderType.OPENAI_COMPAT,
                        "https://example.invalid", "fixture"), "", listOf(user), { PermissionMode.FULL_ACCESS }, mutableSetOf(),
                        FileFs(root), RequestOptions(), 100000, AgentMode.ACT).collect { event ->
                        events += event
                        when (event) {
                            is AgentEvent.ApprovalNeeded -> event.request.response.complete(true)
                            is AgentEvent.AssistantCommitted -> state.update { it.copy(messages = it.messages + event.message.copy(turnId = "package-turn")) }
                            is AgentEvent.ToolStarted -> state.update { it.copy(runningCalls = it.runningCalls + event.call) }
                            is AgentEvent.PackageProgress -> state.update { it.copy(packageProgress = it.packageProgress + (event.callId to event.status)) }
                            is AgentEvent.ToolMessageCommitted -> state.update { it.copy(messages = it.messages + event.message.copy(turnId = "package-turn")) }
                            is AgentEvent.ToolFinished -> state.update { it.copy(runningCalls = emptyList(), packageProgress = emptyMap()) }
                            else -> Unit
                        }
                    }
                }
                val download = PackageInstallStatus(listOf("ffmpeg"), PackageInstallPhase.DOWNLOADING,
                    listOf("libx264", "libavcodec", "ffmpeg"), currentPackage = "libavcodec", completed = listOf("libx264"),
                    downloadedBytes = 2048, totalBytes = 8192)
                withTimeout(10_000) { stages.send(download) }
                awaitText("Installing ffmpeg"); awaitText("Downloading libavcodec"); awaitText("25%")
                awaitText("1 of 3 packages unpacked")
                assertFalse(nodes().any { it.text?.toString() == "Installed ffmpeg" })
                assertFalse(nodes().any { it.text?.toString()?.contains("\"packages\"") == true })
                screenshot("download-25")
                stages.send(download.copy(downloadedBytes = 6144)); awaitText("75%")
                scenario.onActivity { scale.value = 1.5f }
                awaitText("Downloading libavcodec"); screenshot("download-large-text")
                stages.send(download.copy(phase = PackageInstallPhase.VERIFYING, downloadedBytes = 8192))
                awaitText("Verifying libavcodec"); awaitText("Installing ffmpeg")
                stages.send(download.copy(phase = PackageInstallPhase.UNPACKING, currentPackage = "ffmpeg", completed = listOf("libx264", "libavcodec")))
                awaitText("Unpacking ffmpeg"); awaitText("2 of 3 packages unpacked")
                screenshot("unpacking-large-text")
                stages.send(download.copy(phase = PackageInstallPhase.CONFIGURING, currentPackage = null, completed = download.packages))
                awaitText("Finalizing tools…"); awaitText("Installing ffmpeg")
                stages.close()
                withTimeout(10_000) { requireNotNull(job).join() }
                awaitText("Installed ffmpeg")
                assertTrue(events.filterIsInstance<AgentEvent.PackageProgress>().all { it.callId == call.id })
                assertFalse(events.any { it is AgentEvent.Error })
                screenshot("installed")
                // A committed error wins even if an old download snapshot arrives late.
                state.update { it.copy(messages = it.messages.filterNot { m -> m.role == Role.TOOL } +
                    ChatMessage(Role.TOOL, "Package installation failed: Checksum mismatch", toolCallId = call.id, toolName = call.name, isError = true, turnId = "package-turn"),
                    packageProgress = mapOf(call.id to download)) }
                awaitText("Install failed: ffmpeg")
                assertFalse(nodes().any { it.text?.toString() == "Downloading libavcodec" })
                screenshot("failed")
            }
        } finally {
            stages.close(); job?.cancelAndJoin()
            instrumentation.runOnMainSync { vm?.viewModelScope?.cancel() }
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            c.settings.setLastActiveSessionId(saved.lastActiveSessionId)
            root.deleteRecursively()
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo) { add(node); for (i in 0 until node.childCount) node.getChild(i)?.let(::visit) }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun awaitText(text: String) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        do {
            if (nodes().any { it.isVisibleToUser && it.text?.toString() == text }) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        screenshot("failure"); error("Expected $text; visible ${nodes().mapNotNull { it.text?.toString() }}")
    }
    private fun screenshot(name: String) {
        val folder = File(c.appContext.getExternalFilesDir(null), "package-tool-cards").apply { mkdirs() }
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.takeScreenshot()!!.let { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
