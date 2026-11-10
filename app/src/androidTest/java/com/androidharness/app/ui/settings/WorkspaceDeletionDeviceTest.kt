package com.androidharness.app.ui.settings

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.theme.HarnessTheme
import com.androidharness.app.workspace.ChatWorkspaceGrant
import com.androidharness.app.workspace.ChatWorkspaceSelection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WorkspaceDeletionDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun savedChatBlocksRemovalWithoutCrashingAndUnusedWorkspaceCanBeRemoved() =
        verifyDeletion(secondary = false)

    @Test fun secondaryChatWorkspaceIsAlsoProtectedFromRemoval() =
        verifyDeletion(secondary = true)

    private fun verifyDeletion(secondary: Boolean) = runBlocking {
        val previous = c.workspace.currentProjectOnce()
        val appProject = c.workspace.projects.first().first { it.kind == "APP" }
        val root = File(c.appContext.cacheDir, "issue45-${System.nanoTime()}").apply { mkdirs() }
        val marker = File(root, "keep.txt").apply { writeText("repository files stay on disk") }
        // GitHub imports become device-folder projects; no network or credentials are needed here.
        val project = c.workspace.addShellProject(root.path, activate = false)
        val sessionId = c.sessions.createSession("Workspace deletion fixture", if (secondary) appProject.id else project.id)
        try {
            if (secondary) c.workspace.assignSelection(sessionId, ChatWorkspaceSelection(appProject.id,
                listOf(ChatWorkspaceGrant(appProject.id, true), ChatWorkspaceGrant(project.id, false))))
            c.workspace.setActiveProject(appProject.id)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    HarnessTheme { SettingsScreen(c, onBack = {}) }
                } }
                c.pendingSettingsScroll.value = "troubleshooting"
                tap("Manage connections")
                repeat(2) {
                    tap("Delete ${project.name}")
                    awaitNode("Delete workspace?")
                    tap("Delete")
                    awaitNode("This workspace belongs to saved chats. Delete those chats before removing it.")
                    assertTrue(c.workspace.projects.first().any { it.id == project.id })
                    assertNotNull(c.sessions.session(sessionId))
                    assertEquals("repository files stay on disk", marker.readText())
                    assertEquals(appProject.id, c.workspace.currentProjectOnce().id)
                    if (it == 0) {
                        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                            File(c.appContext.getExternalFilesDir(null), "issue45-blocked-${if (secondary) "secondary" else "primary"}.png")
                                .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            bitmap.recycle()
                        }
                    }
                    tap("Cancel")
                }
                // Once no chat owns it, the same UI removes only the workspace registration.
                c.sessions.deleteSession(requireNotNull(c.sessions.session(sessionId)))
                tap("Delete ${project.name}")
                awaitNode("Delete workspace?")
                assertNull("An earlier failure must not leak into a new dialog", find(
                    "This workspace belongs to saved chats. Delete those chats before removing it."))
                tap("Delete")
                val deadline = SystemClock.uptimeMillis() + 10_000
                while (c.workspace.projects.first().any { it.id == project.id } && SystemClock.uptimeMillis() < deadline) {
                    SystemClock.sleep(80)
                }
                assertFalse(c.workspace.projects.first().any { it.id == project.id })
                awaitNode("Add workspace")
                assertNull(find("Delete workspace?"))
                assertEquals("repository files stay on disk", marker.readText())
                assertEquals(appProject.id, c.workspace.currentProjectOnce().id)
            }
        } finally {
            c.sessions.session(sessionId)?.let { c.sessions.deleteSession(it) }
            c.workspace.deleteProject(project)
            c.workspace.setActiveProject(previous.id)
            root.deleteRecursively()
        }
    }

    private fun find(text: String, node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
        node ?: return null
        if (node.text?.toString()?.lines()?.contains(text) == true || node.contentDescription?.toString() == text) return node
        for (i in 0 until node.childCount) find(text, node.getChild(i))?.let { return it }
        return null
    }

    private fun scroll(node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): Boolean {
        node ?: return false
        if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
        for (i in 0 until node.childCount) if (scroll(node.getChild(i))) return true
        return false
    }

    private fun awaitNode(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.uiAutomation.clearCache()
            find(text)?.let { if (it.isVisibleToUser) return it }
            scroll()
            SystemClock.sleep(100)
        }
        error("Missing UI node: $text")
    }

    private fun tap(text: String) {
        var node: AccessibilityNodeInfo? = awaitNode(text)
        while (node != null && !node.isClickable) node = node.parent
        check(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Could not click $text" }
        SystemClock.sleep(200)
    }
}
