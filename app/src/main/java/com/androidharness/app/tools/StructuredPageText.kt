package com.androidharness.app.tools

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Only declarative, page-supplied text is read; embedded scripts are never evaluated. */
internal object StructuredPageText {
    private val scripts = Regex("<script\\b([^>]*)>(.*?)</script\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val ldType = Regex("\\btype\\s*=\\s*[\"']application/ld\\+json[\"']", RegexOption.IGNORE_CASE)
    private val types = setOf("Article", "NewsArticle", "BlogPosting", "DiscussionForumPosting", "SocialMediaPosting", "Comment", "Question", "Answer")
    private val json = Json { ignoreUnknownKeys = true }
    private const val LIMIT = 200_000

    fun extract(html: String): String? {
        val sections = linkedSetOf<String>()
        var visited = 0
        var substantive = 0
        fun clean(value: String): String = htmlToText(value, "html").trim().take(24_000)
        fun text(o: JsonObject, key: String): String = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        fun walk(element: JsonElement?, depth: Int) {
            if (element == null || depth > 10 || ++visited > 400 || sections.sumOf { it.length } >= LIMIT) return
            when (element) {
                is JsonArray -> element.take(80).forEach { walk(it, depth + 1) }
                is JsonObject -> {
                    val schemaTypes = when (val type = element["@type"]) {
                        is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        is JsonPrimitive -> listOf(type.content)
                        else -> emptyList()
                    }.map { it.substringAfterLast('/').substringAfterLast('#') }
                    if (schemaTypes.any { it in types }) {
                        val body = clean(text(element, "articleBody").ifBlank { text(element, "text") })
                        val title = clean(text(element, "headline").ifBlank { text(element, "name") })
                        val author = when (val author = element["author"]) {
                            is JsonObject -> clean(text(author, "name"))
                            is JsonPrimitive -> clean(author.content)
                            else -> ""
                        }
                        if (body.isNotBlank() && body !in setOf("[deleted]", "[removed]")) {
                            substantive += body.length
                            sections += listOf(title, author.takeIf { it.isNotBlank() }?.let { "Author: $it" }.orEmpty(), body)
                                .filter(String::isNotBlank).joinToString("\n")
                        }
                    }
                    for (key in listOf("@graph", "mainEntity", "mainEntityOfPage", "comment", "acceptedAnswer", "suggestedAnswer", "hasPart")) walk(element[key], depth + 1)
                }
                else -> Unit
            }
        }
        scripts.findAll(html).take(24).filter { ldType.containsMatchIn(it.groupValues[1]) }.forEach { script ->
            val raw = script.groupValues[2].trim().takeIf { it.length <= 512_000 } ?: return@forEach
            runCatching { json.parseToJsonElement(raw) }.getOrNull()?.let { walk(it, 0) }
        }
        if (substantive < 80 || sections.isEmpty()) return null
        return "[Page-supplied structured text; a discussion may include only some replies]\n" + sections.joinToString("\n\n").take(LIMIT)
    }

    /** Supports an explicitly fetched public Reddit JSON response, without inventing or probing API URLs. */
    fun reddit(raw: String, url: String): String? {
        val host = url.toHttpUrlOrNull()?.host ?: return null
        if (host != "reddit.com" && !host.endsWith(".reddit.com")) return null
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonArray ?: return null
        val out = StringBuilder()
        var post = false
        var comments = 0
        var visited = 0
        fun field(o: JsonObject, key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        fun walk(element: JsonElement?, depth: Int) {
            if (element == null || depth > 12 || ++visited > 600 || out.length >= LIMIT) return
            when (element) {
                is JsonArray -> element.take(100).forEach { walk(it, depth + 1) }
                is JsonObject -> {
                    val kind = field(element, "kind")
                    val data = element["data"] as? JsonObject ?: return
                    when (kind) {
                        "Listing" -> walk(data["children"], depth + 1)
                        "t3" -> {
                            val title = field(data, "title").trim()
                            if (title.isNotBlank()) {
                                post = true
                                out.append("Post: ").append(title.take(1000)).append('\n')
                                field(data, "author").takeIf { it.isNotBlank() }?.let { out.append("Author: ").append(it.take(100)).append('\n') }
                                val body = field(data, "selftext").trim()
                                if (body !in setOf("[removed]", "[deleted]")) out.append(body.take(40_000)).append("\n\n")
                            }
                        }
                        "t1" -> {
                            val body = field(data, "body").trim()
                            if (comments < 60 && body.isNotBlank() && body !in setOf("[removed]", "[deleted]")) {
                                comments++
                                out.append("Comment ").append(comments).append(" by ").append(field(data, "author").take(100)).append(":\n")
                                    .append(body.take(12_000)).append("\n\n")
                            }
                            if (comments < 60) walk(data["replies"], depth + 1)
                        }
                    }
                }
                else -> Unit
            }
        }
        walk(root, 0)
        if (!post || out.length < 50) return null
        return "[Reddit post and $comments supplied comments; partial thread, not all replies]\n" + out.toString().take(LIMIT).trim()
    }
}

/** A successful HTTP response with no readable document can be retried through the rendered reader. */
internal class RenderedPageRequired(message: String) : Exception(message)
