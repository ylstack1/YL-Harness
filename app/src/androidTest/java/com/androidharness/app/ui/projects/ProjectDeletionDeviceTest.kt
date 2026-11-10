package com.androidharness.app.ui.projects

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.MainActivity
import com.androidharness.app.automation.*
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.data.db.ProjectEntity
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.theme.HarnessTheme
import com.androidharness.app.workspace.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class ProjectDeletionDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun sidebarDeletesReviewedChatsAndSchedulesAndKeepsFiles(): Unit = runBlocking {
        fixture { a, b, root ->
            val sid = c.sessions.createProjectSession(a.id)
            val other = c.sessions.createProjectSession(b.id)
            c.sessions.addMessage(sid, ChatMessage(role = Role.USER, text = "Project history"))
            val task = AutomationTask(title = "Deletion schedule", prompt = "Fixture", projectId = a.id, projectName = a.name, enabled = false)
            c.automation.repository.save(task)
            val history = AutomationHistoryEntry(taskId = task.id, title = task.title, sessionId = sid,
                startedAt = 1, status = AutomationStatus.COMPLETED)
            c.automation.repository.addHistory(history)
            c.settings.setPinned(sid, true); c.settings.setArchived(sid, true)
            c.settings.setProjectPinned(a.id, true); c.settings.setLastActiveSessionId(sid)
            c.settings.setOnboardingDone(true); c.settings.setResumeLastChat(false)
            ActivityScenario.launch(MainActivity::class.java).use {
                tap("Open navigation"); tap("Projects")
                tap("Actions for project ${a.name}"); tap("Delete project")
                await("Delete project?"); await("Your folder and files stay on disk.")
                screenshot("sidebar-confirm")
                tap("Keep project")
                assertNotNull(c.workspace.projects.first().firstOrNull { it.id == a.id })
                tap("Actions for project ${a.name}"); tap("Delete project"); await("Keep project")
                await("Your folder and files stay on disk.")
                tap("Delete project")
                eventually { runBlocking { c.workspace.projects.first().none { it.id == a.id } } }
                await("Add project")
                assertNull(c.sessions.session(sid)); assertTrue(c.sessions.messages(sid).isEmpty())
                assertNotNull(c.sessions.session(other)); assertNull(c.automation.repository.task(task.id))
                assertNull(c.automation.repository.history.value.single { it.id == history.id }.sessionId)
                val settings = c.settings.settings.first()
                assertFalse(sid in settings.pinnedSessions); assertFalse(sid in settings.archivedSessions)
                assertFalse(a.id in settings.pinnedProjects); assertNull(settings.lastActiveSessionId)
                assertEquals("keep this file", File(root, "alpha/keep.txt").readText())
                assertTrue(runCatching { c.sessions.createSession("Stale request", a.id) }.isFailure)
            }
        }
    }

    @Test fun projectDetailsDeletionWorksWithLargeText(): Unit = runBlocking {
        fixture { a, _, _ ->
            val left = AtomicBoolean(false)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                        HarnessTheme { ProjectScreen(c, a.id, onBack = { left.set(true) }, onOpenChat = {}, onNewChat = {},
                            creatingChat = false, onMemory = {}, onAutomation = { _, _ -> }, onOpenSettings = {},
                            onOpenFiles = {}, onOpenTerminal = {}, onOpenBuildTest = {}, onSendPrompt = {}) }
                    }
                } }
                scrollTo("Project details"); tap("Project details"); tap("Delete project")
                await("Your folder and files stay on disk."); screenshot("details-large-text")
                tap("Delete project"); eventually { left.get() }
                assertTrue(c.workspace.projects.first().none { it.id == a.id })
            }
        }
    }

    @Test fun listDeleteAndSharedWorkspaceBlockerAreVisible(): Unit = runBlocking {
        fixture { a, b, _ ->
            val shared = c.sessions.createProjectSession(b.id)
            c.runManager.assignWorkspaces(shared, ChatWorkspaceSelection(b.id,
                listOf(ChatWorkspaceGrant(b.id, true), ChatWorkspaceGrant(a.id))))
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { HarnessTheme { ProjectsScreen(c, {}, {}) } } }
                tap("Actions for project ${a.name}"); tap("Delete project"); await("Keep project")
                eventually { nodes().any { it.text?.toString()?.startsWith("Used by chats in other projects:") == true } }
                assertFalse(clickTarget(requireNotNull(find("Delete project"))).isEnabled)
                screenshot("shared-blocker"); tap("Keep project")
            }
            val review = c.projectDeletion.preview(a.id)
            assertNotNull(review.blocker)
            assertTrue(runCatching { c.projectDeletion.delete(review) }.isFailure)
            assertNotNull(c.sessions.session(shared))
        }
    }

    @Test fun changedReviewAndBusyAutomationAndDefaultProjectAreProtected(): Unit = runBlocking {
        fixture { a, _, _ ->
            val review = c.projectDeletion.preview(a.id)
            val added = c.sessions.createProjectSession(a.id)
            assertTrue(runCatching { c.projectDeletion.delete(review) }.isFailure)
            assertNotNull(c.sessions.session(added))
            val fresh = c.projectDeletion.preview(a.id)
            c.automation.withIdleAutomations {
                assertTrue(runCatching { c.projectDeletion.delete(fresh) }.isFailure)
            }
            val default = c.workspace.projects.first().first { it.kind == WorkspaceManager.KIND_APP }
            val protected = c.projectDeletion.preview(default.id)
            assertNotNull(protected.blocker)
            assertTrue(runCatching { c.projectDeletion.delete(protected) }.isFailure)
            assertTrue(c.workspace.projects.first().any { it.id == a.id })
        }
    }

    private suspend fun fixture(block: suspend (ProjectEntity, ProjectEntity, File) -> Unit) {
        val previous = c.workspace.currentProjectOnce()
        val saved = c.settings.settings.first()
        val root = File(c.appContext.cacheDir, "project-delete-${System.nanoTime()}").apply { mkdirs() }
        val unique = System.nanoTime().toString().takeLast(6)
        val a = c.workspace.addShellProject(File(root, "alpha").apply { mkdirs(); File(this, "keep.txt").writeText("keep this file") }.path)
            .let { c.workspace.renameProject(it.id, "Delete Alpha $unique"); it.copy(name = "Delete Alpha $unique") }
        val b = c.workspace.addShellProject(File(root, "beta").apply { mkdirs() }.path, activate = false)
            .let { c.workspace.renameProject(it.id, "Delete Beta $unique"); it.copy(name = "Delete Beta $unique") }
        try { block(a, b, root) } finally {
            val ids = setOf(a.id, b.id)
            c.automation.repository.tasks.value.filter { it.projectId in ids }.forEach { c.automation.delete(it.id) }
            c.sessions.sessions.first().filter { it.projectId in ids }.forEach { c.sessions.deleteSession(it) }
            c.workspace.deleteProject(a); c.workspace.deleteProject(b); c.workspace.setActiveProject(previous.id)
            c.settings.removeProjectReferences(a.id, emptySet()); c.settings.removeProjectReferences(b.id, emptySet())
            c.settings.setOnboardingDone(saved.onboardingDone); c.settings.setResumeLastChat(saved.resumeLastChat)
            c.settings.setLastActiveSessionId(saved.lastActiveSessionId); c.settings.setSidebarProjects(saved.sidebarProjects)
            root.deleteRecursively()
        }
    }
    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo) { if (node.isVisibleToUser) add(node); for (i in 0 until node.childCount) node.getChild(i)?.let(::visit) }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun find(text: String) = nodes().firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }
    private fun clickTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var target = node
        while (!target.isClickable && target.isEnabled) target = target.parent ?: return target
        return target
    }
    private fun eventually(check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) { if (check()) return; SystemClock.sleep(100) }
        error("Project deletion condition timed out")
    }
    private fun await(text: String) { eventually { find(text) != null } }
    private fun tap(text: String) {
        eventually { find(text)?.let { clickTarget(it).isEnabled } == true }
        val rect = Rect().also(requireNotNull(find(text))::getBoundsInScreen)
        val time = SystemClock.uptimeMillis()
        listOf(0, 1).forEach { action ->
            val event = android.view.MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0)
                .apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        instrumentation.waitForIdleSync(); SystemClock.sleep(200)
    }
    private fun scrollTo(text: String) {
        repeat(10) {
            if (find(text) != null) return
            nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(200)
        }
        await(text)
    }
    private fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(c.appContext.getExternalFilesDir(null), "project-deletion-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
