package com.androidharness.app.ui.chat

import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.data.*
import com.androidharness.app.ui.AppNav
import com.androidharness.app.ui.theme.HarnessTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ChatNavigationDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun switchingChatsAndSectionsDoesNotStackHistoryButSettingsStillGoesBack() = withChats { _, beta, _ ->
        val rootBacks = AtomicInteger()
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // Observe the activity's normal root Back fallback without ending the test window.
                activity.onBackPressedDispatcher.addCallback(activity, object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() { rootBacks.incrementAndGet() }
                })
                activity.setContent { HarnessTheme { AppNav(c) } }
            }
            await("Alpha answer")
            tap("Open navigation"); tap("Open Navigation beta"); await("Beta answer")
            tap("Open navigation"); tap("Code"); await("New Code chat"); tap("Collapse sidebar")
            await("Code answer")
            tap("Open navigation"); tap("Chat"); await("New chat"); tap("Collapse sidebar")
            await("Beta answer")
            tap("Open navigation"); tap("Open Navigation alpha"); await("Alpha answer")
            tap("Open navigation"); tap("Open Navigation beta"); await("Beta answer")
            back()
            assertEquals("Back from Chat reaches the activity, not an older conversation", 1, rootBacks.get())
            await("Beta answer")
            assertEquals(beta, runBlocking { c.settings.settings.first().lastActiveSessionId })

            // The drawer and settings pages keep their own normal Back behavior.
            tap("Open navigation"); await("Collapse sidebar"); back(); await("Beta answer")
            assertEquals(1, rootBacks.get())
            tap("Open navigation"); tap("Settings"); await("Chat settings")
            tap("Coding"); tap("Code conversation & commands"); await("Show live code")
            back(); await("Sub-Agent settings"); assertEquals(1, rootBacks.get())
            back(); await("Chat settings"); assertEquals(1, rootBacks.get())
            back(); await("Beta answer"); assertEquals(1, rootBacks.get())

            // A fresh draft and a notification/agent deep link are also peer chats.
            tap("Open navigation"); tap("New chat"); await("What is on your mind?")
            back(); assertEquals(2, rootBacks.get())
            runBlocking { c.pendingSessionId.emit(beta) }
            await("Beta answer"); back(); assertEquals(3, rootBacks.get())
            screenshot("chat-root-back")
        }
    }

    @Test fun chatMenuPinsArchivesUndoesAndRestoresFromTheSidebar() = withChats { alpha, _, _ ->
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { HarnessTheme { AppNav(c) } } }
            await("Alpha answer"); tap("More options"); await("Pin"); await("Archive")
            screenshot("chat-pin-archive-menu")
            tap("Pin"); awaitPreference { alpha in it.pinnedSessions }
            tap("Open navigation"); await("Pinned"); await("Open Navigation alpha")
            screenshot("chat-pinned-sidebar")
            tap("Actions for Navigation alpha"); tap("Unpin")
            awaitPreference { alpha !in it.pinnedSessions }
            tap("Collapse sidebar"); tap("More options"); tap("Archive")
            awaitPreference { alpha in it.archivedSessions }
            tap("Undo"); awaitPreference { alpha !in it.archivedSessions }
            tap("More options"); tap("Archive"); awaitPreference { alpha in it.archivedSessions }
            assertTrue(runBlocking { SettingsRepository(c.appContext).settings.first().archivedSessions.contains(alpha) })
            tap("Open navigation"); await("Archived chats")
            assertNull("Archived conversations leave the active list", find("Open Navigation alpha"))
            tap("Archived chats"); await("Open Navigation alpha")
            screenshot("chat-archived-sidebar")
            tap("Actions for Navigation alpha"); tap("Unarchive")
            awaitPreference { alpha !in it.archivedSessions }
            tap("Back to active Chats"); await("Open Navigation alpha")
            tap("Collapse sidebar"); await("Alpha answer")
        }
    }

    private fun withChats(block: (String, String, String) -> Unit): Unit = runBlocking {
        val saved = c.settings.settings.first()
        val before = c.sessions.sessions.first().map { it.id }.toSet()
        val project = c.workspace.currentProjectOnce()
        suspend fun chat(name: String, answer: String, kind: ConversationKind): String {
            val id = c.sessions.createSession(name, project.id, ConversationConfig(kind))
            c.sessions.addMessage(id, ChatMessage(Role.USER, "Question for $name"))
            c.sessions.addMessage(id, ChatMessage(Role.ASSISTANT, answer))
            return id
        }
        try {
            val alpha = chat("Navigation alpha", "Alpha answer", ConversationKind.CHAT)
            val beta = chat("Navigation beta", "Beta answer", ConversationKind.CHAT)
            val code = chat("Navigation code", "Code answer", ConversationKind.CODE)
            c.settings.setOnboardingDone(true)
            c.settings.setStartScreen(StartScreen.CHAT)
            c.settings.setChatStart(ChatStartMode.LAST_CHAT)
            c.settings.setCodeStart(CodeStartMode.LAST_CODE)
            c.settings.setResumeLastChat(true)
            c.settings.setWorkspaceLayout(WorkspaceLayoutMode.PHONE)
            c.settings.setLastActiveSessionId(alpha)
            block(alpha, beta, code)
        } finally {
            c.sessions.sessions.first().filter { it.id !in before }.forEach { c.sessions.deleteSession(it) }
            c.settings.restorePortable(saved)
            c.settings.setOnboardingDone(saved.onboardingDone)
            c.settings.setLastActiveSessionId(saved.lastActiveSessionId)
            c.settings.setLastConversationKind(saved.lastConversationKind)
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isVisibleToUser) add(node)
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun find(text: String) = nodes().firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }
    private fun await(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            find(text)?.let { return it }
            SystemClock.sleep(100)
        }
        screenshot("missing-${text.take(20)}")
        error("Missing UI text: $text")
    }
    private fun tap(text: String) {
        var node = await(text)
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue("Click $text", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync(); SystemClock.sleep(350)
    }
    private fun back() {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        instrumentation.waitForIdleSync(); SystemClock.sleep(500)
    }
    private fun awaitPreference(predicate: (AppSettings) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (runBlocking { predicate(c.settings.settings.first()) }) return
            SystemClock.sleep(100)
        }
        error("Setting did not update")
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val directory = File(c.appContext.getExternalFilesDir(null), "chat-navigation-evidence").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
