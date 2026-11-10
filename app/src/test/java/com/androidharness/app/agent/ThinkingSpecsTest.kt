package com.androidharness.app.agent

import com.androidharness.app.llm.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ThinkingSpecsTest {
    @Test fun `provider effort metadata overrides stale presets and supplies custom editor choices`() {
        val c = config("deepseek-flash")
        ModelsDev.replaceForTesting(mapOf("deepseek" to mapOf(c.model to entry("high", toggle = true))))
        val reported = ModelEntry(c.model, reasoning = true, reasoningEfforts = listOf("low", "high", "max"), defaultReasoningEffort = "low")
        val spec = ThinkingSpecs.forProvider(c, reported)
        assertEquals(listOf(ThinkingLevel.OFF, ThinkingLevel.LOW, ThinkingLevel.HIGH, ThinkingLevel.MAX), spec.levels)
        assertEquals(listOf("Disabled", "Low", "High", "Max"), CustomReasoning.defaultLevels(spec))
        assertEquals("max", ThinkingSpecs.resolve(c, ThinkingLevel.MAX, modelEntry = reported).effort)
        assertEquals("none", ThinkingSpecs.resolve(c, ThinkingLevel.OFF, modelEntry = reported).effort)
        val unknownEndpoint = c.copy(baseUrl = "https://custom.example/v1", model = "unknown")
        assertEquals("low", ThinkingSpecs.resolve(unknownEndpoint, ThinkingLevel.OFF,
            modelEntry = reported.copy(id = "unknown")).effort)
        val narrower = reported.copy(reasoningEfforts = listOf("low", "high"))
        assertFalse(ThinkingLevel.MAX in ThinkingSpecs.forProvider(c, narrower).levels)
        assertEquals("high", ThinkingSpecs.resolve(c, ThinkingLevel.MAX, modelEntry = narrower).effort)
        assertTrue(ThinkingSpecs.forProvider(c, reported.copy(reasoningEfforts = emptyList())).levels.isEmpty())
        assertEquals("max", ThinkingSpecs.resolve(c, ThinkingLevel.MAX,
            modelEntry = reported.copy(reasoningEfforts = listOf("low", "max", "ultra"))).effort)
    }
    private fun config(model: String, type: ProviderType = ProviderType.OPENAI_COMPAT,
        url: String = "https://api.deepseek.com/v1") = ProviderConfig("test", "Test", type, url, model)
    private fun entry(vararg values: String, toggle: Boolean = false, reasoning: Boolean = true,
        output: Int? = null, budget: Int? = null) =
        ModelsDev.Entry(reasoning, values.toList().takeIf { it.isNotEmpty() }, budget != null, budget, toggle, outputTokens = output)
    @After fun reset() = ModelsDev.replaceForTesting(emptyMap())

    @Test fun `offline DeepSeek Max is native max and all controls agree`() {
        val c = config("deepseek-v4-flash")
        assertEquals(listOf(ThinkingLevel.OFF, ThinkingLevel.LOW, ThinkingLevel.HIGH, ThinkingLevel.MAX), ThinkingSpecs.forProvider(c).levels)
        for (level in ThinkingLevel.entries) {
            val result = ThinkingSpecs.resolve(c, level)
            assertTrue(result.level in result.spec.levels)
            assertEquals(result.level.label, result.label)
        }
        assertEquals("max", ThinkingSpecs.resolve(c, ThinkingLevel.MAX).effort)
        assertEquals("max", ThinkingSpecs.resolve(c, ThinkingLevel.ULTRA).effort)
        assertEquals("thinking", ThinkingSpecs.resolve(c, ThinkingLevel.OFF).spec.toggleField)
    }

    @Test fun `endpoint vocabulary wins over another provider of the same model`() {
        ModelsDev.replaceForTesting(mapOf(
            "deepseek" to mapOf("deepseek-v4-flash" to entry("low", "high", "max", toggle = true)),
            "openrouter" to mapOf("deepseek/deepseek-v4-flash" to entry("none", "high", "xhigh")),
            "kilo" to mapOf("deepseek/deepseek-v4-flash" to entry("none", "low", "high", "max"))))
        assertEquals("max", ThinkingSpecs.resolve(config("deepseek-v4-flash"), ThinkingLevel.MAX).effort)
        val router = config("deepseek/deepseek-v4-flash", url = "https://openrouter.ai/api/v1")
        assertEquals("xhigh", ThinkingSpecs.resolve(router, ThinkingLevel.MAX).effort)
        assertEquals(ThinkingLevel.XHIGH, ThinkingSpecs.resolve(router, ThinkingLevel.MAX).level)
        assertTrue(ThinkingSpecs.resolve(router, ThinkingLevel.MAX).detail.contains("Using X-High"))
        val kilo = router.copy(baseUrl = "https://api.kilo.ai/api/gateway")
        assertEquals("max", ThinkingSpecs.routerReasoning(kilo, ThinkingLevel.MAX)?.effort)
    }

    @Test fun `catalog cannot invent unknown efforts or a disable level`() {
        ModelsDev.replaceForTesting(mapOf("test" to mapOf("model" to entry("high", "unknown", "low"))))
        val spec = ThinkingSpecs.forModel("model", "test")
        assertEquals(listOf(ThinkingLevel.LOW, ThinkingLevel.HIGH), spec.levels)
        assertEquals("low", ThinkingSpecs.effortWire("model", ThinkingLevel.MEDIUM, "test"))
        assertEquals("high", ThinkingSpecs.effortWire("model", ThinkingLevel.ULTRA, "test"))
        assertFalse(ThinkingLevel.OFF in spec.levels)
    }

    @Test fun `OpenAI documented families preserve native max offline`() {
        for (model in listOf("gpt-6.1-sol", "gpt-6-astra", "gpt-6-sol", "gpt-5.6", "gpt-5.6-sol")) {
            val c = config(model, ProviderType.OPENAI_RESPONSES, "https://api.openai.com/v1")
            assertEquals("max", ThinkingSpecs.resolve(c, ThinkingLevel.MAX).effort)
            assertFalse(ThinkingLevel.MINIMAL in ThinkingSpecs.forProvider(c).levels)
        }
        assertEquals("xhigh", ThinkingSpecs.effortWire("gpt-5.5", ThinkingLevel.MAX))
        assertEquals("medium", ThinkingSpecs.effortWire("gpt-6.1-sol", ThinkingLevel.OFF))
        assertEquals("none", ThinkingSpecs.effortWire("gpt-6-sol", ThinkingLevel.OFF))
        assertEquals("high", ThinkingSpecs.effortWire("o4-mini", ThinkingLevel.MAX))
    }

    @Test fun `unknown and automatic models have no artificial ladder`() {
        for (model in listOf("some-custom-model", "deepseek-r1", "grok-4-fast", "qwen-custom")) {
            val c = config(model, url = "https://custom.example/v1")
            assertEquals("Thinking", ThinkingSpecs.resolve(c, ThinkingLevel.MAX).label)
            assertTrue(ThinkingSpecs.forProvider(c).levels.isEmpty())
            assertNull(ThinkingSpecs.resolve(c, ThinkingLevel.MAX).effort)
        }
        assertTrue(ThinkingSpecs.forProvider(config("gpt-6-sol", url = "http://localhost:1234/v1")).levels.isEmpty())
    }

    @Test fun `toggle-only models show On and Off and no fake strengths`() {
        ModelsDev.replaceForTesting(mapOf("openrouter" to mapOf("z-ai/glm" to entry(toggle = true))))
        val c = config("z-ai/glm", url = "https://openrouter.ai/api/v1")
        val selected = ThinkingSpecs.resolve(c, ThinkingLevel.MAX)
        assertEquals("On", selected.label)
        assertEquals(listOf(ThinkingLevel.OFF, ThinkingLevel.HIGH), selected.spec.levels)
        assertEquals("On", selected.optionLabel(ThinkingLevel.HIGH))
        assertEquals(true, ThinkingSpecs.routerReasoning(c, ThinkingLevel.MAX)?.enabled)
        assertEquals(false, ThinkingSpecs.routerReasoning(c, ThinkingLevel.OFF)?.enabled)
        assertNull(ThinkingSpecs.routerReasoning(c, ThinkingLevel.MAX)?.effort)
        val local = config("local:model", url = "local://model")
        assertEquals("On", ThinkingSpecs.resolve(local, ThinkingLevel.ULTRA).label)
    }

    @Test fun `nonreasoning metadata disables controls and all parameters`() {
        ModelsDev.replaceForTesting(mapOf("deepseek" to mapOf("model" to entry(reasoning = false))))
        val result = ThinkingSpecs.resolve(config("model"), ThinkingLevel.MAX)
        assertEquals(ThinkingSpecs.Style.NONE, result.spec.style)
        assertFalse(result.active)
        assertTrue(result.spec.levels.isEmpty())
        assertNull(result.effort)
    }

    @Test fun `native Gemini levels match its actual model family`() {
        fun levels(model: String) = ThinkingSpecs.forProvider(config(model, ProviderType.GEMINI,
            "https://generativelanguage.googleapis.com/v1beta")).levels
        assertEquals(listOf(ThinkingLevel.LOW, ThinkingLevel.HIGH), levels("gemini-3-pro-preview"))
        assertEquals(listOf(ThinkingLevel.MINIMAL, ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH), levels("gemini-3-flash-preview"))
        assertEquals(listOf(ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH), levels("gemini-3.8-flash"))
        assertEquals(listOf(ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH), levels("gemini-4-argon"))
        assertFalse(ThinkingLevel.OFF in levels("gemini-2.5-pro"))
    }

    @Test fun `budgets leave room for the answer and never expose duplicate budgets`() {
        ModelsDev.replaceForTesting(mapOf("anthropic" to mapOf("claude-sonnet-4-5" to entry(output = 64_000, budget = 60_000))))
        val c = config("claude-sonnet-4-5", ProviderType.ANTHROPIC, "https://api.anthropic.com")
        val selected = ThinkingSpecs.resolve(c, ThinkingLevel.MAX, 8_192)
        assertEquals(59_904, selected.budget)
        assertEquals(64_000, selected.maxOutputTokens)
        assertTrue(selected.maxOutputTokens > selected.budget!!)
        assertFalse(ThinkingLevel.MINIMAL in selected.spec.levels)
        assertFalse(ThinkingLevel.ULTRA in selected.spec.levels)
        val flash = config("gemini-2.5-flash", ProviderType.GEMINI, "https://generativelanguage.googleapis.com/v1beta")
        assertEquals(24_576, ThinkingSpecs.resolve(flash, ThinkingLevel.MAX).budget)
        assertFalse(ThinkingLevel.XHIGH in ThinkingSpecs.forProvider(flash).levels)
    }

    @Test fun `adaptive Claude uses effort with valid off support`() {
        for (model in listOf("claude-opus-4-6", "claude-opus-4-8", "claude-sonnet-5", "claude-opus-5-5")) {
            val c = config(model, ProviderType.ANTHROPIC, "https://api.anthropic.com")
            val selected = ThinkingSpecs.resolve(c, ThinkingLevel.MAX)
            assertEquals(ThinkingSpecs.Style.ADAPTIVE, selected.spec.style)
            assertEquals("max", selected.effort)
            assertNull(selected.budget)
        }
        for (model in listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-mythos-preview")) {
            assertFalse(ThinkingLevel.OFF in ThinkingSpecs.forProvider(config(model, ProviderType.ANTHROPIC, "https://api.anthropic.com")).levels)
        }
        assertFalse(ThinkingLevel.XHIGH in ThinkingSpecs.forProvider(config("claude-opus-4-6", ProviderType.ANTHROPIC, "https://api.anthropic.com")).levels)
    }

    @Test fun `routed aliases do not claim adjustable effort`() {
        assertEquals("Thinking", ThinkingSpecs.resolve(HarnessProvider.config, ThinkingLevel.MAX).label)
        assertEquals("Thinking", ThinkingSpecs.resolve(config("kilo-auto/frontier", url = HarnessProvider.KILO_BASE_URL), ThinkingLevel.MAX).label)
    }

    @Test fun `resolution is monotonic and belongs to the displayed vocabulary`() {
        val providers = listOf(
            config("deepseek-v4-flash"), config("gpt-5.6-sol", ProviderType.OPENAI_RESPONSES, "https://api.openai.com/v1"),
            config("claude-opus-4-6", ProviderType.ANTHROPIC, "https://api.anthropic.com"),
            config("gemini-2.5-flash", ProviderType.GEMINI, "https://generativelanguage.googleapis.com/v1beta"),
            config("gemini-3-flash", ProviderType.GEMINI, "https://generativelanguage.googleapis.com/v1beta"))
        providers.forEach { c ->
            val sequence = ThinkingLevel.entries.filter { it != ThinkingLevel.OFF }.map { ThinkingSpecs.resolve(c, it) }
            assertTrue(sequence.all { it.level in it.spec.levels })
            assertTrue(sequence.zipWithNext().all { (a,b) -> a.level.rank <= b.level.rank })
            assertEquals(sequence.last().level, ThinkingSpecs.resolve(c, ThinkingLevel.MAX).level)
        }
    }

    @Test fun `verified generic budgets honor the catalog range without fabricating effort values`() {
        ModelsDev.replaceForTesting(mapOf("custom" to mapOf("budget-model" to
            ModelsDev.Entry(true, null, true, 16_384, true, outputTokens = 32_768, budgetMin = 4_096))),
            listOf(ModelsDev.ProviderInfo("custom", "Custom", "https://custom.example/v1", "@ai-sdk/anthropic", 1)))
        val c = config("budget-model", ProviderType.ANTHROPIC, "https://custom.example/v1")
        val max = ThinkingSpecs.resolve(c, ThinkingLevel.MAX)
        assertEquals(16_384, max.budget)
        assertEquals(ThinkingSpecs.Style.BUDGET, max.spec.style)
        for (level in max.spec.levels.filter { it != ThinkingLevel.OFF })
            assertTrue(ThinkingSpecs.resolve(c, level).budget!! in 4_096..16_384)
        // A bare compat endpoint does not implement Anthropic's native budget field.
        assertEquals("On", ThinkingSpecs.resolve(c.copy(type = ProviderType.OPENAI_COMPAT), ThinkingLevel.MAX).label)
    }

    @Test fun `provider host matching is exact and gateway aliases have catalog keys`() {
        assertEquals("kilo", ModelsDev.providerKeyFor(HarnessProvider.KILO_BASE_URL))
        assertEquals("kilo", ModelsDev.providerKeyFor("https://ai-gateway.kilo.ai/api/v1"))
        assertNull(ModelsDev.providerKeyFor("https://api.openai.com.untrusted.example/v1"))
        assertEquals("opencode-go", ModelsDev.providerKeyFor("https://opencode.ai/zen/go/v1"))
    }
}
