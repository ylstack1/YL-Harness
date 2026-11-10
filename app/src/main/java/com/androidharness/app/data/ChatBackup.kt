package com.androidharness.app.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.androidharness.app.data.db.AppDatabase
import com.androidharness.app.data.db.MessageEntity
import com.androidharness.app.data.db.SessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class ChatBackupException(message: String) : Exception(message)

/**
 * Chats-only backup file: sessions with their messages, the workspaces those
 * chats used, saved memories, and prompt snippets. No API keys, provider
 * config, MCP servers, or settings ever enter the file, so it is safe to keep
 * anywhere. toolCallsJson and imagesJson ride along as the exact strings stored
 * in the database, lossless even if those payloads evolve in later app versions.
 */
@Serializable
data class ChatBackupFile(
    val format: String = ChatBackupCodec.FORMAT,
    val version: Int = ChatBackupCodec.VERSION,
    val exportedAt: Long = 0,
    val sessions: List<BackupSession> = emptyList(),
    val workspaces: List<BackupWorkspace> = emptyList(),
    val memories: List<BackupMemory> = emptyList(),
    val snippets: List<BackupSnippet> = emptyList(),
)

@Serializable
data class BackupWorkspace(val id: String, val name: String)

data class ChatImportPreview(
    val sessions: List<BackupSession>,
    val missingWorkspaces: List<BackupWorkspace>,
    val skipped: Int,
    val memories: List<BackupMemory> = emptyList(),
    val snippets: List<BackupSnippet> = emptyList(),
)

internal object ChatRestoreMapping {
    const val UNASSIGNED = "legacy-unassigned-workspace"
    fun referenced(session: BackupSession): Set<String> = buildSet {
        add(session.projectId ?: UNASSIGNED)
        session.workspaceSelectionJson?.let { raw ->
            val selection = com.androidharness.app.workspace.ChatWorkspaceSelection.decode(raw)
            require(selection.primaryId == session.projectId) { "A restored chat has inconsistent workspace access." }
            addAll(selection.grants.map { it.projectId })
        }
    }
    fun remap(session: BackupSession, mapping: Map<String, String>): BackupSession {
        val primary = session.projectId ?: UNASSIGNED
        val selection = session.workspaceSelectionJson?.let {
            val saved = com.androidharness.app.workspace.ChatWorkspaceSelection.decode(it)
            val grants = saved.grants.map { grant -> grant.copy(projectId = mapping[grant.projectId] ?: grant.projectId) }
            com.androidharness.app.workspace.ChatWorkspaceSelection.encode(
                saved.copy(primaryId = mapping[saved.primaryId] ?: saved.primaryId, grants = grants).validated())
        }
        return session.copy(projectId = mapping[primary] ?: session.projectId, workspaceSelectionJson = selection)
    }
}

@Serializable
data class BackupSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val totalInputTokens: Long = 0,
    val totalOutputTokens: Long = 0,
    val totalCachedTokens: Long = 0,
    val totalCacheWriteTokens: Long = 0,
    val requestCount: Long = 0,
    val lastInputTokens: Long = 0,
    val projectId: String? = null,
    val compactionSummary: String = "",
    val compactionBefore: Long = 0,
    val messages: List<BackupMessage> = emptyList(),
    val modelSelectionJson: String? = null,
    val workspaceSelectionJson: String? = null,
    val conversationJson: String? = null,
)

@Serializable
data class BackupMessage(
    val id: String,
    val role: String,
    val text: String,
    val toolCallsJson: String = "[]",
    val toolCallId: String? = null,
    val toolName: String? = null,
    val isError: Boolean = false,
    val thinking: String = "",
    val thinkingMs: Long = 0,
    val outputTokens: Int = 0,
    val generationMs: Long = 0,
    val firstTokenMs: Long = 0,
    val streamMs: Long = 0,
    val imagesJson: String = "[]",
    val turnId: String? = null,
    val createdAt: Long,
    val artifactJson: String? = null,
    val generationJson: String? = null,
)

/** Pure encode/decode plus file validation; no Android types, so JVM-testable. */
object ChatBackupCodec {
    const val FORMAT = "androidharness-chats"
    const val VERSION = 5

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(file: ChatBackupFile): String =
        json.encodeToString(ChatBackupFile.serializer(), file)

    /** Decodes and validates; throws [ChatBackupException] with a user-facing message. */
    fun decode(text: String): ChatBackupFile {
        val file = try {
            json.decodeFromString(ChatBackupFile.serializer(), text)
        } catch (e: Exception) {
            throw ChatBackupException("Not a chat backup file")
        }
        if (file.format != FORMAT) throw ChatBackupException("Not a chat backup file")
        if (file.version > VERSION) {
            throw ChatBackupException("This backup was written by a newer version of the app")
        }
        return file
    }
}

