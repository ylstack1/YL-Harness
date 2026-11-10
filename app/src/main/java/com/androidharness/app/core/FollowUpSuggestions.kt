package com.androidharness.app.core

/**
 * The model ends a substantive answer with a small directive:
 *
 * ```
 * ::followups
 * - Next question
 * - Another question
 * ::
 * ```
 *
 * It is stripped before storage and shown as tappable chips under the answer.
 */
data class FollowUpSuggestions(val text: String, val items: List<String>) {
    companion object {
        private const val MAX_ITEMS = 3
        private const val MAX_ITEM_CHARS = 120
        private val bullet = Regex("^\\s*[-*]\\s+(.+)$")

        fun extract(raw: String): FollowUpSuggestions {
            val lines = raw.lines()
            var fenced = false
            var start = -1
            for ((index, line) in lines.withIndex()) {
                val trimmed = line.trimStart()
                if (trimmed.startsWith("```")) {
                    fenced = !fenced
                    continue
                }
                if (!fenced && isMarker(line)) {
                    start = index
                    break
                }
            }
            if (start < 0) return FollowUpSuggestions(raw, emptyList())
            var end = start + 1
            while (end < lines.size && lines[end].trim() != "::") end++
            if (end >= lines.size) return FollowUpSuggestions(raw, emptyList())
            val items = lines.subList(start + 1, end).mapNotNull { line ->
                bullet.matchEntire(line.trim())?.groupValues?.get(1)?.trim()
            }.map { it.take(MAX_ITEM_CHARS) }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(MAX_ITEMS)
            val clean = (lines.subList(0, start) + lines.subList(end + 1, lines.size))
                .joinToString("\n")
                .trimEnd()
            return FollowUpSuggestions(clean, items)
        }

        /** Hides a complete or still-streaming follow-up block from the visible answer. */
        fun stripForDisplay(raw: String): String {
            val lines = raw.lines()
            var fenced = false
            for ((index, line) in lines.withIndex()) {
                val trimmed = line.trimStart()
                if (trimmed.startsWith("```")) {
                    fenced = !fenced
                    continue
                }
                if (!fenced && isMarker(line)) {
                    return lines.subList(0, index).joinToString("\n").trimEnd()
                }
            }
            return raw
        }

        private fun isMarker(line: String): Boolean {
            val value = line.trim().removePrefix("::").trim()
            return line.trim().startsWith("::") && value.equals("followups", ignoreCase = true)
        }
    }
}
