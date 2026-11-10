package com.androidharness.app.llm

import com.androidharness.app.agent.ThinkingLevel
import com.androidharness.app.core.*
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ProviderThinkingIntegrationTest {
    private val json = ProviderFactory.json
    private val user = ChatMessage(Role.USER, "Inspect this file")
    private val schema = ToolSchema("read_file", "Read", json.parseToJsonElement("""{"type":"object"}""").jsonObject)
    private val tools = listOf(schema)
    private fun config(model: String, type: ProviderType = ProviderType.OPENAI_COMPAT, url: String = "https://api.deepseek.com/v1") =
        ProviderConfig("fixture", "Fixture", type, url, model)
    private fun client(vararg events: String) = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body(events.joinToString("") { "data: $it\n\n" }.toResponseBody("text/event-stream".toMediaType())).build()
    }.build()
    @Test fun `inherited custom reasoning survives the GPT6 tool transport redirect`() = runBlocking {
        val c = ProviderConfig("openai-fixture", "OpenAI", ProviderType.OPENAI_COMPAT, "https://api.openai.com/v1", "gpt-6.1-sol")
        var sent: JsonObject? = null
        var path: String? = null
        val intercepted = OkHttpClient.Builder().addInterceptor { chain ->
            path = chain.request().url.encodedPath
            val buffer = okio.Buffer()
            chain.request().body!!.writeTo(buffer)
            sent = json.parseToJsonElement(buffer.readUtf8()).jsonObject
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("data: [DONE]\n\n".toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val key = com.androidharness.app.agent.CustomReasoning.key(c)
        val opts = RequestOptions(thinking = ThinkingLevel.LOW,
            customReasoning = mapOf(key to com.androidharness.app.agent.CustomReasoningConfig(listOf("Low", "Max"))),
            customThinkingLevels = mapOf(key to "max"))
        OpenAiCompatProvider(intercepted, json).streamChat(c, "fixture", "sys", listOf(user), tools, opts).toList()
        assertEquals("/v1/responses", path)
        assertEquals("max", sent!!["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertFalse(sent!!.containsKey("reasoning_effort"))
        assertThrows(IllegalArgumentException::class.java) { opts.forTransportRoute(c, c.copy(baseUrl = "https://other.example/v1")) }
        Unit
    }

    @After fun reset() = ModelsDev.replaceForTesting(emptyMap())

    @Test fun `DeepSeek Max and Off use explicit native controls without a catalog`() {
        val provider = OpenAiCompatProvider(OkHttpClient(), json)
        val c = config("deepseek-v4-flash")
        fun body(level: ThinkingLevel) = provider.buildRequestBody(c, "sys", listOf(user), tools, RequestOptions(thinking = level))
        assertEquals("max", body(ThinkingLevel.MAX)["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("enabled", body(ThinkingLevel.MAX)["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("disabled", body(ThinkingLevel.OFF)["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertNull(body(ThinkingLevel.OFF)["reasoning_effort"])
    }

    @Test fun `DeepSeek tool continuation preserves reasoning and exact reported tokens after save`() = runBlocking {
        val c = config("deepseek-v4-flash")
        val provider = OpenAiCompatProvider(client(
            """{"choices":[{"delta":{"reasoning_content":"Inspect "}}]}""",
            """{"choices":[{"delta":{"reasoning_content":"the file.","tool_calls":[{"index":0,"id":"call1","function":{"name":"read_file","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""",
            """{"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":50,"completion_tokens_details":{"reasoning_tokens":40}}}""",
            "[DONE]"), json)
        val events = provider.streamChat(c, "fixture", "sys", listOf(user), tools, RequestOptions(thinking = ThinkingLevel.MAX)).toList()
        val call = events.filterIsInstance<StreamEvent.ToolCallReady>().single().call
        val assistant = ChatMessage(Role.ASSISTANT, toolCalls = listOf(call), thinking = "Inspect the file.")
        val generation = GenerationMetadata(events.filterIsInstance<StreamEvent.ResponseState>().single().state.boundTo(assistant),
            events.filterIsInstance<StreamEvent.Usage>().single().reasoningTokens)
        val saved = assistant.copy(generation = GenerationMetadata.decode(GenerationMetadata.encode(generation)))
        val history = listOf(user, saved, ChatMessage(Role.TOOL, "file", toolCallId = call.id, toolName = call.name))
        val body = provider.buildRequestBody(c, "sys", history, tools, RequestOptions(thinking = ThinkingLevel.MAX))
        val replay = body["messages"]!!.jsonArray[2].jsonObject
        assertEquals("Inspect the file.", replay["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals(40, saved.generation.reasoningTokens)
        assertEquals("call1", replay["tool_calls"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
        assertFalse(provider.buildRequestBody(c.copy(baseUrl = "https://other.example/v1"), "sys", history, tools, RequestOptions())
            ["messages"]!!.jsonArray[2].jsonObject.containsKey("reasoning_content"))
    }

    @Test fun `OpenRouter structured summaries and encrypted fragments are preserved by block`() = runBlocking {
        val c = config("deepseek/deepseek-v4-flash", url = "https://openrouter.ai/api/v1")
        val provider = OpenAiCompatProvider(client(
            """{"choices":[{"delta":{"reasoning_details":[{"type":"reasoning.summary","index":0,"summary":"Checking "},{"type":"reasoning.encrypted","index":0,"data":"signed-"}]}}]}""",
            """{"choices":[{"delta":{"reasoning_details":[{"type":"reasoning.summary","index":0,"summary":"carefully"},{"type":"reasoning.encrypted","index":0,"data":"payload"}],"content":"Answer"},"finish_reason":"stop"}]}""", "[DONE]"), json)
        val events = provider.streamChat(c, "fixture", "sys", listOf(user), emptyList(), RequestOptions(thinking = ThinkingLevel.MAX)).toList()
        assertEquals("Checking carefully", events.filterIsInstance<StreamEvent.ThinkingDelta>().joinToString("") { it.text })
        val assistant = ChatMessage(Role.ASSISTANT, "Answer")
        val saved = assistant.copy(generation = GenerationMetadata(events.filterIsInstance<StreamEvent.ResponseState>().single().state.boundTo(assistant)))
        val body = provider.buildRequestBody(c, "sys", listOf(user, saved), emptyList(), RequestOptions())
        val details = body["messages"]!!.jsonArray.last().jsonObject["reasoning_details"]!!.jsonArray
        assertEquals(listOf("reasoning.summary", "reasoning.encrypted"), details.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals("signed-payload", details.last().jsonObject["data"]!!.jsonPrimitive.content)
        assertFalse(events.filterIsInstance<StreamEvent.ThinkingDelta>().any { "signed" in it.text })
    }

    @Test fun `adaptive and legacy Claude send their different native settings`() {
        val provider = AnthropicProvider(OkHttpClient(), json)
        val adaptive = provider.buildRequestBody(config("claude-opus-4-8", ProviderType.ANTHROPIC, "https://api.anthropic.com"),
            "sys", listOf(user), tools, RequestOptions(thinking = ThinkingLevel.MAX))
        assertEquals("adaptive", adaptive["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("summarized", adaptive["thinking"]!!.jsonObject["display"]!!.jsonPrimitive.content)
        assertEquals("max", adaptive["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertNull(adaptive["thinking"]!!.jsonObject["budget_tokens"])
        val legacy = provider.buildRequestBody(config("claude-sonnet-4-5", ProviderType.ANTHROPIC, "https://api.anthropic.com"),
            "sys", listOf(user), tools, RequestOptions(maxOutputTokens = 4_096, thinking = ThinkingLevel.MAX))
        assertEquals("enabled", legacy["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(legacy["max_tokens"]!!.jsonPrimitive.int > legacy["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.int)
    }

    @Test fun `Claude signatures redacted thinking and block order survive a tool followup`() = runBlocking {
        val c = config("claude-opus-4-8", ProviderType.ANTHROPIC, "https://api.anthropic.com")
        val provider = AnthropicProvider(OkHttpClient(), json)
        val script = listOf(
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Inspect first"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig-"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"opaque"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"redacted_thinking","data":"redacted-opaque"}}""",
            """{"type":"content_block_stop","index":1}""",
            """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"call1","name":"read_file","input":{}}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"a.kt\"}"}}""",
            """{"type":"content_block_stop","index":2}""",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":30}}""",
            """{"type":"message_stop"}""")
        val events = provider.parseStream(script.map(json::parseToJsonElement).asFlow(), c).toList()
        val call = events.filterIsInstance<StreamEvent.ToolCallReady>().single().call
        val assistant = ChatMessage(Role.ASSISTANT, toolCalls = listOf(call))
        val state = events.filterIsInstance<StreamEvent.ResponseState>().single().state.boundTo(assistant)
        val saved = assistant.copy(generation = GenerationMetadata.decode(GenerationMetadata.encode(GenerationMetadata(state))))
        val body = provider.buildRequestBody(c, "sys", listOf(user, saved, ChatMessage(Role.TOOL, "file", toolCallId = call.id)), tools, RequestOptions(thinking = ThinkingLevel.MAX))
        val content = body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        assertEquals(listOf("thinking", "redacted_thinking", "tool_use"), content.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals("sig-opaque", content[0].jsonObject["signature"]!!.jsonPrimitive.content)
        assertEquals("redacted-opaque", content[1].jsonObject["data"]!!.jsonPrimitive.content)
        assertEquals("a.kt", content[2].jsonObject["input"]!!.jsonObject["path"]!!.jsonPrimitive.content)
        assertFalse(provider.serializeMessages(listOf(saved.copy(text = "Changed by compaction")), c).toString().contains("sig-opaque"))
    }

    @Test fun `Gemini parallel calls preserve thought signatures IDs and thought usage`() = runBlocking {
        val c = config("gemini-3-flash-preview", ProviderType.GEMINI, "https://generativelanguage.googleapis.com/v1beta")
        val parts = json.parseToJsonElement("""[{"text":"Inspect","thought":true},{"functionCall":{"id":"fc1","name":"read_file","args":{}},"thoughtSignature":"sig-A"},{"functionCall":{"id":"fc2","name":"read_file","args":{}}},{"text":"","thoughtSignature":"sig-B"}]""").jsonArray
        val provider = GeminiProvider(client("""{"candidates":[{"content":{"parts":$parts},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":2,"thoughtsTokenCount":30}}"""), json)
        val events = provider.streamChat(c, "fixture", "sys", listOf(user), tools, RequestOptions(thinking = ThinkingLevel.MAX)).toList()
        val calls = events.flatMap { if (it is StreamEvent.Batch) it.events else listOf(it) }.filterIsInstance<StreamEvent.ToolCallReady>().map { it.call }
        assertEquals(listOf("fc1", "fc2"), calls.map { it.id })
        val assistant = ChatMessage(Role.ASSISTANT, toolCalls = calls)
        val saved = assistant.copy(generation = GenerationMetadata(events.filterIsInstance<StreamEvent.ResponseState>().single().state.boundTo(assistant)))
        val followup = provider.buildRequestBody(c, "sys", listOf(user, saved,
            ChatMessage(Role.TOOL, "one", toolCallId = "fc1", toolName = "read_file"),
            ChatMessage(Role.TOOL, "two", toolCallId = "fc2", toolName = "read_file")), tools, RequestOptions(thinking = ThinkingLevel.MAX))
        assertEquals(parts, followup["contents"]!!.jsonArray[1].jsonObject["parts"])
        assertEquals(listOf("fc1", "fc2"), followup["contents"]!!.jsonArray[2].jsonObject["parts"]!!.jsonArray.map { it.jsonObject["functionResponse"]!!.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals("high", followup["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content)
        assertEquals(32, events.filterIsInstance<StreamEvent.Usage>().single().outputTokens)
        assertEquals(30, events.filterIsInstance<StreamEvent.Usage>().single().reasoningTokens)
        assertFalse(provider.serializeMessages(listOf(saved), c.copy(model = "gemini-2.5-flash")).toString().contains("sig-A"))
    }

    @Test fun `Responses preserves generic API reasoning and reads reasoning usage`() = runBlocking {
        val c = config("gpt-6.1-sol", ProviderType.OPENAI_RESPONSES, "https://api.openai.com/v1")
        val item = """{"type":"reasoning","id":"r1","encrypted_content":"opaque","summary":[]}"""
        val provider = OpenAiResponsesProvider(client(
            """{"type":"response.reasoning_text.delta","delta":"Thinking"}""",
            """{"type":"response.output_item.done","item":$item}""",
            """{"type":"response.output_text.delta","delta":"Answer"}""",
            """{"type":"response.completed","response":{"output":[$item],"usage":{"input_tokens":10,"output_tokens":25,"output_tokens_details":{"reasoning_tokens":20}}}}"""), json)
        val events = provider.streamChat(c, "fixture", "sys", listOf(user), tools, RequestOptions(thinking = ThinkingLevel.MAX)).toList()
        assertEquals("Thinking", events.filterIsInstance<StreamEvent.ThinkingDelta>().single().text)
        assertEquals(20, events.filterIsInstance<StreamEvent.Usage>().single().reasoningTokens)
        val assistant = ChatMessage(Role.ASSISTANT, "Answer")
        val saved = assistant.copy(generation = GenerationMetadata(events.filterIsInstance<StreamEvent.ResponseState>().single().state.boundTo(assistant)))
        val body = provider.buildRequestBody(c, "sys", listOf(user, saved), tools, RequestOptions(thinking = ThinkingLevel.MAX))
        assertEquals("max", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("opaque", body["input"]!!.jsonArray[1].jsonObject["encrypted_content"]!!.jsonPrimitive.content)
        assertFalse(provider.buildRequestBody(c.copy(id = "other"), "sys", listOf(saved), tools, RequestOptions()).toString().contains("opaque"))
    }

    @Test fun `official OpenAI reasoning tool requests use Responses even from a compat provider`() = runBlocking {
        var captured: Request? = null
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            captured = chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n".toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        OpenAiCompatProvider(http, json).streamChat(config("gpt-6.1-sol", url = "https://api.openai.com/v1"),
            "fixture", "sys", listOf(user), tools, RequestOptions(thinking = ThinkingLevel.MAX)).toList()
        assertEquals("/v1/responses", captured!!.url.encodedPath)
        val buffer = okio.Buffer().also { captured!!.body!!.writeTo(it) }
        val body = json.parseToJsonElement(buffer.readUtf8()).jsonObject
        assertEquals("max", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertTrue(body["tools"]!!.jsonArray.single().jsonObject.containsKey("name"))
    }

    @Test fun `Kimi and ZAI use their verified controls and preserve reasoning`() {
        val provider = OpenAiCompatProvider(OkHttpClient(), json)
        val kimi = config("kimi-k3", url = "https://api.moonshot.ai/v1")
        val max = provider.buildRequestBody(kimi, "sys", listOf(user), tools, RequestOptions(thinking = ThinkingLevel.MAX))
        assertEquals("max", max["reasoning_effort"]!!.jsonPrimitive.content)
        assertNull(max["thinking"])
        val glm = config("glm-4.7", url = "https://api.z.ai/api/paas/v4")
        val enabled = provider.buildRequestBody(glm, "sys", listOf(user), tools, RequestOptions(thinking = ThinkingLevel.MAX))
        assertFalse(enabled["thinking"]!!.jsonObject["clear_thinking"]!!.jsonPrimitive.boolean)
        assertNull(enabled["clear_thinking"])
        val disabled = provider.buildRequestBody(glm, "sys", listOf(user), tools, RequestOptions(thinking = ThinkingLevel.OFF))
        assertEquals("disabled", disabled["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun `DeepSeek Anthropic format uses native effort even for Claude aliases`() {
        val provider = AnthropicProvider(OkHttpClient(), json)
        val c = config("claude-opus-4-8", ProviderType.ANTHROPIC, "https://api.deepseek.com/anthropic")
        val selected = com.androidharness.app.agent.ThinkingSpecs.resolve(c, ThinkingLevel.MAX)
        assertFalse(ThinkingLevel.XHIGH in selected.spec.levels)
        val body = provider.buildRequestBody(c, "sys", listOf(user), tools, RequestOptions(thinking = ThinkingLevel.MAX))
        assertEquals("max", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("enabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertNull(body["thinking"]!!.jsonObject["budget_tokens"])
    }

    @Test fun `continuation binding never writes endpoint credentials into chat backups`() {
        val c = config("deepseek-flash", url = "https://username:fixture-password@custom.example/v1?api_key=fixture-key")
        val message = ChatMessage(Role.ASSISTANT, "Answer")
        val state = ProviderMessageState.of(c, "chat_completions", emptyList()).boundTo(message)
        val saved = GenerationMetadata.encode(GenerationMetadata(state))!!
        assertFalse(saved.contains("fixture-password"))
        assertFalse(saved.contains("fixture-key"))
        assertFalse(saved.contains("custom.example"))
        assertTrue(GenerationMetadata.decode(saved).state!!.matches(c, "chat_completions", message))
        assertFalse(state.matches(c.copy(baseUrl = "https://custom.example/v2"), "chat_completions", message))
    }

    @Test fun `old and unknown Claude models never receive invented thinking budgets`() {
        val provider = AnthropicProvider(OkHttpClient(), json)
        for (model in listOf("claude-3-haiku-20240307", "claude-3-5-sonnet-20241022", "claude-future-custom")) {
            val c = config(model, ProviderType.ANTHROPIC, "https://api.anthropic.com")
            assertNull(provider.buildRequestBody(c, "sys", listOf(user), tools, RequestOptions(thinking = ThinkingLevel.MAX))["thinking"])
        }
    }

    @Test fun `request and header use the same provider catalog including model overrides`() {
        val c = config("deepseek-v4-flash")
        val entry = ModelEntry(c.model, reasoning = false)
        val options = RequestOptions(thinking = ThinkingLevel.MAX, modelCatalogs = mapOf(c.id to listOf(entry)))
        assertEquals(com.androidharness.app.agent.ThinkingSpecs.resolve(c, ThinkingLevel.MAX, modelEntry = entry), options.resolveThinking(c))
        val body = OpenAiCompatProvider(OkHttpClient(), json).buildRequestBody(c, "sys", listOf(user), tools, options)
        assertNull(body["reasoning_effort"])
        assertNull(body["thinking"])
        // A different model must not inherit the first model's false capability flag.
        assertEquals("max", options.resolveThinking(c.copy(model = "deepseek-v4-pro")).effort)
        val alias = config("kilo-auto/free", url = HarnessProvider.KILO_BASE_URL)
        assertEquals("Thinking", com.androidharness.app.agent.ThinkingSpecs.resolve(alias, ThinkingLevel.MAX,
            modelEntry = ModelEntry(alias.model, reasoning = false)).label)
    }

    @Test fun `unknown metadata stays unavailable and legacy storage stays readable`() {
        assertNull(GenerationMetadata.decode(null).reasoningTokens)
        assertNull(GenerationMetadata.decode("invalid").state)
        assertEquals(0, GenerationMetadata.decode("""{"reasoningTokens":0,"future":true}""").reasoningTokens)
        assertNull(GenerationMetadata.encode(GenerationMetadata()))
    }
}
