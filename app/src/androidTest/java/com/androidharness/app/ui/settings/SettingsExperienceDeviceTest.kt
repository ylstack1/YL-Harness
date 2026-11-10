package com.androidharness.app.ui.settings

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.data.*
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.theme.HarnessTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SettingsExperienceDeviceTest {
    private val i get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (i.targetContext.applicationContext as HarnessApp).container

    @Test fun codingGroupsAndStartChoiceRemainUsableAtLargeText() {
        val settings = mutableStateOf(AppSettings(codeStart = CodeStartMode.NEW_CODE))
        var opened: SettingsPage? = null
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { host -> host.setContent { HarnessTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
                androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(androidx.compose.ui.platform.LocalDensity.current.density, 1.5f)) {
                        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            CodeStartOptionsSection(settings.value) { settings.value = settings.value.copy(codeStart = it) }
                            CodingSection { opened = it }
                        }
                    }
                }
            } } }
            await("Agent & workflow"); screenshot("coding-groups-dark")
            click(c.appContext.getString(com.androidharness.app.R.string.settings_start_code))
            click(c.appContext.getString(com.androidharness.app.R.string.settings_start_code_last))
            assertEquals(CodeStartMode.LAST_CODE, settings.value.codeStart)
            click(c.appContext.getString(SettingsPage.AGENT.title)); assertEquals(SettingsPage.AGENT, opened)
            click(c.appContext.getString(SettingsPage.ENVIRONMENT.title)); assertEquals(SettingsPage.ENVIRONMENT, opened)
        }
    }

    @Test fun terminalSettingsHideAdvancedControlsAndHelpUntilOpened() {
        c.pendingSettingsScroll.value = "coding"
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { host -> host.setContent { HarnessTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
                SettingsScreen(c, {})
            } } }
            click(c.appContext.getString(SettingsPage.ENVIRONMENT.title))
            await("Connections"); screenshot("terminal-connections-dark")
            assertNull(find("Access mode"))
            assertNull(find(c.appContext.getString(com.androidharness.app.R.string.wireless_adb_explain_steps)))
            click("How to connect")
            await(c.appContext.getString(com.androidharness.app.R.string.wireless_adb_explain_steps))
            click("How to connect")
            click("Advanced device access"); await("Access mode"); screenshot("terminal-advanced-expanded")
            click("Advanced device access")
            SystemClock.sleep(400); assertNull(find("Access mode"))
        }
    }

    private fun find(text: String, node: AccessibilityNodeInfo? = i.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
        node ?: return null
        if (node.isVisibleToUser && (node.text?.toString() == text || node.contentDescription?.toString() == text)) return node
        for (n in 0 until node.childCount) find(text, node.getChild(n))?.let { return it }
        return null
    }
    private fun await(text: String): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + 15_000
        var attempt = 0
        fun scroll(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.isScrollable) return node
            for (n in 0 until node.childCount) scroll(node.getChild(n))?.let { return it }
            return null
        }
        while (SystemClock.uptimeMillis() < end) {
            i.uiAutomation.clearCache()
            find(text)?.let { return it }
            if (++attempt > 3) scroll(i.uiAutomation.rootInActiveWindow)?.performAction(if ((attempt / 8) % 2 == 0) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            SystemClock.sleep(160)
        }
        screenshot("missing-setting"); error("Missing $text")
    }
    private fun click(text: String) {
        var node = await(text)
        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        SystemClock.sleep(150)
        node = await(text)
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); SystemClock.sleep(200)
    }
    private fun screenshot(name: String) {
        val dir = File(c.appContext.getExternalFilesDir(null), "settings-experience-qa").apply { mkdirs() }
        i.waitForIdleSync(); SystemClock.sleep(300)
        i.uiAutomation.takeScreenshot()?.let { bitmap -> File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
    }
}
