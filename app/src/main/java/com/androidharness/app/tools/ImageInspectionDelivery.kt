package com.androidharness.app.tools

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ImageData
import com.androidharness.app.core.Role
import kotlinx.serialization.json.*

/** Metadata about a saved image is never a claim that its bytes reached this request. */
internal object ImageInspectionDelivery {
    private const val marker = "\n[screenshot result; page text read successfully]\n"
    private val contextErrors = setOf("not_in_current_context", "image_evicted", "images_disabled", "attachment_unavailable")
    data class Envelope(val prefix: String, val body: JsonObject)

    fun envelope(text: String): Envelope? {
        val prefix = text.indexOf(marker).takeIf { it >= 0 }?.let { text.substring(0, it + marker.length) }.orEmpty()
        val body = runCatching { Json.parseToJsonElement(text.removePrefix(prefix).substringBefore("\n[took ")) as? JsonObject }.getOrNull()
            ?.takeIf { "image_attached" in it } ?: return null
        return Envelope(prefix, body)
    }

    fun update(message: ChatMessage, data: List<ImageData>, error: String = "attachment_unavailable",
        note: String = "No image bytes could be attached. A saved-image reference alone is not visual evidence."): ChatMessage {
        if (message.role != Role.TOOL || message.toolName !in setOf("view_chat_image", "zoom_chat_image", "browser_read"))
            return message.copy(imageData = data)
        val parsed = envelope(message.text) ?: return message.copy(imageData = data)
        val body = parsed.body
        val previousError = (body["error"] as? JsonPrimitive)?.contentOrNull
        val recoverable = previousError == null || previousError in contextErrors
        val included = data.isNotEmpty() && recoverable
        val metadata = (body["attached_image"] as? JsonObject) ?: (body["available_image"] as? JsonObject)
        val wasIncluded = (body["image_attached"] as? JsonPrimitive)?.booleanOrNull == true
        val effectiveError = if (included) null else if (!wasIncluded && previousError != null) previousError else error
        val output = JsonObject(body + buildMap {
            put("ok", JsonPrimitive(included)); put("image_attached", JsonPrimitive(included)); put("delivered", JsonPrimitive(included))
            put("error", effectiveError?.let(::JsonPrimitive) ?: JsonNull)
            put("attached_image", if (included) metadata ?: JsonNull else JsonNull)
            put("available_image", if (!included) metadata ?: JsonNull else JsonNull)
            put("attached_bytes", if (included) metadata?.get("attached_bytes") ?: metadata?.get("bytes") ?: body["attached_bytes"] ?: JsonNull else JsonPrimitive(0))
            put("context_status", JsonPrimitive(if (included) "included" else "omitted"))
            if (!included && wasIncluded) put("message", JsonPrimitive(note))
            else if (included && !wasIncluded) put("message", JsonPrimitive("The saved image is attached to this request and is available for visual inspection."))
        })
        // A screenshot delivery issue does not turn a successful page-text read into a failed read.
        return message.copy(text = parsed.prefix + output, imageData = if (included) data else emptyList(),
            isError = if (parsed.prefix.isNotEmpty()) false else !included)
    }
}
