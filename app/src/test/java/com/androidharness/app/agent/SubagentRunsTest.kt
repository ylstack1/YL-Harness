package com.androidharness.app.agent

import com.androidharness.app.core.ToolCallData
import com.androidharness.app.tools.ToolResult
import com.androidharness.app.tools.ToolContext
import com.androidharness.app.tools.subagentControlTools
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

class SubagentRunsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stores = mutableListOf<SubagentRuns>()
    private fun store(file: java.io.File = java.io.File(temporary.root, "runs.json"), parallelism: Int = 2,
        acquired: AtomicInteger = AtomicInteger(), released: AtomicInteger = AtomicInteger()): SubagentRuns =
        SubagentRuns(file, scope, acquireKeepalive = { acquired.incrementAndGet() },
            releaseKeepalive = { released.incrementAndGet() }, parallelism = parallelism).also { stores += it }
    private fun run(id: String, session: String = "main") = SubagentRun(session, "turn",
        ToolCallData(id, "task", "{\"prompt\":\"Inspect files\"}"), "Checker $id", "Inspect files", "model", "provider", true)
    private suspend fun SubagentRuns.status(id: String, expected: SubagentStatus) = withTimeout(5000) {
        runs.first { list -> list.any { it.id == id && it.status == expected } }
    }
    @After fun cleanup() = runBlocking { stores.forEach { it.stopAllAndJoin() }; scope.cancel() }

    @Test fun `background outlives the launching coroutine and balances keepalive`() = runBlocking {
        val acquired = AtomicInteger(); val released = AtomicInteger()
        val store = store(acquired = acquired, released = released)
        val finish = CompletableDeferred<Unit>()
        val parent = launch { store.background(run("one"), currentCoroutineContext()) { finish.await(); ToolResult(true, "Checked") } }
        parent.join()
        store.status("one", SubagentStatus.RUNNING)
        assertTrue(parent.isCompleted)
        assertEquals(1, acquired.get()); assertEquals(0, released.get())
        finish.complete(Unit)
        assertEquals("Checked", store.waitFor("main", "one", 5000)?.output)
        store.stopAllAndJoin()
        assertEquals(acquired.get(), released.get())
    }

    @Test fun `two background tasks run and the third waits without blocking its caller`() = runBlocking {
        val store = store()
        val entered = Channel<String>(Channel.UNLIMITED)
        val gates = (1..3).associate { "$it" to CompletableDeferred<Unit>() }
        gates.filterKeys { it != "3" }.forEach { (id, gate) -> store.background(run(id)) { entered.send(id); gate.await(); ToolResult(true, id) } }
        val first = withTimeout(5000) { entered.receive() }; val second = withTimeout(5000) { entered.receive() }
        store.background(run("3")) { entered.send("3"); gates.getValue("3").await(); ToolResult(true, "3") }
        assertEquals(setOf("1", "2"), setOf(first, second))
        assertEquals(SubagentStatus.QUEUED, store.list("main").first { it.id == "3" }.status)
        gates.getValue(first).complete(Unit)
        assertEquals("3", withTimeout(5000) { entered.receive() })
        gates.values.forEach { it.complete(Unit) }
    }

    @Test fun `stopping one subagent keeps the sibling and main coroutine alive`() = runBlocking {
        val store = store()
        store.background(run("one")) { awaitCancellation() }
        store.background(run("two")) { awaitCancellation() }
        store.status("one", SubagentStatus.RUNNING); store.status("two", SubagentStatus.RUNNING)
        store.stop("main", "one")
        store.status("one", SubagentStatus.STOPPED)
        assertTrue(currentCoroutineContext().isActive)
        assertEquals(SubagentStatus.RUNNING, store.list("main").first { it.id == "two" }.status)
    }

    @Test fun `foreground stop returns a tool failure without cancelling the parent`() = runBlocking {
        val store = store()
        val finished = async { store.foreground(run("one").copy(background = false), {}) { awaitCancellation() } }
        store.status("one", SubagentStatus.RUNNING)
        store.stop("main", "one")
        assertFalse(withTimeout(5000) { finished.await() }.ok)
        assertTrue(currentCoroutineContext().isActive)
    }

    @Test fun `status wait and cancellation are scoped to the owning chat`() = runBlocking {
        val store = store()
        store.background(run("private", "other")) { awaitCancellation() }
        store.status("private", SubagentStatus.RUNNING)
        val tools = subagentControlTools(store, "main")
        val ctx = ToolContext(FileFs(temporary.root), sessionId = "main")
        for (name in listOf("subagent_status", "subagent_wait", "subagent_stop")) {
            val result = tools.first { it.name == name }.execute(buildJsonObject { put("task_id", "private") }, ctx)
            assertFalse(result.ok)
            assertFalse(result.output.contains("Checker private"))
        }
        assertNull(store.waitFor("main", "private", 1))
        assertEquals(SubagentStatus.RUNNING, store.list("other").single().status)
    }

    @Test fun `restart marks in flight jobs interrupted and preserves completed answers`() = runBlocking {
        val file = java.io.File(temporary.root, "restart.json")
        val store = store(file)
        store.background(run("done")) { ToolResult(true, "Saved findings") }
        store.waitFor("main", "done", 5000)
        store.background(run("active")) { awaitCancellation() }
        store.status("active", SubagentStatus.RUNNING)
        // Awaiting the next mutation also ensures the previous durable write finished.
        store.acknowledge("main", "done")
        val restored = store(file)
        restored.awaitReady()
        assertEquals(SubagentStatus.INTERRUPTED, restored.list("main").first { it.id == "active" }.status)
        assertEquals("Saved findings", restored.list("main").first { it.id == "done" }.output)
        assertFalse(restored.pending("main").any { it.id == "done" })
    }

    @Test fun `completion stays pending until acknowledged and wait returns genuine results`() = runBlocking {
        val store = store()
        val finish = CompletableDeferred<Unit>()
        store.background(run("one")) { finish.await(); ToolResult(false, "Compiler unavailable") }
        store.status("one", SubagentStatus.RUNNING)
        assertEquals(SubagentStatus.RUNNING, store.waitFor("main", "one", 1)?.status)
        assertTrue(store.pending("main").isEmpty())
        finish.complete(Unit)
        assertEquals(SubagentStatus.FAILED, store.waitFor("main", "one", 5000)?.status)
        assertEquals("Compiler unavailable", store.pending("main").single().output)
        store.acknowledge("main", "one")
        assertTrue(store.pending("main").isEmpty())
    }
}
