package com.androidharness.app.ui.files

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.theme.HarnessTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class FileNavigationDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun nestedFolderSurvivesAgentUpdatesRefreshAndRecreationButWorkspaceSwitchResetsIt(): Unit = runBlocking {
        val previous = c.workspace.currentProjectOnce()
        val root = File(c.appContext.cacheDir, "file-navigation-${System.nanoTime()}").apply { mkdirs() }
        val aRoot = File(root, "alpha").apply { mkdirs() }
        val nested = File(aRoot, "clients/Timur").apply { mkdirs() }
        File(nested, "report.txt").writeText("original")
        val bRoot = File(root, "beta").apply { mkdirs() }
        File(bRoot, "second-root.txt").writeText("second workspace")
        val a = c.workspace.addShellProject(aRoot.path, activate = false)
        val b = c.workspace.addShellProject(bRoot.path, activate = false)
        val sid = c.sessions.createSession("File navigation fixture", a.id)
        val opened = AtomicReference<String?>()
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                fun mount() = scenario.onActivity { activity -> activity.setContent { HarnessTheme {
                    FilesScreen(c, sessionId = sid, onBack = {}, onOpenFile = opened::set)
                } } }
                mount()
                await("clients"); tap("clients"); await("Timur"); tap("Timur"); await("report.txt")
                // These are the same Room session updates produced as an agent commits tool results.
                repeat(6) { index ->
                    File(nested, "progress-$index.txt").writeText("agent update $index")
                    c.sessions.addMessage(sid, ChatMessage(role = Role.SYSTEM, text = "Agent progress $index"))
                    await("progress-$index.txt")
                    assertNotNull("Agent update $index must keep the nested folder open", find("report.txt"))
                    assertNotNull(find(".."))
                }
                tap("Refresh"); await("report.txt")
                scenario.recreate(); mount(); await("report.txt")
                // A recent-workspace change elsewhere must not move this chat's file browser.
                c.workspace.setActiveProject(b.id)
                c.sessions.addMessage(sid, ChatMessage(role = Role.SYSTEM, text = "Agent still running"))
                await("report.txt"); tap("report.txt")
                assertEquals("clients/Timur/report.txt", opened.get())
                // Selecting a genuinely different workspace still starts at that workspace's root.
                c.runManager.assignWorkspace(sid, b.id)
                await("second-root.txt")
                assertNull(find("report.txt")); assertNull(find(".."))
            }
        } finally {
            c.sessions.sessions.first().firstOrNull { it.id == sid }?.let { c.sessions.deleteSession(it) }
            c.workspace.deleteProject(a); c.workspace.deleteProject(b); c.workspace.setActiveProject(previous.id)
            root.deleteRecursively()
        }
    }

    private fun find(text: String): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && (node.text?.toString() == text || node.contentDescription?.toString() == text)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun await(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) { find(text)?.let { return it }; SystemClock.sleep(100) }
        error("Missing file navigation UI: $text")
    }
    private fun tap(text: String) {
        val rect = Rect().also(await(text)::getBoundsInScreen)
        val time = SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0)
                .apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        instrumentation.waitForIdleSync(); SystemClock.sleep(150)
    }
}
