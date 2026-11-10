package com.androidharness.app.tools

import com.androidharness.app.artifacts.ChatArtifact
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

typealias ArtifactLoader = suspend (sessionId: String) -> List<ChatArtifact>

/** Creates a message attachment, never a workspace file or an executable native action. */
class CreateArtifactTool(private val load: ArtifactLoader) : Tool {
    override val name = "create_artifact"
    override val description = "Show an interactive visual concept, UI mockup, chart, diagram, or small tool directly in this chat. " +
        "Provide complete self-contained HTML with inline CSS and JavaScript (up to 256 KB). " +
        "The preview has no network, files, cookies, native bridge, popups, or permissions. Use inline SVG/canvas for graphics; no CDN dependencies. " +
        "For revisions, pass this chat's existing artifact_id and the complete updated HTML. This does not modify workspace files."
    override val parametersSchema = Schema.obj(mapOf(
        "title" to Schema.string("Short descriptive title, up to 80 characters."),
        "html" to Schema.string("Complete responsive HTML document with inline CSS/JS. Must work offline at phone widths."),
        "artifact_id" to Schema.string("Optional existing artifact_id from this chat, only when revising a concept."),
    ), required = listOf("title", "html"))
    // Like ask_user/todo_write: this only produces conversation content, including in Plan mode.
    override val isReadOnly = true
    override fun isAvailable(ctx: ToolContext) = ctx.sessionId != null
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val session = ctx.sessionId ?: throw ToolFailure("Open a chat before creating a concept.")
        fun string(key: String): String? = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val id = string("artifact_id")
        if (id != null && load(session).none { it.id == id }) throw ToolFailure("That concept is not in this chat. Create a new concept or use an artifact_id from this chat.")
        val artifact = try { ChatArtifact.create(
            string("title") ?: throw ToolFailure("Provide a title."),
            string("html") ?: throw ToolFailure("Provide the complete HTML."), id,
        ) } catch (e: IllegalArgumentException) { throw ToolFailure(e.message ?: "Invalid concept.") }
        return ToolResult(true, buildJsonObject {
            put("artifact_id", artifact.id); put("revision", artifact.revision); put("title", artifact.title)
            put("status", "Shown as an interactive concept in this chat. The user can preview, expand, view source, and save it.")
        }.toString(), artifact = artifact)
    }
}

class ReadArtifactTool(private val load: ArtifactLoader) : Tool {
    override val name = "read_artifact"
    override val description = "Read saved HTML source of a concept in this chat before revising it with create_artifact. By default reads the latest revision; provide revision to inspect an attached older version. Omit artifact_id to list this chat's concepts."
    override val parametersSchema = Schema.obj(mapOf(
        "artifact_id" to Schema.string("Concept identifier returned by create_artifact."),
        "revision" to Schema.string("Optional exact revision from the attached concept. Omit for the latest version."),
    ))
    override val isReadOnly = true
    override fun isAvailable(ctx: ToolContext) = ctx.sessionId != null
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val session = ctx.sessionId ?: throw ToolFailure("Open a chat first.")
        val history = load(session)
        val artifacts = history.asReversed().distinctBy { it.id }
        val revision = (args["revision"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val id = (args["artifact_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (id == null) return ToolResult(true, artifacts.joinToString("\n") { "${it.id}: ${it.title}" }.ifEmpty { "No concepts in this chat yet." })
        val artifact = history.lastOrNull { it.id == id && (revision == null || it.revision == revision) } ?: throw ToolFailure("That concept is not in this chat.")
        return ToolResult(true, ChatArtifact.encode(artifact))
    }
}
