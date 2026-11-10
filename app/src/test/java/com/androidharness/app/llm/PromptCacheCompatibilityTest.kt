package com.androidharness.app.llm

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptCacheCompatibilityTest {
    private val protocols = listOf(ProviderType.OPENAI_COMPAT, ProviderType.OPENAI_RESPONSES)

    @Test fun `strict gateways accept requests with a session cache key`() = runBlocking {
        for (type in protocols) {
            for (baseUrl in listOf(
                "https://api.unorouter.com/v1",
                "https://integrate.api.nvidia.com/v1",
                "https://gateway.example.com/v1",
                "http://localhost:1234/v1",
                "http://192.168.1.2:8080/v1",
            )) {
                request(baseUrl, type, "chat-session", rejectCacheFields = true).also { body ->
                    assertFalse(baseUrl, body.containsKey("prompt_cache_key"))
                    assertFalse(baseUrl, body.containsKey("user"))
                }
            }
        }
    }

    @Test fun `documented endpoints retain bounded session cache hints`() = runBlocking {
        for (type in protocols) {
            for (baseUrl in listOf("https://api.openai.com/v1", "https://openrouter.ai/api/v1")) {
                for (key in listOf("chat-session", "session-".repeat(20))) {
                    val body = request(baseUrl, type, key)
                    assertEquals(key.take(64), body["prompt_cache_key"]?.jsonPrimitive?.content)
                    assertEquals("pc_${key.take(64)}", body["user"]?.jsonPrimitive?.content)
                }
            }
        }
    }

    @Test fun `host names in paths credentials or suffixes do not enable cache hints`() = runBlocking {
        for (type in protocols) {
            for (baseUrl in listOf(
                "https://api.openai.com.example.com/v1",
                "https://openrouter.ai.example.com/api/v1",
                "https://gateway.example.com/api.openai.com/v1",
                "https://openrouter.ai@gateway.example.com/v1",
            )) {
                request(baseUrl, type, "chat-session", rejectCacheFields = true)
            }
        }
    }

    @Test fun `requests without a session key omit cache hints`() = runBlocking {
        for (type in protocols) {
            for (baseUrl in listOf("https://api.openai.com/v1", "https://openrouter.ai/api/v1")) {
                request(baseUrl, type, null, rejectCacheFields = true)
            }
        }
    }

    private suspend fun request(
        baseUrl: String,
        type: ProviderType,
        cacheKey: String?,
        rejectCacheFields: Boolean = false,
    ): JsonObject {
        val json = ProviderFactory.json
        var sentBody: JsonObject? = null
        var attempts = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            attempts++
            val body = json.parseToJsonElement(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()).jsonObject
            sentBody = body
            val rejected = rejectCacheFields && ("prompt_cache_key" in body || "user" in body)
            val payload = if (rejected) {
                """{"error":{"message":"Validation: Unsupported parameter(s): `prompt_cache_key`","type":"Bad Request","code":400}}"""
            } else if (type == ProviderType.OPENAI_COMPAT) {
                """data: {"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":100,"completion_tokens":2,"prompt_tokens_details":{"cached_tokens":80}}}""" + "\n\ndata: [DONE]\n\n"
            } else {
                """data: {"type":"response.output_text.delta","delta":"ok"}""" + "\n\n" +
                    """data: {"type":"response.completed","response":{"usage":{"input_tokens":100,"output_tokens":2,"input_tokens_details":{"cached_tokens":80}}}}""" + "\n\n"
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (rejected) 400 else 200).message(if (rejected) "Bad Request" else "OK")
                .body(payload.toResponseBody((if (rejected) "application/json" else "text/event-stream").toMediaType()))
                .build()
        }.build()
        val provider: LlmProvider = when (type) {
            ProviderType.OPENAI_COMPAT -> OpenAiCompatProvider(client, json)
            ProviderType.OPENAI_RESPONSES -> OpenAiResponsesProvider(client, json)
            else -> error("Unexpected test protocol")
        }
        val events = provider.streamChat(
            ProviderConfig("test", "Test", type, baseUrl, "test-model"), "fixture-key", "Help with coding.",
            listOf(ChatMessage(Role.USER, "Hello")), emptyList(), RequestOptions(cacheKey = cacheKey),
        ).toList()
        assertEquals("$type $baseUrl", 1, attempts)
        assertEquals(listOf(StreamEvent.TextDelta("ok")), events.filterIsInstance<StreamEvent.TextDelta>())
        assertTrue(events.any { it is StreamEvent.Done })
        assertFalse(events.any { it is StreamEvent.Failure })
        // Omitting an optional routing hint must not disable provider-reported cache telemetry.
        assertEquals(StreamEvent.Usage(100, 2, cachedInputTokens = 80, cacheReported = true),
            events.filterIsInstance<StreamEvent.Usage>().single())
        return checkNotNull(sentBody)
    }
}
