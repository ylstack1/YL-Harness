package com.androidharness.app.tools

/**
 * The text shape of a web_search result list. The tool renders it and the UI
 * parses it, so both sides share one definition instead of two regexes that
 * can drift apart.
 *
 * Format:
 *   1. Title
 *      https://example.com/page
 *      [2 days ago] Snippet text
 */
internal object WebSearchText {
    private val numbered = Regex("^\\d+\\.\\s+(.+)$")

    fun render(results: List<WebSearchResult>, count: Int): String {
        val seen = HashSet<String>()
        return results
            .filter { seen.add(canonicalResultUrl(it.url)) }
            .take(count)
            .mapIndexed { index, result ->
                buildString {
                    append(index + 1).append(". ").append(result.title).append('\n')
                    append("   ").append(result.url).append('\n')
                    append("   ")
                    result.date?.takeIf { it.isNotBlank() }?.let { append('[').append(it).append("] ") }
                    append(result.snippet)
                }
            }
            .joinToString("\n")
    }

    /** Reads back the numbered title and URL pairs, ignoring snippets and notes. */
    fun parse(text: String): List<WebSearchResult> {
        if (text.isBlank()) return emptyList()
        val lines = text.lineSequence().toList()
        val out = ArrayList<WebSearchResult>()
        var index = 0
        while (index < lines.size) {
            val title = numbered.matchEntire(lines[index].trim())?.groupValues?.get(1)
            if (title == null) {
                index++
                continue
            }
            val url = lines.getOrNull(index + 1)?.trim().orEmpty()
            if (!url.startsWith("https://") && !url.startsWith("http://")) {
                index++
                continue
            }
            out += WebSearchResult(title, url, "")
            index += 2
        }
        return out
    }
}
