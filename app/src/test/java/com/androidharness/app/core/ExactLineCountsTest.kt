package com.androidharness.app.core

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExactLineCountsTest {
    @Test fun countsChangesBeyondThePreviewLimitAndWholeNewFiles() {
        val lines = (0..5999).map { "line $it" }
        val changed = lines.toMutableList().apply { this[4500] = "changed line"; add(5500, "new line") }
        assertEquals(2 to 1, Diff.exactLineCounts(lines.joinToString("\n"), changed.joinToString("\n")))
        val large = (1..15000).joinToString("\n", postfix = "\n") { "line $it" }
        assertEquals(15000 to 0, Diff.exactLineCounts("", large))
        assertEquals(0 to 15000, Diff.exactLineCounts(large, ""))
        assertEquals(0 to 0, Diff.exactLineCounts("a\r\nb\r\n", "a\nb\n"))
    }

    @Test fun matchesAnIndependentLcsOracleWithRepeatedAndEmptyLines() {
        val random = Random(15)
        repeat(300) {
            val before = List(random.nextInt(1, 16)) { listOf("a", "b", "", "c")[random.nextInt(4)] }
            val after = List(random.nextInt(1, 16)) { listOf("a", "b", "", "c")[random.nextInt(4)] }
            val lcs = Array(before.size + 1) { IntArray(after.size + 1) }
            for (a in before.indices.reversed()) for (b in after.indices.reversed()) {
                lcs[a][b] = if (before[a] == after[b]) 1 + lcs[a + 1][b + 1] else maxOf(lcs[a + 1][b], lcs[a][b + 1])
            }
            assertEquals(after.size - lcs[0][0] to before.size - lcs[0][0],
                Diff.exactLineCounts(before.joinToString("\n", postfix = "\n"), after.joinToString("\n", postfix = "\n")))
        }
    }

    @Test fun skipsExpensiveComparisonsInsteadOfReturningTruncatedTotals() {
        val before = (1..3000).joinToString("\n") { "old $it" }
        val after = (1..3000).joinToString("\n") { "new $it" }
        assertNull(Diff.exactLineCounts(before, after))
    }
}
