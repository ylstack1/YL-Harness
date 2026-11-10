package com.androidharness.app.core

import org.junit.Assert.*
import org.junit.Test

class ToolOutputTextTest {
    @Test fun normalOutputKeepsWholeLinesAndAllCharacters() {
        val text = (0..400).joinToString("\n") { "Output line $it: inspect this line and verify the implementation." }
        val chunks = ToolOutputText.chunks(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.dropLast(1).all { it.endsWith('\n') })
        assertTrue(chunks.all { it.length <= 4_096 })
    }
    @Test fun longLinesAndUnicodeRemainBoundedWithoutSplittingSurrogates() {
        val text = "a".repeat(2_047) + "🙂" + "Long unbroken output ".repeat(4_000)
        val chunks = ToolOutputText.chunks(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 4_096 })
        assertTrue(chunks.none { it.last().isHighSurrogate() || it.first().isLowSurrogate() })
        assertTrue(ToolOutputText.chunks("").isEmpty())
    }
}
