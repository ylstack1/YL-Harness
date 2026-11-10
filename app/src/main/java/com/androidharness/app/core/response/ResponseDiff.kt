package com.androidharness.app.core.response

/** Line comparison for bounded reply snippets; it never applies changes to files. */
object ResponseDiff {
    data class Line(val marker: String, val text: String)
    fun lines(before: String, after: String): List<Line> {
        val a = if (before.isEmpty()) emptyList() else before.lines()
        val b = if (after.isEmpty()) emptyList() else after.lines()
        require(a.size <= 400 && b.size <= 400)
        val lengths = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            lengths[i][j] = if (a[i] == b[j]) 1 + lengths[i + 1][j + 1] else maxOf(lengths[i + 1][j], lengths[i][j + 1])
        }
        return buildList {
            var i = 0; var j = 0
            while (i < a.size || j < b.size) {
                when {
                    i < a.size && j < b.size && a[i] == b[j] -> { add(Line(" ", a[i])); i++; j++ }
                    i < a.size && (j == b.size || lengths[i + 1][j] >= lengths[i][j + 1]) -> add(Line("-", a[i++]))
                    else -> add(Line("+", b[j++]))
                }
            }
        }
    }
}
