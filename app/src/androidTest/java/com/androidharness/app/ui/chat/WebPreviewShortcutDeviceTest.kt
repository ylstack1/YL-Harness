package com.androidharness.app.ui.chat

import android.graphics.Bitmap
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
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.agent.*
import com.androidharness.app.core.*
import com.androidharness.app.data.ThemeMode
import com.androidharness.app.ui.LocalDesktopLayout
import com.androidharness.app.ui.chat.components.MainHeader
import com.androidharness.app.ui.theme.HarnessTheme
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebPreviewShortcutDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun detectsCurrentChatLinksAndHtmlAndOpensOnlyThroughTheShortcut(): Unit = runBlocking {
        val root = File(c.appContext.cacheDir, "preview-shortcut-${System.nanoTime()}").apply { mkdirs() }
        val project = c.workspace.addShellProject(root.path, activate = false)
        val sid = c.sessions.createProjectSession(project.id)
        val plain = c.sessions.createProjectSession(project.id)
        var vm: ChatViewModel? = null
        try {
            instrumentation.runOnMainSync { vm = ChatViewModel(c, sid) }
            withTimeout(10000) { vm!!.state.first { !it.isLoadingMessages } }
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { HarnessTheme {
                    ChatScreen(vm!!, {}, { _, _ -> }, {}, {}, onOpenSubagent = {})
                } } }
                await("Open GitHub"); SystemClock.sleep(400)
                assertNull(find("Open web browser"))
                c.sessions.addMessage(sid, ChatMessage(role = Role.ASSISTANT, text = "Site ready at localhost:5173/app"))
                await("Open web browser"); assertAdjacent()
                assertNoWebView()
                tap("Open web browser"); await("Web & HTML Preview"); await("localhost:5173/app")
                assertNoWebView()
                screenshot("browser-shortcut-links")
                tap("Close")

                // A different chat does not inherit the previous chat's links.
                instrumentation.runOnMainSync { vm!!.viewModelScope.cancel(); vm = ChatViewModel(c, plain) }
                scenario.onActivity { activity -> activity.setContent { HarnessTheme {
                    ChatScreen(vm!!, {}, { _, _ -> }, {}, {}, onOpenSubagent = {})
                } } }
                eventually { !vm!!.state.value.isLoadingMessages && find("Open web browser") == null }
                val html = File(root, "index.html").apply {
                    writeText("<html><meta name='viewport' content='width=device-width,initial-scale=1'><h1>Shortcut HTML fixture</h1></html>")
                }
                c.sessions.addMessage(plain, ChatMessage(role = Role.TOOL, toolName = "shell", text = "Site files written"))
                await("Open web browser"); assertAdjacent(); assertNoWebView()
                tap("Open web browser"); await("index.html"); tap("index.html")
                await("Shortcut HTML fixture")
                assertNotNull(nodes().firstOrNull { it.className?.toString()?.contains("WebView") == true })
                tap("More browser actions"); tap("Close")
                html.delete()
                c.sessions.addMessage(plain, ChatMessage(role = Role.TOOL, toolName = "shell", text = "Site removed"))
                eventually { find("Open web browser") == null }

                // Creating a file while away must be picked up on return without another message.
                scenario.moveToState(Lifecycle.State.CREATED)
                File(root, "returned.htm").writeText("<p>Returned site</p>")
                scenario.moveToState(Lifecycle.State.RESUMED)
                await("Open web browser"); assertAdjacent(); assertNoWebView()
            }
        } finally {
            instrumentation.runOnMainSync { vm?.viewModelScope?.cancel() }
            listOf(sid, plain).forEach { id -> c.sessions.session(id)?.let { c.sessions.deleteSession(it) } }
            c.workspace.deleteProject(project); root.deleteRecursively()
        }
    }

    @Test fun headerShortcutFitsBesideGitHubInBothThemesAtNormalAndLargeText(): Unit = runBlocking {
        val desktop = InstrumentationRegistry.getArguments().getString("desktop") == "true"
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (scale in listOf(1f, 1.5f)) {
            val show = mutableStateOf(false)
            val clicked = mutableStateOf(false)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    CompositionLocalProvider(LocalDesktopLayout provides desktop,
                        LocalDensity provides Density(LocalDensity.current.density, scale)) {
                        HarnessTheme(theme, dynamicColor = false) {
                            Surface(Modifier.fillMaxSize().statusBarsPadding()) {
                                Column {
                                    MainHeader(sessionTitle = "Website preview", busy = false,
                                        pickerLabel = "Provider / long-model-name", mode = AgentMode.ACT,
                                        thinkingLevel = ThinkingLevel.MEDIUM, thinkingLevels = listOf(ThinkingLevel.OFF, ThinkingLevel.MEDIUM),
                                        permissionMode = PermissionMode.CONFIRM_RISKY, canUndo = false,
                                        onOpenDrawer = {}, onPickModel = {}, onOpenTerminal = {}, onSetThinking = {},
                                        onSetPermission = {}, onSetMode = {}, onOpenContext = {}, onOpenUndo = {},
                                        onOpenFiles = {}, onOpenGitHub = {}, showWebPreviewShortcut = show.value,
                                        onOpenWebPreview = { clicked.value = true })
                                }
                            }
                        }
                    }
                } }
                await("Open GitHub"); assertNull(find("Open web browser"))
                instrumentation.runOnMainSync { show.value = true }
                await("Open web browser"); assertAdjacent()
                screenshot("browser-shortcut-${if (desktop) "desktop" else "phone"}-$theme-$scale")
                tap("Open web browser"); eventually { clicked.value }
                instrumentation.runOnMainSync { show.value = false }
                eventually { find("Open web browser") == null }
                await("Open GitHub")
            }
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser) add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun find(text: String) = nodes().firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }
    private fun await(text: String): AccessibilityNodeInfo {
        eventually { find(text) != null }
        return requireNotNull(find(text))
    }
    private fun tap(text: String) {
        val bounds = Rect().also(await(text)::getBoundsInScreen)
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        instrumentation.waitForIdleSync()
    }
    private fun assertAdjacent() {
        val github = Rect().also(await("Open GitHub")::getBoundsInScreen)
        val browser = Rect().also(await("Open web browser")::getBoundsInScreen)
        val screen = c.appContext.resources.displayMetrics
        assertTrue("Buttons overlap: $github $browser", github.right <= browser.left)
        assertEquals(github.centerY(), browser.centerY())
        assertTrue("Browser button clipped: $browser", browser.left >= 0 && browser.right <= screen.widthPixels && browser.top >= 0 && browser.bottom <= screen.heightPixels)
    }
    private fun assertNoWebView() = assertNull(nodes().firstOrNull { it.className?.toString()?.contains("WebView") == true })
    private fun eventually(check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 12000
        while (SystemClock.uptimeMillis() < end) { if (check()) return; SystemClock.sleep(60) }
        screenshot("browser-shortcut-failed")
        assertTrue("Timed out waiting for preview shortcut", check())
    }
    private fun screenshot(name: String) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(c.appContext.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
