package com.androidharness.app.ui.chat

/** Every row shares these widths; narrow tables fill the viewport, wide ones scroll. */
internal fun markdownColumnWidths(
    naturalWidths: List<Int>,
    viewportWidth: Int,
    minWidth: Int,
    maxWidth: Int,
): List<Int> {
    if (naturalWidths.isEmpty()) return emptyList()
    val widths = naturalWidths.map { it.coerceIn(minWidth, maxWidth) }
    if (widths.size == 1) return listOf(viewportWidth.coerceAtLeast(minWidth))
    // A label/value table should wrap within a phone instead of forcing a swipe.
    if (widths.size == 2 && viewportWidth >= minWidth * 2 && widths.sum() > viewportWidth) {
        val extra = viewportWidth - minWidth * 2
        val weights = widths.map { it - minWidth }
        val first = minWidth + (extra.toLong() * weights[0] / weights.sum()).toInt()
        return listOf(first, viewportWidth - first)
    }
    val extra = (viewportWidth - widths.sum()).coerceAtLeast(0)
    return widths.mapIndexed { index, width ->
        width + extra / widths.size + if (index < extra % widths.size) 1 else 0
    }
}

/** An escaped pipe is cell content, not a column boundary. */
internal fun splitTableRow(row: String): List<String> {
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

/** Tab-separated plain text pastes as columns in spreadsheet and document apps. */
internal fun tableAsTsv(rows: List<List<String>>): String =
    rows.joinToString("\n") { row ->
        row.joinToString("\t") { cell -> cell.replace(Regex("[\\t\\r\\n]+"), " ").trim() }
    }
