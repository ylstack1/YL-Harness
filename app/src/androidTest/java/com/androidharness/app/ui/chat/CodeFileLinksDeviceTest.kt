package com.androidharness.app.ui.chat

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
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
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.data.*
import com.androidharness.app.ui.AppNav
import com.androidharness.app.ui.ChatWorkspaceScreen
import com.androidharness.app.ui.LocalDesktopLayout
import com.androidharness.app.ui.chat.rich.LocalResponseFileOpen
import com.androidharness.app.ui.theme.HarnessTheme
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class CodeFileLinksDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun markdownInlineAndBareReferencesHaveNativeFileActionsDuringStreamingAndAfterCommit() {
        val text = mutableStateOf("")
        val live = mutableStateOf(false)
        val enabled = mutableStateOf(true)
        val chosen = AtomicReference<Pair<String, Int?>?>()
        val clicks = AtomicInteger()
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { host -> host.setContent { HarnessTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalResponseFileOpen provides if (enabled.value) ({ path, line ->
                    chosen.set(path to line); clicks.incrementAndGet(); Unit
                }) else null) {
                    Surface(Modifier.fillMaxSize().systemBarsPadding()) { Column { MarkdownText(text.value, streaming = live.value) } }
                }
            } } }
            for ((index, example) in listOf(
                "Open [Main source](src/Main.kt#L42)." to "Main source",
                "Inspect `src/Main.kt:42` next." to "src/Main.kt:42",
                "Inspect src/Main.kt:42 next." to "src/Main.kt:42",
                "Open **[Main source](src/Main.kt:42)**." to "Main source",
                "> Open [Main source](src/Main.kt#L42)." to "Main source",
                "- Open [Main source](src/Main.kt:42)." to "Main source",
                "| File |\n| --- |\n| [Main source](src/Main.kt#L42) |" to "Main source",
            ).withIndex()) {
                scenario.onActivity { chosen.set(null); text.value = example.first; live.value = index % 2 == 0 }
                frames()
                val before = clicks.get()
                clickLink(example.second)
                waitFor { chosen.get() == ("src/Main.kt" to 42) }
                assertEquals("One tap should open the file once", before + 1, clicks.get())
                scenario.onActivity { live.value = false }
                frames()
                waitFor { link(example.second) != null }
            }
            for (target in listOf("<src/My File.kt#L7>", "src/My%20File.kt:7", "file:///work/src/My%20File.kt#L7")) {
                scenario.onActivity { chosen.set(null); text.value = "[Spaced source]($target)" }
                frames()
                clickLink("Spaced source")
                waitFor { chosen.get() == ((if (target.startsWith("file:")) "/work/src/My File.kt" else "src/My File.kt") to 7) }
            }
            scenario.onActivity { enabled.value = false; chosen.set(null); text.value = "`src/ChatOnly.kt:11`" }
            frames()
            waitFor { nodes().any { it.text?.toString()?.contains("src/ChatOnly.kt:11") == true } }
            clickLink("src/ChatOnly.kt:11"); SystemClock.sleep(150)
            assertNull("Ordinary Chat must not expose workspace actions", chosen.get())
            scenario.onActivity { enabled.value = true; text.value = "```kotlin\nval file = \"src/Main.kt:42\"\n```" }
            frames()
            waitFor { nodes().any { it.text?.toString()?.contains("val file") == true } }
            clickLink("src/Main.kt:42"); SystemClock.sleep(150)
            assertNull("Fenced source stays literal", chosen.get())
        }
    }

    @Test fun phoneLinksOpenTheActualWorkspaceEditorAtTheRequestedLineAndHandleSpaces() = fixture(false)
    @Test fun desktopLinksUseThePaneAndRepeatedJumpsToTheSameFileStillWork() = fixture(true)

    private fun fixture(desktop: Boolean): Unit = runBlocking {
        val saved = c.settings.settings.first()
        val previous = c.workspace.currentProjectOnce()
        val root = File(c.appContext.cacheDir, "file-links-${System.nanoTime()}").apply { mkdirs() }
        val source = (1..100).joinToString("\n", postfix = "\n") { "val line$it = $it" }
        File(root, "src").mkdirs()
        File(root, "src/Main.kt").writeText(source)
        File(root, "src/My +File.kt").writeText(source)
        val project = c.workspace.addShellProject(root.path, activate = false)
        val sid = c.sessions.createSession("File link fixture", project.id, ConversationConfig(ConversationKind.CODE))
        val store = ViewModelStore()
        val externalOpens = AtomicInteger()
        try {
            c.settings.setOnboardingDone(true)
            c.settings.setStartScreen(StartScreen.CODE)
            c.settings.setCodeStart(CodeStartMode.LAST_CODE)
            c.settings.setResumeLastChat(true)
            c.settings.setLastActiveSessionId(sid)
            c.settings.setWorkspaceLayout(if (desktop) WorkspaceLayoutMode.DESKTOP else WorkspaceLayoutMode.PHONE)
            c.sessions.addMessage(sid, ChatMessage(Role.USER, "Show the relevant source"), "links")
            c.sessions.addMessage(sid, ChatMessage(Role.ASSISTANT,
                "[Open line 73](src/Main.kt#L73)\n\n[Open line 15](src/Main.kt:15)\n\n[Open spaced file](<src/My +File.kt#L7>)"), "links")
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { host ->
                    if (desktop) {
                        val vm = ChatViewModel(c, sid); store.put("chat", vm)
                        host.setContent { CompositionLocalProvider(LocalDensity provides Density(1f), LocalDesktopLayout provides true) {
                            HarnessTheme(dynamicColor = false) {
                                ChatWorkspaceScreen(vm, {}, { _, _ -> externalOpens.incrementAndGet() }, {}, {}, onOpenSubagent = {})
                            }
                        } }
                    } else host.setContent { HarnessTheme(dynamicColor = false) { AppNav(c) } }
                }
                clickLink("Open line 73")
                awaitEditor(scenario, source, 72)
                if (desktop) {
                    assertEquals("Desktop opens in its editor pane", 0, externalOpens.get())
                    scenario.onActivity { editor(it.window.decorView)!!.setSelection(2, 0) }
                    clickLink("Open line 73")
                    awaitEditor(scenario, source, 72)
                    clickLink("Open line 15")
                    awaitEditor(scenario, source, 14)
                } else {
                    instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                    waitFor { link("Open spaced file") != null }
                    clickLink("Open spaced file")
                    awaitEditor(scenario, source, 6)
                    waitFor { nodes().any { it.text?.toString()?.contains("My +File.kt") == true || it.contentDescription?.toString()?.contains("My +File.kt") == true } }
                }
                frames()
                val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                val dir = File(c.appContext.getExternalFilesDir(null), "file-link-evidence").apply { mkdirs() }
                File(dir, if (desktop) "desktop-line-jump.png" else "phone-line-jump.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            assertEquals("Following file links must not start an agent", false, c.runManager.isRunning(sid))
            assertEquals("Following links does not edit the source", source, File(root, "src/Main.kt").readText())
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            c.workspace.deleteProject(project)
            c.workspace.setActiveProject(previous.id)
            c.settings.restorePortable(saved)
            c.settings.setOnboardingDone(saved.onboardingDone)
            c.settings.setLastActiveSessionId(saved.lastActiveSessionId)
            root.deleteRecursively()
        }
    }

    private fun awaitEditor(scenario: ActivityScenario<ChatListTestActivity>, source: String, line: Int) {
        waitFor {
            var ready = false
            scenario.onActivity { host -> ready = editor(host.window.decorView)?.let { it.text.toString() == source && it.cursor.leftLine == line && it.firstVisibleLine <= line && it.lastVisibleLine >= line } == true }
            ready
        }
    }
    private fun editor(view: View): CodeEditor? {
        if (view is CodeEditor) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) editor(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun frames() {
        val latch = java.util.concurrent.CountDownLatch(1)
        instrumentation.runOnMainSync {
            android.view.Choreographer.getInstance().postFrameCallback {
                android.view.Choreographer.getInstance().postFrameCallback { latch.countDown() }
            }
        }
        assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
            add(node); for (i in 0 until node.childCount) node.getChild(i)?.let { addAll(visit(it)) }
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit).orEmpty().filter { it.isVisibleToUser }
    }
    private fun link(label: String): AccessibilityNodeInfo? = nodes().firstOrNull {
        it.text?.toString()?.contains(label) == true || it.contentDescription?.toString() == label
    }
    private fun clickLink(label: String) {
        waitFor { link(label) != null }
        val node = requireNotNull(link(label))
        val value = node.text?.toString().orEmpty()
        val start = value.indexOf(label).coerceAtLeast(0)
        val args = android.os.Bundle().apply {
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, start)
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, label.length)
        }
        node.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, args)
        @Suppress("DEPRECATION")
        val locations = node.extras.getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)
            ?.filterIsInstance<android.graphics.RectF>().orEmpty()
        val character = locations.firstOrNull { !it.isEmpty }
        val area = android.graphics.Rect().also(node::getBoundsInScreen)
        val x = character?.centerX() ?: area.centerX().toFloat()
        val y = character?.centerY() ?: area.centerY().toFloat()
        val down = SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            SystemClock.sleep(40)
        }
    }
    private fun waitFor(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 12_000
        while (SystemClock.uptimeMillis() < until) { if (condition()) return; SystemClock.sleep(60) }
        assertTrue("Visible UI: ${nodes().map { "${it.text ?: it.contentDescription} clickable=${it.isClickable} actions=${it.actionList.map { action -> action.id }}" }}", condition())
    }
}
