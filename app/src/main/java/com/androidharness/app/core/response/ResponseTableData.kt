package com.androidharness.app.core.response

object ResponseTableData {
    data class Status(val kind: String, val detail: String)
    private val statusPrefix = Regex("""^(pass(?:ed)?|fail(?:ed)?|unverified|✅|✔️?|❌|⚠️?)(?:[.:]\s*|$)""", RegexOption.IGNORE_CASE)
    fun status(value: String): Status? {
        val match = statusPrefix.find(value.trim()) ?: return null
        val label = match.groupValues[1].lowercase()
        val kind = when { label.startsWith("pass") || label.startsWith("✅") || label.startsWith("✔") -> "passed"
            label.startsWith("fail") || label == "❌" -> "failed"; else -> "unverified" }
        return Status(kind, value.trim().drop(match.value.length))
    }

    fun indices(table: RichResponse.Node.Table, query: String, column: Int, ascending: Boolean): List<Int> {
        val filtered = table.rows.indices.filter { index -> query.isBlank() || table.rows[index].any { it.contains(query.trim(), ignoreCase = true) } }
        if (column !in table.columns.indices) return filtered
        val ordered = filtered.sortedWith { a, b ->
            val left = table.rows[a][column]; val right = table.rows[b][column]
            val x = left.toDoubleOrNull()?.takeIf { it.isFinite() }; val y = right.toDoubleOrNull()?.takeIf { it.isFinite() }
            val comparison = when {
                x != null && y != null -> x.compareTo(y)
                x != null -> -1
                y != null -> 1
                else -> left.compareTo(right, ignoreCase = true)
            }
            if (ascending) comparison else -comparison
        }
        return ordered
    }
    fun csv(table: RichResponse.Node.Table, indices: List<Int>): String = (listOf(table.columns) + indices.map { table.rows[it] })
        .joinToString("\r\n") { row -> row.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" } }
}
