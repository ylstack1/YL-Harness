package com.androidharness.app.ui.chat

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.agent.ThinkingLevel
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.data.ThemeMode
import com.androidharness.app.llm.ModelEntry
import com.androidharness.app.llm.ProviderType
import com.androidharness.app.ui.chat.components.ChatAppearance
import com.androidharness.app.ui.chat.components.MessageComposer
import com.androidharness.app.ui.theme.HarnessTheme
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real production chat and composer controls, with local history and no provider request. */
@RunWith(AndroidJUnit4::class)
class ChatAppearanceDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun chatPreservesWallpaperColorsInLightDarkAndAmoled() {
        val mode = mutableStateOf(ThemeMode.LIGHT)
        val outer = AtomicReference<ColorScheme>()
        val inner = AtomicReference<ColorScheme>()
        val rendered = AtomicReference<ThemeMode>()
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { HarnessTheme(mode.value, dynamicColor = true) {
                outer.set(MaterialTheme.colorScheme)
                ChatAppearance {
                    inner.set(MaterialTheme.colorScheme)
                    rendered.set(mode.value)
                    androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                            Text("Wallpaper colors", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                            MessageComposer(false, TextFieldValue("Make the spacing clearer"), null, {}, {}, {}, {}, {}, {})
                        }
                    }
                }
            } } }
            for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.AMOLED)) {
                scenario.onActivity { mode.value = theme }
                instrumentation.waitForIdleSync()
                awaitGeometry("Theme should finish recomposing") { rendered.get() == theme }
                assertSame("Chat must retain every parent palette role", outer.get(), inner.get())
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    val expected = if (theme == ThemeMode.LIGHT) dynamicLightColorScheme(c.appContext) else dynamicDarkColorScheme(c.appContext)
                    assertEquals(expected.primary, inner.get().primary)
                    if (theme == ThemeMode.AMOLED) assertEquals(androidx.compose.ui.graphics.Color.Black, inner.get().surface)
                    else assertEquals(expected.surface, inner.get().surface)
                }
                screenshot("material-you-${theme.name.lowercase()}")
            }
        }
    }

    @Test fun workspaceOrdersAnswerFilesAndActivityAndRetainsActions() = runBlocking {
        val saved = c.settings.settings.first()
        val provider = c.providers.add("Appearance fixture", ProviderType.OPENAI_COMPAT,
            "http://127.0.0.1:9/v1", "claude-sonnet-4-5", "fixture")
        c.providers.saveCatalog(provider.id, listOf(ModelEntry(provider.model)))
        c.settings.setModelSelectionScope(true)
        c.settings.setActiveSelection(provider.id, provider.model)
        c.settings.setThinkingLevel(ThinkingLevel.HIGH)
        val project = c.workspace.currentProjectOnce()
        val sid = c.sessions.createSession("Polish the login screen", project.id)
        val turn = "appearance-turn"
        val call = ToolCallData("appearance-read", "read_file", "{\"path\":\"LoginScreen.kt\"}")
        c.sessions.addMessage(sid, ChatMessage(Role.USER, "Make the login screen cleaner"), turn)
        c.sessions.addMessage(sid, ChatMessage(Role.ASSISTANT, toolCalls = listOf(call)), turn)
        c.sessions.addMessage(sid, ChatMessage(Role.TOOL, "Original layout", toolCallId = call.id, toolName = call.name), turn)
        c.sessions.recordFileEdit(sid, turn, "src/LoginScreen.kt", 18, 12)
        c.sessions.addMessage(sid, ChatMessage(Role.ASSISTANT,
            "### Login screen updated\n\nThe form now has a clearer heading, softer fields, and one prominent sign-in button.\n\nI also aligned secondary actions and made the spacing consistent.",
            outputTokens = 100, generationMs = 2500, firstTokenMs = 500, streamMs = 2000), turn)
        c.sessions.recordUsage(sid, provider.name, provider.model, 1000, 50, 800, 0, turn, cacheReported = true)
        c.sessions.recordUsage(sid, provider.name, provider.model, 1000, 50, 0, 0, turn, cacheReported = true)
        val store = ViewModelStore()
        val theme = mutableStateOf(ThemeMode.LIGHT)
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val vm = ChatViewModel(c, sid)
                    store.put("appearance", vm)
                    activity.setContent { HarnessTheme(themeMode = theme.value, dynamicColor = false) {
                        ChatScreen(vm, {}, { _, _ -> }, {}, {}, onOpenSubagent = {})
                    } }
                }
                await("Work summary")
                val answer = bounds(reveal("Login screen updated", forward = false))
                screenshot("workspace-top")
                val file = bounds(reveal("LoginScreen.kt"))
                val activity = bounds(reveal("Work summary"))
                if (instrumentation.targetContext.resources.configuration.fontScale <= 1f) {
                    assertTrue("Answer precedes file receipt", answer.top < file.top)
                    assertTrue("File receipt precedes work summary", file.top < activity.top)
                }
                await("Thinking level"); await("Message actions"); await(voiceLabel)
                reveal("50 tk/s"); reveal("Cache hit · 40%")
                if (find(instrumentation.uiAutomation.rootInActiveWindow, "Scroll to latest", false) != null) {
                    tap("Scroll to latest")
                    SystemClock.sleep(350)
                }
                val speedBounds = bounds(await("50 tk/s"))
                assertTrue("Token speed must align with the left content edge",
                    kotlin.math.abs(speedBounds.left - answer.left) <= 4 * instrumentation.targetContext.resources.displayMetrics.density)
                screenshot("workspace-light")
                tap("Cache hit · 40%")
                await("800 of 2000 input tokens served from cache")
                if (instrumentation.targetContext.resources.configuration.fontScale <= 1f) {
                    await("Request 1"); await("Request 2"); await("Cache miss")
                }
                tap("Cache hit · 40%")
                tap("Expand activity")
                await("Read", contains = true)
                tap("Collapse activity")
                tap("More response actions")
                await("Fork from this message")
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                tap("More options"); await("GitHub")
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                scenario.onActivity { theme.value = ThemeMode.DARK }
                SystemClock.sleep(350)
                screenshot("workspace-dark")
                tap("Thinking level"); await("Thinking options")
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            c.settings.setModelSelectionScope(saved.sameModelAcrossChats)
            c.settings.setThinkingLevel(saved.thinkingLevel)
            if (saved.activeProviderId != null) c.settings.setActiveSelection(saved.activeProviderId, saved.activeModel)
            else { c.settings.setActiveProvider(null); c.settings.setActiveModel(saved.activeModel) }
            c.providers.delete(provider.id)
        }
    }

    @Test fun composerRetainsAttachmentVoiceSendQueueAndStopActions() {
        val value = mutableStateOf(TextFieldValue("Refine the spacing"))
        val busy = mutableStateOf(false)
        val sent = AtomicInteger(); val stopped = AtomicInteger(); val voice = AtomicInteger(); val images = AtomicInteger()
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { HarnessTheme { ChatAppearance {
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp)) {
                    MessageComposer(busy = busy.value, value = value.value, attachedSkill = null,
                        onValueChange = { value.value = it }, onClearSkill = {}, onSend = { sent.incrementAndGet() },
                        onStop = { stopped.incrementAndGet() }, onAttachImage = { images.incrementAndGet() }, onAttachFile = {},
                        onToggleInbuiltVoice = { voice.incrementAndGet() }, attachments = { Text("sample.kt") })
                }
            } } } }
            await("sample.kt")
            tap(voiceLabel); assertEquals(1, voice.get())
            tap("Send message"); assertEquals(1, sent.get())
            tap("Message actions"); tap("Photo or image"); assertEquals(1, images.get())
            scenario.onActivity { busy.value = true }
            tap("Queue message"); assertEquals(2, sent.get())
            scenario.onActivity { value.value = TextFieldValue("") }
            await(voiceLabel)
            tap("Stop"); assertEquals(1, stopped.get())
            scenario.onActivity { busy.value = false }
            await(voiceLabel)
            val send = await("Send message")
            var action = send
            while (action.parent != null && !action.isClickable) action = action.parent
            assertFalse("Empty composer must not send", action.isEnabled)
            val density = instrumentation.targetContext.resources.displayMetrics.density
            val compactHeight = bounds(await("Message input")).height()
            assertTrue("Empty input should remain compact", compactHeight <= 64 * density)
            val inputBottom = bounds(await("Message input")).bottom
            assertTrue("Controls should share the input row", kotlin.math.abs(inputBottom - bounds(send).bottom) < 16 * density)
            scenario.onActivity { value.value = TextFieldValue("First line\nSecond line\nThird line\nFourth line") }
            awaitGeometry("Input should grow for multiline text") { bounds(await("Message input")).height() > compactHeight + 16 * density }
            scenario.onActivity { value.value = TextFieldValue("") }
            awaitGeometry("Clearing text should restore compact input") { bounds(await("Message input")).height() <= 64 * density }
        }
    }

    private fun awaitGeometry(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        throw AssertionError(message)
    }

    private val voiceLabel get() = c.appContext.getString(com.androidharness.app.R.string.settings_page_voice_title)

    private fun find(node: AccessibilityNodeInfo?, text: String, contains: Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        val labels = listOfNotNull(node.text?.toString(), node.contentDescription?.toString(),
            if (android.os.Build.VERSION.SDK_INT >= 28) node.paneTitle?.toString() else null)
        if (labels.any { if (contains) it.contains(text) else it == text }) return node
        for (i in 0 until node.childCount) find(node.getChild(i), text, contains)?.let { return it }
        return null
    }
    private fun await(text: String, contains: Boolean = false): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 10_000
        do {
            val node = find(instrumentation.uiAutomation.rootInActiveWindow, text, contains)
            if (node != null) return node
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Expected visible: $text")
    }
    private fun reveal(text: String, forward: Boolean = true): AccessibilityNodeInfo {
        fun scrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) scrollable(node.getChild(i))?.let { return it }
            return null
        }
        for (direction in listOf(forward, !forward)) repeat(6) {
            find(instrumentation.uiAutomation.rootInActiveWindow, text, false)?.let { return it }
            scrollable(instrumentation.uiAutomation.rootInActiveWindow)?.performAction(
                if (direction) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            SystemClock.sleep(300)
        }
        return await(text)
    }
    private fun tap(text: String) {
        var node = reveal(text)
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue("Click $text", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun screenshot(name: String) {
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "chat-appearance").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()!!.let { bitmap ->
            File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
