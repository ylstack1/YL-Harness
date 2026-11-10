package com.androidharness.app.tools

import com.androidharness.app.core.ImageRef
import com.androidharness.app.ui.chat.RemoteImageFetch
import kotlinx.serialization.json.*

internal fun imageInspectionFailure(error: String, description: String, source: ChatImageSource? = null, fetched: RemoteImageFetch? = null): ToolResult =
    ToolResult(false, buildJsonObject {
        put("ok", false); put("image_attached", false); put("delivered", false); put("attached_image", JsonNull); put("error", error); put("message", description)
        put("image_id", source?.id?.let(::JsonPrimitive) ?: JsonNull)
        put("source_url", (fetched?.url ?: (source as? ChatImageSource.Remote)?.url ?: (source as? ChatImageSource.Stored)?.ref?.sourceUrl)?.let(::JsonPrimitive) ?: JsonNull)
        fetched?.httpStatus?.let { put("http_status", it) }
        put("content_type", fetched?.mimeType?.let(::JsonPrimitive) ?: JsonNull)
        put("cached", fetched?.cached?.let(::JsonPrimitive) ?: JsonNull)
        put("width", fetched?.width?.let(::JsonPrimitive) ?: JsonNull)
        put("height", fetched?.height?.let(::JsonPrimitive) ?: JsonNull)
        put("source_bytes", fetched?.bytes?.size?.let(::JsonPrimitive) ?: JsonNull)
        put("attached_bytes", 0)
    }.toString())

internal fun imageInspectionSuccess(
    image: ImageRef,
    source: ChatImageSource,
    number: Int? = null,
    description: String = "The attached image is available for visual inspection.",
    httpStatus: Int? = null,
    cached: Boolean? = null,
    contentType: String? = null,
    sourceWidth: Int? = image.originalWidth ?: image.width,
    sourceHeight: Int? = image.originalHeight ?: image.height,
    sourceBytes: Long? = image.originalBytes ?: image.bytes,
    crop: List<Int>? = null,
    degraded: Boolean = false,
    originalAvailable: Boolean = true,
    fetchSource: String = if (cached == true) "cache" else "network",
): ToolResult = ToolResult(true, buildJsonObject {
    put("ok", true); put("image_attached", true); put("delivered", true); put("error", JsonNull); put("message", description)
    put("image_id", source.id); put("source_image_id", source.id)
    put("image_number", number?.let(::JsonPrimitive) ?: JsonNull)
    put("image_number_scope", "assistant_turn")
    put("display_url", com.androidharness.app.core.ChatResponseImages.url(image.imageId ?: source.id))
    put("source_url", ((source as? ChatImageSource.Remote)?.url ?: image.sourceUrl)?.let(::JsonPrimitive) ?: JsonNull)
    put("resolved_url", image.sourceUrl?.let(::JsonPrimitive) ?: JsonNull)
    put("caption", source.label); put("origin", source.origin); put("display_role", source.displayRole)
    httpStatus?.let { put("http_status", it) }
    put("fetch_source", fetchSource)
    put("content_type", (contentType ?: image.originalMime ?: image.mime))
    put("width", sourceWidth?.let(::JsonPrimitive) ?: JsonNull); put("height", sourceHeight?.let(::JsonPrimitive) ?: JsonNull)
    put("source_bytes", sourceBytes?.let(::JsonPrimitive) ?: JsonNull)
    put("attached_bytes", image.bytes?.let(::JsonPrimitive) ?: JsonNull)
    put("cached", cached?.let(::JsonPrimitive) ?: JsonNull)
    put("degraded", degraded); put("original_available", originalAvailable)
    // Coordinates map to the actual decoded source, including reduced-copy fallback.
    val rect = if (sourceWidth != null && sourceHeight != null) {
        val c = crop ?: listOf(0, 0, 1000, 1000)
        listOf((c[0].toLong() * sourceWidth / 1000).toInt(), (c[1].toLong() * sourceHeight / 1000).toInt(),
            ((c[2].toLong() * sourceWidth + 999) / 1000).toInt(), ((c[3].toLong() * sourceHeight + 999) / 1000).toInt())
    } else null
    if (rect != null) putJsonObject("source_region") {
        put("left", rect[0]); put("top", rect[1]); put("right", rect[2]); put("bottom", rect[3])
    }
    putJsonObject("attached_image") {
        put("attachment_id", image.imageId ?: source.id)
        put("mime_type", image.mime); put("width", image.width?.let(::JsonPrimitive) ?: JsonNull)
        put("height", image.height?.let(::JsonPrimitive) ?: JsonNull)
        put("attached_bytes", image.bytes?.let(::JsonPrimitive) ?: JsonNull)
        if (rect != null && rect[2] > rect[0] && rect[3] > rect[1]) {
            image.width?.let { put("scale_x", it.toDouble() / (rect[2] - rect[0])) }
            image.height?.let { put("scale_y", it.toDouble() / (rect[3] - rect[1])) }
        }
    }
    if (crop != null) putJsonArray("crop_0_to_1000") { crop.forEach { add(it) } }
}.toString(), image = image)
