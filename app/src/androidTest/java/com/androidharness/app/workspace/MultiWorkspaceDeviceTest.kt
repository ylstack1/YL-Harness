package com.androidharness.app.workspace

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.MainActivity
import com.androidharness.app.agent.*
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.data.db.AppDatabase
import com.androidharness.app.data.db.SessionEntity
import com.androidharness.app.llm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import com.androidharness.app.tools.Tool
import com.androidharness.app.tools.ToolContext
import com.androidharness.app.tools.ToolResult
import com.androidharness.app.tools.Schema
import kotlinx.serialization.json.JsonObject

@RunWith(AndroidJUnit4::class)
class MultiWorkspaceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun version15MigrationPreservesExistingChatAndModelSelection() = runBlocking {
        val context = instrumentation.targetContext
        val name = "multi-migration-${System.nanoTime()}.db"
        try {
            val initial = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            initial.dao().insertSession(SessionEntity("legacy", "Keep this chat", 1, 2, projectId = "original", modelSelectionJson = "saved model"))
            initial.close()
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, 0).use { old ->
                old.execSQL("ALTER TABLE sessions DROP COLUMN workspaceSelectionJson")
                old.execSQL("ALTER TABLE messages DROP COLUMN artifactJson")
                old.execSQL("ALTER TABLE messages DROP COLUMN generationJson")
                old.version = 15
            }
            val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_21_22, AppDatabase.MIGRATION_20_21, AppDatabase.MIGRATION_15_16, AppDatabase.MIGRATION_16_17, AppDatabase.MIGRATION_17_18, AppDatabase.MIGRATION_19_20, AppDatabase.MIGRATION_18_19).build()
            try {
                val row = requireNotNull(migrated.dao().session("legacy"))
                assertEquals("Keep this chat", row.title); assertEquals("original", row.projectId)
                assertEquals("saved model", row.modelSelectionJson); assertNull(row.workspaceSelectionJson)
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun grantsSurviveReopeningAndProtectSecondaryProjectsFromDeletion() = runBlocking {
        val context = instrumentation.targetContext
        val name = "multi-${System.nanoTime()}.db"
        val root = File(context.cacheDir, name).apply { mkdirs() }
        var db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            var manager = WorkspaceManager(context, db.dao())
            val a = manager.addShellProject(root.resolve("a").apply { mkdirs() }.path)
            val b = manager.addShellProject(root.resolve("b").apply { mkdirs() }.path)
            db.dao().insertSession(SessionEntity("chat", "Multi", 1, 1, projectId = a.id))
            root.resolve("a/same.txt").writeText("changed")
            db.dao().insertCheckpoint(com.androidharness.app.data.db.CheckpointEntity("cp", "chat", "old-turn", "same.txt",
                java.util.Base64.getEncoder().encodeToString("original".toByteArray()), true, false, 1))
            db.dao().upsertSessionFileChange(com.androidharness.app.data.db.SessionFileChangeEntity("chat", "same.txt", hasBase = true))
            val selected = ChatWorkspaceSelection(a.id, listOf(ChatWorkspaceGrant(a.id, true), ChatWorkspaceGrant(b.id)))
            manager.assignSelection("chat", selected)
            assertTrue(runCatching { manager.deleteProject(b) }.isFailure)
            db.close()
            db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            manager = WorkspaceManager(context, db.dao())
            manager.setActiveProject(b.id)
            assertEquals(selected, manager.selectionForChat("chat").first())
            val fs = manager.forSession("chat") as ChatWorkspaceFs
            assertEquals(a.id, fs.primaryId)
            assertFalse(fs.binding(b.id).canEdit)
            assertEquals(fs.key(a.id, "same.txt"), db.dao().checkpointsForTurn("chat", "old-turn").single().relPath)
            assertEquals(fs.key(a.id, "same.txt"), db.dao().sessionFileChangesFlow("chat").first().single().relPath)
            assertEquals(1, com.androidharness.app.data.CheckpointStore(db.dao()).rewind("chat", "old-turn", fs).restored)
            assertEquals("original", root.resolve("a/same.txt").readText())
            db.dao().deleteProject(b)
            assertTrue(runCatching { manager.forSession("chat") }.isFailure)
            assertNull(manager.forChat("chat").first())
        } finally { db.close(); context.deleteDatabase(name); root.deleteRecursively() }
    }

    @Test fun agentRoutesReadsEditsAndUndoWithoutFullAccessBypassingReadOnly() = runBlocking {
        val previous = c.workspace.currentProjectOnce()
        val permission = c.settings.settings.first().permissionMode
        val root = File(c.appContext.cacheDir, "multi-run-${System.nanoTime()}").apply { mkdirs() }
        val aRoot = root.resolve("a").apply { mkdirs() }; val bRoot = root.resolve("b").apply { mkdirs() }
        aRoot.resolve("same.txt").writeText("alpha original"); bRoot.resolve("same.txt").writeText("beta original")
        val a = c.workspace.addShellProject(aRoot.path); val b = c.workspace.addShellProject(bRoot.path)
        val chat = c.sessions.createSession("Multi run", a.id)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val round = AtomicInteger()
        var allWritable = false
        val provider = object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
                messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                assertTrue(systemPrompt.contains(a.id)); assertTrue(systemPrompt.contains(b.id))
                if (round.get() == 0) { entered.complete(Unit); release.await() }
                val calls = if (!allWritable) listOf(
                    "read_file" to """{"workspace_id":"${b.id}","path":"same.txt"}""",
                    "write_file" to """{"workspace_id":"${b.id}","path":"same.txt","content":"denied"}""",
                    "write_file" to """{"workspace_id":"${a.id}","path":"same.txt","content":"alpha changed"}""",
                    "shell" to """{"workspace_id":"${a.id}","command":"echo denied > ../b/same.txt"}""",
                ) else listOf(
                    "write_file" to """{"workspace_id":"${a.id}","path":"same.txt","content":"alpha new"}""",
                    "write_file" to """{"workspace_id":"${b.id}","path":"same.txt","content":"beta new"}""",
                    "workspace_probe" to """{"workspace_id":"${b.id}"}""",
                )
                val index = round.getAndIncrement()
                if (index < calls.size) {
                    val (name, args) = calls[index]
                    emit(StreamEvent.ToolCallReady(ToolCallData("${if (allWritable) "edit" else "read"}-$index", name, args)))
                    emit(StreamEvent.Done("tool_calls"))
                } else { emit(StreamEvent.TextDelta("Completed fixture")); emit(StreamEvent.Done("stop")) }
            }
        }
        val probe = object : Tool {
            override val name = "workspace_probe"
            override val description = "Test captured command directory"
            override val isReadOnly = true
            override val parametersSchema = Schema.obj(emptyMap())
            override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
                val result = c.shellRouter.runWorkspace("pwd", ctx.workspace, 5000, 4096)
                return ToolResult(result.exitCode == 0, result.rawOutput)
            }
        }
        val engine = AgentEngine({ provider }, c.registry.withExtra(listOf(probe)), c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills)
        val runs = RunManager(c.appContext, engine, c.sessions, c.checkpoints, c.workspace, c.linuxEnv, c.settings, TodoStore(), c.providers)
        val config = ProviderConfig("multi-fixture", "Multi fixture", ProviderType.OPENAI_COMPAT, "https://example.invalid", "fixture")
        suspend fun start() = runs.startRun(chat, "Read and edit selected projects", emptyList(), config, "fixture",
            PermissionMode.FULL_ACCESS, AgentMode.ACT, 1024, 32000, ThinkingLevel.OFF, 8, notifyOnFinish = false)
        suspend fun done() { withTimeout(30000) { while (runs.isRunning(chat)) delay(30) }; assertNull(runs.live(chat).value.error) }
        try {
            c.settings.setPermissionMode(PermissionMode.FULL_ACCESS)
            val selected = ChatWorkspaceSelection(a.id, listOf(ChatWorkspaceGrant(a.id, true), ChatWorkspaceGrant(b.id)))
            runs.assignWorkspaces(chat, selected)
            start(); withTimeout(30000) { entered.await() }
            assertTrue(runCatching { runs.withIdleWorkspace(c.workspace.fsFor(b)) {} }.isFailure)
            assertTrue(runCatching { runs.assignWorkspaces(chat, selected.copy(grants = selected.grants.map { it.copy(canEdit = true) })) }.isFailure)
            release.complete(Unit); done()
            assertEquals("alpha changed\n", aRoot.resolve("same.txt").readText())
            assertEquals("beta original", bRoot.resolve("same.txt").readText())
            val results = c.sessions.messages(chat).filter { it.role == Role.TOOL }
            assertTrue(results.first { it.toolName == "read_file" }.text.contains("beta original"))
            assertTrue(results.first { it.toolCallId == "read-1" }.isError)
            assertTrue(results.first { it.toolName == "shell" }.isError)
            val writtenCall = c.sessions.messages(chat).flatMap { it.toolCalls }.first { it.id == "read-2" }
            assertEquals(a.name, writtenCall.workspaceName)
            val fs = c.workspace.forSession(chat) as ChatWorkspaceFs
            assertEquals(1, c.sessions.fileChangesFor(chat).first().size)
            assertEquals(fs.key(a.id, "same.txt"), c.sessions.fileChangesFor(chat).first().single().relPath)
            assertTrue(runCatching { runs.assignWorkspaces(chat, ChatWorkspaceSelection.single(b.id)) }.isFailure)
            val turn = c.checkpoints.turnsWithCheckpoints(chat).single()
            assertEquals(0, runs.rewindFromTurn(chat, turn).filesFailed)
            assertEquals("alpha original", aRoot.resolve("same.txt").readText())
            allWritable = true; round.set(0)
            runs.assignWorkspaces(chat, selected.copy(grants = selected.grants.map { it.copy(canEdit = true) }))
            // Simulate the durable paused-task record; resume must ignore the recent workspace.
            val original = c.workspace.forSession(chat)
            runs.controls.update(chat) { it.copy(status = "paused", completed = false, workspacePath = original.displayPath,
                workspacePaths = original.workspacePaths(), provider = config, turnId = "resumed-${System.nanoTime()}") }
            c.workspace.setActiveProject(b.id)
            runs.resumeTask(chat, "fixture", notifyOnFinish = false); done()
            assertEquals("alpha new\n", aRoot.resolve("same.txt").readText())
            assertEquals("beta new\n", bRoot.resolve("same.txt").readText())
            val command = c.sessions.messages(chat).last { it.toolName == "workspace_probe" }
            assertFalse(command.text, command.isError); assertTrue(command.text, command.text.contains(bRoot.canonicalPath))
            val paths = c.sessions.fileChangesFor(chat).first().map { it.relPath }.toSet()
            assertEquals(setOf(fs.key(a.id, "same.txt"), fs.key(b.id, "same.txt")), paths)
            assertEquals(0, runs.rewindFromTurn(chat, c.checkpoints.turnsWithCheckpoints(chat).single()).filesFailed)
            assertEquals("alpha original", aRoot.resolve("same.txt").readText())
            assertEquals("beta original", bRoot.resolve("same.txt").readText())
        } finally {
            release.complete(Unit); runs.stopAllAndJoin()
            c.sessions.session(chat)?.let { c.sessions.deleteSession(it) }
            c.workspace.deleteProject(a); c.workspace.deleteProject(b); c.workspace.setActiveProject(previous.id)
            c.settings.setPermissionMode(permission); root.deleteRecursively()
        }
    }

    @Test fun sidebarSelectionCancelAndRecreationKeepChatAccess() = runBlocking {
        // Remove only disposable fixtures left if an earlier instrumentation process was killed.
        val stale = c.workspace.projects.first().filter { it.uri?.startsWith(c.appContext.cacheDir.path + "/multi-ui-") == true }
        c.sessions.sessions.first().filter { session -> stale.any { it.id == session.projectId } }.forEach { c.sessions.deleteSession(it) }
        stale.forEach { c.workspace.deleteProject(it) }
        val previous = c.workspace.currentProjectOnce()
        val saved = c.settings.settings.first()
        val root = File(c.appContext.cacheDir, "multi-ui-${System.nanoTime()}").apply { mkdirs() }
        val a = c.workspace.addShellProject(root.resolve("Chip Alpha").apply { mkdirs() }.path)
        val b = c.workspace.addShellProject(root.resolve("Chip Beta").apply { mkdirs() }.path)
        val chat = c.sessions.createSession("Compact workspace test", a.id)
        try {
            c.settings.setOnboardingDone(true); c.settings.setResumeLastChat(true); c.settings.setLastActiveSessionId(chat)
            val activity = instrumentation.startActivitySync(Intent(c.appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            try {
                fun openAccess(count: Int, primary: String) {
                    tap("Open navigation")
                    awaitNode("Tools")
                    SystemClock.sleep(350)
                    instrumentation.uiAutomation.clearCache()
                    if (find("Workspaces for this chat") == null) tap("Tools")
                    awaitNode("$count workspace${if (count == 1) "" else "s"} · Primary: $primary")
                    val projects = Rect().also { awaitNode("Projects").getBoundsInScreen(it) }
                    val access = Rect().also { awaitNode("Workspaces for this chat").getBoundsInScreen(it) }
                    assertTrue("Chat workspace access should be below Projects", access.top >= projects.bottom)
                    screenshot("sidebar-chat-workspaces.png")
                    tap("Workspaces for this chat")
                    awaitNode("Choose access for each project. Primary is the default place for new files.")
                }
                awaitNode("Open navigation")
                assertNull(find("1 workspace"))
                openAccess(1, a.name)
                tap(b.name)
                tap("Close without saving")
                awaitNode("Open navigation")
                assertNull(c.sessions.session(chat)?.workspaceSelectionJson)
                openAccess(1, a.name); tap(b.name); tap("Use 2 workspaces")
                awaitSheetClosed()
                awaitNode("Open navigation")
                assertNull(find("2 workspaces"))
                screenshot("issue38-chat.png")
                val selected = ChatWorkspaceSelection.decode(c.sessions.session(chat)!!.workspaceSelectionJson!!)
                assertEquals(a.id, selected.primaryId); assertFalse(selected.grants.first { it.projectId == b.id }.canEdit)
                instrumentation.runOnMainSync { activity.recreate() }
                awaitNode("Open navigation"); openAccess(2, a.name); awaitNode("${b.name} access: Read only")
                screenshot("issue38-workspaces.png")
                tap("${b.name} access: Read only"); tap("Can edit"); awaitNode("${b.name} access: Can edit"); tap("Use 2 workspaces")
                awaitSheetClosed()
                awaitNode("Open navigation")
                assertTrue(ChatWorkspaceSelection.decode(c.sessions.session(chat)!!.workspaceSelectionJson!!).grants.all { it.canEdit })
                openAccess(2, a.name); tap("Primary workspace: ${b.name}"); tap("Use 2 workspaces")
                awaitSheetClosed()
                awaitNode("Open navigation")
                assertEquals(b.id, c.sessions.session(chat)?.projectId)
            } finally { instrumentation.runOnMainSync {
                androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).toList()
                    .filterIsInstance<MainActivity>().forEach { it.finish() }
            } }
        } finally {
            c.sessions.session(chat)?.let { c.sessions.deleteSession(it) }
            c.workspace.deleteProject(a); c.workspace.deleteProject(b); c.workspace.setActiveProject(previous.id)
            c.settings.setLastActiveSessionId(saved.lastActiveSessionId); c.settings.setResumeLastChat(saved.resumeLastChat)
            c.settings.setOnboardingDone(saved.onboardingDone); root.deleteRecursively()
        }
    }

    @Test fun sidebarAccessBeforeFirstMessageKeepsTheDraftAndCreatesOnlyItsChat() = runBlocking<Unit> {
        val previous = c.workspace.currentProjectOnce()
        val saved = c.settings.settings.first()
        val root = File(c.appContext.cacheDir, "multi-draft-${System.nanoTime()}").apply { mkdirs() }
        val a = c.workspace.addShellProject(root.resolve("Draft Alpha").apply { mkdirs() }.path)
        val b = c.workspace.addShellProject(root.resolve("Draft Beta").apply { mkdirs() }.path)
        var draftChat: String? = null
        try {
            c.workspace.setActiveProject(a.id)
            c.settings.setOnboardingDone(true); c.settings.setResumeLastChat(false); c.settings.setLastActiveSessionId(null)
            val activity = instrumentation.startActivitySync(Intent(c.appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            try {
                awaitNode("Open navigation")
                fun editable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                    node ?: return null
                    if (node.isEditable) return node
                    for (i in 0 until node.childCount) editable(node.getChild(i))?.let { return it }
                    return null
                }
                val input = requireNotNull(editable(instrumentation.uiAutomation.rootInActiveWindow))
                assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Keep this unsent draft")
                }))
                assertNull(c.settings.settings.first().lastActiveSessionId)
                tap("Open navigation")
                awaitNode("Tools")
                SystemClock.sleep(350)
                instrumentation.uiAutomation.clearCache()
                if (find("Workspaces for this chat") == null) tap("Tools")
                tap("Workspaces for this chat")
                awaitNode("Choose access for each project. Primary is the default place for new files.")
                tap(b.name); tap("Use 2 workspaces"); awaitSheetClosed()
                draftChat = c.settings.settings.first().lastActiveSessionId
                awaitNode("Keep this unsent draft")
                val session = requireNotNull(c.sessions.session(requireNotNull(draftChat)))
                val selection = ChatWorkspaceSelection.decode(requireNotNull(session.workspaceSelectionJson))
                assertEquals(a.id, selection.primaryId)
                assertFalse(selection.grants.single { it.projectId == b.id }.canEdit)
                assertTrue(c.sessions.messages(session.id).isEmpty())
                tap("Open navigation"); awaitNode("2 workspaces · Primary: ${a.name}")
            } finally { instrumentation.runOnMainSync { activity.finish() } }
        } finally {
            draftChat?.let { c.sessions.session(it) }?.let { c.sessions.deleteSession(it) }
            c.workspace.deleteProject(a); c.workspace.deleteProject(b); c.workspace.setActiveProject(previous.id)
            c.settings.setLastActiveSessionId(saved.lastActiveSessionId); c.settings.setResumeLastChat(saved.resumeLastChat)
            c.settings.setOnboardingDone(saved.onboardingDone); root.deleteRecursively()
        }
    }

    private fun find(text: String, node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
        node ?: return null
        if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
        for (i in 0 until node.childCount) find(text, node.getChild(i))?.let { return it }
        return null
    }
    private fun screenshot(name: String) {
        SystemClock.sleep(350)
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            c.appContext.cacheDir.resolve(name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun awaitNode(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 20000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.uiAutomation.clearCache()
            find(text)?.let { return it }; SystemClock.sleep(80)
        }
        error("Missing UI node: $text")
    }
    private fun tap(text: String) {
        val node = awaitNode(text)
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Could not tap $text" }
        SystemClock.sleep(120)
    }
    private fun awaitSheetClosed() {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.uiAutomation.clearCache()
            if (find("Workspaces for this chat") == null) return
            SystemClock.sleep(80)
        }
        error("Workspace sheet did not close after saving")
    }
}