/**
 * Pure import planning: sessions already in the database (by id) are skipped,
 * so re-importing the same file is a no-op instead of a duplicate flood.
 */
object ChatBackupMerge {
    data class Plan(val toImport: List<BackupSession>, val skipped: Int)

    fun plan(existingSessionIds: Set<String>, incoming: List<BackupSession>): Plan {
        val fresh = incoming.filterNot { it.id in existingSessionIds }
        return Plan(fresh, incoming.size - fresh.size)
    }
}

/**
 * Exports and imports chats through SAF uris. Export writes every session
 * with its full message list; import merges, skipping sessions whose id
 * already exists. All work runs on Dispatchers.IO; callers surface errors.
 */
class ChatBackupManager(
    private val db: AppDatabase,
    private val appContext: Context,
    private val workspace: com.androidharness.app.workspace.WorkspaceManager = com.androidharness.app.workspace.WorkspaceManager(appContext, db.dao()),
) {
    data class ExportResult(val sessions: Int, val messages: Int, val memories: Int = 0, val snippets: Int = 0)
    data class ImportResult(val sessions: Int, val messages: Int, val skipped: Int, val memories: Int = 0, val snippets: Int = 0)

    suspend fun exportTo(uri: Uri): ExportResult = withContext(Dispatchers.IO) {
        val sessions = db.dao().allSessions()
        val bySession = db.dao().allMessages().groupBy { it.sessionId }
        val file = ChatBackupFile(
            exportedAt = System.currentTimeMillis(),
            workspaces = db.dao().projectsFlow().first().map { BackupWorkspace(it.id, it.name) },
            memories = db.dao().allMemories().map { it.toBackupMemory() },
            snippets = db.dao().allSnippets().map { it.toBackupSnippet() },
            sessions = sessions.map { s ->
                BackupSession(
                    id = s.id,
                    title = s.title,
                    createdAt = s.createdAt,
                    updatedAt = s.updatedAt,
                    totalInputTokens = s.totalInputTokens,
                    totalOutputTokens = s.totalOutputTokens,
                    totalCachedTokens = s.totalCachedTokens,
                    totalCacheWriteTokens = s.totalCacheWriteTokens,
                    requestCount = s.requestCount,
                    lastInputTokens = s.lastInputTokens,
                    projectId = s.projectId,
                    compactionSummary = s.compactionSummary,
                    compactionBefore = s.compactionBefore,
                    modelSelectionJson = s.modelSelectionJson,
                    workspaceSelectionJson = s.workspaceSelectionJson,
                    conversationJson = s.conversationJson,
                    messages = bySession[s.id].orEmpty().map { m ->
                        BackupMessage(
                            id = m.id,
                            role = m.role,
                            text = m.text,
                            toolCallsJson = m.toolCallsJson,
                            toolCallId = m.toolCallId,
                            toolName = m.toolName,
                            isError = m.isError,
                            thinking = m.thinking,
                            thinkingMs = m.thinkingMs,
                            outputTokens = m.outputTokens,
                            generationMs = m.generationMs,
                            firstTokenMs = m.firstTokenMs,
                            streamMs = m.streamMs,
                            generationJson = m.generationJson,
                            imagesJson = m.imagesJson,
                            artifactJson = m.artifactJson,
                            turnId = m.turnId,
                            createdAt = m.createdAt,
                        )
                    },
                )
            },
        )
        val out = appContext.contentResolver.openOutputStream(uri)
            ?: throw ChatBackupException("Could not open the file for writing")
        out.use { stream ->
            stream.buffered().writer(Charsets.UTF_8).use { it.write(ChatBackupCodec.encode(file)) }
        }
        ExportResult(file.sessions.size, file.sessions.sumOf { it.messages.size }, file.memories.size, file.snippets.size)
    }

    suspend fun prepareImport(uri: Uri): ChatImportPreview = withContext(Dispatchers.IO) {
        val text = appContext.contentResolver.openInputStream(uri)?.use {
            it.bufferedReader(Charsets.UTF_8).readText()
        } ?: throw ChatBackupException("Could not open the file")
        val file = ChatBackupCodec.decode(text)
        val plan = ChatBackupMerge.plan(db.dao().allSessionIds().toSet(), file.sessions)
        val available = db.dao().projectsFlow().first().map { it.id }.toSet()
        val memoryProjects = file.memories.filter { it.scope == MemoryScope.PROJECT.name }
            .mapNotNull { it.projectId?.takeIf(String::isNotBlank) }
        val missing = (plan.toImport.flatMap { ChatRestoreMapping.referenced(it) } + memoryProjects).distinct().filterNot { it in available }
            .map { id -> BackupWorkspace(id, file.workspaces.firstOrNull { it.id == id }?.name
                ?: if (id == ChatRestoreMapping.UNASSIGNED) "Chats without a workspace" else
                    plan.toImport.firstOrNull { it.projectId == id }?.title?.let { "Workspace for $it" } ?: "Restored workspace") }
        ChatImportPreview(plan.toImport, missing, plan.skipped, file.memories, file.snippets)
    }

    suspend fun importFrom(uri: Uri): ImportResult = importPrepared(prepareImport(uri), emptyMap())

    suspend fun importPrepared(preview: ChatImportPreview, workspaceMapping: Map<String, String>): ImportResult = withContext(Dispatchers.IO) {
        require(preview.missingWorkspaces.all { !workspaceMapping[it.id].isNullOrBlank() }) {
            "Choose a workspace for each restored project before importing."
        }
        var imported = emptyList<BackupSession>()
        var importedMemories = 0
        var importedSnippets = 0
        db.withTransaction {
            val available = db.dao().projectsFlow().first().map { it.id }.toSet()
            val fresh = ChatBackupMerge.plan(db.dao().allSessionIds().toSet(), preview.sessions)
            imported = fresh.toImport.map { session ->
                val remapped = ChatRestoreMapping.remap(session, workspaceMapping)
                require(ChatRestoreMapping.referenced(remapped).all { it in available }) {
                    "A chosen workspace is no longer available. Review the restored projects again."
                }
                remapped.workspaceSelectionJson?.let { workspace.validateSelection(com.androidharness.app.workspace.ChatWorkspaceSelection.decode(it)) }
                remapped
            }
            val plan = ChatBackupMerge.Plan(imported, fresh.skipped)
            if (plan.toImport.isNotEmpty()) {
                for (s in plan.toImport) {
                    db.dao().insertSession(
                        SessionEntity(
                            id = s.id,
                            title = s.title,
                            createdAt = s.createdAt,
                            updatedAt = s.updatedAt,
                            totalInputTokens = s.totalInputTokens,
                            totalOutputTokens = s.totalOutputTokens,
                            totalCachedTokens = s.totalCachedTokens,
                            totalCacheWriteTokens = s.totalCacheWriteTokens,
                            requestCount = s.requestCount,
                            lastInputTokens = s.lastInputTokens,
                            projectId = s.projectId,
                            compactionSummary = s.compactionSummary,
                            compactionBefore = s.compactionBefore,
                            modelSelectionJson = s.modelSelectionJson,
                            workspaceSelectionJson = s.workspaceSelectionJson,
                            conversationJson = s.conversationJson,
                        )
                    )
                    val messages = s.messages.map { m ->
                        MessageEntity(
                            id = m.id,
                            sessionId = s.id,
                            role = m.role,
                            text = m.text,
                            toolCallsJson = m.toolCallsJson,
                            toolCallId = m.toolCallId,
                            toolName = m.toolName,
                            isError = m.isError,
                            thinking = m.thinking,
                            thinkingMs = m.thinkingMs,
                            outputTokens = m.outputTokens,
                            generationMs = m.generationMs,
                            firstTokenMs = m.firstTokenMs,
                            streamMs = m.streamMs,
                            generationJson = m.generationJson,
                            imagesJson = m.imagesJson,
                            artifactJson = m.artifactJson,
                            turnId = m.turnId,
                            createdAt = m.createdAt,
                        )
                    }
                    if (messages.isNotEmpty()) db.dao().insertImportedMessages(messages)
                }
            }
            val memoryPlan = MemoryBackupMerge.plan(db.dao().allMemories(), preview.memories) { id ->
                id?.let { workspaceMapping[it] ?: it }
            }
            val now = System.currentTimeMillis()
            memoryPlan.forEach { db.dao().upsertMemory(MemoryBackupMerge.entity(it, now)) }
            importedMemories = memoryPlan.size
            val snippetPlan = SnippetBackupMerge.plan(db.dao().allSnippets(), preview.snippets)
            snippetPlan.forEach { db.dao().insertSnippet(SnippetBackupMerge.entity(it)) }
            importedSnippets = snippetPlan.size
        }
        ImportResult(imported.size, imported.sumOf { it.messages.size }, preview.skipped + preview.sessions.size - imported.size,
            importedMemories, importedSnippets)
    }
}
