package com.androidharness.app.llm

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class ProviderHeadersTest {
    private val headers = listOf(ProviderHeader("X-Client-Type", "AndroidHarness"), ProviderHeader("X-Disabled", "secret", false))
    private fun config(type: ProviderType = ProviderType.OPENAI_COMPAT) = ProviderConfig(
        "custom", "Custom", type, "https://gateway.example/v1", "test-model", headers = headers)

    @Test fun `every wire sends only enabled headers for streaming and model discovery`() = runBlocking {
        for (type in ProviderType.entries) {
            val requests = mutableListOf<Request>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                requests += chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("data: [DONE]\n\n".toResponseBody("text/event-stream".toMediaType())).build()
            }.build()
            val provider: LlmProvider = when (type) {
                ProviderType.OPENAI_COMPAT -> OpenAiCompatProvider(client, ProviderFactory.json)
                ProviderType.OPENAI_RESPONSES -> OpenAiResponsesProvider(client, ProviderFactory.json)
                ProviderType.ANTHROPIC -> AnthropicProvider(client, ProviderFactory.json)
                ProviderType.GEMINI -> GeminiProvider(client, ProviderFactory.json)
            }
            provider.streamChat(config(type), "fixture-key", "Help.", listOf(ChatMessage(Role.USER, "Hello")),
                emptyList(), RequestOptions()).toList()
            requests += ModelCatalog.buildRequest(config(type), "fixture-key")
            assertEquals(2, requests.size)
            requests.forEach {
                assertEquals("AndroidHarness", it.header("X-Client-Type"))
                assertNull(it.header("X-Disabled"))
                val authHeader = when (type) {
                    ProviderType.ANTHROPIC -> "x-api-key"
                    ProviderType.GEMINI -> "x-goog-api-key"
                    else -> "Authorization"
                }
                assertTrue(it.header(authHeader).orEmpty().contains("fixture-key"))
            }
        }
    }

    @Test fun `optional provider headers override defaults without duplicating names`() {
        val config = config(ProviderType.ANTHROPIC).copy(headers = listOf(ProviderHeader("anthropic-version", "2024-01-01")))
        val request = ModelCatalog.buildRequest(config, "fixture-key")
        assertEquals(listOf("2024-01-01"), request.headers.values("anthropic-version"))
    }

    @Test fun `invalid header input is rejected before a request is built`() {
        val invalid = listOf(
            listOf(ProviderHeader("", "v")), listOf(ProviderHeader("bad:name", "v")),
            listOf(ProviderHeader("x-test", "bad\r\nInjected: value")), listOf(ProviderHeader("x-test", "bad\u0000value")),
            listOf(ProviderHeader("x-test", "non-ASCII \u2603")),
            listOf(ProviderHeader("X-Duplicate", "a"), ProviderHeader("x-duplicate", "b", false)),
            listOf(ProviderHeader("x-long", "a".repeat(8193))),
            List(21) { ProviderHeader("x-$it", "v") },
        ) + listOf("Authorization", "HOST", "x-api-key", "x-goog-api-key", "Content-Type", "Transfer-Encoding").map {
            listOf(ProviderHeader(it, "unsafe"))
        }
        invalid.forEach { values ->
            assertNotNull(ProviderHeaders.error(values))
            assertThrows(IllegalArgumentException::class.java) {
                ModelCatalog.buildRequest(config().copy(headers = values), "fixture-key")
            }
        }
        assertEquals("X-Client-Type", ProviderHeaders.normalized(listOf(ProviderHeader(" X-Client-Type ", "v"))).single().name)
    }

    @Test fun `credentials stay out of provider and task serialization and diagnostic strings`() {
        val json = Json { encodeDefaults = true }
        val config = config().copy(headers = listOf(ProviderHeader("X-Secret", "unique-header-secret")))
        val encoded = json.encodeToString(ProviderConfig.serializer(), config)
        assertFalse(encoded.contains("headers"))
        assertFalse(config.toString().contains("unique-header-secret"))
        assertTrue(json.decodeFromString(ProviderConfig.serializer(), encoded).headers.isEmpty())
        assertTrue(json.decodeFromString<ProviderConfig>("""{"id":"old","name":"Old","type":"OPENAI_COMPAT","baseUrl":"https://example.com","model":"m"}""").headers.isEmpty())
    }

    @Test fun `managed connections cannot acquire custom headers`() {
        for (id in listOf(HarnessProvider.ID, "chatgpt:account", "local-model:model")) {
            val request = ProviderHeaders.apply(Request.Builder().url("https://example.com"), config().copy(id = id)).build()
            assertNull(request.header("X-Client-Type"))
        }
    }

    @Test fun `model discovery never forwards custom headers through a redirect`() = runBlocking {
        ServerSocket(0).use { server ->
            server.soTimeout = 1500
            val seen = CopyOnWriteArrayList<String>()
            val worker = thread(isDaemon = true) {
                try {
                    repeat(2) {
                        server.accept().use { socket ->
                            socket.soTimeout = 1500
                            val input = socket.getInputStream().bufferedReader()
                            val request = buildString { while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break; appendLine(line) } }
                            seen += request
                            val response = if (seen.size == 1) "HTTP/1.1 302 Found\r\nLocation: http://localhost:${server.localPort}/elsewhere\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                else "HTTP/1.1 200 OK\r\nContent-Length: 11\r\nConnection: close\r\n\r\n{\"data\":[]}"
                            socket.getOutputStream().write(response.toByteArray())
                        }
                    }
                } catch (_: SocketTimeoutException) { }
            }
            val result = ModelCatalog.listModels(config().copy(baseUrl = "http://127.0.0.1:${server.localPort}"), "fixture-key")
            worker.join(4000)
            assertTrue(result is ModelCatalog.Result.Failed)
            assertEquals(1, seen.size)
            assertTrue(seen.single().contains("X-Client-Type: AndroidHarness"))
        }
    }
}
