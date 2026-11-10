package com.androidharness.app.ui.files

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class EditorTabPosition(val path: String, val start: Int = 0, val end: Int = 0, val x: Int = 0, val y: Int = 0)
@Serializable
internal data class EditorTabsSnapshot(val paths: List<String> = emptyList(), val active: String? = null,
    val requestId: String = "", val positions: List<EditorTabPosition> = emptyList())

internal class EditorWorkspace(val key: String, val identity: String, initial: EditorTabsSnapshot) {
    var revision by mutableStateOf(0)
    var persistenceError by mutableStateOf<String?>(null)
    val paths = mutableStateListOf<String>().apply { addAll(initial.paths.distinct().take(100)) }
    var active by mutableStateOf(initial.active?.takeIf { it in paths } ?: paths.firstOrNull())
    var requestId = initial.requestId
    val buffers = mutableStateMapOf<String, EditorBuffer>()
    private val positions = initial.positions.associateBy { it.path }
    fun buffer(path: String) = buffers.getOrPut(path) { EditorBuffer().apply {
        positions[path]?.let { selectionStart = it.start; selectionEnd = it.end; offsetX = it.x; offsetY = it.y }
    } }
    fun open(path: String) { if (path !in paths) paths.add(path); active = path }
    fun close(path: String) {
        val index = paths.indexOf(path)
        paths.remove(path); buffers.remove(path)
        if (active == path) active = paths.getOrNull(index.coerceAtMost(paths.lastIndex))
    }
    fun snapshot() = EditorTabsSnapshot(paths.toList(), active, requestId, paths.map { path ->
        buffers[path]?.let { EditorTabPosition(path, it.selectionStart, it.selectionEnd, it.offsetX, it.offsetY) }
            ?: positions[path] ?: EditorTabPosition(path)
    })
}

/** Only document state is retained; inactive tabs never hold an Activity or native editor view. */
internal class EditorTabsStore(private val root: File, private val scope: CoroutineScope) {
    private val sessions = mutableMapOf<String, EditorWorkspace>()
    private val loading = Mutex()
    private val writes = Mutex()
    private val pending = mutableMapOf<String, String>()
    private val json = Json { ignoreUnknownKeys = true }
    suspend fun workspace(key: String, identity: String): EditorWorkspace = loading.withLock {
        sessions[key] ?: run {
            val snapshot = withContext(Dispatchers.IO) {
                val file = File(root, "$key.json")
                if (!file.exists()) EditorTabsSnapshot() else {
                    check(file.length() < 256_000) { "Saved editor tabs could not be read." }
                    json.decodeFromString<EditorTabsSnapshot>(file.readText())
                }
            }
            EditorWorkspace(key, identity, snapshot).also { sessions[key] = it }
        }
    }
    fun invalidateSaved(identity: String, paths: Set<String>) {
        sessions.values.filter { it.identity == identity }.forEach { workspace ->
            paths.forEach { path -> if (workspace.buffers[path]?.dirty?.value != true) workspace.buffers.remove(path) }
            workspace.revision++
        }
    }
    fun persist(workspace: EditorWorkspace) {
        val text = json.encodeToString(workspace.snapshot())
        synchronized(pending) { pending[workspace.key] = text }
        scope.launch {
            writes.withLock {
                val latest = synchronized(pending) { pending.remove(workspace.key) } ?: return@withLock
                val result = withContext(Dispatchers.IO) { runCatching {
                    check(root.isDirectory || root.mkdirs())
                    val temporary = File(root, "${workspace.key}.tmp")
                    java.io.FileOutputStream(temporary).use { out -> out.write(latest.toByteArray()); out.fd.sync() }
                    check(temporary.renameTo(File(root, "${workspace.key}.json")))
                } }
                workspace.persistenceError = result.exceptionOrNull()?.message
            }
        }
    }
}
