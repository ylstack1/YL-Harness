package com.androidharness.app.tools

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import kotlinx.serialization.json.*

class ViewChatImageTool(private val images: com.androidharness.app.data.ImageStore, private val history: () -> List<ChatMessage> = { emptyList() }) : Tool {
    private var sourceLookup: (() -> List<ChatImageSource>)? = null
    internal fun forInventory(lookup: () -> List<ChatImageSource>) = ViewChatImageTool(images, history).apply { sourceLookup = lookup }
    override val name = "view_chat_image"
    override val description = "Inspect a full image. Provide exactly one of image_id (preferred), image_number from the current chat image list, or a direct public image URL. The result explicitly reports success/failure and identifies the attached image. Do not describe an image if image_attached is false."
    override val parametersSchema = Schema.obj(mapOf("url" to Schema.string("A direct public image URL; omit for a chat image."),
        "image_id" to Schema.string("Stable image_id from the current chat's image list."),
        "image_number" to Schema.integer("Position in the current chat image list. Prefer image_id.")))
    override val isReadOnly = true
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!ctx.imageInputAllowed) return@withContext imageInspectionFailure("images_disabled", "This model cannot receive image input.")
        val selectors = listOf("url", "image_id", "image_number").count { it in args }
        if (selectors != 1) return@withContext imageInspectionFailure("invalid_selection", "Provide exactly one of url, image_id, or image_number.")
        val sources = sourceLookup?.invoke() ?: ChatImageInventory.from(history())
        val source = if ("url" in args) {
            val raw = (args["url"] as? JsonPrimitive)?.contentOrNull
            val url = httpImageUrl(raw) ?: return@withContext imageInspectionFailure("invalid_url", "Provide a direct http(s) image URL.")
            ChatImageSource.Remote(url, "Image from $url", displayRole = "tool")
        } else {
            if (ctx.sessionId == null) return@withContext imageInspectionFailure("image_not_found", "Open a chat first.")
            ChatImageInventory.select(args, sources) ?: return@withContext imageInspectionFailure("image_not_found", "That image is not in the current chat image list.")
        }
        val number = sources.indexOfFirst { it.id == source.id }.takeIf { it >= 0 }?.plus(1)
        try {
            when (source) {
                is ChatImageSource.Stored -> {
                    val ref = images.metadata(source.ref).copy(imageId = source.id)
                    if (images.savedImageFile(ref.name) == null || images.resolve(ref) == null) return@withContext imageInspectionFailure("original_evicted_no_fallback", "Neither the original nor a usable saved copy is available. Reattach the image to inspect it.", source)
                    if (ref.width == null || ref.height == null) return@withContext imageInspectionFailure("decode_failed", "The saved image cannot be decoded.", source)
                    val originalAvailable = images.originalFile(ref.name).isFile
                    imageInspectionSuccess(ref, source, number, cached = true, degraded = !originalAvailable,
                        originalAvailable = originalAvailable, fetchSource = "saved_copy",
                        description = if (originalAvailable) "The attached full frame is available for visual inspection."
                            else "The original is unavailable; the attached full frame uses the saved reduced copy. Fine detail may be lost.")
                }
                is ChatImageSource.Remote -> {
                    val fetched = images.fetchRemote(source.url)
                    if (fetched.error != null) return@withContext imageInspectionFailure(fetched.error, "Image inspection failed. No image was attached; do not invent its visual details.", source, fetched)
                    val ref = images.saveRemote(fetched, source.id, source.label)
                        ?: return@withContext imageInspectionFailure("decode_failed", "The image could not be decoded for the model.", source, fetched)
                    imageInspectionSuccess(ref, source, number, httpStatus = fetched.httpStatus, cached = fetched.cached,
                        contentType = fetched.mimeType, sourceWidth = fetched.width, sourceHeight = fetched.height, sourceBytes = fetched.bytes?.size?.toLong())
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { imageInspectionFailure("attachment_failed", "Could not prepare the image attachment.", source) }
    }
}

/** Reads conversation input, never a workspace or a user-supplied filesystem path. */
class ReadChatTextTool(private val load: suspend (String) -> List<ChatMessage>) : Tool {
    override val name = "read_chat_text"
    override val description = "Read user-supplied text and inline text-file attachments in this chat. Omit message_id for the latest user message. No filesystem access."
    override val parametersSchema = Schema.obj(mapOf(
        "message_id" to Schema.string("Optional user message id from this chat."),
        "offset" to Schema.integer("1-based character offset, default 1."),
        "limit" to Schema.integer("Characters to read, 1..12000, default 6000."),
    ))
    override val isReadOnly = true
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val sid = ctx.sessionId ?: throw ToolFailure("Open a chat first.")
        val id = args["message_id"]?.jsonPrimitive?.content
        val message = load(sid).lastOrNull { it.role == Role.USER && (id == null || it.id == id) }
            ?: throw ToolFailure("No matching user message in this chat.")
        val offset = args["offset"]?.jsonPrimitive?.intOrNull ?: 1
        val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 6000
        if (offset < 1 || limit !in 1..12000) throw ToolFailure("Use a positive offset and a limit of 1..12000.")
        val text = message.text
        val start = (offset - 1).coerceAtMost(text.length)
        return ToolResult(true, "User-supplied text (${text.length} characters), message_id=${message.id.orEmpty()}:\n" + text.substring(start, minOf(start + limit, text.length)))
    }
}

class OfferCodeHandoffTool : Tool {
    override val name = "offer_code_handoff"
    override val description = "Offer a Continue in Code card only when the user requests Code or project editing/execution is needed. Does not navigate, run commands, or change files."
    override val parametersSchema = Schema.obj(mapOf(
        "title" to Schema.string("Concise action title for the card in the user's language, such as Build your reading tracker. Up to 80 characters."),
        "reason" to Schema.string("One short sentence describing the project editing or execution needed, in the user's language. Up to 400 characters."),
    ), required = listOf("reason"))
    override val isReadOnly = true
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val reason = args["reason"]?.jsonPrimitive?.content?.trim().orEmpty()
        val title = args["title"]?.jsonPrimitive?.content?.trim()
        if (title != null && (title.isBlank() || title.length > 80)) throw ToolFailure("Provide an action title of 1 to 80 characters, or omit it.")
        if (reason.isBlank() || reason.length > 400) throw ToolFailure("Provide a short reason, up to 400 characters.")
        return ToolResult(true, "Continue in Code is offered. The user must choose it before a Code conversation is created.")
    }
}
