package com.androidharness.app.ui.chat

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.core.ChatExperience
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.data.*
import com.androidharness.app.ui.chat.components.EmptyState
import com.androidharness.app.ui.theme.HarnessTheme
import com.androidharness.app.agent.*
import com.androidharness.app.llm.*
import com.androidharness.app.tools.*
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ChatPersonalizationDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun emptyChatChoicePersistsHidesAfterMessageAndNewChatDefaultsToOn(): Unit = runBlocking {
        val sid = c.sessions.createSession("Personalization fixture", conversation = ConversationConfig(ConversationKind.CHAT,
            instructions = "Keep this instruction", imageFrequency = ImageFrequency.MORE))
        val saved = c.settings.settings.first()
        val store = ViewModelStore()
        lateinit var vm: ChatViewModel
        fun display(scenario: ActivityScenario<ChatListTestActivity>, id: String?) {
            scenario.onActivity { host ->
                store.clear()
                vm = ChatViewModel(c, id, ConversationKind.CHAT)
                store.put("chat", vm)
                host.setContent { HarnessTheme { ChatScreen(viewModel = vm, onOpenDrawer = {}, onOpenFile = { _, _ -> }, onNewChat = {},
                        onOpenTerminal = {}, onOpenSubagent = {}) } }
            }
        }
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                display(scenario, sid)
                await("Personalized")
                waitFor { vm.state.value.conversationReady }
                val control = Rect().also { await("Personalized").getBoundsInScreen(it) }
                val headline = Rect().also { await("What is on your mind?").getBoundsInScreen(it) }
                assertTrue("Personalization must sit above the empty Chat headline", control.bottom < headline.top)
                tap("Personalized"); tap("Unpersonalized")
                await("Unpersonalized")
                waitFor { !vm.state.value.conversationSaving }
                val config = ConversationConfig.decode(c.sessions.session(sid)!!.conversationJson)
                assertFalse(config.personalizationEnabled)
                assertEquals("Keep this instruction", config.instructions)
                assertEquals(ImageFrequency.MORE, config.imageFrequency)
                assertTrue(c.sessions.messages(sid).isEmpty())
                display(scenario, sid)
                await("Unpersonalized")
                screenshot("empty-chat-personalization-off")
                c.sessions.addMessage(sid, ChatMessage(Role.USER, "First message in this Chat"))
                await("First message in this Chat")
                waitFor { find("Unpersonalized") == null && find("Personalized") == null }
                display(scenario, null)
                await("Personalized")
                assertTrue(vm.state.value.conversation.personalizationEnabled)
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            c.settings.setLastActiveSessionId(saved.lastActiveSessionId)
        }
    }

    @Test fun imageDefaultShowsAndTracksAppSettingWithoutCopyingItIntoChat(): Unit = runBlocking {
        val saved = c.settings.settings.first()
        val sid = c.sessions.createSession("Image inheritance fixture", conversation = ConversationConfig(ConversationKind.CHAT))
        val store = ViewModelStore()
        lateinit var vm: ChatViewModel
        try {
            c.settings.setChatImageFrequency(ImageFrequency.ALWAYS)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { host ->
                    vm = ChatViewModel(c, sid); store.put("chat", vm)
                    host.setContent { HarnessTheme { ChatScreen(viewModel = vm, onOpenDrawer = {}, onOpenFile = { _, _ -> }, onNewChat = {},
                        onOpenTerminal = {}, onOpenSubagent = {}) } }
                }
                await("Personalized")
                tap("More options"); tap("Chat settings")
                await("Use app setting (Always)")
                assertTrue(checked("Use app setting (Always)"))
                var config = ConversationConfig.decode(c.sessions.session(sid)!!.conversationJson)
                assertEquals(ImageFrequency.DEFAULT, config.imageFrequency)
                assertEquals(ImageFrequency.ALWAYS, ChatExperience.effectiveImageFrequency(config.imageFrequency,
                    c.settings.settings.first().chatImageFrequency))
                c.settings.setChatImageFrequency(ImageFrequency.NONE)
                await("Use app setting (No image)")
                assertTrue(checked("Use app setting (No image)"))
                tap("More often"); tap("Save")
                waitFor { !vm.state.value.conversationSaving && find("Images in this chat") == null }
                config = ConversationConfig.decode(c.sessions.session(sid)!!.conversationJson)
                assertEquals(ImageFrequency.MORE, config.imageFrequency)
                assertEquals(ImageFrequency.NONE, c.settings.settings.first().chatImageFrequency)
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            c.settings.setChatImageFrequency(saved.chatImageFrequency)
            c.settings.setLastActiveSessionId(saved.lastActiveSessionId)
        }
    }

    @Test fun newChatInstructionsStayEditableWhileOffAndReachEveryRequestWhenEnabled(): Unit = runBlocking {
        val saved = c.settings.settings.first()
        val store = ViewModelStore()
        lateinit var vm: ChatViewModel
        var sid: String? = null
        var memoryId: String? = null
        val captured = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Set<String>>>()
        var round = 0
        val provider = object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
                messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                captured += systemPrompt to tools.map { it.name }.toSet()
                if (round++ == 0) {
                    emit(StreamEvent.ToolCallReady(com.androidharness.app.core.ToolCallData("fixture-${System.nanoTime()}", "read_chat_text", "{}")))
                    emit(StreamEvent.Done("tool_calls"))
                } else {
                    emit(StreamEvent.TextDelta("Fixture answer")); emit(StreamEvent.Done("stop"))
                }
            }
        }
        fun fixtureTool(name: String) = object : Tool {
            override val name = name
            override val description = "Fixture tool"
            override val parametersSchema = kotlinx.serialization.json.JsonObject(emptyMap())
            override val isReadOnly = true
            override suspend fun execute(args: kotlinx.serialization.json.JsonObject, ctx: ToolContext) = ToolResult(true, "Fixture")
        }
        val engine = AgentEngine({ provider }, ToolRegistry(listOf("read_chat_text", "remember", "recall", "search_chats").map(::fixtureTool)),
            c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills, universalMemory = c.memory)
        val runs = RunManager(c.appContext, engine, c.sessions, c.checkpoints, c.workspace, c.linuxEnv, c.settings, TodoStore(), c.providers)
        suspend fun request(): List<Pair<String, Set<String>>> {
            captured.clear(); round = 0
            runs.startRun(requireNotNull(sid), "Check preferences", emptyList(), ProviderConfig("fixture", "Fixture", ProviderType.OPENAI_COMPAT,
                "https://example.invalid", "fixture"), "fixture", PermissionMode.FULL_AUTO, AgentMode.ACT,
                1024, 32_000, ThinkingLevel.OFF, 5, notifyOnFinish = false)
            withTimeout(10_000) { runs.live(requireNotNull(sid)).first { !it.running } }
            assertEquals(2, captured.size)
            return captured.toList()
        }
        val global = "GLOBAL_CHAT_INSTRUCTION_925"
        val instruction = "Use the phrase CUSTOM_CHAT_INSTRUCTION_742 in brief answers."
        val memory = "Personalization fixture fact ${System.nanoTime()}"
        try {
            c.settings.setChatInstructions(global)
            c.settings.setMemoryEnabled(true)
            memoryId = c.memory.add(memory, MemoryCategory.FACT, MemoryScope.GLOBAL, null)!!.id
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { host ->
                    vm = ChatViewModel(c, null, ConversationKind.CHAT); store.put("chat", vm)
                    host.setContent { HarnessTheme { ChatScreen(viewModel = vm, onOpenDrawer = {}, onOpenFile = { _, _ -> },
                        onNewChat = {}, onOpenTerminal = {}, onOpenSubagent = {}) } }
                }
                await("Personalized"); tap("More options"); tap("Chat settings")
                tap("Replace for this chat")
                // Instructions appear above the image choices without needing to scroll.
                val instructionsBounds = Rect().also { await("Chat instructions").getBoundsInScreen(it) }
                val imagesBounds = Rect().also { await("Images in this chat").getBoundsInScreen(it) }
                assertTrue(instructionsBounds.bottom < imagesBounds.top)
                tap("Personalization")
                val editor = nodes().first { it.isEditable }
                assertTrue("Instructions remain editable with personalization off", editor.isEnabled)
                val args = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, instruction) }
                assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
                tap("Save")
                waitFor { vm.state.value.sessionId != null && !vm.state.value.conversationSaving }
                waitFor { find("Images in this chat") == null }
                sid = vm.state.value.sessionId
                val config = ConversationConfig.decode(c.sessions.session(requireNotNull(sid))!!.conversationJson)
                assertEquals(instruction, config.instructions)
                assertFalse(config.personalizationEnabled)
                assertEquals(ImageFrequency.DEFAULT, config.imageFrequency)
                val off = request()
                assertTrue(off.all { !it.first.contains(instruction) && !it.first.contains(global) && !it.first.contains(memory) })
                assertTrue(off.all { it.second.intersect(setOf("remember", "recall", "search_chats")).isEmpty() })

                tap("More options"); tap("Chat settings"); await(instruction)
                tap("Personalization"); tap("Save")
                waitFor { !vm.state.value.conversationSaving && vm.state.value.conversation.personalizationEnabled }
                val on = request()
                assertTrue(on.all { it.first.contains(instruction) && it.first.contains(memory) && !it.first.contains(global) })
                assertTrue(on.all { it.second.containsAll(setOf("remember", "recall", "search_chats")) })
                tap("More options"); tap("Chat settings")
                tap("Use global Chat instructions"); tap("Save")
                waitFor { !vm.state.value.conversationSaving && vm.state.value.conversation.useGlobalInstructions }
                assertTrue(request().all { it.first.contains(global) && !it.first.contains(instruction) })
                val changedGlobal = "UPDATED_GLOBAL_INSTRUCTION_926"
                c.settings.setChatInstructions(changedGlobal)
                assertTrue(request().all { it.first.contains(changedGlobal) && !it.first.contains(global) })
                tap("More options"); tap("Chat settings"); tap("Replace for this chat"); await(instruction)
                assertTrue(nodes().first { it.isEditable }.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                }))
                tap("Save"); waitFor { !vm.state.value.conversationSaving && !vm.state.value.conversation.useGlobalInstructions }
                assertTrue(request().all { !it.first.contains(changedGlobal) && !it.first.contains(instruction) })
                tap("More options"); tap("Chat settings")
                screenshot("chat-instructions-settings")
            }
        } finally {
            runs.stopAllAndJoin()
            sid = sid ?: runCatching { vm.state.value.sessionId }.getOrNull()
            instrumentation.runOnMainSync { store.clear() }
            sid?.let { c.sessions.session(it)?.let { session -> c.sessions.deleteSession(session) } }
            memoryId?.let { c.memory.remove(it) }
            c.settings.setMemoryEnabled(saved.memoryEnabled)
            c.settings.setChatInstructions(saved.chatInstructions)
            c.settings.setLastActiveSessionId(saved.lastActiveSessionId)
        }
    }

    @Test fun failedSaveKeepsTheSheetAndDraftAndRestoresThePersistedSelection(): Unit = runBlocking {
        val sid = c.sessions.createSession("Failed settings fixture", conversation = ConversationConfig(ConversationKind.CHAT))
        val saved = c.settings.settings.first()
        val store = ViewModelStore()
        lateinit var vm: ChatViewModel
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { host ->
                    vm = ChatViewModel(c, sid); store.put("chat", vm)
                    host.setContent { HarnessTheme { ChatScreen(viewModel = vm, onOpenDrawer = {}, onOpenFile = { _, _ -> },
                        onNewChat = {}, onOpenTerminal = {}, onOpenSubagent = {}) } }
                }
                await("Personalized"); tap("More options"); tap("Chat settings"); tap("Replace for this chat")
                val editor = nodes().first { it.isEditable }
                val draft = "Retain my instruction after a failed save"
                assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, draft)
                }))
                tap("Personalization")
                c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
                tap("Save")
                waitFor { !vm.state.value.conversationSaving && vm.state.value.chatSettingsError != null }
                await(draft); await("Save")
                assertTrue(vm.state.value.conversation.personalizationEnabled)
                assertEquals("", vm.state.value.conversation.instructions)
                screenshot("chat-instructions-save-failure")
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            c.settings.setLastActiveSessionId(saved.lastActiveSessionId)
        }
    }

    @Test fun switchStaysReachableAtLargeTextAndNeverAppearsInCode() {
        val chat = mutableStateOf(true)
        val enabled = mutableStateOf(true)
        val desktop = mutableStateOf(false)
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { host -> host.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f),
                    com.androidharness.app.ui.LocalDesktopLayout provides desktop.value) {
                    HarnessTheme { Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                            if (chat.value) com.androidharness.app.ui.chat.components.ChatPersonalizationDropdown(
                                enabled.value, true, onSelect = { enabled.value = it })
                            EmptyState(true, {}, {}, chatOnly = chat.value, modifier = Modifier.weight(1f))
                        }
                    } }
                }
            } }
            await("Personalized")
            tap("Personalized"); tap("Unpersonalized"); await("Unpersonalized")
            assertFalse(enabled.value)
            screenshot("empty-chat-large-text")
            scenario.onActivity { desktop.value = true }
            tap("Unpersonalized"); tap("Personalized"); await("Personalized")
            assertTrue(enabled.value)
            scenario.onActivity { chat.value = false }
            await("What do you want to build?")
            assertNull(find("Personalized")); assertNull(find("Unpersonalized"))
        }
    }

    private fun waitFor(predicate: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 10_000
        while (!predicate() && SystemClock.uptimeMillis() < until) SystemClock.sleep(100)
        assertTrue(predicate())
    }
    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo) { add(node); for (i in 0 until node.childCount) node.getChild(i)?.let(::visit) }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun find(text: String) = nodes().firstOrNull { it.isVisibleToUser &&
        (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    private fun await(text: String): AccessibilityNodeInfo {
        val until = SystemClock.uptimeMillis() + 15_000
        do { find(text)?.let { return it }; SystemClock.sleep(100) } while (SystemClock.uptimeMillis() < until)
        screenshot("failure")
        throw AssertionError("Missing $text: ${nodes().mapNotNull { it.text }}")
    }
    private fun checked(text: String): Boolean {
        var node = await(text)
        repeat(6) { if (node.isChecked || node.isSelected) return true; node = node.parent ?: return false }
        return false
    }
    private fun tap(text: String) {
        repeat(10) {
            if (find(text) == null) {
                nodes().lastOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                SystemClock.sleep(100)
            }
        }
        var node = await(text)
        while (!node.isClickable && node.parent != null) node = node.parent
        // Exercise hit testing: accessibility ACTION_CLICK bypasses overlapping UI layers.
        val bounds = Rect().also(node::getBoundsInScreen)
        assertFalse("Empty touch target for $text", bounds.isEmpty)
        val down = SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
            SystemClock.sleep(50)
        }
        instrumentation.waitForIdleSync()
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500) // Capture settled sheet transitions and released touch feedback.
        val folder = File(c.appContext.getExternalFilesDir(null), "chat-personalization").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
