package com.androidharness.app.agent

import com.androidharness.app.core.Diff
import com.androidharness.app.data.CodeFilePolicy
import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Bounded local snapshots for foreground shell calls; never scans remote hosts or device-wide roots. */
internal object CodeActivityCapture {
    data class Snapshot(val files: Map<String, String>, val complete: Boolean)
    data class Change(val path: String, val added: Long, val removed: Long)
    suspend fun capture(fs: WorkspaceFs): Snapshot? = withContext(Dispatchers.IO) {
        val root = fs.shellRoot ?: return@withContext null
        if (fs.isSaf || root.canonicalPath == "/") return@withContext null
        val rootPath = root.canonicalFile.toPath()
        val files = linkedMapOf<String, String>()
        var bytes = 0L
        var visited = 0
        var complete = true
        val deadline = System.nanoTime() + 1_000_000_000L
        try {
            for (node in fs.walk(".")) {
                currentCoroutineContext().ensureActive()
                if (++visited > 4096 || bytes > 4_000_000L || System.nanoTime() > deadline) { complete = false; break }
                if (!node.isFile || !CodeFilePolicy.counts(node.relPath)) continue
                if (node is com.androidharness.app.workspace.FileFsNode && !node.file.canonicalFile.toPath().startsWith(rootPath)) { complete = false; continue }
                if (node.length > 512_000L) { complete = false; continue }
                val data = node.openInputStream()?.use { it.readBytes() }
                if (data == null) { complete = false; continue }
                bytes += data.size
                val text = runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data)).toString() }.getOrNull()
                if (text == null || '\u0000' in text) { complete = false; continue }
                files[node.relPath] = text
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { complete = false }
        Snapshot(files, complete)
    }
    fun changes(before: Snapshot, after: Snapshot): List<Change> {
        val paths = if (before.complete && after.complete) before.files.keys + after.files.keys else before.files.keys.intersect(after.files.keys)
        return paths.mapNotNull { path ->
            val (added, removed) = Diff.exactLineCounts(before.files[path].orEmpty(), after.files[path].orEmpty()) ?: return@mapNotNull null
            if (added == 0 && removed == 0) null else Change(path, added.toLong(), removed.toLong())
        }
    }
}
