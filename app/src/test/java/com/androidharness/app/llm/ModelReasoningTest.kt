package com.androidharness.app.llm

import org.junit.Assert.*
import org.junit.Test

class ModelReasoningTest {
    @Test fun explicitProviderMetadataWinsOverFamilyHints() {
        assertEquals(ModelReasoning.REPORTED, ModelReasoning.of(ModelEntry("unfamiliar", reasoning = true)))
        assertEquals(ModelReasoning.UNSUPPORTED, ModelReasoning.of(ModelEntry("qwen3", reasoning = false)))
        assertEquals(ModelReasoning.INFERRED, ModelReasoning.of(ModelEntry("qwen3")))
        assertEquals(ModelReasoning.UNKNOWN, ModelReasoning.of(ModelEntry("unfamiliar")))
    }

    @Test fun customClaimsAndLegacyGeminiNameHeuristicsAreNeverPresentedAsProviderReports() {
        assertEquals(ModelReasoning.CUSTOM, ModelReasoning.of(ModelEntry("custom", true, custom = true)))
        assertEquals(ModelReasoning.INFERRED, ModelReasoning.of(ModelEntry("gemini-2.5-flash", true), ProviderType.GEMINI))
        assertEquals(ModelReasoning.UNKNOWN, ModelReasoning.of(ModelEntry("gemini-legacy", false), ProviderType.GEMINI))
    }
}
