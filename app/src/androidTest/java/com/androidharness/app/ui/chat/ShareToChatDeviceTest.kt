package com.androidharness.app.ui.chat

import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.MainActivity
import com.androidharness.app.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ShareToChatDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun parsesSingleMultipleClipDataAndRejectsLocalPaths() {
        val one = Uri.parse("content://fixture/one")
        val two = Uri.parse("content://fixture/two")
        val single = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "https://x.com/example/status/123")
            .putExtra(Intent.EXTRA_SUBJECT, "A post")
        assertEquals("A post\n\nhttps://x.com/example/status/123", PendingShare.fromIntent(single)!!.text)
        val multiple = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(one, two))
        multiple.clipData = ClipData.newRawUri("One", one).apply { addItem(ClipData.Item(two)) }
        assertEquals(listOf(one, two), PendingShare.fromIntent(multiple)!!.streams)
        val clip = Intent(Intent.ACTION_SEND).setType("application/pdf").apply {
            clipData = ClipData.newRawUri("PDF", one).apply { addItem(ClipData.Item("Shared text")) }
        }
        assertEquals(listOf(one), PendingShare.fromIntent(clip)!!.streams)
        assertEquals("Shared text", PendingShare.fromIntent(clip)!!.text)
        assertNull(PendingShare.fromIntent(Intent(Intent.ACTION_SEND)))
        assertNull(PendingShare.fromIntent(Intent(Intent.ACTION_VIEW, one)))
        try {
            PendingShare.fromIntent(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///private.txt")))
            fail("Local paths must be refused")
        } catch (_: IllegalArgumentException) { }
        val targeted = PendingShare(text = "A share", targetSessionId = "chat")
        assertFalse(targeted.isFor("chat", ConversationConfig(ConversationKind.CODE)))
        assertFalse(targeted.isFor("other", ConversationConfig(ConversationKind.CHAT)))
        assertTrue(targeted.isFor("chat", ConversationConfig(ConversationKind.CHAT)))
    }

    @Test fun shareTargetResolvesTextImagesDocumentsAndMultipleItems() {
        for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            for (mime in listOf("text/plain", "image/png", "application/pdf", "*/*")) {
                val targets = c.appContext.packageManager.queryIntentActivities(
                    Intent(action).setType(mime).setPackage(c.appContext.packageName), 0)
                assertTrue("$action $mime", targets.any { it.activityInfo.name.endsWith(".ShareToChat") })
                assertFalse(targets.any { it.activityInfo.name.endsWith(".MainActivity") })
            }
        }
    }

    @Test fun warmShareFromCodeOpensOnlyChosenChatAndPreservesItsDraft(): Unit = runBlocking {
        val saved = c.settings.settings.first()
        val code = c.sessions.createSession("Share Code fixture")
        val chat = c.sessions.createSession("Share Chat fixture", conversation = ConversationConfig(ConversationKind.CHAT))
        c.sessions.saveConversation(chat, ConversationConfig(ConversationKind.CHAT, draftText = "Existing draft"))
        try {
            prepare(code)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                await("Workspace files")
                share(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://x.com/example/status/123"))
                await("Share to Chat")
                tap("New Chat")
                assertNull(find("Share Code fixture"))
                searchChats("Share Chat fixture")
                tapDestination("Share Chat fixture")
                tap("Open in Chat")
                await("Share Chat fixture")
                awaitContains("Existing draft\n\nhttps://x.com/example/status/123")
                assertNull(find("Workspace files"))
                share(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Second shared item"))
                await("Share to Chat")
                tap("New Chat")
                searchChats("Share Chat fixture")
                tapDestination("Share Chat fixture")
                tap("Open in Chat")
                awaitContains("Existing draft\n\nhttps://x.com/example/status/123\n\nSecond shared item")
                scenario.recreate()
                awaitContains("Existing draft\n\nhttps://x.com/example/status/123\n\nSecond shared item")
                assertNull(find("Share to Chat"))
                assertTrue(c.sessions.messages(chat).isEmpty())
                assertTrue(c.sessions.messages(code).isEmpty())
                screenshot("existing-chat-share")
            }
        } finally {
            c.pendingShare.value = null
            for (id in listOf(code, chat)) c.sessions.session(id)?.let { c.sessions.deleteSession(it) }
            restore(saved)
        }
    }

    @Test fun coldShareImportsMixedAttachmentsIntoNewChatWithoutSending(): Unit = runBlocking {
        val saved = c.settings.settings.first()
        val before = c.sessions.sessions.first().map { it.id }.toSet()
        val root = File(c.appContext.cacheDir, "shared/share-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            prepare(null)
            val image = File(root, "shared-photo.png").apply {
                Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).let { bitmap ->
                    outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                }
            }
            val text = File(root, "shared-note.txt").apply { writeText("Shared readable document") }
            val pdf = File(root, "shared-document.pdf").apply {
                writeText("%PDF-1.4\n1 0 obj\n<< /Length 33 >>\nstream\nBT (Shared PDF text) Tj ET\nendstream\nendobj\n%%EOF")
            }
            val unsupported = File(root, "shared-unsupported.bin").apply { writeBytes(byteArrayOf(0, 1, 2)) }
            val uris = arrayListOf(image, text, pdf, unsupported).map {
                FileProvider.getUriForFile(c.appContext, c.appContext.packageName + ".update", it)
            }
            val intent = Intent(c.appContext, MainActivity::class.java).setAction(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                .putExtra(Intent.EXTRA_TEXT, "Shared mixed items")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newRawUri("Attachments", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                await("Share to Chat")
                scenario.recreate()
                await("Share to Chat")
                tap("Open in Chat")
                awaitContains("Shared mixed items")
                await("shared-unsupported.bin")
                awaitContains("Unsupported in Chat")
                awaitAttachment("shared-note.txt")
                awaitAttachment("shared-document.pdf")
                awaitContains("1 page")
                assertNull(find("Workspace files"))
                val session = c.settings.settings.first { it.lastActiveSessionId != null }.lastActiveSessionId!!
                assertTrue(ConversationConfig.decode(c.sessions.session(session)!!.conversationJson).isChat)
                assertTrue(c.sessions.messages(session).isEmpty())
                assertNull(c.pendingShare.value)
                screenshot("mixed-attachments-share")
            }
        } finally {
            c.pendingShare.value = null
            c.sessions.sessions.first().filter { it.id !in before }.forEach { c.sessions.deleteSession(it) }
            root.deleteRecursively()
            restore(saved)
        }
    }

    @Test fun largeTextShareActionsAndCancellationStayReachable(): Unit = runBlocking {
        val saved = c.settings.settings.first()
        val before = c.sessions.sessions.first().map { it.id }.toSet()
        try {
            prepare(null)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 1.5f)) {
                        com.androidharness.app.ui.theme.HarnessTheme { com.androidharness.app.ui.AppNav(c) }
                    }
                } }
                await("Workspace files")
                c.pendingShare.value = PendingShare(text = "https://example.com/an-article")
                await("Share to Chat")
                tap("Open in Chat") // Scrolls the large-text sheet to its action.
                awaitContains("https://example.com/an-article")
                assertNull(find("Workspace files"))
                val beforeCancel = c.sessions.sessions.first().map { it.id }.toSet()
                c.pendingShare.value = PendingShare(text = "Cancelled share")
                await("Share to Chat")
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                val until = SystemClock.uptimeMillis() + 5000
                while (c.pendingShare.value != null && SystemClock.uptimeMillis() < until) SystemClock.sleep(100)
                assertNull(c.pendingShare.value)
                assertNull(find("Share to Chat"))
                assertEquals(beforeCancel, c.sessions.sessions.first().map { it.id }.toSet())
                screenshot("large-text-share")
            }
        } finally {
            c.pendingShare.value = null
            c.sessions.sessions.first().filter { it.id !in before }.forEach { c.sessions.deleteSession(it) }
            restore(saved)
        }
    }

    private suspend fun prepare(code: String?) {
        c.pendingShare.value = null
        c.settings.setOnboardingDone(true)
        c.settings.setBiometricLockEnabled(false)
        c.settings.setWorkspaceLayout(WorkspaceLayoutMode.PHONE)
        c.settings.setStartScreen(StartScreen.CODE)
        c.settings.setResumeLastChat(code != null)
        c.settings.setLastActiveSessionId(code)
    }
    private suspend fun restore(saved: AppSettings) {
        c.settings.setOnboardingDone(saved.onboardingDone)
        c.settings.setBiometricLockEnabled(saved.biometricLockEnabled)
        c.settings.setWorkspaceLayout(saved.workspaceLayout)
        c.settings.setStartScreen(saved.startScreen)
        c.settings.setResumeLastChat(saved.resumeLastChat)
        c.settings.setLastActiveSessionId(saved.lastActiveSessionId)
        c.settings.setLastConversationKind(saved.lastConversationKind)
    }
    private fun share(intent: Intent) {
        c.appContext.startActivity(intent.apply {
            component = ComponentName(c.appContext.packageName, "com.androidharness.app.ShareToChat")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo) { add(node); for (i in 0 until node.childCount) node.getChild(i)?.let(::visit) }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun find(text: String) = nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    private fun await(text: String): AccessibilityNodeInfo {
        val until = SystemClock.uptimeMillis() + 15_000
        do { find(text)?.let { return it }; SystemClock.sleep(100) } while (SystemClock.uptimeMillis() < until)
        screenshot("failure")
        throw AssertionError("Missing $text: ${nodes().mapNotNull { it.text }}")
    }
    private fun awaitAttachment(name: String) {
        repeat(12) {
            if (find(name) != null) return
            nodes().lastOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(250)
        }
        await(name)
    }
    private fun awaitContains(text: String) {
        val until = SystemClock.uptimeMillis() + 15_000
        do { if (nodes().any { it.text?.toString()?.contains(text) == true }) return; SystemClock.sleep(100) } while (SystemClock.uptimeMillis() < until)
        throw AssertionError("Missing draft $text: ${nodes().mapNotNull { it.text }}")
    }
    private fun searchChats(query: String) {
        await("Find a Chat")
        val field = nodes().first { it.isEditable }
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
        }))
        instrumentation.waitForIdleSync()
    }
    private fun tapDestination(title: String) {
        // The editable search text can equal the result label; choose the result row.
        await(title)
        var node = nodes().last { it.isVisibleToUser && !it.isEditable && it.text?.toString() == title }
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    private fun tap(text: String) {
        // A long multi-item share can put the final action below the first viewport.
        repeat(8) {
            if (find(text) != null) return@repeat
            nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(150)
        }
        var node = await(text)
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    private fun screenshot(name: String) {
        val folder = File(c.appContext.getExternalFilesDir(null), "share-to-chat").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
}
