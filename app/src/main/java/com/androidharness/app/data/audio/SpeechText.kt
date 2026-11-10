package com.androidharness.app.data.audio

/**
 * Turns rendered markdown into text worth hearing. Speech engines read
 * punctuation literally ("hash", "pipe"), and Groq rejects an input that has
 * no letters or digits at all, which is exactly what a table's `|---|---|`
 * separator row is. Markers, table plumbing, code and URLs are therefore
 * removed before any engine sees the text.
 */
internal object SpeechText {
    private val imageRegex = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
    private val linkRegex = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val referenceLinkRegex = Regex("\\[([^\\]]+)]\\s*\\[[^\\]]*]")
    private val angleUrlRegex = Regex("<(https?://[^>]+)>", RegexOption.IGNORE_CASE)
    private val bareUrlRegex = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val codeSpanRegex = Regex("`+([^`]*)`+")
    private val footnoteRegex = Regex("\\[\\^[^\\]]*]")
    private val htmlTagRegex = Regex("<[a-zA-Z/][^>]*>")
    private val emphasisUnderscoreRegex = Regex("(?<![\\w])_([^_\\n]+)_(?![\\w])")
    private val hashNumberRegex = Regex("(?<![\\w#])#(?=\\d)")
    private val standaloneHashRegex = Regex("(?<![\\w])#+(?=\\s|$)")
    private val escapeRegex = Regex("\\\\([\\\\`*_{}\\[\\]()#+\\-.!>|])")

    private val quoteRegex = Regex("^\\s*>\\s?")
    private val headingRegex = Regex("^\\s*#{1,6}\\s*")
    private val bulletRegex = Regex("^\\s*[-*+]\\s+")
    private val taskBoxRegex = Regex("^\\s*\\[[xX ]\\]\\s+")
    private val ruleRegex = Regex("^\\s*([-*_])\\s*(?:\\1\\s*){2,}$")
    private val separatorCell = Regex(":?-+:?")
    private val repeatedSpace = Regex("[ \\t]{2,}")

    /** True when [text] still contains something an engine can pronounce. */
    fun isSpeakable(text: String): Boolean = text.any(Char::isLetterOrDigit)

    fun forSpeech(text: String): String {
        if (text.isBlank()) return ""
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').replace('\u00A0', ' ').split('\n')
        val out = mutableListOf<String>()
        var index = 0
        while (index < lines.size) {
            val trimmed = lines[index].trim()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                // Code is not useful read aloud; skip the whole fence.
                val fence = trimmed.take(3)
                index++
                while (index < lines.size && !lines[index].trim().startsWith(fence)) index++
                if (index < lines.size) index++
                out += ""
                continue
            }
            if (isTableStart(lines, index)) {
                val headers = splitRow(lines[index]).map(::inline)
                var row = index + 2
                val rows = mutableListOf<List<String>>()
                while (row < lines.size) {
                    val line = lines[row].trim()
                    if (line.isEmpty() || !line.contains('|')) break
                    rows += splitRow(lines[row]).map(::inline)
                    row++
                }
                if (rows.isNotEmpty()) {
                    out += ""
                    rows.forEach { cells ->
                        out += speakRow(headers, cells)
                        out += ""
                    }
                }
                index = row
                continue
            }
            out += cleanLine(lines[index])
            index++
        }
        val kept = out.map(String::trim)
            .filter { it.isEmpty() || it.any(Char::isLetterOrDigit) }
        return collapse(kept)
    }

    private fun isTableStart(lines: List<String>, index: Int): Boolean {
        if (index + 1 >= lines.size) return false
        val header = lines[index].trim()
        val delimiter = lines[index + 1].trim()
        if (!header.contains('|') || !delimiter.contains('|')) return false
        val cells = splitRow(delimiter)
        return cells.isNotEmpty() &&
            cells.size == splitRow(header).size &&
            cells.all { it.matches(separatorCell) }
    }

    /** Same rules as the table renderer: escaped pipes stay inside a cell. */
    private fun splitRow(row: String): List<String> {
        val text = row.trim()
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var index = 0
        var endsWithSeparator = false
        while (index < text.length) {
            val char = text[index]
            if (char == '\\' && index + 1 < text.length) {
                val next = text[index + 1]
                if (next == '|' || next == '\\') {
                    cell.append(next)
                    index += 2
                    endsWithSeparator = false
                    continue
                }
            }
            if (char == '|') {
                cells += cell.toString().trim()
                cell.clear()
                endsWithSeparator = true
            } else {
                cell.append(char)
                endsWithSeparator = false
            }
            index++
        }
        cells += cell.toString().trim()
        if (text.startsWith('|')) cells.removeAt(0)
        if (endsWithSeparator && cells.isNotEmpty()) cells.removeAt(cells.lastIndex)
        return cells
    }

    /** "Name: Build. Status: Passing." — readable as a sentence per row. */
    private fun speakRow(headers: List<String>, cells: List<String>): String {
        val parts = cells.mapIndexedNotNull { index, cell ->
            val value = cell.trim().trimEnd('.', ',', ';', ':', '。', '，')
            if (value.isEmpty()) {
                null
            } else {
                val label = headers.getOrElse(index) { "" }.trim()
                if (label.isEmpty()) value else "$label: $value"
            }
        }
        return if (parts.isEmpty()) "" else parts.joinToString(". ") + "."
    }

    private fun cleanLine(line: String): String {
        var value = line
        value = value.replace(quoteRegex, "")
        value = value.replace(headingRegex, "")
        value = value.replace(bulletRegex, "")
        value = value.replace(taskBoxRegex, "")
        if (ruleRegex.matches(value.trim())) return ""
        return inline(value)
    }

    private fun inline(text: String): String {
        var value = text
        value = value.replace(escapeRegex, "$1")
        value = value.replace(imageRegex, "$1")
        value = value.replace(linkRegex, "$1")
        value = value.replace(referenceLinkRegex, "$1")
        value = value.replace(angleUrlRegex, "link")
        value = value.replace(bareUrlRegex, "link")
        value = value.replace(codeSpanRegex, "$1")
        value = value.replace(footnoteRegex, "")
        value = value.replace("~~", "")
        value = value.replace(emphasisUnderscoreRegex, "$1")
        value = value.replace("*", "")
        value = value.replace(Regex("`+"), "")
        value = value.replace(htmlTagRegex, "")
        value = value.replace(hashNumberRegex, "number ")
        value = value.replace(standaloneHashRegex, "")
        value = value.replace('|', ' ')
        value = value
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&mdash;", "—")
        return value.replace(repeatedSpace, " ").trim()
    }

    /** Blank lines stay as paragraph breaks so the chunker keeps its pauses. */
    private fun collapse(lines: List<String>): String {
        val result = StringBuilder()
        var pendingBreak = false
        for (line in lines) {
            if (line.isEmpty()) {
                pendingBreak = true
                continue
            }
            if (result.isNotEmpty()) result.append(if (pendingBreak) "\n\n" else "\n")
            result.append(line)
            pendingBreak = false
        }
        return result.toString().trim()
    }
}
