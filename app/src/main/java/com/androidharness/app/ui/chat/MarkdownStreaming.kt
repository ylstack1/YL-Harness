package com.androidharness.app.ui.chat

/** Incremental block boundaries, so blanks inside code/math never split a live block. */
internal class MarkdownStreamTracker {
    data class Window(val stableEnd: Int, val openFenceStart: Int?, val fenceBodyStart: Int?, val revision: Int)
    private var revision = 0
    private var previous = ""
    private var scanned = 0
    private var stableEnd = 0
    private var fenceLength = 0
    private var fenceStart: Int? = null
    private var bodyStart: Int? = null
    private var math: String? = null

    fun update(text: String): Window {
        if (!text.startsWith(previous)) {
            revision++
            scanned = 0; stableEnd = 0; fenceLength = 0; fenceStart = null; bodyStart = null; math = null
        }
        while (true) {
            val end = text.indexOf('\n', scanned)
            if (end < 0) break
            val line = text.substring(scanned, end).trim()
            val ticks = line.takeWhile { it == '`' }.length
            when {
                fenceLength > 0 -> if (ticks >= fenceLength && line.drop(ticks).isBlank()) {
                    fenceLength = 0; fenceStart = null; bodyStart = null
                }
                math != null -> if (line.contains(if (math == "$$") "$$" else "\\]")) math = null
                ticks >= 3 -> { fenceLength = ticks; fenceStart = scanned; bodyStart = end + 1 }
                line.startsWith("$$") && !line.drop(2).contains("$$") -> math = "$$"
                line.startsWith("\\[") && !line.drop(2).contains("\\]") -> math = "\\["
                line.isBlank() -> stableEnd = end + 1
            }
            scanned = end + 1
        }
        previous = text
        // A closing delimiter is already complete before its trailing newline arrives.
        // Do not include that delimiter in the live code Copy action.
        var pos = scanned
        while (pos < text.length && text[pos].isWhitespace()) pos++
        val firstTick = pos
        while (pos < text.length && text[pos] == '`') pos++
        val closedTail = fenceLength > 0 && pos - firstTick >= fenceLength && text.substring(pos).isBlank()
        return Window(stableEnd, if (closedTail) null else fenceStart, if (closedTail) null else bodyStart, revision)
    }
}
