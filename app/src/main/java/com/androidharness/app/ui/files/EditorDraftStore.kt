package com.androidharness.app.ui.files

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class EditorDraft(
    val version: Int = 1,
    val text: String,
    val baseline: String,
    val saved: String,
    val diskVersion: String,
    val charset: String,
    val bom: Boolean,
    val eol: String,
    val missingTrailingNewline: Boolean,
    val editing: Boolean,
) {
    fun decoded() = EditorFileCodec.Decoded(saved, Charset.forName(charset), bom, eol, missingTrailingNewline)
}

internal enum class EditorDraftStatus { IDLE, SAVING, SAVED, FAILED }

/** App-lifetime writer: closing an editor cannot cancel a pending draft write. */
internal class EditorDraftStore(
    private val root: File,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val debounceMillis: Long = 250,
) {
    private data class Pending(val draft: EditorDraft?)
    private val gate = Any()
    private val writes = Mutex()
    private val pending = mutableMapOf<String, Pending>()
    private val jobs = mutableMapOf<String, Job>()
    private val statuses = MutableStateFlow<Map<String, EditorDraftStatus>>(emptyMap())
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun status(key: String): Flow<EditorDraftStatus> = statuses.map { it[key] ?: EditorDraftStatus.IDLE }.distinctUntilChanged()

    fun submit(key: String, draft: EditorDraft?, immediate: Boolean = false) {
        synchronized(gate) {
            val request = Pending(draft)
            pending[key] = request
            jobs.remove(key)?.cancel()
            statuses.update { it + (key to EditorDraftStatus.SAVING) }
            jobs[key] = scope.launch {
                if (!immediate) delay(debounceMillis)
                writePending(key, request)
            }
        }
    }

    suspend fun flush(key: String) {
        val request = synchronized(gate) { pending[key] } ?: return
        writePending(key, request)
    }

    suspend fun read(key: String): EditorDraft? = withContext(Dispatchers.IO) {
        writes.withLock {
            val queued = synchronized(gate) { pending[key] }
            if (queued != null) return@withLock queued.draft
            val file = file(key)
            if (!file.exists()) return@withLock null
            check(file.length() <= 128_000_000L) { "The editor recovery file is too large to read." }
            val draft = json.decodeFromString<EditorDraft>(file.readText())
            check(draft.version == 1 && draft.charset in setOf("UTF-8", "UTF-16LE", "UTF-16BE") &&
                draft.eol in setOf("\n", "\r\n") &&
                (draft.diskVersion == "missing" || draft.diskVersion.matches(Regex("[a-f0-9]{64}")))) {
                "The editor recovery file could not be read. It has been kept on the device."
            }
            statuses.update { it + (key to EditorDraftStatus.SAVED) }
            draft
        }
    }

    private suspend fun writePending(key: String, request: Pending) = withContext(Dispatchers.IO) {
        writes.withLock {
            if (synchronized(gate) { pending[key] !== request }) return@withLock
            try {
                check(root.isDirectory || root.mkdirs()) { "Could not create editor recovery storage." }
                val target = file(key)
                if (request.draft == null) {
                    check(!target.exists() || target.delete()) { "Could not remove the saved draft." }
                } else {
                    val temporary = File(root, "${target.name}.tmp")
                    try {
                        FileOutputStream(temporary).use { stream ->
                            stream.write(json.encodeToString(EditorDraft.serializer(), request.draft).toByteArray(Charsets.UTF_8))
                            stream.fd.sync()
                        }
                        // Same-directory rename replaces the previous complete record atomically.
                        check(temporary.renameTo(target)) { "Could not finish saving the editor draft." }
                    } finally { temporary.delete() }
                }
                synchronized(gate) {
                    if (pending[key] === request) {
                        pending.remove(key)
                        jobs.remove(key)
                        statuses.update { it + (key to EditorDraftStatus.SAVED) }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                synchronized(gate) {
                    if (pending[key] === request) statuses.update { it + (key to EditorDraftStatus.FAILED) }
                }
            }
        }
    }

    private fun file(key: String) = File(root, "$key.json")

    companion object {
        fun key(sessionId: String?, projectId: String?, workspace: String, path: String): String {
            val identity = listOf(sessionId.orEmpty(), projectId.orEmpty(), workspace,
                com.androidharness.app.workspace.normalizeRelPath(path)).joinToString("") { "${it.length}:$it" }
            return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
