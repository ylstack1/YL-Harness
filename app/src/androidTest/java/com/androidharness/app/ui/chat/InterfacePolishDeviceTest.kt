package com.androidharness.app.ui.chat

import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.R
import com.androidharness.app.agent.AgentMode
import com.androidharness.app.agent.PermissionMode
import com.androidharness.app.agent.ThinkingLevel
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.ConversationConfig
import com.androidharness.app.data.ConversationKind
import com.androidharness.app.data.ThemeMode
import com.androidharness.app.ui.LocalDesktopInput
import com.androidharness.app.ui.LocalDesktopLayout
import com.androidharness.app.ui.chat.components.MainHeader
import com.androidharness.app.ui.chat.components.MessageComposer
import com.androidharness.app.ui.chat.components.UserBubble
import com.androidharness.app.ui.theme.HarnessTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class InterfacePolishDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun queuedDraftKeepsVoiceAndStopReachableOnPhoneAndBothDesktopLayouts() {
        val layout = mutableStateOf(0)
        val engine = mutableStateOf(AppSettings.VOICE_ENGINE_INBUILT)
        val recording = mutableStateOf(GroqRecordState.IDLE)
        val draft = mutableStateOf(TextFieldValue("Add this after the current task"))
        val queues = AtomicInteger(); val stops = AtomicInteger(); val voices = AtomicInteger()
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f),
                    LocalDesktopLayout provides (layout.value != 0), LocalDesktopInput provides (layout.value == 2)) {
                    HarnessTheme(ThemeMode.DARK, dynamicColor = false) {
                        Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                            Column(Modifier.widthIn(max = 320.dp).fillMaxHeight(), verticalArrangement = Arrangement.Bottom) {
                                MessageComposer(true, draft.value, null, { draft.value = it }, {},
                                    onSend = { queues.incrementAndGet(); draft.value = TextFieldValue() },
                                    onStop = { stops.incrementAndGet() }, onAttachImage = {}, onAttachFile = {},
                                    voiceEngine = engine.value, groqRecordState = recording.value,
                                    onToggleInbuiltVoice = { voices.incrementAndGet() },
                                    onStartGroqRecord = { voices.incrementAndGet(); recording.value = GroqRecordState.LOCKED })
                            }
                        }
                    }
                }
            } }
            for (mode in 0..2) for (voiceEngine in listOf(AppSettings.VOICE_ENGINE_INBUILT, AppSettings.VOICE_ENGINE_GROQ)) {
                scenario.onActivity {
                    layout.value = mode; engine.value = voiceEngine; recording.value = GroqRecordState.IDLE
                    draft.value = TextFieldValue("Add this after the current task")
                }
                await("Add this after the current task")
                val queue = context.getString(R.string.ui_queue_message)
                val voice = context.getString(if (voiceEngine == AppSettings.VOICE_ENGINE_GROQ) R.string.ui_tap_to_lock_recording_hold_to_record else R.string.settings_page_voice_title)
                await(queue); await(voice)
                assertFalse("Queue and voice targets overlap on layout $mode", Rect.intersects(bounds(queue), bounds(voice)))
                assertOnScreen(voice); assertOnScreen(queue)
                val sent = queues.get(); click(queue)
                waitFor { queues.get() == sent + 1 }
                await(context.getString(R.string.settings_action_stop)); await(voice)
                assertEquals("Queueing must not stop the agent", 0, stops.get())
                val spoken = voices.get(); click(voice)
                waitFor { voices.get() == spoken + 1 }
                assertEquals("Voice must not trigger Stop", 0, stops.get())
                screenshot("queue-voice-$mode-${if (voiceEngine == AppSettings.VOICE_ENGINE_GROQ) "groq" else "inbuilt"}")
            }
        }
    }

    @Test fun headerAndBottomMenusStayOnScreenAndDispatchTheirOwnActionsAtLargeText() {
        val kind = mutableStateOf(ConversationKind.CODE)
        val dark = mutableStateOf(false)
        val terminals = AtomicInteger(); val settings = AtomicInteger(); val files = AtomicInteger()
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f), LocalDesktopLayout provides false) {
                    HarnessTheme(if (dark.value) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.fillMaxSize()) {
                                MainHeader("A conversation with a long title", false, "deepseek-v4-1-flash", AgentMode.ACT,
                                    thinkingLevel = ThinkingLevel.MEDIUM, thinkingLevels = listOf(ThinkingLevel.OFF, ThinkingLevel.MEDIUM),
                                    permissionMode = PermissionMode.CONFIRM_ALL, canUndo = false,
                                    onOpenDrawer = {}, onPickModel = {}, onOpenTerminal = { terminals.incrementAndGet() },
                                    onSetThinking = {}, onSetPermission = {}, onSetMode = {}, onOpenContext = {}, onOpenUndo = {},
                                    onOpenFiles = {}, onOpenGitHub = {}, conversation = ConversationConfig(kind.value),
                                    onOpenChatSettings = { settings.incrementAndGet() })
                                Spacer(Modifier.weight(1f))
                                MessageComposer(false, TextFieldValue(), null, {}, {}, {}, {}, {}, { files.incrementAndGet() },
                                    modifier = Modifier.navigationBarsPadding())
                            }
                        }
                    }
                }
            } }
            val more = context.getString(R.string.ui_more_options)
            val terminal = context.getString(R.string.ui_terminal)
            click(more)
            scrollTo(terminal); assertOnScreen(terminal)
            screenshot("code-overflow-large-light")
            click(terminal); assertEquals(1, terminals.get())
            click(more)
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            waitFor { find(terminal) == null }
            scenario.onActivity { kind.value = ConversationKind.CHAT; dark.value = true }
            click(more)
            val chatSettings = context.getString(R.string.chat_settings_title)
            await(chatSettings); assertOnScreen(chatSettings)
            screenshot("chat-overflow-large-dark")
            click(chatSettings); assertEquals(1, settings.get())
            click(context.getString(R.string.ui_message_actions))
            val file = context.getString(R.string.ui_any_file)
            await(file); assertOnScreen(file); assertOnScreen(context.getString(R.string.ui_photo_or_image))
            screenshot("attachment-menu-large-dark")
            click(file); assertEquals(1, files.get())
        }
    }

    @Test fun holdingAMessageKeepsItsBoundsAndOpensItsActionsOnce() {
        val holds = AtomicInteger()
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { HarnessTheme(dynamicColor = false) {
                Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                    Column(Modifier.fillMaxWidth().padding(18.dp)) {
                        UserBubble("Hold this message", onLongPress = { holds.incrementAndGet() })
                    }
                }
            } } }
            val before = bounds("Hold this message")
            val down = SystemClock.uptimeMillis()
            fun send(action: Int) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, before.centerX().toFloat(), before.centerY().toFloat(), 0)
                try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            }
            send(MotionEvent.ACTION_DOWN)
            try {
                waitFor { holds.get() == 1 }
                val pressed = bounds("Hold this message")
                assertTrue("Hold feedback must keep the message in place", kotlin.math.abs(before.centerY() - pressed.centerY()) < 4)
                screenshot("message-held-native-ripple")
            } finally { send(MotionEvent.ACTION_UP) }
            SystemClock.sleep(200)
            assertEquals(1, holds.get())
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
            add(node); for (i in 0 until node.childCount) node.getChild(i)?.let { addAll(visit(it)) }
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit).orEmpty().filter { it.isVisibleToUser }
    }
    private fun find(label: String) = nodes().firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }
    private fun waitFor(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < until) { if (condition()) return; SystemClock.sleep(50) }
        assertTrue("Visible controls: ${nodes().mapNotNull { it.text ?: it.contentDescription }}", condition())
    }
    private fun await(label: String): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        waitFor { result = find(label); result != null }
        return requireNotNull(result)
    }
    private fun bounds(label: String) = Rect().also(await(label)::getBoundsInScreen)
    private fun assertOnScreen(label: String) {
        val rect = bounds(label); val screen = context.resources.displayMetrics
        assertTrue("$label outside screen: $rect", rect.left >= 0 && rect.top >= 0 && rect.right <= screen.widthPixels && rect.bottom <= screen.heightPixels)
    }
    private fun click(label: String) {
        var node = await(label)
        while (!node.isClickable) node = node.parent ?: error("No click target: $label")
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); SystemClock.sleep(250)
    }
    private fun scrollTo(label: String) {
        repeat(15) {
            if (find(label) != null) return
            nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(150)
        }
        await(label)
    }
    private fun screenshot(name: String) {
        val dir = File(context.getExternalFilesDir(null), "polish-evidence").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
