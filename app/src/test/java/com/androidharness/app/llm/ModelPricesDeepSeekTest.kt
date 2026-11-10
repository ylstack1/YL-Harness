package com.androidharness.app.llm

import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ModelPricesDeepSeekTest {
    private fun utc(day: Int, hour: Int, minute: Int = 0) =
        LocalDateTime.of(2026, 10, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Before fun useShippedPrices() { ModelsDev.replaceForTesting(emptyMap()) }

    @Test fun peakIsWeekday0100To0400And0600To1000Utc() {
        assertTrue(ModelPrices.deepSeekOffPeak(utc(19, 0, 59)))   // Monday before peak
        assertFalse(ModelPrices.deepSeekOffPeak(utc(19, 1)))      // Monday 01:00 peak
        assertFalse(ModelPrices.deepSeekOffPeak(utc(19, 3, 59)))  // last peak minute
        assertTrue(ModelPrices.deepSeekOffPeak(utc(19, 4)))       // 04:00 off-peak
        assertTrue(ModelPrices.deepSeekOffPeak(utc(19, 5, 59)))   // between windows
        assertFalse(ModelPrices.deepSeekOffPeak(utc(19, 6)))      // 06:00 peak
        assertFalse(ModelPrices.deepSeekOffPeak(utc(19, 9, 59)))  // last peak minute
        assertTrue(ModelPrices.deepSeekOffPeak(utc(19, 10)))      // 10:00 off-peak
        assertTrue(ModelPrices.deepSeekOffPeak(utc(17, 2)))       // Saturday
        assertTrue(ModelPrices.deepSeekOffPeak(utc(18, 7)))       // Sunday
    }

    @Test fun offPeakHalvesDeepSeekRequestCost() {
        val atPeak = ModelPrices.estimateAt("deepseek-v4.1-flash-1017", 1_000_000, 1_000_000, atMillis = utc(19, 2))
        val offPeak = ModelPrices.estimateAt("deepseek-v4.1-flash-1017", 1_000_000, 1_000_000, atMillis = utc(19, 12))
        assertEquals(1.50, atPeak!!, 0.0001)
        assertEquals(0.75, offPeak!!, 0.0001)
    }

    @Test fun cachedTokensUseThePeakCacheRateAndHalveOffPeak() {
        val peak = ModelPrices.estimateAt("deepseek-v4.1-flash", 1_000_000, 0, cachedTokens = 1_000_000, atMillis = utc(19, 2))
        val offPeak = ModelPrices.estimateAt("deepseek-v4.1-flash", 1_000_000, 0, cachedTokens = 1_000_000, atMillis = utc(19, 12))
        assertEquals(0.006, peak!!, 0.0001)
        assertEquals(0.003, offPeak!!, 0.0001)
    }

    @Test fun aggregatedRowsDiscountOnlyOffPeakTokens() {
        // 1M input + 1M output. Peak input only: 0.30 + 0.60 (discounted output) = 0.90.
        assertEquals(0.90, ModelPrices.estimateSplit(
            "deepseek-v4.1-flash", 1_000_000, 1_000_000,
            peakInputTokens = 1_000_000, peakOutputTokens = 0)!!, 0.0001)
        assertEquals(1.50, ModelPrices.estimateSplit(
            "deepseek-v4.1-flash", 1_000_000, 1_000_000,
            peakInputTokens = 1_000_000, peakOutputTokens = 1_000_000)!!, 0.0001)
        assertEquals(0.75, ModelPrices.estimateSplit(
            "deepseek-v4.1-flash", 1_000_000, 1_000_000)!!, 0.0001)
    }

    @Test fun otherModelsIgnoreTheTimeWindowAndPeakSplit() {
        val full = ModelPrices.estimate("gemini-2.0-flash", 1_000_000, 1_000_000)!!
        assertEquals(0.50, full, 0.0001)
        assertEquals(full, ModelPrices.estimateAt("gemini-2.0-flash", 1_000_000, 1_000_000, atMillis = utc(19, 2))!!, 0.0001)
        assertEquals(full, ModelPrices.estimateSplit("gemini-2.0-flash", 1_000_000, 1_000_000,
            peakInputTokens = 1_000_000, peakOutputTokens = 1_000_000)!!, 0.0001)
    }

    @Test fun bothReportedModelIdsResolveToTheV41PeakTable() {
        listOf("deepseek-v4.1-flash", "deepseek-v4.1-flash-1017").forEach { model ->
            val cost = ModelPrices.costFor(model)
            assertEquals(model, 0.30, cost.input, 0.0001)
            assertEquals(model, 1.20, cost.output, 0.0001)
        }
    }
}
