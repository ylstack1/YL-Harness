package com.androidharness.app.core

import com.androidharness.app.agent.ThinkingTiming
import org.junit.Assert.*
import org.junit.Test

class ChatThinkingMetricsTest {
    @Test fun liveTimerAdvancesAndStopsWithoutCountingAnswerTime() {
        val timing = ThinkingTiming(startedAt = 1000, elapsedMs = 500)
        assertEquals(1500L, chatThinkingTotals(emptyList(), "abcd", timing, null, 2000).milliseconds)
        assertEquals(2500L, chatThinkingTotals(emptyList(), "abcd", timing, null, 3000).milliseconds)
        val stopped = timing.stop(3000)
        assertEquals(2500L, chatThinkingTotals(emptyList(), "abcd", stopped, null, 9000).milliseconds)
    }

    @Test fun reportedReasoningUsageNeverDropsBelowTheStreamedEstimate() {
        val estimate = chatThinkingTotals(emptyList(), "abcd".repeat(50), null, null, 0)
        assertEquals(50L, estimate.tokens); assertTrue(estimate.estimated)
        val smaller = chatThinkingTotals(emptyList(), "abcd".repeat(50), null, 42, 0)
        assertEquals(50L, smaller.tokens); assertTrue(smaller.estimated)
        val larger = chatThinkingTotals(emptyList(), "abcd".repeat(50), null, 80, 0)
        assertEquals(80L, larger.tokens); assertFalse(larger.estimated)
    }

    @Test fun committedTurnsWithTimestampsShowTheirWallClockSpan() {
        val messages = listOf(
            ChatMessage(Role.USER, text = "q", createdAt = 1_000),
            ChatMessage(Role.ASSISTANT, thinkingMs = 2_000, createdAt = 6_000),
            ChatMessage(Role.TOOL, toolCallId = "c", createdAt = 30_000),
            ChatMessage(Role.ASSISTANT, thinkingMs = 1_000, createdAt = 45_000),
        )
        assertEquals(44_000L, chatThinkingTotals(messages, "", null, null, 0).milliseconds)
        // Without a second timestamp the summed reasoning spans remain the answer.
        assertEquals(2_000L, chatThinkingTotals(listOf(messages[1]), "", null, null, 0).milliseconds)
    }

    @Test fun savedRequestsAndLiveReasoningAreSummedWithoutToolOrUserTokens() {
        val messages = listOf(
            ChatMessage(Role.ASSISTANT, thinkingMs = 2000, thinking = "abcd".repeat(10)),
            ChatMessage(Role.ASSISTANT, thinkingMs = 1000, generation = GenerationMetadata(reasoningTokens = 20)),
            ChatMessage(Role.USER, thinking = "ignore", thinkingMs = 9999),
            ChatMessage(Role.ASSISTANT, thinking = "ignore", thinkingMs = 9999, toolCallId = "child"))
        val total = chatThinkingTotals(messages, "abcdefgh", ThinkingTiming(elapsedMs = 500), 7, 0)
        assertEquals(3500L, total.milliseconds)
        assertEquals(37L, total.tokens)
        assertTrue(total.estimated)
    }
    @Test fun tokenCountsUseCompactUnitsWithoutUnnecessaryDecimals() {
        assertEquals("999", compactThinkingTokenCount(999))
        assertEquals("1k", compactThinkingTokenCount(1000))
        assertEquals("1.5k", compactThinkingTokenCount(1500))
        assertEquals("1.3k", compactThinkingTokenCount(1250))
        assertEquals("12.3k", compactThinkingTokenCount(12345))
        assertEquals("1m", compactThinkingTokenCount(999999))
        assertEquals("1.5m", compactThinkingTokenCount(1500000))
        assertEquals("1.5b", compactThinkingTokenCount(1500000000))
    }
}
