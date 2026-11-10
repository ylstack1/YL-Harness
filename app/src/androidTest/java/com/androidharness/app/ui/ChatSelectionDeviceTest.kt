package com.androidharness.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.MainActivity
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.data.*
import com.androidharness.app.data.db.SessionEntity
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.theme.HarnessTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs the real drawer, confirmation dialogs, repositories and navigation for both sections. */
@RunWith(AndroidJUnit4::class)
class ChatSelectionDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val c get() = (context.applicationContext as HarnessApp).container

    @Test fun chatSidebarSelectsDetailsArchivesUndoesAndDeletesOnlySelectedChats() = exercise(ConversationKind.CHAT)
    @Test fun codeSidebarSelectsDetailsArchivesUndoesAndDeletesOnlySelectedChats() = exercise(ConversationKind.CODE)

    @Test fun selectionControlsFitNarrowSidebarsWithLargeTextInBothSections() {
        val now = System.currentTimeMillis()
        val chats = listOf("One", "Two", "Three").map { SessionEntity(it, "Large selection $it", now, now) }
        val details = java.util.concurrent.atomic.AtomicReference<Set<String>>()
        for (chatOnly in listOf(false, true)) {
            val compact = mutableStateOf(false)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                        HarnessTheme(ThemeMode.DARK, false) { Surface(Modifier.fillMaxSize()) {
                            var selection by remember { mutableStateOf(setOf("One", "Two")) }
                            Box(Modifier.fillMaxSize()) {
                            Box(Modifier.width(280.dp).then(if (compact.value) Modifier.height(360.dp) else Modifier.fillMaxHeight()).safeDrawingPadding()) {
                                ProjectNavigationSidebar(emptyList(), chats, null, null, emptySet(), emptySet(), "", {}, emptyList(), false, {},
                                    activity = { null }, onNewChat = {}, onChat = {}, onMessage = {}, onProjectChat = {}, onProjects = {},
                                    onChatActions = {}, onAction = { _, _ -> }, onBuild = {}, onTerminal = {}, onAutomation = {}, onProviders = {}, onSettings = {},
                                    workspaceLabel = "Workspaces", workspaceSummary = "", onWorkspaces = {}, onCollapse = {}, currentRoute = null,
                                    chatOnly = chatOnly, selectedChats = selection, onSelectedChatsChange = { selection = it },
                                    onSelectedChatsAction = { ids, action -> if (action == SidebarChatAction.DETAILS) details.set(ids) })
                            }
                            }
                        } }
                    }
                } }
                await { nodes().any { text(it) == "2 selected" } }
                click("Details")
                assertEquals(setOf("One", "Two"), details.get())
                screenshot("chat-selection-large-${if (chatOnly) "chat" else "code"}")
                scenario.onActivity { compact.value = true }
                await { nodes().any { text(it) == "2 selected" } }
                await { nodes().none { it.contentDescription?.toString() == "Search chats and messages" } }
                screenshot("chat-selection-compact-${if (chatOnly) "chat" else "code"}")
                click("Select all")
                await { nodes().any { text(it) == "3 selected" } }
                click("Exit selection")
                await { nodes().none { it.contentDescription?.toString() == "Exit selection" } }
            }
        }
    }

    private fun exercise(kind: ConversationKind) = runBlocking {
        val saved = c.settings.settings.first()
        val project = c.workspace.currentProjectOnce()
        val prefix = "Batch-${kind.name}-${System.nanoTime()}"
        val first = c.sessions.createSession("$prefix One", project.id, ConversationConfig(kind = kind))
        val second = c.sessions.createSession("$prefix Two", project.id, ConversationConfig(kind = kind))
        val keep = c.sessions.createSession("$prefix Keep", project.id, ConversationConfig(kind = kind))
        val other = c.sessions.createSession("$prefix Other section", project.id, ConversationConfig(kind = if (kind == ConversationKind.CHAT) ConversationKind.CODE else ConversationKind.CHAT))
        val created = setOf(first, second, keep, other)
        var activity: MainActivity? = null
        try {
            c.sessions.addMessage(first, ChatMessage(Role.USER, "Selected history one"))
            c.sessions.addMessage(second, ChatMessage(Role.USER, "Selected history two"))
            c.sessions.addMessage(keep, ChatMessage(Role.USER, "Keep this unselected history"))
            c.settings.setOnboardingDone(true)
            c.settings.setWorkspaceLayout(WorkspaceLayoutMode.PHONE)
            c.settings.setSidebarProjects(false)
            c.settings.setStartScreen(if (kind == ConversationKind.CHAT) StartScreen.CHAT else StartScreen.CODE)
            c.settings.setChatStart(ChatStartMode.LAST_CHAT)
            c.settings.setCodeStart(CodeStartMode.LAST_CODE)
            c.settings.setLastActiveSessionId(first)
            c.settings.setThemeMode(if (kind == ConversationKind.CODE) ThemeMode.DARK else ThemeMode.LIGHT)
            c.settings.setPinned(first, true); c.settings.setPinned(keep, true)
            c.settings.setArchived(keep, true)
            activity = instrumentation.startActivitySync(Intent(c.appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            instrumentation.runOnMainSync { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            click("Open navigation")
            await { nodes().any { it.contentDescription?.toString() == "Search chats and messages" } }
            val searchLabel = nodes().first { it.contentDescription?.toString() == "Search chats and messages" }
            val field = generateSequence(searchLabel) { it.parent }.firstOrNull { it.isEditable }
                ?: nodes().first { it.isEditable && it.isVisibleToUser }
            assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prefix)
            }))
            await { nodes().any { text(it) == "$prefix One" } && nodes().any { text(it) == "$prefix Two" } }
            longClick("$prefix One")
            await { nodes().any { text(it) == "Select" } }
            val selectTop = bounds(nodes().first { text(it) == "Select" }).top
            val renameTop = bounds(nodes().first { text(it) == "Rename" }).top
            assertTrue("Select belongs above Rename", selectTop < renameTop)
            click("Select")
            await { nodes().any { text(it) == "1 selected" } }
            click("$prefix Two")
            await { nodes().any { text(it) == "2 selected" } }
            assertTrue(nodes().filter { it.isCheckable && it.isChecked }.size >= 2)
            assertEquals("Selecting must not navigate", first, c.settings.settings.first().lastActiveSessionId)
            screenshot("chat-selection-${kind.name.lowercase()}")
            click("Details")
            await { nodes().any { text(it) == "Chat details" } }
            assertTrue(nodes().any { text(it) == "$prefix One" })
            assertTrue(nodes().any { text(it) == "$prefix Two" })
            assertTrue(nodes().none { text(it) == "$prefix Keep" })
            screenshot("chat-selection-details-${kind.name.lowercase()}")
            click("Close")
            click("Archive")
            await { runBlocking { c.settings.settings.first().archivedSessions.containsAll(setOf(first, second)) } }
            assertTrue(keep in c.settings.settings.first().archivedSessions)
            click("Undo")
            await { runBlocking { c.settings.settings.first().archivedSessions.let { first !in it && second !in it && keep in it } } }
            longClick("$prefix One"); click("Select")
            click("Select all")
            await { nodes().any { text(it) == "3 selected" } }
            assertTrue("Other section must stay outside this selection", nodes().none { text(it) == "4 selected" })
            click("Clear selection")
            await { nodes().any { it.contentDescription?.toString() == "AndroidHarness icon" } }
            longClick("$prefix One"); click("Select"); click("$prefix Two")
            click("Delete")
            await { nodes().any { text(it) == "Delete selected chats?" } }
            click("Cancel")
            assertNotNull(c.sessions.session(first)); assertNotNull(c.sessions.session(second))
            click("Delete")
            await { nodes().any { text(it) == "Delete selected chats?" } }
            screenshot("chat-selection-delete-${kind.name.lowercase()}")
            click("Delete")
            await { runBlocking { c.sessions.session(first) == null && c.sessions.session(second) == null } }
            await { runBlocking { c.settings.settings.first().pinnedSessions.let { first !in it && keep in it } } }
            assertNotNull(c.sessions.session(keep)); assertNotNull(c.sessions.session(other))
            assertTrue(keep in c.settings.settings.first().archivedSessions)
            assertTrue(c.sessions.messages(keep).any { it.text == "Keep this unselected history" })
        } finally {
            activity?.let { host -> instrumentation.runOnMainSync { host.finish() } }
            c.sessions.sessions.first().filter { it.id in created }.let { c.sessions.deleteSessions(it) }
            c.settings.forgetSidebarSessions(created)
            c.settings.restorePortable(saved)
            c.settings.setOnboardingDone(saved.onboardingDone)
            c.settings.setLastActiveSessionId(saved.lastActiveSessionId)
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        val root = instrumentation.uiAutomation.rootInActiveWindow
        if (root?.packageName?.toString() != context.packageName) return emptyList()
        fun walk(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { walk(node.getChild(it)) }
        return walk(root)
    }
    private fun text(node: AccessibilityNodeInfo) = node.text?.toString().orEmpty()
    private fun bounds(node: AccessibilityNodeInfo) = android.graphics.Rect().also(node::getBoundsInScreen)
    private fun match(node: AccessibilityNodeInfo, label: String) = text(node).lines().contains(label) || node.contentDescription?.toString() == label
    private fun click(label: String) {
        await { nodes().any { match(it, label) } }
        val button = nodes().filter { match(it, label) }.firstNotNullOfOrNull { node -> generateSequence(node) { it.parent }.firstOrNull { it.isClickable } }
        assertNotNull("Missing action $label", button)
        assertTrue(button!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)); SystemClock.sleep(200)
    }
    private fun longClick(label: String) {
        await { nodes().any { text(it) == label } }
        val row = generateSequence(nodes().first { text(it) == label }) { it.parent }.first { it.isLongClickable }
        assertTrue(row.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)); SystemClock.sleep(200)
    }
    private fun await(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < until) { if (condition()) return; SystemClock.sleep(100) }
        screenshot("chat-selection-failure")
        error("Chat selection did not reach the expected state")
    }
    private fun screenshot(name: String) {
        if (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() != context.packageName) return
        SystemClock.sleep(500)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
}
