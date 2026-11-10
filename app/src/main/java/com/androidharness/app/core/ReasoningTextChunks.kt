package com.androidharness.app.core

/** Stable, bounded text layouts for the live transcript. Joining chunks preserves every character. */
internal object ReasoningTextChunks {
    private const val CHUNK_SIZE = 512

    /** Reuse all completed text layouts; only the growing tail needs new strings. */
    class Stream {
        private var previous = ""
        private var chunks: List<String> = emptyList()
        fun update(text: String): List<String> {
            if (text == previous) return chunks
            val append = text.startsWith(previous)
            val stable = if (append) chunks.dropLast(1) else emptyList()
            val offset = stable.sumOf { it.length }
            chunks = stable + split(text.substring(offset))
            previous = text
            return chunks
        }
    }

    fun split(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        return buildList {
            var start = 0
            while (start < text.length) {
                val limit = (start + CHUNK_SIZE * 2).coerceAtMost(text.length)
                val newline = text.indexOf('\n', start + CHUNK_SIZE).takeIf { it in start until limit }
                val space = text.indexOf(' ', start + CHUNK_SIZE).takeIf { it in start until limit }
                var end = listOfNotNull(newline, space).minOrNull()?.plus(1) ?: limit
                if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
                add(text.substring(start, end))
                start = end
            }
        }
    }
}
