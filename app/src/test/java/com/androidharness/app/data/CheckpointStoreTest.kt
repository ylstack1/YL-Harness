package com.androidharness.app.data

import com.androidharness.app.data.db.CheckpointEntity
import com.androidharness.app.data.db.HarnessDao
import com.androidharness.app.workspace.FsNode
import com.androidharness.app.workspace.WorkspaceFs
import java.lang.reflect.Proxy
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CheckpointStoreTest {
    private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name, args ?: emptyArray())
        } as T

    private class Node(var text: String = "changed", var exists: Boolean = true) {
        var fail = false
        var deleteResult = true
        var cancelled = false
        var writes = 0
    }

    private fun checkpoint(path: String, existed: Boolean = true) = CheckpointEntity(
        path, "session", "turn", path,
        Base64.getEncoder().encodeToString("original".toByteArray()), existed, false, 1L,
    )

    private fun fixture(rows: MutableList<CheckpointEntity>, nodes: Map<String, Node>): Pair<CheckpointStore, WorkspaceFs> {
        val dao = proxy<HarnessDao> { name, args ->
            when (name) {
                "checkpointsForTurn" -> rows.toList()
                "deleteCheckpoint" -> { rows.remove(args[0]); Unit }
                else -> error("Unexpected DAO call: $name")
            }
        }
        val fs = proxy<WorkspaceFs> { name, args ->
            check(name == "resolve")
            val node = nodes.getValue(args[0] as String)
            proxy<FsNode> { op, values ->
                when (op) {
                    "getExists" -> node.exists
                    "getIsDirectory", "isDirectory" -> false
                    "writeText" -> {
                        if (node.cancelled) throw CancellationException("cancelled")
                        check(!node.fail) { "Access denied" }
                        node.text = values[0] as String
                        node.writes++
                        Unit
                    }
                    "delete" -> { if (node.deleteResult) node.exists = false; node.deleteResult }
                    else -> error("Unexpected node call: $op")
                }
            }
        }
        return CheckpointStore(dao) to fs
    }

    @Test fun `partial failure keeps only failed checkpoints and retry restores them`() = runBlocking {
        val rows = mutableListOf(checkpoint("good"), checkpoint("bad"))
        val good = Node()
        val bad = Node().apply { fail = true }
        val (store, fs) = fixture(rows, mapOf("good" to good, "bad" to bad))
        val first = store.rewind("session", "turn", fs)
        assertEquals(1, first.restored)
        assertEquals(1, first.failed)
        assertEquals(setOf("good"), first.paths)
        assertEquals(listOf("bad"), rows.map { it.relPath })
        bad.fail = false
        val retry = store.rewind("session", "turn", fs)
        assertEquals(1, retry.restored)
        assertEquals(0, retry.failed)
        assertEquals("original", bad.text)
        assertEquals(1, good.writes)
        assertTrue(rows.isEmpty())
    }

    @Test fun `false delete result remains retryable`() = runBlocking {
        val rows = mutableListOf(checkpoint("new", existed = false))
        val node = Node().apply { deleteResult = false }
        val (store, fs) = fixture(rows, mapOf("new" to node))
        assertEquals(1, store.rewind("session", "turn", fs).failed)
        assertEquals(1, rows.size)
        node.deleteResult = true
        assertEquals(1, store.rewind("session", "turn", fs).restored)
        assertFalse(node.exists)
        assertTrue(rows.isEmpty())
    }

    @Test fun `already absent new file completes without delete`() = runBlocking {
        val rows = mutableListOf(checkpoint("new", existed = false))
        val (store, fs) = fixture(rows, mapOf("new" to Node(exists = false)))
        assertEquals(1, store.rewind("session", "turn", fs).restored)
        assertTrue(rows.isEmpty())
    }

    @Test fun `cancellation preserves checkpoint and propagates`() = runBlocking {
        val rows = mutableListOf(checkpoint("file"))
        val (store, fs) = fixture(rows, mapOf("file" to Node().apply { cancelled = true }))
        try {
            store.rewind("session", "turn", fs)
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(1, rows.size)
        }
    }

    @Test fun `directory replaced by file is a retryable failure`() = runBlocking {
        val rows = mutableListOf(checkpoint("dir").copy(wasDirectory = true))
        val (store, fs) = fixture(rows, mapOf("dir" to Node()))
        assertEquals(1, store.rewind("session", "turn", fs).failed)
        assertEquals(1, rows.size)
    }

    @Test fun `rewinding an agent created directory preserves untracked shell files and saves removed source`() = runBlocking {
        val root = java.nio.file.Files.createTempDirectory("rewind-safety").toFile()
        try {
            val fs = com.androidharness.app.workspace.FileFs(java.io.File(root, "workspace"))
            fs.resolve("site/tracked.js").writeText("saved source")
            fs.resolve("site/untracked.css").writeText("shell work")
            val rows = mutableListOf(checkpoint("site", false), checkpoint("site/tracked.js", false))
            val dao = proxy<HarnessDao> { name, args -> when (name) {
                "checkpointsForTurn" -> rows.toList()
                "deleteCheckpoint" -> { rows.remove(args[0]); Unit }
                else -> error("Unexpected DAO call: $name")
            } }
            val history = WorkspaceFileHistory(java.io.File(root, "history"))
            val result = CheckpointStore(dao, history).rewind("session", "turn", fs)
            assertEquals(1, result.failed)
            assertEquals(1, result.restored)
            assertEquals("shell work", fs.resolve("site/untracked.css").readText())
            assertEquals(listOf("site"), rows.map { it.relPath })
            val version = history.versions("session", fs, "site/tracked.js").first { !it.deleted }
            history.restore("session", fs, "site/tracked.js", version.id)
            assertEquals("saved source", fs.resolve("site/tracked.js").readText())
        } finally { root.deleteRecursively() }
    }
}
