package com.androidharness.app.ui.files

import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.theme.HarnessTheme
import io.github.rosemoe.sora.widget.CodeEditor
import java.io.File
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorPersistenceDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container
    private val recoveryRoot get() = File(c.appContext.noBackupFilesDir, "editor-drafts")

    @Test fun rotationAndFreshEditorKeepDraftWrapEncodingAndConflictProtection(): Unit = runBlocking {
        val oldSettings = c.settings.settings.first()
        c.settings.setEditorWordWrap(false)
        c.settings.setEditorSearchOptions(false, false)
        val root = File(c.appContext.cacheDir, "editor-persist-${System.nanoTime()}").apply { mkdirs() }
        val file = File(root, "code.kt")
        val original = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + "saved\r\n".toByteArray()
        file.writeBytes(original)
        val project = c.workspace.addShellProject(root.path, activate = false)
        val sid = c.sessions.createProjectSession(project.id)
        val key = key(sid, file.name)
        val draft = "unsaved Ω\n"
        try {
            open(sid, file.name).use { scenario ->
                tapDesc("Edit")
                scenario.onActivity { editor(it.window.decorView)!!.setText(draft) }
                tapDesc("More"); tapText("Word wrap: off")
                awaitEditor(scenario) { it.text.toString() == draft && it.isWordwrap }
                tapDesc("More"); tapText("Find…")
                tapDesc("Match case"); tapDesc("Regex"); tapDesc("Close find")
                eventually { runBlocking { c.settings.settings.first().let { it.editorMatchCase && it.editorRegexSearch } } }
                eventually { readDisk(key)?.text == draft }
                scenario.recreate(); mount(scenario, sid, file.name)
                awaitEditor(scenario) { it.text.toString() == draft && it.isWordwrap && it.isEditable }
            }
            open(sid, file.name).use { scenario ->
                awaitEditor(scenario) { it.text.toString() == draft && it.isWordwrap }
                awaitText("Recovered unsaved draft · file unchanged")
                val reopenedSettings = com.androidharness.app.data.SettingsRepository(c.appContext).settings.first()
                assertTrue(reopenedSettings.editorMatchCase && reopenedSettings.editorRegexSearch)
                assertArrayEquals(original, file.readBytes())
                file.writeBytes(EditorFileCodec.encode(EditorFileCodec.decode(original)!!, "external\n"))
                tapDesc("Save"); awaitText("File changed on disk")
                assertEquals("external\n", EditorFileCodec.decode(file.readBytes())!!.text)
                tapText("Keep my edits")
                eventually { EditorFileCodec.decode(file.readBytes())!!.text == draft }
                eventually { readDisk(key) == null }
                assertTrue(file.readBytes().take(3) == original.take(3))
                assertTrue(file.readBytes().toString(Charsets.UTF_8).endsWith("\r\n"))
            }
            open(sid, file.name).use { scenario ->
                awaitEditor(scenario) { it.text.toString() == draft && it.isWordwrap }
                assertFalse(nodes().any { it.text?.toString() == "Recovered unsaved draft · file unchanged" })
            }
        } finally {
            clear(key)
            c.settings.setEditorWordWrap(oldSettings.editorWordWrap)
            c.settings.setEditorSearchOptions(oldSettings.editorMatchCase, oldSettings.editorRegexSearch)
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            c.workspace.deleteProject(project); root.deleteRecursively()
        }
    }

    @Test fun draftsStaySeparateAcrossFilesAndChatsAndExplicitDiscardClearsRecovery(): Unit = runBlocking {
        val root = File(c.appContext.cacheDir, "editor-isolation-${System.nanoTime()}").apply { mkdirs() }
        File(root, "a.kt").writeText("saved a\n"); File(root, "b.kt").writeText("saved b\n")
        val project = c.workspace.addShellProject(root.path, activate = false)
        val a = c.sessions.createProjectSession(project.id)
        val b = c.sessions.createProjectSession(project.id)
        val keys = listOf(key(a, "a.kt"), key(a, "b.kt"), key(b, "a.kt"))
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                for ((sid, path, text) in listOf(Triple(a, "a.kt", "draft a\n"), Triple(a, "b.kt", "draft b\n"), Triple(b, "a.kt", "other chat\n"))) {
                    mount(scenario, sid, path)
                    tapDesc("Edit")
                    scenario.onActivity { editor(it.window.decorView)!!.setText(text) }
                    // Switching immediately exercises the flush before debounce.
                }
            }
            open(a, "a.kt").use { scenario ->
                awaitEditor(scenario) { it.text.toString() == "draft a\n" }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); awaitText("Unsaved changes"); tapText("Discard")
                awaitEditor(scenario) { it.text.toString() == "saved a\n" }
                eventually { readDisk(keys[0]) == null }
            }
            open(a, "a.kt").use { scenario -> awaitEditor(scenario) { it.text.toString() == "saved a\n" } }
            open(a, "b.kt").use { scenario -> awaitEditor(scenario) { it.text.toString() == "draft b\n" } }
            open(b, "a.kt").use { scenario -> awaitEditor(scenario) { it.text.toString() == "other chat\n" } }
        } finally {
            keys.forEach { clear(it) }
            listOf(a, b).forEach { sid -> c.sessions.session(sid)?.let { c.sessions.deleteSession(it) } }
            c.workspace.deleteProject(project); root.deleteRecursively()
        }
    }

    /** Host runs phase=seed, force-stops the live editor, then runs phase=verify. */
    @Test fun draftSurvivesActualProcessTermination(): Unit = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("editorRecoveryPhase")
        assumeTrue("Requires host-controlled force-stop between seed and verify", phase in setOf("seed", "verify"))
        val fixtureFile = File(c.appContext.noBackupFilesDir, "editor-process-fixture.properties")
        if (phase == "seed") {
            val previousSettings = c.settings.settings.first()
            c.settings.setEditorWordWrap(false)
            c.settings.setEditorSearchOptions(true, true)
            val root = File(c.appContext.noBackupFilesDir, "editor-process-test").apply { mkdirs() }
            File(root, "process.kt").writeText("saved before termination\n")
            val project = c.workspace.addShellProject(root.path, activate = false)
            val sid = c.sessions.createProjectSession(project.id)
            val key = key(sid, "process.kt")
            open(sid, "process.kt").use { scenario ->
                tapDesc("Edit")
                scenario.onActivity { editor(it.window.decorView)!!.setText("draft across process termination\n") }
                tapDesc("More"); tapText("Word wrap: off")
                awaitEditor(scenario) { it.isWordwrap }
                eventually { readDisk(key)?.text == "draft across process termination\n" }
                eventually { runBlocking { c.settings.settings.first().editorWordWrap } }
                Properties().apply {
                    setProperty("sid", sid); setProperty("project", project.id); setProperty("root", root.path)
                    setProperty("pid", android.os.Process.myPid().toString()); setProperty("wrap", previousSettings.editorWordWrap.toString())
                    setProperty("case", previousSettings.editorMatchCase.toString()); setProperty("regex", previousSettings.editorRegexSearch.toString())
                }.also { value -> fixtureFile.outputStream().use { value.store(it, "Ready for host force-stop") } }
                // The process is intentionally killed while this editor is still active.
                CountDownLatch(1).await(90, TimeUnit.SECONDS)
                fail("The host did not terminate the editor process")
            }
        } else {
            val fixture = Properties().apply { fixtureFile.inputStream().use { load(it) } }
            val sid = fixture.getProperty("sid")
            val root = File(fixture.getProperty("root"))
            val key = key(sid, "process.kt")
            try {
                assertNotEquals(fixture.getProperty("pid").toInt(), android.os.Process.myPid())
                assertTrue(c.settings.settings.first().editorWordWrap)
                assertTrue(c.settings.settings.first().let { it.editorMatchCase && it.editorRegexSearch })
                open(sid, "process.kt").use { scenario ->
                    awaitEditor(scenario) { it.text.toString() == "draft across process termination\n" && it.isWordwrap && it.isEditable }
                    awaitText("Recovered unsaved draft · file unchanged")
                    val file = File(root, "process.kt")
                    assertEquals("saved before termination\n", file.readText())
                    file.writeText("external change after termination\n")
                    tapDesc("Save"); awaitText("File changed on disk"); tapText("Reload file")
                    awaitEditor(scenario) { it.text.toString() == "external change after termination\n" }
                    eventually { readDisk(key) == null }
                }
            } finally {
                clear(key); c.settings.setEditorWordWrap(fixture.getProperty("wrap").toBoolean())
                c.settings.setEditorSearchOptions(fixture.getProperty("case").toBoolean(), fixture.getProperty("regex").toBoolean())
                c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
                c.workspace.projects.first().firstOrNull { it.id == fixture.getProperty("project") }?.let { c.workspace.deleteProject(it) }
                root.deleteRecursively(); fixtureFile.delete()
            }
        }
    }

    private suspend fun key(sid: String, path: String) = EditorDraftStore.key(sid, null, c.workspace.forSession(sid).displayPath, path)
    private fun readDisk(key: String) = runBlocking { EditorDraftStore(recoveryRoot).read(key) }
    private suspend fun clear(key: String) { c.editorDrafts.submit(key, null, immediate = true); c.editorDrafts.flush(key) }
    private fun open(sid: String, path: String) = ActivityScenario.launch(ChatListTestActivity::class.java).also { mount(it, sid, path) }
    private fun mount(scenario: ActivityScenario<ChatListTestActivity>, sid: String, path: String) {
        scenario.onActivity { activity -> activity.setContent { HarnessTheme { CodeEditorScreen(c, path, sessionId = sid, onBack = {}) } } }
        awaitEditor(scenario) { true }
    }
    private fun awaitEditor(scenario: ActivityScenario<ChatListTestActivity>, check: (CodeEditor) -> Boolean) = eventually {
        var matched = false
        scenario.onActivity { activity -> matched = editor(activity.window.decorView)?.let(check) ?: false }
        matched
    }
    private fun editor(view: View): CodeEditor? {
        if (view is CodeEditor) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) editor(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser) add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun tapText(text: String) = tap { it.text?.toString() == text }
    private fun tapDesc(text: String) = tap { it.contentDescription?.toString() == text }
    private fun tap(match: (AccessibilityNodeInfo) -> Boolean) {
        var node: AccessibilityNodeInfo? = null
        eventually { node = nodes().firstOrNull(match); node != null }
        val bounds = Rect().also { node!!.getBoundsInScreen(it) }
        val time = android.os.SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, time, action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
    }
    private fun awaitText(text: String) = eventually { nodes().any { it.text?.toString() == text } }
    private fun eventually(check: () -> Boolean) {
        val until = android.os.SystemClock.uptimeMillis() + 8000
        while (android.os.SystemClock.uptimeMillis() < until) {
            if (check()) return
            Thread.sleep(40)
        }
        assertTrue("Timed out waiting for editor state", check())
    }
}
