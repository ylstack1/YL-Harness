package com.androidharness.app.ui.settings

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.MainActivity
import com.androidharness.app.chatgpt.ChatGptAccount
import com.androidharness.app.chatgpt.ChatGptAccountState
import com.androidharness.app.llm.ModelEntry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ChatGptAccountsDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun accountControlsSelectAndManageAtPhoneWidth() {
        val ui = ChatGptAccountState(accounts = listOf(
            ChatGptAccount("chatgpt:primary", "alex@example.test · 1", true, listOf(ModelEntry("sol", displayName = "GPT Sol"))),
            ChatGptAccount("chatgpt:backup", "backup@example.test · 2", true, listOf(ModelEntry("luna", displayName = "GPT Luna"))),
            ChatGptAccount("chatgpt:expired", "other@example.test · 3", false, emptyList()),
        ))
        var selected by mutableStateOf("chatgpt:primary")
        var model by mutableStateOf("sol")
        val manage = mutableListOf<String>()
        val activity = instrumentation.startActivitySync(Intent(c.appContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            instrumentation.runOnMainSync { activity.setContent {
                val dark = InstrumentationRegistry.getArguments().getString("dark") == "true"
                com.androidharness.app.ui.theme.HarnessTheme(themeMode = if (dark)
                    com.androidharness.app.data.ThemeMode.DARK else com.androidharness.app.data.ThemeMode.LIGHT) {
                    Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                        .verticalScroll(rememberScrollState()).padding(16.dp)) {
                        Text("Connected accounts", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(16.dp))
                        ChatGptAccountsPanel(ui, selected, model, null, null, onAdd = { manage += "add" },
                            onCancelSignIn = {},
                            onSelect = { selected = it.providerId; model = it.models.first().id },
                            onRefresh = { manage += "refresh" }, onCheckModels = { manage += "check" },
                            onReconnect = { manage += "reconnect" }, onSignOut = { manage += "signout" }, onUsage = { manage += "usage" })
                    } }
                }
            } }
            await("backup@example.test · 2")
            screenshot("chatgpt-accounts-default")
            scrollTo("backup@example.test · 2"); tap("backup@example.test · 2")
            instrumentation.waitForIdleSync()
            assertEquals("chatgpt:backup", selected); assertEquals("luna", model)
            scrollTo("Manage backup@example.test · 2"); tap("Manage backup@example.test · 2")
            await("Refresh models"); screenshot("chatgpt-accounts-menu")
            tap("Refresh models"); assertEquals("refresh", manage.last())
            scrollTo("Limits & usage in ChatGPT"); tap("Limits & usage in ChatGPT")
            assertEquals("usage", manage.last())
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun findAll(node: AccessibilityNodeInfo?, test: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        if (node == null) return emptyList()
        return (if (node.isVisibleToUser && test(node)) listOf(node) else emptyList()) + (0 until node.childCount).flatMap { findAll(node.getChild(it), test) }
    }
    private fun freshRoot(): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.clearCache()
        return instrumentation.uiAutomation.rootInActiveWindow
    }
    private fun await(text: String, last: Boolean = false): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < end) {
            val nodes = findAll(freshRoot()) { it.text?.toString() == text || it.contentDescription?.toString() == text }
            (if (last) nodes.lastOrNull() else nodes.firstOrNull())?.let { return it }
            SystemClock.sleep(100)
        }
        error("Missing control: $text")
    }
    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        val bounds = android.graphics.Rect(); target?.getBoundsInScreen(bounds)
        check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
            screenshot("chatgpt-accounts-tap-failure")
            "Control cannot be tapped: text=${node.text}; bounds=$bounds; enabled=${target?.isEnabled}; visible=${target?.isVisibleToUser}; actions=${target?.actionList}"
        }
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(100, 3000)
    }
    private fun tap(text: String, last: Boolean = false) = click(await(text, last))
    private fun scrollTo(text: String) {
        repeat(12) {
            val root = freshRoot()
            val node = findAll(root) { it.text?.toString() == text || it.contentDescription?.toString() == text }.firstOrNull()
            if (node != null) {
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                instrumentation.waitForIdleSync(); SystemClock.sleep(400)
                return
            }
            findAll(root) { it.isScrollable }.firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(250)
        }
        repeat(12) {
            val root = freshRoot()
            if (findAll(root) { it.text?.toString() == text || it.contentDescription?.toString() == text }.isNotEmpty()) return
            findAll(root) { it.isScrollable }.firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            SystemClock.sleep(250)
        }
        await(text)
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync(); SystemClock.sleep(250)
        val folder = File(c.appContext.getExternalFilesDir(null), "chatgpt-account-screenshots").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
}
