package com.androidharness.app.core

import org.junit.Assert.*
import org.junit.Test

class ReasoningTextChunksTest {
    @Test fun appendCacheReusesCompletedChunksAndResetsForReplacement() {
        val cache = ReasoningTextChunks.Stream()
        val initial = "A careful reasoning step.\n".repeat(200)
        val first = cache.update(initial)
        val next = cache.update(initial + "More thoughts.\n".repeat(40))
        assertSame(first.first(), next.first())
        assertEquals(initial + "More thoughts.\n".repeat(40), next.joinToString(""))
        assertSame(next, cache.update(initial + "More thoughts.\n".repeat(40)))
        assertEquals(listOf("A new response."), cache.update("A new response."))
        assertTrue(cache.update("").isEmpty())
    }

    @Test fun fastAppendBurstsKeepEveryCharacterAndBoundEachLayout() {
        val cache = ReasoningTextChunks.Stream()
        var text = ""
        repeat(160) { index ->
            text += "Step $index: " + "token 😀 ".repeat(64) + "\n"
            val chunks = cache.update(text)
            assertEquals(text, chunks.joinToString(""))
            assertTrue(chunks.all { it.length <= 1024 })
            assertTrue(chunks.none { it.last().isHighSurrogate() || it.first().isLowSurrogate() })
        }
    }
    @Test fun longUnbrokenReasoningIsBoundedAndLossless() {
        val text = "x".repeat(250_000)
        val chunks = ReasoningTextChunks.split(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 2048 })
    }

    @Test fun completedChunksStayStableAsMoreTokensArrive() {
        val initial = "Reason carefully about this.\n".repeat(200)
        val before = ReasoningTextChunks.split(initial)
        val after = ReasoningTextChunks.split(initial + "Next reasoning step.\n".repeat(100))
        assertEquals(before.dropLast(1), after.take(before.size - 1))
        assertEquals(initial, before.joinToString(""))
    }

    @Test fun unicodePairsAndNewlinesSurviveChunkBoundaries() {
        val text = "a".repeat(2047) + "😀" + "b".repeat(5000) + "\n\nLast paragraph"
        val chunks = ReasoningTextChunks.split(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.none { it.last().isHighSurrogate() || it.first().isLowSurrogate() })
        assertTrue(chunks.all { it.length <= 2048 })
    }
    @Test fun aLaterNewlineCannotMoveAnAlreadyPublishedBoundary() {
        val prefix = "word ".repeat(250)
        val before = ReasoningTextChunks.split(prefix)
        val after = ReasoningTextChunks.split(prefix + "remaining words\n".repeat(100))
        assertEquals(before.dropLast(1), after.take(before.size - 1))
        assertEquals("\n  ", ReasoningTextChunks.split("\n  ").joinToString(""))
    }
}
