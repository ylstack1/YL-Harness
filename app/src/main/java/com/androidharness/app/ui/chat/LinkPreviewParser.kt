package com.androidharness.app.ui.chat

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Unfurled link metadata, enough for a compact card. */
internal data class LinkPreview(
    val url: String,
    val title: String?,
    val description: String?,
    val imageUrl: String?,
    val siteName: String?,
    val embedUrl: String? = null,
)

/**
 * Reads Open Graph and Twitter card tags from a page head. The same idea as
 * ChatGPT's link cards: the model writes a URL, the frontend turns it into a
 * readable card, and the model never has to describe the page.
 */
internal object LinkPreviewParser {
    private val metaTag = Regex("<meta\\s+[^>]*>", RegexOption.IGNORE_CASE)
    private val tagKey = Regex("(?:property|name)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val tagContent = Regex("content\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
    private val titleTag = Regex(
        "<title[^>]*>(.*?)</title>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val youtubeId = Regex("[A-Za-z0-9_-]{6,}")

    fun parse(html: String, finalUrl: String, fallbackUrl: String = finalUrl): LinkPreview {
        val tags = metaTags(html)
        val host = finalUrl.toHttpUrlOrNull()?.host?.removePrefix("www.") ?: fallbackUrl
        val videoId = youtubeVideoId(finalUrl)
        if (videoId != null) {
            return LinkPreview(
                url = finalUrl,
                title = clean(tags["og:title"] ?: tags["twitter:title"]),
                description = clean(tags["og:description"] ?: tags["twitter:description"]),
                imageUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                siteName = "YouTube",
                embedUrl = "https://www.youtube.com/embed/$videoId",
            )
        }
        return LinkPreview(
            url = finalUrl,
            title = clean(tags["og:title"] ?: tags["twitter:title"])
                ?: titleTag.find(html)?.groupValues?.get(1)?.let(::clean),
            description = clean(tags["og:description"] ?: tags["twitter:description"] ?: tags["description"]),
            imageUrl = imageCandidate(tags)?.let { resolve(finalUrl, it) },
            siteName = clean(tags["og:site_name"]) ?: host,
        )
    }

    /** YouTube needs no page fetch: the thumbnail and embed URL come from the id. */
    fun youtubeVideoId(raw: String): String? {
        val url = raw.toHttpUrlOrNull() ?: return null
        val host = url.host.lowercase().removePrefix("www.")
        return when {
            host == "youtu.be" -> url.pathSegments.firstOrNull()?.takeIf { youtubeId.matches(it) }
            host.endsWith("youtube.com") -> url.queryParameter("v")?.takeIf { youtubeId.matches(it) }
            else -> null
        }
    }

    private fun metaTags(html: String): Map<String, String> {
        val out = HashMap<String, String>()
        metaTag.findAll(html).forEach { match ->
            val tag = match.value
            val key = tagKey.find(tag)?.groupValues?.get(1)?.lowercase() ?: return@forEach
            val content = tagContent.find(tag)?.groupValues?.get(1) ?: return@forEach
            out.putIfAbsent(key, content)
        }
        return out
    }

    private fun imageCandidate(tags: Map<String, String>): String? =
        tags["og:image"]
            ?: tags["og:image:url"]
            ?: tags["og:image:secure_url"]
            ?: tags["twitter:image"]
            ?: tags["twitter:image:src"]

    private fun resolve(base: String, raw: String): String? {
        val cleaned = raw.trim()
        if (cleaned.isEmpty()) return null
        if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) return cleaned
        return base.toHttpUrlOrNull()?.resolve(cleaned)?.toString()
    }

    private fun clean(value: String?): String? {
        val text = value?.let(::decodeEntities)
            ?.replace(Regex("<[^>]+>"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?: return null
        return text.takeIf { it.isNotEmpty() }?.take(400)
    }

    private fun decodeEntities(value: String): String = value
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace(Regex("&#(\\d+);")) { codePoint(it.groupValues[1].toIntOrNull()) }
        .replace(Regex("&#[xX]([0-9a-fA-F]+);")) { codePoint(it.groupValues[1].toIntOrNull(16)) }

    private fun codePoint(value: Int?): String {
        if (value == null || value <= 0 || value > 0x10FFFF) return ""
        return runCatching { String(Character.toChars(value)) }.getOrDefault("")
    }
}
