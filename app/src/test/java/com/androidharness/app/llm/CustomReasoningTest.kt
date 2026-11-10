package com.androidharness.app.llm

import com.androidharness.app.agent.*
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.data.ChatModelSelection
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class CustomReasoningTest {
    @Test fun `future provider vocabulary is selectable without saving an override and custom settings still win`() {
        val c = ProviderConfig("future", "Future", ProviderType.OPENAI_COMPAT, "https://custom.example/v1", "new-model")
        val entry = ModelEntry(c.model, reasoning = true, reasoningEfforts = listOf("low", "high", "extreme"), defaultReasoningEffort = "high")
        val opts = RequestOptions(thinking = ThinkingLevel.MAX, modelCatalogs = mapOf(c.id to listOf(entry)))
        assertEquals("Extreme", opts.resolveThinking(c).label)
        assertEquals("extreme", OpenAiCompatProvider(client, json).buildRequestBody(c, "fixture", user, emptyList(), opts)["reasoning_effort"]!!.jsonPrimitive.content)
        val key = CustomReasoning.key(c)
        val chosen = opts.copy(customThinkingLevels = mapOf(key to "low"))
        assertEquals("Low", chosen.resolveThinking(c).label)
        val custom = CustomReasoningConfig(listOf("Only mine"), """{"reasoning_effort":"high"}""")
        val saved = opts.copy(customReasoning = mapOf(key to custom))
        assertEquals(listOf("Only mine"), saved.resolveThinking(c).customConfig!!.levels)
        assertEquals("high", OpenAiCompatProvider(client, json).buildRequestBody(c, "fixture", user, emptyList(), saved)["reasoning_effort"]!!.jsonPrimitive.content)
        assertTrue(opts.customReasoning.isEmpty())
        assertEquals("Max", opts.copy(modelCatalogs = mapOf(c.id to listOf(entry.copy(reasoningEfforts = listOf("low", "max", "extreme")))))
            .resolveThinking(c).label)
    }
    private val config = ProviderConfig("deepseek", "DeepSeek", ProviderType.OPENAI_COMPAT, "https://api.deepseek.com/v1", "deepseek-v4-flash")
    private val user = listOf(ChatMessage(Role.USER, "Check"))
    private val client = OkHttpClient()
    private val json = ProviderFactory.json
    private fun options(c: ProviderConfig = config, custom: CustomReasoningConfig, level: String = "max") = RequestOptions(
        thinking = ThinkingLevel.LOW, customReasoning = mapOf(CustomReasoning.key(c) to custom),
        customThinkingLevels = mapOf(CustomReasoning.key(c) to level))
    private fun body(c: ProviderConfig = config, custom: CustomReasoningConfig, level: String = "max"): JsonObject =
        OpenAiCompatProvider(client, json).buildRequestBody(c, "sys", user, emptyList(), options(c, custom, level))

    @Test fun `names normalize case and x highest aliases without losing user order`() {
        val normalized = CustomReasoning.normalized(CustomReasoningConfig(listOf("disabled", "LOW", "hIGH", "max", "X highest")))
        assertEquals(listOf("Disabled", "Low", "High", "Max", "X-High"), normalized.levels)
        assertEquals("max", CustomReasoning.wire("  MaX "))
        assertEquals("xhigh", CustomReasoning.wire("X Highest"))
        assertThrows(IllegalArgumentException::class.java) { CustomReasoning.normalized(CustomReasoningConfig(listOf("max", "MAX"))) }
    }

    @Test fun `optional blank mapping uses the selected native effort and off toggle`() {
        val custom = CustomReasoningConfig(listOf("disabled", "low", "high", "max"))
        val selected = options(custom = custom).resolveThinking(config)
        assertEquals("Max", selected.label)
        assertEquals("max", body(custom = custom)["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("disabled", body(custom = custom, level = "disabled")["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val budget = config.copy(type = ProviderType.ANTHROPIC, baseUrl = "https://api.anthropic.com", model = "claude-sonnet-4-5")
        val budgetOptions = options(budget, CustomReasoningConfig(listOf("Low", "Max")))
        val request = AnthropicProvider(client, json).buildRequestBody(budget, "sys", user, emptyList(), budgetOptions)
        assertEquals(ThinkingSpecs.resolve(budget, ThinkingLevel.MAX).budget,
            request["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.int)
        assertTrue(request["max_tokens"]!!.jsonPrimitive.int > request["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.int)
    }

    @Test fun `real CEL supports conditional nested objects numeric lookup and null`() {
        val expression = """reasoningLevel == "disabled" ? {"thinking": {"type": "disabled"}, "reasoning_effort": null} : {"thinking": {"type": "enabled", "budget_tokens": {"low": 1024, "max": 32768}[reasoningLevel]}}"""
        val custom = ReasoningMapping.validate(CustomReasoningConfig(listOf("disabled", "low", "max"), expression), config, ThinkingSpecs.forProvider(config))
        assertEquals(32768, body(custom = custom)["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.int)
        assertFalse(body(custom = custom, level = "disabled").containsKey("reasoning_effort"))
        assertEquals("max", ReasoningMapping.evaluate("""{"effort": reasoningLevel}""", "MAX")["effort"]!!.jsonPrimitive.content)
    }

    @Test fun `invalid expressions or nonobject results are rejected for every configured branch`() {
        for (expression in listOf("unknownField", "1 +", "42", "{'budget': 0.0 / 0.0}", "reasoningLevel == 'low' ? {'x': 1} : {'x': 1 / 0}")) {
            assertThrows(IllegalArgumentException::class.java) {
                ReasoningMapping.validate(CustomReasoningConfig(listOf("Low", "Max"), expression), config, ThinkingSpecs.forProvider(config))
            }
        }
        assertThrows(IllegalArgumentException::class.java) { body(custom = CustomReasoningConfig(listOf("Max"), "42")) }
    }

    @Test fun `recursive merge preserves output config and explicit null removes keys`() {
        val base = json.parseToJsonElement("""{"generationConfig":{"maxOutputTokens":9000,"thinkingConfig":{"old":true}},"stream":true}""").jsonObject
        val patch = json.parseToJsonElement("""{"generationConfig":{"thinkingConfig":{"old":null,"thinkingLevel":"high"}}}""").jsonObject
        val merged = ReasoningMapping.merge(base, patch)
        assertEquals(9000, merged["generationConfig"]!!.jsonObject["maxOutputTokens"]!!.jsonPrimitive.int)
        assertFalse(merged["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject.containsKey("old"))
        assertEquals(true, merged["stream"]!!.jsonPrimitive.boolean)
    }

    @Test fun `custom mappings reach all four remote protocols and preserve messages and tools`() {
        val custom = CustomReasoningConfig(listOf("Low", "X highest"), """{"custom_reasoning": {"effort": reasoningLevel}}""")
        val fixtures = listOf(config, config.copy(type = ProviderType.OPENAI_RESPONSES),
            config.copy(type = ProviderType.ANTHROPIC), config.copy(type = ProviderType.GEMINI))
        for (c in fixtures) {
            val opts = options(c, custom, "X HIGHEST")
            val body = when(c.type) {
                ProviderType.OPENAI_COMPAT -> OpenAiCompatProvider(client, json).buildRequestBody(c, "sys", user, emptyList(), opts)
                ProviderType.OPENAI_RESPONSES -> OpenAiResponsesProvider(client, json).buildRequestBody(c, "sys", user, emptyList(), opts)
                ProviderType.ANTHROPIC -> AnthropicProvider(client, json).buildRequestBody(c, "sys", user, emptyList(), opts)
                ProviderType.GEMINI -> GeminiProvider(client, json).buildRequestBody(c, "sys", user, emptyList(), opts)
            }
            assertEquals("xhigh", body["custom_reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
            assertTrue(body.containsKey(when(c.type) { ProviderType.OPENAI_RESPONSES -> "input"; ProviderType.GEMINI -> "contents"; else -> "messages" }))
        }
    }

    @Test fun `custom configuration cannot leak across provider model protocol or endpoint changes`() {
        val opts = options(custom = CustomReasoningConfig(listOf("max"), """{"reasoning_effort":"custom"}"""))
        for (other in listOf(config.copy(id = "other"), config.copy(model = "other"), config.copy(baseUrl = "https://other.example/v1"), config.copy(type = ProviderType.OPENAI_RESPONSES))) {
            assertNull(opts.resolveThinking(other).customConfig)
            assertNull(opts.applyReasoningMapping(other, JsonObject(emptyMap()))["reasoning_effort"])
        }
        assertEquals(CustomReasoning.key(config), CustomReasoning.key(config.copy(baseUrl = config.baseUrl + "/")))
        assertFalse(CustomReasoning.key(config).contains("deepseek"))
    }

    @Test fun `declared levels inherit protocol mapping without a native preset whitelist`() {
        val c = config.copy(baseUrl = "https://custom.example/v1", model = "new-provider-model")
        val custom = CustomReasoningConfig(listOf("low", "MAX", "Turbo"))
        val stalePresets = ThinkingSpecs.Spec(ThinkingSpecs.Style.EFFORT, listOf(ThinkingLevel.LOW, ThinkingLevel.HIGH))
        val saved = ReasoningMapping.validate(custom, c, stalePresets)
        assertEquals("", saved.mapping)
        assertEquals(listOf("Low", "Max", "Turbo"), saved.levels)
        assertEquals("max", body(c, saved, "MAX")["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("turbo", body(c, saved, "Turbo")["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test fun `custom effort is not silently clamped to a different model preset`() {
        // The custom list is the user's capability declaration, not an assertion about this fixture's real API.
        val c = config.copy(baseUrl = "https://api.openai.com/v1", model = "gpt-oss-120b")
        val custom = ReasoningMapping.validate(CustomReasoningConfig(listOf("Low", "Max")), c, ThinkingSpecs.forProvider(c))
        assertEquals("max", body(c, custom)["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("Max", options(c, custom).resolveThinking(c).label)
    }

    @Test fun `custom declarations can override incomplete reasoning capability metadata`() {
        val c = config.copy(baseUrl = "https://custom.example/v1", model = "new-release")
        val custom = CustomReasoningConfig(listOf("Low", "Max"))
        val spec = ThinkingSpecs.forProvider(c, ModelEntry(c.model, reasoning = false))
        assertEquals(ThinkingSpecs.Style.NONE, spec.style)
        ReasoningMapping.validate(custom, c, spec)
        val opts = options(c, custom).copy(modelCatalogs = mapOf(c.id to listOf(ModelEntry(c.model, reasoning = false))))
        val sent = OpenAiCompatProvider(client, json).buildRequestBody(c, "sys", user, emptyList(), opts)
        assertEquals("max", sent["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test fun `a blank override stays inherited while an explicit override wins`() {
        val inherited = ReasoningMapping.validate(CustomReasoningConfig(listOf("low", "max")), config, ThinkingSpecs.forProvider(config))
        assertEquals("", inherited.mapping)
        assertEquals(ReasoningDefaults.inherited(config, ThinkingSpecs.forProvider(config)), options(custom = inherited).resolveThinking(config).requestMapping)
        val overridden = inherited.copy(mapping = """{"reasoning_effort": "high", "thinking": {"type": "enabled"}}""")
        assertEquals("high", body(custom = overridden)["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals(overridden.mapping, options(custom = overridden).resolveThinking(config).requestMapping)
    }

    @Test fun `missing inherited map and invalid CEL have distinct errors`() {
        val c = config.copy(type = ProviderType.ANTHROPIC, baseUrl = "https://custom.example", model = "custom-anthropic-model")
        val missing = assertThrows(IllegalArgumentException::class.java) {
            ReasoningMapping.validate(CustomReasoningConfig(listOf("Max")), c, ThinkingSpecs.forProvider(c))
        }
        assertTrue(missing.message!!.contains("No inherited reasoning mapping"))
        val invalid = assertThrows(IllegalArgumentException::class.java) {
            ReasoningMapping.validate(CustomReasoningConfig(listOf("Max"), "{bad"), c, ThinkingSpecs.forProvider(c))
        }
        assertTrue(invalid.message!!.startsWith("Reasoning mapping:"))
        ReasoningMapping.validate(CustomReasoningConfig(listOf("Max"), """{"output_config": {"effort": reasoningLevel}}"""), c, ThinkingSpecs.forProvider(c))
    }

    @Test fun `inherited mappings use native nested fields for each remote API`() {
        val fixtures = listOf(
            config,
            config.copy(type = ProviderType.OPENAI_RESPONSES, baseUrl = "https://api.openai.com/v1", model = "gpt-6.1-sol"),
            config.copy(type = ProviderType.ANTHROPIC, baseUrl = "https://api.anthropic.com", model = "claude-sonnet-4-6"),
            config.copy(type = ProviderType.GEMINI, baseUrl = "https://generativelanguage.googleapis.com/v1beta", model = "gemini-3-pro"))
        for (c in fixtures) {
            val custom = ReasoningMapping.validate(CustomReasoningConfig(listOf("Low", "Max")), c, ThinkingSpecs.forProvider(c))
            val patch = ReasoningMapping.evaluate(ReasoningDefaults.effective(custom, c, ThinkingSpecs.forProvider(c))!!, "MAX")
            val actual = when (c.type) {
                ProviderType.OPENAI_COMPAT -> patch["reasoning_effort"]
                ProviderType.OPENAI_RESPONSES -> patch["reasoning"]!!.jsonObject["effort"]
                ProviderType.ANTHROPIC -> patch["output_config"]!!.jsonObject["effort"]
                ProviderType.GEMINI -> patch["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingLevel"]
            }
            assertEquals("max", actual!!.jsonPrimitive.content)
        }
    }

    @Test fun `router and Gemini budget defaults preserve native token ceilings and explicit disable`() {
        val router = config.copy(baseUrl = "https://openrouter.ai/api/v1", model = "claude-sonnet-4-5")
        val gemini = config.copy(type = ProviderType.GEMINI, baseUrl = "https://generativelanguage.googleapis.com/v1beta", model = "gemini-2.5-pro")
        for (c in listOf(router, gemini)) {
            val spec = ThinkingSpecs.forProvider(c)
            val expression = ReasoningDefaults.inherited(c, spec)!!
            val max = ReasoningMapping.evaluate(expression, "max")
            val budget = if (c == router) max["reasoning"]!!.jsonObject["max_tokens"]!!.jsonPrimitive.int
                else max["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingBudget"]!!.jsonPrimitive.int
            assertEquals(spec.budgetMax, budget)
            val off = ReasoningMapping.evaluate(expression, "disabled")
            if (c == router) assertFalse(off["reasoning"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
            else assertEquals(0, off["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingBudget"]!!.jsonPrimitive.int)
        }
        val toggle = ThinkingSpecs.Spec(ThinkingSpecs.Style.TOGGLE, listOf(ThinkingLevel.OFF, ThinkingLevel.HIGH))
        assertTrue(ReasoningMapping.evaluate(ReasoningDefaults.inherited(router, toggle)!!, "max")["reasoning"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
    }

    @Test fun `local models retain native thinking controls without pretending to send JSON`() {
        val c = config.copy(baseUrl = "local://fixture", model = "local-fixture")
        val custom = ReasoningMapping.validate(CustomReasoningConfig(listOf("Disabled", "On")), c, ThinkingSpecs.forProvider(c))
        assertNull(ReasoningDefaults.inherited(c, ThinkingSpecs.forProvider(c)))
        assertEquals(ThinkingLevel.OFF, options(c, custom, "disabled").resolveThinking(c).level)
        assertEquals(ThinkingLevel.HIGH, options(c, custom, "on").resolveThinking(c).level)
        assertThrows(IllegalArgumentException::class.java) {
            ReasoningMapping.validate(custom.copy(mapping = "{}"), c, ThinkingSpecs.forProvider(c))
        }
    }

    @Test fun `selections round trip independently for each chat and older JSON remains readable`() {
        val a = ChatModelSelection(customThinkingLevels = mapOf(CustomReasoning.key(config) to "max"))
        assertEquals(a, ChatModelSelection.decode(ChatModelSelection.encode(a)))
        assertTrue(ChatModelSelection.decode("{}")!!.customThinkingLevels.isEmpty())
        assertTrue(ChatModelSelection().customThinkingLevels.isEmpty())
    }

    @Test fun `model managed thinking says On only when support is known`() {
        val c = config.copy(baseUrl = "https://custom.example/v1", model = "custom")
        assertEquals("On", RequestOptions().resolveThinking(c, ModelEntry("custom", reasoning = true)).label)
        assertEquals("Thinking", RequestOptions().resolveThinking(c).label)
        assertEquals("Off", RequestOptions().resolveThinking(c, ModelEntry("custom", reasoning = false)).label)
    }
}
