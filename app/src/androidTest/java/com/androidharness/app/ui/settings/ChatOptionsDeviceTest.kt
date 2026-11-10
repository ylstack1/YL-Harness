package com.androidharness.app.ui.settings

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.data.*
import com.androidharness.app.ui.AppNav
import com.androidharness.app.ui.LocalDesktopLayout
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.theme.HarnessTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ChatOptionsDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun reorganizedSettingsKeepCodingBackNavigationAndSearchConnected(): Unit = runBlocking {
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                CompositionLocalProvider(LocalDesktopLayout provides false) { HarnessTheme { SettingsScreen(c, {}) } }
            } }
            tap("Coding")
            tap("Code conversation & commands")
            await("Show live code")
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            await("Sub-Agent settings")
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            await("Chat settings")
            val search = nodes().first { it.isEditable }
            assertTrue(search.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "telegram")
            }))
            tap("Telegram")
            await("Connect bot")
            c.pendingSettingsScroll.value = "agent"
            await("Default permission mode")
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            await("Connect bot")
        }
    }

    @Test fun wideSettingsKeepCodingNavigationReachable(): Unit = runBlocking {
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                CompositionLocalProvider(LocalDesktopLayout provides true) { HarnessTheme { SettingsScreen(c, {}) } }
            } }
            tap("Coding")
            tap("Code conversation & commands")
            await("Show live code")
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            await("Sub-Agent settings")
            screenshot("wide-settings-coding")
        }
    }

    @Test fun globalInstructionsCanBeEditedFromChatOptionsAndSurviveReloadAndRestore(): Unit = runBlocking {
        val saved = c.settings.settings.first()
        val instruction = "GLOBAL_SETTINGS_INSTRUCTION_613: keep answers concise."
        fun show(scenario: ActivityScenario<ChatListTestActivity>) = scenario.onActivity { host -> host.setContent {
            HarnessTheme(themeMode = ThemeMode.DARK, dynamicColor = false) { SettingsScreen(c, {}) }
        } }
        try {
            c.settings.setChatInstructions("")
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                show(scenario)
                tap("Chat settings"); tap("Global Chat instructions")
                await("Chat instructions")
                val editor = nodes().first { it.isEditable }
                assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, instruction)
                }))
                screenshot("global-chat-instructions-editor")
                tap("Save"); awaitPreference { it.chatInstructions == instruction }
            }
            assertEquals(instruction, SettingsRepository(c.appContext).settings.first().chatInstructions)
            val portable = SettingsBackupPolicy.exportSettings(c.settings.settings.first(), emptySet())
            c.settings.setChatInstructions("Changed after export")
            c.settings.restorePortable(portable)
            assertEquals(instruction, c.settings.settings.first().chatInstructions)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                show(scenario); tap("Chat settings"); await(instruction)
                screenshot("global-chat-instructions-settings")
            }
        } finally { c.settings.restorePortable(saved) }
    }

    @Test fun optionIsBelowLanguageAndRadioChoicePersistsWithLargeText(): Unit = runBlocking {
        val saved = c.settings.settings.first()
        val scale = mutableStateOf(1f)
        try {
            c.settings.setStartScreen(StartScreen.CODE)
            fun show(scenario: ActivityScenario<ChatListTestActivity>) = scenario.onActivity { activity -> activity.setContent {
                HarnessTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDesktopLayout provides false, LocalDensity provides Density(density.density, scale.value)) {
                        SettingsScreen(c, {})
                    }
                }
            } }
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                show(scenario)
                val accounts = android.graphics.Rect().also { await("Connected accounts").getBoundsInScreen(it) }
                val language = android.graphics.Rect().also { await("App language").getBoundsInScreen(it) }
                assertTrue("Connected accounts is above App language", accounts.bottom < language.top)
                val option = android.graphics.Rect().also { await("Chat settings").getBoundsInScreen(it) }
                assertTrue("Chat settings follows Language", option.top > language.bottom)
                screenshot("chat-option-settings-home")
                tap("Chat settings"); await("Start screen"); awaitSelected("Code")
                tap("Chat"); awaitPreference { it.startScreen == StartScreen.CHAT }; awaitSelected("Chat")
                scenario.onActivity { scale.value = 1.5f }
                await("Last selected"); screenshot("chat-option-large-dark")
                tap("Last selected"); awaitPreference { it.startScreen == StartScreen.LAST_SELECTED }
            }
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                show(scenario)
                tap("Chat settings"); await("Start screen"); awaitSelected("Last selected")
                screenshot("chat-option-persisted")
            }
        } finally { c.settings.setStartScreen(saved.startScreen) }
    }

    @Test fun startupChoiceAppliesOnTheNextLaunchAndCanRememberEitherSection(): Unit = runBlocking {
        val saved = c.settings.settings.first()
        try {
            prepare()
            for ((preference, previous, expected) in listOf(
                Triple(StartScreen.CODE, ConversationKind.CHAT, ConversationKind.CODE),
                Triple(StartScreen.CHAT, ConversationKind.CODE, ConversationKind.CHAT),
                Triple(StartScreen.LAST_SELECTED, ConversationKind.CHAT, ConversationKind.CHAT),
                Triple(StartScreen.LAST_SELECTED, ConversationKind.CODE, ConversationKind.CODE))) {
                c.settings.setStartScreen(preference); c.settings.setLastConversationKind(previous)
                ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                    scenario.onActivity { activity -> activity.setContent { HarnessTheme { AppNav(c) } } }
                    awaitSection(expected)
                    awaitPreference { it.lastConversationKind == expected }
                    if (preference == StartScreen.CODE) {
                        c.settings.setStartScreen(StartScreen.CHAT)
                        awaitSection(ConversationKind.CODE)
                    }
                }
            }
        } finally { restore(saved) }
    }

    @Test fun lastSelectedRemembersAnEmptyChatAfterClosingAndRelaunching(): Unit = runBlocking {
        val saved = c.settings.settings.first()
        try {
            prepare()
            c.settings.setStartScreen(StartScreen.LAST_SELECTED)
            c.settings.setLastConversationKind(ConversationKind.CODE)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { HarnessTheme { AppNav(c) } } }
                awaitSection(ConversationKind.CODE)
                tap("Open navigation"); tap("Chat"); awaitSelected("Chat")
                tap("New chat"); awaitSection(ConversationKind.CHAT)
                awaitPreference { it.lastConversationKind == ConversationKind.CHAT && it.lastActiveSessionId == null }
            }
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { HarnessTheme { AppNav(c) } } }
                awaitSection(ConversationKind.CHAT)
                screenshot("start-screen-last-selected-chat")
            }
        } finally { restore(saved) }
    }

    @Test fun savedChatResumesOnlyWhenItsStartChoiceAllowsIt(): Unit = runBlocking {
        val saved = c.settings.settings.first()
        val project = c.workspace.currentProjectOnce()
        val chat = c.sessions.createSession("Saved startup Chat", project.id, ConversationConfig(ConversationKind.CHAT))
        val code = c.sessions.createSession("Saved startup Code", project.id)
        try {
            prepare(); c.settings.setResumeLastChat(true); c.settings.setChatStart(ChatStartMode.LAST_CHAT)
            for ((preference, session, expected) in listOf(
                Triple(StartScreen.CHAT, chat, ConversationKind.CHAT),
                Triple(StartScreen.CODE, chat, ConversationKind.CODE),
                Triple(StartScreen.CHAT, code, ConversationKind.CHAT),
                Triple(StartScreen.CODE, code, ConversationKind.CODE))) {
                c.settings.setStartScreen(preference); c.settings.setLastActiveSessionId(session)
                ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                    scenario.onActivity { activity -> activity.setContent { HarnessTheme { AppNav(c) } } }
                    awaitSection(expected)
                    if (session == chat && expected == ConversationKind.CHAT) await("Saved startup Chat")
                    else if (session == code && expected == ConversationKind.CODE) await("Saved startup Code")
                    else { assertNull(find("Saved startup Chat")); assertNull(find("Saved startup Code")) }
                }
            }
            c.settings.setChatStart(ChatStartMode.NEW_CHAT)
            c.settings.setStartScreen(StartScreen.CHAT); c.settings.setLastActiveSessionId(chat)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { HarnessTheme { AppNav(c) } } }
                awaitSection(ConversationKind.CHAT)
                assertNull("New chat is the default", find("Saved startup Chat"))
            }
        } finally {
            for (id in listOf(chat, code)) c.sessions.session(id)?.let { c.sessions.deleteSession(it) }
            restore(saved)
        }
    }

    private suspend fun prepare() {
        c.settings.setOnboardingDone(true)
        c.settings.setWorkspaceLayout(WorkspaceLayoutMode.PHONE)
        c.settings.setResumeLastChat(false)
        c.settings.setChatStart(ChatStartMode.NEW_CHAT)
        c.settings.setLastActiveSessionId(null)
    }
    private suspend fun restore(saved: AppSettings) {
        c.settings.setOnboardingDone(saved.onboardingDone)
        c.settings.setWorkspaceLayout(saved.workspaceLayout)
        c.settings.setResumeLastChat(saved.resumeLastChat)
        c.settings.setChatStart(saved.chatStart)
        c.settings.setLastActiveSessionId(saved.lastActiveSessionId)
        c.settings.setStartScreen(saved.startScreen)
        c.settings.setLastConversationKind(saved.lastConversationKind)
    }
    private fun awaitSection(kind: ConversationKind) {
        if (kind == ConversationKind.CODE) await("Workspace files")
        else { await("Chat"); assertNull(find("Workspace files")) }
    }
    private suspend fun awaitPreference(predicate: (AppSettings) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        do { if (predicate(c.settings.settings.first())) return; SystemClock.sleep(100) } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Preference did not persist")
    }
    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo) { add(node); for (index in 0 until node.childCount) node.getChild(index)?.let(::visit) }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun find(text: String) = nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    private fun await(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 15_000
        var attempts = 0
        do {
            find(text)?.let { return it }
            if (++attempts % 4 == 0) nodes().lastOrNull { it.isScrollable }
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        screenshot("failure"); throw AssertionError("Expected $text; visible ${nodes().mapNotNull { it.text }}")
    }
    private fun selected(text: String): Boolean {
        var node = find(text)
        repeat(5) { if (node?.isSelected == true || node?.isChecked == true) return true; node = node?.parent }
        return false
    }
    private fun assertSelected(text: String) = assertTrue("Selected: $text", selected(text))
    private fun awaitSelected(text: String) {
        await(text)
        val deadline = SystemClock.uptimeMillis() + 10_000
        do { if (selected(text)) return; SystemClock.sleep(100) } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Not selected: $text")
    }
    private fun tap(text: String) {
        var node = await(text)
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500)
        val folder = File(c.appContext.getExternalFilesDir(null), "chat-options").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()!!.let { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
