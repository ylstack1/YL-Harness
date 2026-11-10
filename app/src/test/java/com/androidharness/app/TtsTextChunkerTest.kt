package com.androidharness.app

import com.androidharness.app.data.audio.TtsTextChunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsTextChunkerTest {
    @Test
    fun shortTextStaysOneChunk() {
        assertEquals(listOf("Hello there."), TtsTextChunker.chunk("Hello there.", 500))
    }

    @Test
    fun longTextIsSplitUnderTheLimit() {
        val sentence = "This is a sentence that should stay readable. "
        val text = sentence.repeat(200)
        val chunks = TtsTextChunker.chunk(text, 300)
        assertTrue(chunks.size > 1)
        chunks.forEach { assertTrue("chunk too long: ${it.length}", it.length <= 300) }
    }

    @Test
    fun paragraphsAndSentencesKeepTheirWords() {
        val text = "First paragraph has two sentences. It ends here.\n\nSecond paragraph."
        val chunks = TtsTextChunker.chunk(text, 200)
        val joined = chunks.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        assertEquals(
            "First paragraph has two sentences. It ends here. Second paragraph.",
            joined,
        )
        assertFalse(chunks.any { it.isBlank() })
    }

    @Test
    fun unbrokenTextIsHardSplitWithoutDroppingCharacters() {
        val text = "x".repeat(1_000)
        val chunks = TtsTextChunker.chunk(text, 300)
        assertEquals(1_000, chunks.sumOf { it.length })
        chunks.forEach { assertTrue(it.length <= 300) }
    }

    @Test
    fun blankInputProducesNothing() {
        assertTrue(TtsTextChunker.chunk("   \n\n  ", 300).isEmpty())
    }

    @Test
    fun symbolOnlyLinesAreSkipped() {
        assertTrue(TtsTextChunker.chunk("|---|---|\n===\n***", 300).isEmpty())
    }
}
