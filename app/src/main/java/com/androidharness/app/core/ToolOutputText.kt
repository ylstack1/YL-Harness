package com.androidharness.app.core

/** Bound text layouts while keeping ordinary output lines together and every source character. */
internal object ToolOutputText {
    fun chunks(text: String): List<String> = buildList {
        var start = 0
        while (start < text.length) {
            val target = (start + 2_048).coerceAtMost(text.length)
            var end = if (target == text.length) target else text.indexOf('\n', target)
                .takeIf { it in target until (target + 2_048).coerceAtMost(text.length) }?.plus(1) ?: target
            if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            add(text.substring(start, end))
            start = end
        }
    }
}
