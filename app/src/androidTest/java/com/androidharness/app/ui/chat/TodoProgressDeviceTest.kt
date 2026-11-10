package com.androidharness.app.ui.chat

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.agent.*
import com.androidharness.app.core.*
import com.androidharness.app.llm.*
import com.androidharness.app.tools.TodoWriteTool
import com.androidharness.app.ui.chat.components.TodoCard
import com.androidharness.app.ui.theme.HarnessTheme
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class TodoProgressDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun taskUpdatesPrecedeParallelWorkAndRefreshEveryRequest(): Unit = runBlocking {
        val store = TodoStore()
        val sid = UUID.randomUUID().toString()
        store.beginRun(sid)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val subagents = AtomicInteger()
        val root = File(c.appContext.cacheDir, "todo-progress-$sid").apply { mkdirs() }
        File(root, "fixture.txt").writeText("Verified fixture input")
        var round = 0
        fun todo(id: String, statuses: List<String>) = ToolCallData(id, "todo_write",
            """{"todos":[{"content":"Inspect the task progress update path","status":"${statuses[0]}"},{"content":"Improve the task list layout on a narrow phone","status":"${statuses[1]}"},{"content":"Verify the result with larger text","status":"${statuses[2]}"}]}""")
        val provider = object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
                messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                if (systemPrompt.contains("research subagent")) {
                    assertEquals("Progress must be published before delegated work starts", TodoItem.Status.IN_PROGRESS, store.todos.value.firstOrNull()?.status)
                    if (subagents.incrementAndGet() == 2) entered.complete(Unit)
                    release.await()
                    emit(StreamEvent.TextDelta("Inspected the fixture")); emit(StreamEvent.Done("stop"))
                    return@flow
                }
                when (++round) {
                    1 -> {
                        emit(StreamEvent.ToolCallReady(todo("start", listOf("in_progress", "pending", "pending"))))
                        repeat(2) { emit(StreamEvent.ToolCallReady(ToolCallData("research-$it", "task", """{"prompt":"Inspect fixture.txt","title":"Research $it"}"""))) }
                    }
                    2 -> {
                        assertTrue(systemPrompt.contains("[in_progress] Inspect the task progress update path"))
                        emit(StreamEvent.ToolCallReady(todo("advance", listOf("completed", "in_progress", "pending"))))
                    }
                    3, 4, 5 -> {
                        assertTrue(systemPrompt.contains("[completed] Inspect the task progress update path"))
                        assertTrue(systemPrompt.contains("[in_progress] Improve the task list layout"))
                        assertFalse(systemPrompt.contains("Progress check:"))
                        emit(StreamEvent.ToolCallReady(ToolCallData("read-$round", "read_file", """{"path":"fixture.txt"}""")))
                    }
                    6 -> {
                        assertTrue(systemPrompt.contains("Progress check:"))
                        emit(StreamEvent.ToolCallReady(todo("verify", listOf("completed", "completed", "in_progress"))))
                    }
                    else -> {
                        assertFalse(systemPrompt.contains("Progress check:"))
                        assertTrue(systemPrompt.contains("[in_progress] Verify the result with larger text"))
                        emit(StreamEvent.TextDelta("Verification still needs a real device check."))
                        emit(StreamEvent.Done("stop")); return@flow
                    }
                }
                emit(StreamEvent.Done("tool_calls"))
            }
        }
        val engine = AgentEngine({ provider }, c.registry.withExtra(listOf(TodoWriteTool(store))), c.checkpoints,
            c.images, c.linuxEnv, c.shizuku, c.skills, todoStore = store)
        val config = ProviderConfig("todo-fixture", "Fixture", ProviderType.OPENAI_COMPAT, "https://example.test", "fixture-model")
        val run = async(Dispatchers.Default) {
            engine.run(sid, UUID.randomUUID().toString(), config, "fixture", listOf(ChatMessage(role = Role.USER, text = "Fix task progress")),
                { PermissionMode.FULL_AUTO }, mutableSetOf(), FileFs(root), RequestOptions(), 32000, AgentMode.ACT,
                maxIterations = 10, repoMapEnabled = false).toList()
        }
        try {
            withTimeout(15000) { entered.await() }
            assertTrue("Agent is still working while task progress is visible", run.isActive)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { HarnessTheme {
                    val todos by store.todos.collectAsState()
                    Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().navigationBarsPadding().statusBarsPadding()) {
                        Text("Agent is working", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        TodoCard(todos)
                        OutlinedTextField("", {}, placeholder = { Text("Message") }, modifier = Modifier.fillMaxWidth().padding(12.dp))
                    } }
                } } }
                awaitText("0 of 3 completed")
                screenshot("todo-live-collapsed")
                click("Expand task list")
                awaitText("Pending")
                screenshot("todo-live-expanded")
                assertWithinScreen("Collapse task list")
                release.complete(Unit)
                val events = withTimeout(20000) { run.await() }
                assertFalse(events.any { it is AgentEvent.Error })
                assertTrue(events.filterIsInstance<AgentEvent.ToolMessageCommitted>().all { !it.message.isError })
                awaitText("2 of 3 completed")
                assertEquals("An answer must not silently complete unfinished tasks", TodoItem.Status.IN_PROGRESS, store.todos.value.last().status)
                click("Collapse task list")
                awaitText("Verify the result with larger text")
                screenshot("todo-live-advanced")
                store.setAll(store.todos.value.map { it.copy(status = TodoItem.Status.COMPLETED) })
                awaitText("All tasks completed")
                screenshot("todo-completed")
            }
        } finally { release.complete(Unit); run.cancelAndJoin(); root.deleteRecursively() }
    }

    private fun find(text: String): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && (node.text?.toString() == text || node.contentDescription?.toString() == text)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun awaitText(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) { find(text)?.let { return it }; SystemClock.sleep(100) }
        error("Task list UI missing: $text")
    }
    private fun click(text: String) {
        var node: AccessibilityNodeInfo? = awaitText(text)
        while (node != null && !node.isClickable) node = node.parent
        assertTrue(requireNotNull(node).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    private fun assertWithinScreen(text: String) {
        val bounds = Rect().also(awaitText(text)::getBoundsInScreen)
        val screen = c.appContext.resources.displayMetrics
        assertTrue("$text outside screen: $bounds", bounds.left >= 0 && bounds.top >= 0 && bounds.right <= screen.widthPixels && bounds.bottom <= screen.heightPixels)
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(c.appContext.cacheDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
