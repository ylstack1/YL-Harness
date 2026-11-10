package com.androidharness.app.tools

import com.androidharness.app.data.WorkspaceFileHistory
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class FileHistoryTool(private val history: WorkspaceFileHistory) : Tool {
    override val name = "file_history"
    override val description = "List saved file versions for this chat and workspace. Copies survive app restarts and undo. Use file_restore to recover a missing file or an older version."
    override val parametersSchema = Schema.obj(mapOf("path" to Schema.string("Workspace-relative file path.")), listOf("path"))
    override val isReadOnly = true
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val sid = ctx.sessionId ?: throw ToolFailure("Open a chat to inspect file history")
        val path = args["path"]?.jsonPrimitive?.content ?: throw ToolFailure("Missing required argument: path")
        val versions = history.versions(sid, ctx.workspace, path)
        return ToolResult(true, if (versions.isEmpty()) "No saved versions for $path." else versions.take(50).joinToString("\n") {
            "${it.id} | ${java.time.Instant.ofEpochMilli(it.createdAt)} | ${if (it.deleted) "deleted" else "${it.bytes} bytes"} | SHA-256 ${it.sha256} | ${it.reason}"
        })
    }
}

class FileRestoreTool(private val history: WorkspaceFileHistory) : Tool {
    override val name = "file_restore"
    override val description = "Recover a saved version from file_history. Restores missing files by default. To overwrite an existing file, inspect it first and supply its exact current SHA-256; the current version is backed up before replacement."
    override val parametersSchema = Schema.obj(mapOf(
        "path" to Schema.string("Workspace-relative file path."),
        "version" to Schema.string("Version id returned by file_history for this path."),
        "expected_sha256" to Schema.string("Current file SHA-256, or missing (default)."),
    ), listOf("path", "version"))
    override val isReadOnly = false
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val sid = ctx.sessionId ?: throw ToolFailure("Open a chat to recover a file")
        val path = args["path"]?.jsonPrimitive?.content ?: throw ToolFailure("Missing required argument: path")
        val version = args["version"]?.jsonPrimitive?.content ?: throw ToolFailure("Missing required argument: version")
        val restored = history.restore(sid, ctx.workspace, path, version,
            args["expected_sha256"]?.jsonPrimitive?.content ?: "missing")
        return ToolResult(true, "Restored $path (${restored.bytes} bytes; SHA-256 ${restored.sha256}).")
    }
}
