package com.androidharness.app.ui.settings

import android.app.Activity
import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.IntentFilter
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.*
import com.androidharness.app.agent.*
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.data.*
import com.androidharness.app.llm.*
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.theme.HarnessTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class BackgroundSettingsDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun notificationSwitchClearsAlertsBlocksNewOnesAndKeepsBackgroundIndependent(): Unit = runBlocking {
        val saved = c.settings.settings.first()
        val manager = c.appContext.getSystemService(NotificationManager::class.java)
        val monitor = instrumentation.addMonitor(IntentFilter(Settings.ACTION_APP_NOTIFICATION_SETTINGS),
            Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
        try {
            c.settings.setRunInBackground(true)
            c.settings.setNotificationsEnabled(true)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { host -> host.setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                        HarnessTheme(dynamicColor = false) { SettingsScreen(c, {}) }
                    }
                } }
                c.pendingSettingsScroll.value = "background"
                awaitText("Run in background"); awaitText("Notifications")
                waitUntil { c.runtimePolicy.state.value.notificationsEnabled }
                manager.createNotificationChannel(NotificationChannel("background-fixture", "Fixture", NotificationManager.IMPORTANCE_DEFAULT))
                AppNotifications.post(c.appContext, 19001, Notification.Builder(c.appContext, "background-fixture")
                    .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Fixture alert").build())
                waitUntil { manager.activeNotifications.any { it.id == 19001 } }
                tap("Notifications")
                waitUntil { !c.runtimePolicy.state.value.notificationsEnabled }
                assertFalse(SettingsRepository(c.appContext).settings.first().notificationsEnabled)
                assertTrue(c.settings.settings.first().runInBackground)
                waitUntil { monitor.hits > 0 }
                waitUntil { manager.activeNotifications.none { it.id == 19001 } }
                RunResultNotifications.post(c.appContext, RunResultNotification("blocked-fixture", "Fixture", true, "Must stay hidden"))
                AppNotifications.post(c.appContext, 19002, Notification.Builder(c.appContext, "background-fixture")
                    .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Must stay hidden").build(), "approval-fixture")
                delay(250)
                assertTrue(manager.activeNotifications.none { it.id == 19002 || it.id == RunResultNotifications.notificationId("blocked-fixture") })
                val portable = SettingsBackupPolicy.exportSettings(c.settings.settings.first(), emptySet())
                c.settings.setNotificationsEnabled(true)
                c.settings.restorePortable(portable)
                assertFalse(c.settings.settings.first().notificationsEnabled)
                assertTrue(c.settings.settings.first().runInBackground)
                tap("Run in background")
                waitUntil { !c.runtimePolicy.state.value.runInBackground }
                assertFalse(c.settings.settings.first().notificationsEnabled)
                tap("Notifications")
                waitUntil { c.runtimePolicy.state.value.notificationsEnabled }
                assertFalse(c.settings.settings.first().runInBackground)
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                File(c.appContext.getExternalFilesDir(null), "background-settings-large.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            manager.cancel(19001); manager.cancel("approval-fixture", 19002)
            c.settings.restorePortable(saved)
        }
    }

    @Test fun turningBackgroundOffWhileHiddenStopsMainAndChildAndDoesNotRestartThem(): Unit = runBlocking {
        exerciseBackgroundPolicy(disableWhileHidden = true)
    }

    @Test fun foregroundOnlyAgentsSurviveActivityReplacementButStopWhenTheAppLeaves(): Unit = runBlocking {
        exerciseBackgroundPolicy(disableWhileHidden = false)
    }

    private suspend fun exerciseBackgroundPolicy(disableWhileHidden: Boolean) {
        val saved = c.settings.settings.first()
        val sid = c.sessions.createSession("Background policy fixture", conversation = ConversationConfig(ConversationKind.CHAT))
        val server = StreamingFixture()
        val config = ProviderConfig("background-fixture", "Fixture", ProviderType.OPENAI_COMPAT, server.url, "fixture")
        suspend fun start() = c.runManager.startRun(sid, "Write a reply", emptyList(), config, "fixture",
            PermissionMode.FULL_AUTO, AgentMode.ACT, 4000, 100000, ThinkingLevel.OFF, 2,
            notifyOnFinish = false)
        try {
            c.settings.setRunInBackground(disableWhileHidden)
            c.settings.setNotificationsEnabled(false)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                waitUntil { c.runtimePolicy.state.value.visible }
                start()
                withTimeout(20000) { c.runManager.live(sid).first { it.streamingText?.contains("Fixture") == true } }
                c.runManager.subagents.background(SubagentRun(sid, c.runManager.live(sid).value.turnId!!,
                    ToolCallData("policy-child-$sid", "task", "{}"), "Policy child", "Wait", "fixture", "fixture", true)) {
                    awaitCancellation()
                }
                withTimeout(5000) { c.runManager.subagents.runs.first { rows -> rows.any { it.sessionId == sid && it.status == SubagentStatus.RUNNING } } }
                if (!disableWhileHidden) {
                    scenario.recreate()
                    delay(1000)
                    assertTrue("Activity replacement must not stop an agent", c.runManager.isRunning(sid))
                }
                scenario.moveToState(Lifecycle.State.CREATED)
                waitUntil { !c.runtimePolicy.state.value.visible }
                if (disableWhileHidden) {
                    delay(1000)
                    assertTrue("Background remains allowed until the switch is off", c.runManager.isRunning(sid))
                    c.settings.setRunInBackground(false)
                }
                withTimeout(15000) { c.runManager.live(sid).first { !it.running } }
                withTimeout(5000) { c.runManager.subagents.runs.first { rows -> rows.none { it.sessionId == sid && it.status.active } } }
                assertTrue(c.runManager.live(sid).value.cancelled)
                val before = c.sessions.messages(sid).size
                val failure = runCatching { start() }.exceptionOrNull()
                assertTrue("Hidden foreground-only runs must be refused", failure is IllegalStateException)
                assertEquals(before, c.sessions.messages(sid).size)
                scenario.moveToState(Lifecycle.State.RESUMED)
                delay(1000)
                assertFalse("Opening the app must not silently restart a stopped task", c.runManager.isRunning(sid))
                assertTrue(c.sessions.messages(sid).any { it.text == "Write a reply" })
                assertEquals("idle", c.runManager.controls.flow(sid).value.status)
            }
        } finally {
            c.runManager.stopAndJoin(sid)
            server.close()
            c.settings.restorePortable(saved)
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue("Timed out waiting for background/notification state", condition())
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        instrumentation.uiAutomation.clearCache()
        val list = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) { list += node; for (i in 0 until node.childCount) node.getChild(i)?.let(::visit) }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        return list
    }
    private fun awaitText(text: String): AccessibilityNodeInfo {
        waitUntil { nodes().any { it.isVisibleToUser && it.text?.toString() == text } }
        return nodes().first { it.isVisibleToUser && it.text?.toString() == text }
    }
    private fun tap(text: String) {
        var node: AccessibilityNodeInfo? = awaitText(text)
        while (node != null && !node.isClickable) node = node.parent
        assertTrue(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }

    /** Local streaming provider only; never contacts a real provider or consumes account credits. */
    private class StreamingFixture : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val closed = AtomicBoolean(false)
        private var socket: Socket? = null
        val url = "http://127.0.0.1:${server.localPort}/v1"
        private val worker = thread(isDaemon = true, name = "background-stream-fixture") {
            runCatching {
                server.accept().use { client ->
                    socket = client
                    val input = client.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) Unit
                    val output = client.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                    while (!closed.get()) {
                        output.write("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Fixture reply. \"}}]}\n\n".toByteArray())
                        output.flush()
                        Thread.sleep(100)
                    }
                }
            }
        }
        override fun close() { closed.set(true); socket?.close(); server.close(); worker.join(1000) }
    }
}
