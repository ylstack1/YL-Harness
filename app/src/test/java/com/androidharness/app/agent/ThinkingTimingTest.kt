package com.androidharness.app.agent

import com.androidharness.app.core.ToolCallData
import com.androidharness.app.llm.StreamEvent
import org.junit.Assert.*
import org.junit.Test

class ThinkingTimingTest {
    @Test fun liveDurationAdvancesAndAnswerTimeIsExcluded() {
        var timing = ThinkingTiming().observe(StreamEvent.ThinkingDelta("Inspect"), 100)
        assertEquals(2_000L, timing.durationMs(2_100))
        timing = timing.observe(StreamEvent.TextDelta("Answer"), 3_100)
        assertNull(timing.startedAt)
        assertEquals(3_000L, timing.durationMs(30_000))
    }

    @Test fun interleavedReasoningCountsOnlyReasoningSpans() {
        var timing = ThinkingTiming().start(100).stop(1_100)
        timing = timing.start(8_100).start(8_500).stop(10_100)
        assertEquals(3_000L, timing.durationMs(20_000))
    }

    @Test fun emptyDeltasDoNotStartOrStopTheClock() {
        assertEquals(ThinkingTiming(), ThinkingTiming().observe(StreamEvent.ThinkingDelta(""), 100))
        val timing = ThinkingTiming().start(100).observe(StreamEvent.TextDelta(""), 500)
        assertEquals(100L, timing.startedAt)
        assertEquals(1_000L, timing.durationMs(1_100))
    }

    @Test fun toolArgumentsAndReadyCallsStopReasoningBeforeExecution() {
        val thinking = ThinkingTiming().start(100)
        assertEquals(1_000L, thinking.observe(StreamEvent.ToolCallDelta("call", "read_file", "{"), 1_100).durationMs(20_000))
        assertEquals(1_000L, thinking.observe(StreamEvent.ToolCallReady(ToolCallData("call", "read_file", "{}")), 1_100).durationMs(20_000))
        assertEquals(1_000L, thinking.observe(StreamEvent.Done(), 1_100).durationMs(20_000))
    }

    @Test fun nestedBatchesRespectReasoningThenAnswerOrder() {
        val batch = StreamEvent.Batch(listOf(StreamEvent.Batch(listOf(StreamEvent.ThinkingDelta("Inspect"))), StreamEvent.TextDelta("Answer")))
        val timing = ThinkingTiming().observe(batch, 500)
        assertNull(timing.startedAt)
        assertEquals(0L, timing.durationMs(30_000))
        assertEquals(0L, ThinkingTiming().observe(StreamEvent.TextDelta("No visible thinking"), 500).durationMs(30_000))
    }

    @Test fun estimatesGrowWithTextAndReportedCountsReplaceThemIncludingZero() {
        assertEquals(ThinkingTokenCount(1, true), thinkingTokenCount("one", null))
        assertEquals(ThinkingTokenCount(3, true), thinkingTokenCount("123456789", null))
        assertEquals(ThinkingTokenCount(42, false), thinkingTokenCount("123456789", 42))
        assertEquals(ThinkingTokenCount(0, false), thinkingTokenCount("123456789", 0))
        assertEquals(ThinkingTokenCount(42, false), thinkingTokenCount("", 42))
        assertNull(thinkingTokenCount("", null))
    }
}
