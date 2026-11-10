package com.androidharness.app.ui.transfer

import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.androidharness.app.data.ThemeMode
import com.androidharness.app.data.transfer.*
import com.androidharness.app.data.update.*
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.settings.SettingsScreen
import com.androidharness.app.ui.settings.UpdateInstallationChoice
import com.androidharness.app.ui.theme.HarnessTheme
import com.androidharness.app.ui.update.UpdateDialog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TransferUiDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container
    private fun openUi(scale: Float = 1.5f, content: @Composable () -> Unit): ActivityScenario<ChatListTestActivity> =
        ActivityScenario.launch(ChatListTestActivity::class.java).also { scenario -> scenario.onActivity { it.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                HarnessTheme(ThemeMode.DARK, dynamicColor = false) { Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { content() }
                } }
            }
        } } }
    @Test fun syncAndServerHaveSeparateSearchableSettingsPages() {
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { HarnessTheme(ThemeMode.DARK, dynamicColor = false) { SettingsScreen(c, {}) } } }
            await { find("Search settings") != null }
            edit("Search settings", "FTP")
            await { find("FTP / SFTP sync") != null && find("FTP server") != null }
            tap("FTP / SFTP sync")
            scrollTo("Preview upload"); assertNull(find("Start FTP server"))
            tap("Back")
            await { find("FTP / SFTP sync") != null && find("FTP server") != null }
            tap("FTP server")
            scrollTo("Start FTP server"); assertNull(find("Preview upload"))
        }
    }
    @Test fun serverStartsReadOnlyShowsItsLoginAndStopsAtLargeText() = runBlocking<Unit> {
        val directory = File(c.appContext.cacheDir, "ftp-ui-${UUID.randomUUID()}").apply { mkdirs() }
        val project = c.workspace.addShellProject(directory.path, activate = false)
        val first = c.workspace.projects.first().first()
        val port = ServerSocket(0).use { it.localPort }
        try {
            openUi { FtpServerSection(c) }.use {
                await { find(first.name) != null }
                if (first.id != project.id) { tap(first.name); tap(project.name) }
                edit("Port", "$port"); edit("Username", "fixture"); edit("Password", "fixture-password")
                scrollTo("Read only")
                assertTrue(ancestors(requireNotNull(find("Read only"))).any { it.isChecked })
                assertFalse(ancestors(requireNotNull(find("Allow connections from the local network"))).any { it.isChecked })
                tap("Start FTP server")
                await { c.ftpShare.state.value.running || c.ftpShare.state.value.error != null }
                assertTrue(c.ftpShare.state.value.error, c.ftpShare.state.value.running)
                scrollTo("Stop FTP server"); assertOnScreen("Stop FTP server")
                tap("Show password")
                assertEquals("fixture-password", c.ftpShare.passwordForActiveShare())
                tap("Stop FTP server")
                await { !c.ftpShare.state.value.running }
                scrollTo("Start FTP server")
            }
        } finally { c.ftpShare.stop(); c.workspace.deleteProject(project); directory.deleteRecursively() }
    }
    @Test fun ftpConnectionFormAndPreviewRequireExplicitReplacementApproval() = runBlocking<Unit> {
        val local = File(c.appContext.cacheDir, "sync-ui-${UUID.randomUUID()}").apply { mkdirs() }
        val remote = File(c.appContext.cacheDir, "sync-ui-remote-${UUID.randomUUID()}").apply { mkdirs() }
        val project = c.workspace.addShellProject(local.path, activate = false)
        val first = c.workspace.projects.first().first()
        val server = FtpShareServer(LocalTransferFiles(remote), "fixture", "fixture-password", false)
        var id: String? = null
        try {
            File(local, "hello.txt").writeText("new"); File(local, "created.txt").writeText("created"); File(remote, "hello.txt").writeText("old")
            server.start(0)
            c.sync.cancel()
            openUi { SyncSettingsSection(c) }.use {
                await { find("Add connection") != null }
                if (first.id != project.id) { tap(first.name); tap(project.name) }
                tap("Add connection"); tap("FTP")
                edit("Connection name", "UI FTP fixture"); edit("Host", "127.0.0.1"); edit("Port", "${server.port}")
                edit("Username", "fixture"); edit("Password", "fixture-password"); edit("Remote folder", "/")
                tap("Save")
                await { runBlocking { withContext(Dispatchers.IO) { c.sync.profiles.all().any { it.name == "UI FTP fixture" } } } }
                id = c.sync.profiles.all().single { it.name == "UI FTP fixture" }.id
                tap("Preview upload")
                await { c.sync.state.value.plan != null || c.sync.state.value.message != null && !c.sync.state.value.working }
                assertNotNull(c.sync.state.value.message, c.sync.state.value.plan)
                scrollTo("Replace 1 existing files")
                assertFalse(ancestors(requireNotNull(find("Replace 1 existing files"))).any { it.isChecked })
                tap("Apply changes")
                await { !c.sync.state.value.working && c.sync.state.value.plan == null }
                assertEquals("old", File(remote, "hello.txt").readText()); assertEquals("created", File(remote, "created.txt").readText())
                tap("Preview upload"); await { c.sync.state.value.plan != null }
                tap("Replace 1 existing files"); tap("Apply changes")
                await { !c.sync.state.value.working && c.sync.state.value.plan == null }
                assertEquals("new", File(remote, "hello.txt").readText())
                scrollTo("Delete"); tap("Delete"); await { find("Remove this connection?") != null }; tap("Delete")
                await { c.sync.profiles.all().none { p -> p.id == id } }
            }
        } finally { c.sync.cancel(); id?.let { c.sync.profiles.remove(it) }; server.close(); c.workspace.deleteProject(project); local.deleteRecursively(); remote.deleteRecursively() }
    }
    @Test fun updateInstallationMethodPersistsAndRootProgressIsNamedCorrectly() {
        val saved = c.updates.installMethod.value
        try {
            openUi { UpdateInstallationChoice(c) }.use {
                await { find("Installation method") != null }
                fun label(method: UpdateInstallMethod) = when (method) { UpdateInstallMethod.AUTO -> "Automatic"; UpdateInstallMethod.ROOT -> "Root"; UpdateInstallMethod.SHIZUKU -> "Shizuku"; UpdateInstallMethod.SYSTEM -> "Android installer" }
                for (method in listOf(UpdateInstallMethod.SHIZUKU, UpdateInstallMethod.ROOT, UpdateInstallMethod.SYSTEM, UpdateInstallMethod.AUTO)) {
                    tap(label(c.updates.installMethod.value)); tap(label(method))
                    assertEquals(method, UpdateManager(c.appContext, c.deviceAccess).installMethod.value)
                }
            }
            val release = UpdateManager.LatestRelease("3", "3", "", "https://github.com/Sanuu7/AndroidHarness/releases", null, null, 0)
            val step = mutableStateOf<UpdateManager.Step>(UpdateManager.Step.Installing(release, false, viaRoot = true))
            openUi(scale = 1f) { UpdateDialog(step.value, {}, {}) }.use { scenario ->
                await { find("Installing via Root") != null }
                assertNull(find("Opening system installer"))
                scenario.onActivity { step.value = UpdateManager.Step.Done(false, viaRoot = true) }
                await { find("Update installed via Root.") != null }
            }
        } finally { c.updates.setInstallMethod(saved) }
    }
    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo) { add(node); repeat(node.childCount) { node.getChild(it)?.let(::visit) } }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun find(label: String) = nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label) }
    private fun ancestors(node: AccessibilityNodeInfo) = generateSequence(node) { it.parent }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun tap(label: String) {
        scrollTo(label)
        val clickable = ancestors(requireNotNull(find(label))).first { it.isClickable }
        assertTrue(clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)); SystemClock.sleep(350)
    }
    private fun edit(label: String, value: String) {
        scrollTo(label)
        val input = ancestors(requireNotNull(find(label))).first { it.isEditable }
        assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
        SystemClock.sleep(150)
    }
    private fun scrollTo(label: String) {
        for (action in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) repeat(30) {
            if (find(label) != null) return
            nodes().firstOrNull { it.isScrollable }?.performAction(action); SystemClock.sleep(180)
        }
        error("Control unavailable: $label")
    }
    private fun assertOnScreen(label: String) {
        val rect = bounds(requireNotNull(find(label))); val display = c.appContext.resources.displayMetrics
        assertTrue(rect.left >= 0 && rect.top >= 0 && rect.right <= display.widthPixels && rect.bottom <= display.heightPixels)
    }
    private fun await(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < until) { if (condition()) return; SystemClock.sleep(100) }
        error("Transfer UI condition timed out")
    }
}
