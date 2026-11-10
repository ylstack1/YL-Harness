package com.androidharness.app.ui.chat

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.data.env.EnvState
import com.androidharness.app.data.ThemeMode
import com.androidharness.app.data.AppSettings
import com.androidharness.app.ui.LocalDesktopInput
import com.androidharness.app.ui.LocalDesktopLayout
import com.androidharness.app.ui.chat.components.MessageComposer
import com.androidharness.app.ui.chat.components.ToolCallCard
import com.androidharness.app.ui.common.PackageInstallProgress
import com.androidharness.app.ui.theme.HarnessTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ComposerProgressDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun focusStylingKeepsRecordingRailVisibleAndRetainsLockedRecordingActions() {
        val recording = mutableStateOf(GroqRecordState.HOLDING)
        val trackColor = java.util.concurrent.atomic.AtomicInteger()
        val transcribed = java.util.concurrent.atomic.AtomicInteger()
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                CompositionLocalProvider(LocalDesktopLayout provides false, LocalDesktopInput provides false) {
                    HarnessTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
                        trackColor.set(MaterialTheme.colorScheme.surfaceContainerHighest.toArgb())
                        Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
                                MessageComposer(false, TextFieldValue(), null, {}, {}, {}, {}, {}, {},
                                    voiceEngine = AppSettings.VOICE_ENGINE_GROQ, groqRecordState = recording.value,
                                    recordingDurationMs = 2_000, levels = List(40) { (it % 7) / 7f },
                                    onStopAndTranscribeGroq = { transcribed.incrementAndGet() })
                            }
                        }
                    }
                }
            } }
            val description = context.getString(com.androidharness.app.R.string.ui_recording_release_to_send_slide_left_to)
            await { find { it.contentDescription?.toString() == description } != null }
            SystemClock.sleep(500)
            val mic = bounds(requireNotNull(find { it.contentDescription?.toString() == description }))
            val density = context.resources.displayMetrics.density
            val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            var railPixels = 0
            for (x in (mic.centerX() - 12 * density).toInt()..(mic.centerX() + 12 * density).toInt()) {
                for (y in (mic.top - 80 * density).toInt().coerceAtLeast(0)..(mic.top - 16 * density).toInt()) {
                    if (image.getPixel(x, y) and 0xffffff == trackColor.get() and 0xffffff) railPixels++
                }
            }
            image.recycle()
            assertTrue("The recording lock rail must draw above the composer", railPixels > 200)
            screenshot("composer-recording-rail")
            instrumentation.runOnMainSync { recording.value = GroqRecordState.LOCKED }
            val transcribe = context.getString(com.androidharness.app.R.string.ui_transcribe_voice_message)
            await { find { it.contentDescription?.toString() == transcribe } != null }
            var action = requireNotNull(find { it.contentDescription?.toString() == transcribe })
            while (!action.isClickable && action.parent != null) action = action.parent
            assertTrue(action.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertEquals(1, transcribed.get())
            instrumentation.runOnMainSync { recording.value = GroqRecordState.TRANSCRIBING }
            awaitText(context.getString(com.androidharness.app.R.string.voice_transcribing))
            instrumentation.runOnMainSync { recording.value = GroqRecordState.IDLE }
        }
    }

    @Test fun groqVoiceGesturesSurviveCapsuleTransitionAndKeepBusyDraftSeparate() {
        val state = mutableStateOf(GroqRecordState.IDLE)
        val armed = mutableStateOf(false)
        val samples = mutableStateOf(emptyList<Float>())
        val elapsed = mutableStateOf(0L)
        val finishes = java.util.concurrent.atomic.AtomicInteger()
        val cancels = java.util.concurrent.atomic.AtomicInteger()
        val sends = java.util.concurrent.atomic.AtomicInteger()
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                CompositionLocalProvider(LocalDesktopLayout provides false, LocalDesktopInput provides false) {
                    HarnessTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
                        LaunchedEffect(state.value) {
                            if (state.value == GroqRecordState.HOLDING || state.value == GroqRecordState.LOCKED) {
                                while (true) {
                                    elapsed.value += 60L
                                    val amplitude = ((kotlin.math.sin(elapsed.value / 135.0) + 1) * 0.35).toFloat()
                                    samples.value = (samples.value + amplitude).takeLast(40)
                                    kotlinx.coroutines.delay(60)
                                }
                            }
                        }
                        Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
                                MessageComposer(true, TextFieldValue("Keep this queued draft"), null, {}, {},
                                    { sends.incrementAndGet() }, {}, {}, {},
                                    voiceEngine = AppSettings.VOICE_ENGINE_GROQ, groqRecordState = state.value,
                                    recordingDurationMs = elapsed.value, levels = samples.value, cancelArmed = armed.value,
                                    onCancelArmedChange = { armed.value = it },
                                    onStartGroqRecord = { state.value = if (it) GroqRecordState.LOCKED else GroqRecordState.HOLDING },
                                    onLockGroqRecord = { state.value = GroqRecordState.LOCKED },
                                    onCancelGroqRecord = { cancels.incrementAndGet(); state.value = GroqRecordState.IDLE },
                                    onStopAndTranscribeGroq = { finishes.incrementAndGet(); state.value = GroqRecordState.TRANSCRIBING })
                            }
                        }
                    }
                }
            } }
            val idle = context.getString(com.androidharness.app.R.string.ui_tap_to_lock_recording_hold_to_record)
            val density = context.resources.displayMetrics.density
            fun node(description: String): AccessibilityNodeInfo {
                await { find { it.contentDescription?.toString() == description } != null }
                return requireNotNull(find { it.contentDescription?.toString() == description })
            }
            fun click(description: String) {
                var action = node(description)
                while (!action.isClickable && action.parent != null) action = action.parent
                val target = bounds(action)
                val down = SystemClock.uptimeMillis()
                for (type in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                    val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), type,
                        target.exactCenterX(), target.exactCenterY(), 0)
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
                    finally { event.recycle() }
                }
            }
            fun gesture(dx: Float = 0f, dy: Float = 0f) {
                val target = bounds(node(idle))
                val down = SystemClock.uptimeMillis()
                fun event(action: Int, x: Float, y: Float) {
                    val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
                    finally { event.recycle() }
                }
                event(android.view.MotionEvent.ACTION_DOWN, target.exactCenterX(), target.exactCenterY())
                // A stationary hold must start before release, without a synthetic move.
                await { state.value == GroqRecordState.HOLDING }
                for (step in 1..12) {
                    event(android.view.MotionEvent.ACTION_MOVE, target.exactCenterX() + dx * step / 12,
                        target.exactCenterY() + dy * step / 12)
                    SystemClock.sleep(20)
                }
                event(android.view.MotionEvent.ACTION_UP, target.exactCenterX() + dx, target.exactCenterY() + dy)
            }
            gesture()
            await { state.value == GroqRecordState.TRANSCRIBING }
            assertEquals(1, finishes.get())
            awaitText(context.getString(com.androidharness.app.R.string.voice_transcribing))
            assertNull("Processing cannot start another recording", find { it.contentDescription?.toString() == idle })
            assertEquals("Voice processing never sends the existing draft", 0, sends.get())
            screenshot("voice-transcribing")
            click(context.getString(com.androidharness.app.R.string.settings_action_cancel))
            await { state.value == GroqRecordState.IDLE }
            assertEquals(1, cancels.get())
            gesture(dx = -125 * density)
            await { state.value == GroqRecordState.IDLE }
            assertEquals(2, cancels.get())
            gesture(dy = -95 * density)
            await { state.value == GroqRecordState.LOCKED }
            SystemClock.sleep(500)
            screenshot("voice-hands-free")
            click(context.getString(com.androidharness.app.R.string.ui_transcribe_voice_message))
            await { state.value == GroqRecordState.TRANSCRIBING }
            assertEquals(2, finishes.get())
            assertEquals(0, sends.get())
            instrumentation.runOnMainSync { state.value = GroqRecordState.IDLE }
            click(idle)
            await { state.value == GroqRecordState.LOCKED }
            click(context.getString(com.androidharness.app.R.string.ui_discard_recording))
            await { state.value == GroqRecordState.IDLE }
            assertEquals(3, cancels.get())
            click(idle)
            await { state.value == GroqRecordState.LOCKED }
            click(context.getString(com.androidharness.app.R.string.ui_transcribe_voice_message))
            await { state.value == GroqRecordState.TRANSCRIBING }
            assertEquals(3, finishes.get())
            click(context.getString(com.androidharness.app.R.string.settings_action_cancel))
            await { state.value == GroqRecordState.IDLE }
            assertEquals(4, cancels.get())
            awaitText("Keep this queued draft")
        }
    }

    @Test fun groqVoiceFitsNarrowLayoutAtLargeFonts() {
        val state = mutableStateOf(GroqRecordState.LOCKED)
        val composerHeight = java.util.concurrent.atomic.AtomicInteger()
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                CompositionLocalProvider(LocalDesktopLayout provides false, LocalDesktopInput provides false,
                    LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                    HarnessTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                        Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
                                Box(Modifier.width(320.dp)) {
                                    MessageComposer(false, TextFieldValue(), null, {}, {}, {}, {}, {}, {},
                                        voiceEngine = AppSettings.VOICE_ENGINE_GROQ, groqRecordState = state.value,
                                        recordingDurationMs = 72_000, levels = List(40) { (it % 7) / 7f },
                                        modifier = Modifier.onSizeChanged { composerHeight.set(it.height) })
                                }
                            }
                        }
                    }
                }
            } }
            awaitText("1:12")
            SystemClock.sleep(400)
            val density = context.resources.displayMetrics.density
            assertTrue("Recording stays in one compact row at large fonts", composerHeight.get() <= 80 * density)
            val finish = bounds(requireNotNull(find { it.contentDescription?.toString() == context.getString(com.androidharness.app.R.string.ui_transcribe_voice_message) }))
            val timer = bounds(requireNotNull(find { it.text?.toString() == "1:12" }))
            assertFalse("Timer and finish targets do not overlap", Rect.intersects(timer, finish))
            screenshot("voice-large-font-locked")
            instrumentation.runOnMainSync { state.value = GroqRecordState.TRANSCRIBING }
            val label = context.getString(com.androidharness.app.R.string.voice_transcribing)
            awaitText(label)
            val text = bounds(requireNotNull(find { it.text?.toString() == label }))
            assertTrue("Processing title stays compact at large fonts", text.height() <= 64 * density)
            assertTrue("Processing label stays within the narrow panel", text.right <= 320 * density)
            await {
                find { it.contentDescription?.toString() == context.getString(com.androidharness.app.R.string.settings_action_cancel) }
                    ?.let { it.isVisibleToUser && bounds(it).bottom <= context.resources.displayMetrics.heightPixels } == true
            }
            SystemClock.sleep(400) // Inspect the settled panel, while its progress animation continues.
            screenshot("voice-large-font-transcribing")
            assertTrue("Transcription stays in one compact row at large fonts", composerHeight.get() <= 80 * density)
            // Stop the fixture's infinite processing animation before ActivityScenario waits for idle.
            instrumentation.runOnMainSync { state.value = GroqRecordState.IDLE }
        }
    }

    @Test fun mobileComposerExpandsOnFocusAndReturnsWithoutLosingTheDraft() {
        val value = mutableStateOf(TextFieldValue())
        val height = java.util.concurrent.atomic.AtomicInteger()
        val focus = java.util.concurrent.atomic.AtomicReference<androidx.compose.ui.focus.FocusManager>()
        val theme = mutableStateOf(ThemeMode.LIGHT)
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                CompositionLocalProvider(LocalDesktopLayout provides false, LocalDesktopInput provides false) {
                    HarnessTheme(themeMode = theme.value, dynamicColor = false) {
                        focus.set(LocalFocusManager.current)
                        Surface(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
                                MessageComposer(false, value.value, null, { value.value = it }, {}, {}, {}, {}, {},
                                    chatOnly = true, modifier = Modifier.onSizeChanged { height.set(it.height) })
                            }
                        }
                    }
                }
            } }
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                instrumentation.runOnMainSync { theme.value = mode; value.value = TextFieldValue(); focus.get().clearFocus() }
                SystemClock.sleep(500)
                val restHeight = height.get()
                val restWidth = bounds(awaitInput()).width()
                screenshot("composer-rest-${mode.name.lowercase()}")
                val input = bounds(awaitInput())
                val down = SystemClock.uptimeMillis()
                for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                    val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                        input.exactCenterX(), input.exactCenterY(), 0)
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
                    finally { event.recycle() }
                }
                val density = context.resources.displayMetrics.density
                await { height.get() >= restHeight + 6 * density && bounds(awaitInput()).width() >= restWidth + 8 * density }
                SystemClock.sleep(400)
                assertTrue("Focus growth stays subtle", height.get() <= restHeight + 16 * density)
                setText("Make this easier to read.")
                screenshot("composer-focused-${mode.name.lowercase()}")
                val draft = value.value
                instrumentation.runOnMainSync { focus.get().clearFocus() }
                await { height.get() == restHeight && bounds(awaitInput()).width() == restWidth }
                assertEquals("Focus changes keep text and selection", draft, value.value)
                screenshot("composer-draft-${mode.name.lowercase()}")
            }
        }
    }

    @Test fun composerKeepsItsHeightWhenTypingAndHandlesMultilineSelectionAtLargeFonts() {
        for (scale in listOf(1f, 1.5f)) {
            val value = mutableStateOf(TextFieldValue())
            val cursorColor = java.util.concurrent.atomic.AtomicInteger()
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale),
                        LocalDesktopLayout provides false, LocalDesktopInput provides false) {
                        HarnessTheme {
                            cursorColor.set(MaterialTheme.colorScheme.primary.toArgb())
                            Surface(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
                                    MessageComposer(false, value.value, null, { value.value = it }, {}, {}, {}, {}, {})
                                }
                            }
                        }
                    }
                } }
                assertTrue(awaitInput().performAction(AccessibilityNodeInfo.ACTION_CLICK))
                SystemClock.sleep(500)
                val empty = bounds(awaitInput())
                screenshot("composer-${scale}-empty")
                setText("Agyp")
                await { value.value.text == "Agyp" }
                screenshot("composer-${scale}-single")
                val typed = bounds(awaitInput())
                assertEquals("Typing must preserve field height at font scale $scale", empty.height(), typed.height())
                assertEquals(empty.width(), typed.width())
                // The keyboard may move the entire viewport while its insets settle.
                assertRenderedCursorCentered(typed, cursorColor.get())
                setText("First line\nSecond line\nThird line")
                await { value.value.text.contains("Third line") }
                assertTrue(bounds(awaitInput()).height() > empty.height())
                instrumentation.runOnMainSync { value.value = value.value.copy(selection = TextRange(0, 5)) }
                await { value.value.selection == TextRange(0, 5) }
                val long = (1..15).joinToString("\n") { "Line $it café 😀" }
                setText(long)
                await { value.value.text == long }
                val longBounds = bounds(awaitInput())
                val density = context.resources.displayMetrics.density
                assertTrue("Composer must remain bounded", longBounds.height() <= (142 * density).toInt())
                instrumentation.runOnMainSync {
                    value.value = value.value.copy(selection = TextRange(long.length), composition = TextRange(long.length - 2, long.length))
                }
                await { value.value.composition != null }
                screenshot("composer-${scale}-multiline")
                setText("Agyp")
                await { value.value.text == "Agyp" }
                assertEquals(empty.height(), bounds(awaitInput()).height())
                assertEquals(empty.width(), bounds(awaitInput()).width())
            }
        }
    }

    @Test fun packageProgressUpdatesAndCompletedToolsStopAnimating() {
        val progress = mutableStateOf<EnvState>(EnvState.Preparing)
        val result = mutableStateOf<ChatMessage?>(null)
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { HarnessTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Linux packages", style = MaterialTheme.typography.titleMedium)
                        PackageInstallProgress(progress.value)
                        ToolCallCard(ToolCallData("ui-progress", "shell", "{\"command\":\"python3 check.py\"}"),
                            result.value, running = result.value == null, onOpenFile = { _, _ -> }, showLiveCode = false)
                    }
                }
            } } }
            awaitText("Preparing installation")
            instrumentation.runOnMainSync { progress.value = EnvState.Downloading(1, 4, "python", 2048, 8192) }
            awaitText("25%")
            awaitText("Downloading python")
            assertNull(find { it.text?.toString() == "Installed" })
            instrumentation.runOnMainSync { progress.value = EnvState.Downloading(1, 4, "python", 6144, 8192) }
            awaitText("75%")
            screenshot("package-download-wave-a")
            SystemClock.sleep(350)
            screenshot("package-download-wave-b")
            instrumentation.runOnMainSync { progress.value = EnvState.Installing(2, 4, "python") }
            awaitText("Installing python")
            awaitText("2 of 4 packages installed")
            awaitText("50%")
            screenshot("package-installing")
            instrumentation.runOnMainSync {
                progress.value = EnvState.Ready
                result.value = ChatMessage(Role.TOOL, "Completed", toolCallId = "ui-progress", toolName = "shell")
            }
            await { find { it.text?.toString()?.startsWith("Installing python") == true } == null }
            await { progressNodes() == 0 }
        }
    }

    private fun setText(text: String) {
        assertTrue(awaitInput().performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }))
        await { find { it.isEditable && it.text?.toString() == text } != null }
        SystemClock.sleep(100)
    }
    private fun awaitInput(): AccessibilityNodeInfo {
        var input: AccessibilityNodeInfo? = null
        await { input = find { it.isEditable }; input != null }
        return requireNotNull(input)
    }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun assertRenderedCursorCentered(bounds: Rect, color: Int) {
        // Measure pixels: accessibility character boxes omit the decorator's padding.
        val deadline = SystemClock.uptimeMillis() + 4000
        while (SystemClock.uptimeMillis() < deadline) {
            val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            var left = image.width; var top = image.height; var right = -1; var bottom = -1
            for (y in bounds.top.coerceAtLeast(0) until bounds.bottom.coerceAtMost(image.height)) {
                for (x in bounds.left.coerceAtLeast(0) until bounds.right.coerceAtMost(image.width)) {
                    if (image.getPixel(x, y) and 0xffffff == color and 0xffffff) {
                        left = minOf(left, x); right = maxOf(right, x)
                        top = minOf(top, y); bottom = maxOf(bottom, y)
                    }
                }
            }
            image.recycle()
            if (bottom > top && bottom - top > (right - left) * 4) {
                assertEquals("The visible cursor must be vertically centered in the single-line field", bounds.exactCenterY(),
                    // The caret and accessibility rectangles are rounded to whole physical pixels.
                    (top + bottom + 1) / 2f, 2f * context.resources.displayMetrics.density + .5f)
                return
            }
            SystemClock.sleep(60) // Cursor may be in its blink-off interval.
        }
        fail("No visible caret found in the input viewport")
    }
    private fun awaitText(text: String) = await { find { it.text?.toString() == text } != null }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            if (condition()) return
            SystemClock.sleep(30)
        }
        fail("UI did not reach the expected state")
    }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node)) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        return walk(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun progressNodes(): Int {
        var count = 0
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.rangeInfo != null) count++
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(instrumentation.uiAutomation.rootInActiveWindow)
        return count
    }
    private fun screenshot(name: String) {
        SystemClock.sleep(80)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
