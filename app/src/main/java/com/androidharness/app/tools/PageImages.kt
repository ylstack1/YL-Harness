package com.androidharness.app.tools

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** An image found on a page the agent actually fetched in this turn. */
data class PageImage(
    val title: String,
    val imageUrl: String,
    val pageUrl: String,
    val source: String,
)

/** One image reference lifted from raw HTML, with its alt text when present. */
internal data class PageImageRef(val url: String, val alt: String?)

/** Page title plus image references from a single scan of the HTML. */
internal data class PageImageScan(val title: String?, val images: List<PageImageRef>)

/**
 * The page image block inside web_fetch output. The tool writes it, the chat
 * UI parses it, and both sides keep the same numbered shape.
 */
internal object PageImageText {
    const val MARKER = "[images on this page]"
    private val numbered = Regex("^\\d+\\.\\s+(.+)$")

    fun render(images: List<PageImage>, count: Int): String = buildString {
        append(MARKER).append('\n')
        images.mapNotNull { image -> httpImageUrl(image.imageUrl)?.let { image.copy(imageUrl = it) } }
            .distinctBy { it.imageUrl }.take(count).forEachIndexed { index, image ->
            append(index + 1).append(". ").append(image.title.replace('\n', ' ').take(200)).append('\n')
            append("   image: ").append(image.imageUrl).append('\n')
            append("   page: ").append(image.pageUrl).append('\n')
            append("   source: ").append(image.source).append('\n')
        }
    }

    fun parse(text: String): List<PageImage> {
        if (text.isBlank()) return emptyList()
        val lines = text.lineSequence().toList()
        val start = lines.indexOfFirst { it.trim() == MARKER }
        if (start < 0) return emptyList()
        val out = ArrayList<PageImage>()
        var index = start + 1
        while (index < lines.size) {
            val line = lines[index].trim()
            if (line.startsWith("[")) break
            val title = numbered.matchEntire(line)?.groupValues?.get(1)?.trim()
            if (title == null) {
                index++
                continue
            }
            var image: String? = null
            var page: String? = null
            var source = ""
            var cursor = index + 1
            while (cursor < lines.size) {
                val field = lines[cursor].trim()
                if (numbered.matches(field) || field.startsWith("[")) break
                when {
                    field.startsWith("image:") -> image = field.removePrefix("image:").trim()
                    field.startsWith("page:") -> page = field.removePrefix("page:").trim()
                    field.startsWith("source:") -> source = field.removePrefix("source:").trim()
                }
                cursor++
            }
            val imageUrl = httpImageUrl(image)
            val pageUrl = httpPageUrl(page)
            if (imageUrl != null && pageUrl != null) {
                out += PageImage(
                    title = title.ifBlank { "Image" },
                    imageUrl = imageUrl,
                    pageUrl = pageUrl,
                    source = source.ifBlank { pageUrl.toHttpUrlOrNull()?.host ?: "Web" },
                )
            }
            index = cursor
        }
        return out.distinctBy { it.imageUrl }
    }
}

internal fun httpImageUrl(raw: String?): String? {
    val value = decodePageEntities(raw?.trim().orEmpty())
    if (!value.startsWith("http://") && !value.startsWith("https://")) return null
    val url = value.toHttpUrlOrNull() ?: return null
    if (url.encodedPath.endsWith(".svg", ignoreCase = true) || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    val cleaned = url.newBuilder().fragment(null)
    // Only tracking and opaque version tokens are discarded. Size/format and
    // signed URL parameters can identify different images and must be retained.
    url.queryParameterNames.filter { key -> key.startsWith("utm_", true) || key in setOf("fbclid", "gclid") ||
        url.encodedPath.substringAfterLast('/').startsWith("opengraph-image") &&
            key.matches(Regex("[0-9a-fA-F]{8,64}")) && url.queryParameterValues(key).all { it.isNullOrEmpty() }
    }.forEach(cleaned::removeAllQueryParameters)
    return cleaned.build().toString()
}

internal fun httpPageUrl(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (!value.startsWith("http://") && !value.startsWith("https://")) return null
    return value.toHttpUrlOrNull()?.toString()
}

internal fun cleanPageImageText(value: String?): String? {
    val text = value
        ?.replace(Regex("[\\uE000\\uE001]"), "")
        ?.replace(Regex("<[^>]+>"), " ")
        ?.let(::decodePageEntities)
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?: return null
    return text.takeIf { it.isNotEmpty() }
}

internal fun decodePageEntities(value: String): String = value
    .replace("&quot;", "\"")
    .replace("&#34;", "\"")
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&#39;", "'")
    .replace("&apos;", "'")
    .replace("&nbsp;", " ")
    .replace(Regex("&#(\\d+);")) { codePoint(it.groupValues[1].toIntOrNull()) }
    .replace(Regex("&#[xX]([0-9a-fA-F]+);")) { codePoint(it.groupValues[1].toIntOrNull(16)) }

private fun codePoint(value: Int?): String {
    if (value == null || value <= 0 || value > 0x10FFFF) return ""
    return runCatching { String(Character.toChars(value)) }.getOrDefault("")
}
