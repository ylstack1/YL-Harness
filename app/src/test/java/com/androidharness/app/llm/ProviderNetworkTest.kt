package com.androidharness.app.llm

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test
import java.net.Proxy
import java.util.concurrent.TimeUnit

class ProviderNetworkTest {
    @Test fun globalSettingsProviderOverridesAndDirectConnectionsChooseTheRightRoute() {
        val old = ProviderNetwork.settings
        try {
            val global = ProviderProxy(ProviderProxyMode.CUSTOM, host = "127.0.0.1", port = 3128)
            val custom = global.copy(port = 8888)
            ProviderNetwork.settings = { id -> when (id) { null -> global; "custom" -> custom; "direct" -> ProviderProxy.DIRECT; else -> ProviderProxy() } }
            assertEquals(global, ProviderNetwork.selected("inherit"))
            assertEquals(custom, ProviderNetwork.selected("custom"))
            assertEquals(Proxy.NO_PROXY, ProviderNetwork.client(OkHttpClient(), "direct").proxy)
            val base = OkHttpClient()
            assertSame(base, ProviderNetwork.configure(base, ProviderProxy()))
            assertEquals(Proxy.Type.SOCKS, ProviderNetwork.configure(base, custom.copy(type = ProviderProxyType.SOCKS5)).proxy!!.type())
        } finally { ProviderNetwork.settings = old }
    }

    @Test fun httpProxyReceivesTheRequestInsteadOfResolvingTheOriginDirectly() {
        TestServer().use { proxy ->
            proxy.enqueue(TestResponse().setBody("via proxy")); proxy.start()
            val client = ProviderNetwork.configure(OkHttpClient(), ProviderProxy(ProviderProxyMode.CUSTOM, host = proxy.hostName, port = proxy.port))
            client.newCall(Request.Builder().url("http://provider.invalid/v1/models").build()).execute().use { assertEquals("via proxy", it.body!!.string()) }
            val request = proxy.takeRequest(3, TimeUnit.SECONDS)!!
            assertTrue(request.requestLine.startsWith("GET http://provider.invalid/v1/models"))
        }
    }

    @Test fun httpProxyLoginRespondsToAChallengeAndStopsAfterARejectedCredential() {
        TestServer().use { proxy ->
            repeat(2) { proxy.enqueue(TestResponse().setResponseCode(407).setHeader("Proxy-Authenticate", "Basic realm=fixture")) }
            proxy.start()
            val client = ProviderNetwork.configure(OkHttpClient(), ProviderProxy(ProviderProxyMode.CUSTOM, host = proxy.hostName, port = proxy.port, username = "proxy-user", password = "proxy-password"))
            client.newCall(Request.Builder().url("http://provider.invalid/v1/models").build()).execute().use { assertEquals(407, it.code) }
            assertNull(proxy.takeRequest(3, TimeUnit.SECONDS)!!.getHeader("Proxy-Authorization"))
            assertEquals(Credentials.basic("proxy-user", "proxy-password"), proxy.takeRequest(3, TimeUnit.SECONDS)!!.getHeader("Proxy-Authorization"))
            assertEquals(2, proxy.requestCount)
        }
    }

    @Test fun aBrokenProxyDoesNotLeakTheRequestToTheDirectOrigin() {
        TestServer().use { origin ->
            origin.start()
            val deadPort = java.net.ServerSocket(0).use { it.localPort }
            val client = ProviderNetwork.configure(OkHttpClient.Builder().connectTimeout(150, TimeUnit.MILLISECONDS).callTimeout(1, TimeUnit.SECONDS).build(),
                ProviderProxy(ProviderProxyMode.CUSTOM, host = "127.0.0.1", port = deadPort))
            assertThrows(java.io.IOException::class.java) { client.newCall(Request.Builder().url(origin.url("/models")).build()).execute().close() }
            assertEquals(0, origin.requestCount)
        }
    }

    @Test fun everyApiProtocolUsesTheConfiguredProxyAndRotatesRejectedKeys() = kotlinx.coroutines.runBlocking {
        val previousNetwork = ProviderNetwork.settings; val previousPools = RotatingKeyProvider.pools
        try {
            for (type in ProviderType.entries) TestServer().use { proxy ->
                proxy.start()
                proxy.enqueue(TestResponse().setResponseCode(401).setBody("{\"error\":{\"message\":\"Rejected\",\"type\":\"invalid_api_key\"}}"))
                val success = when (type) {
                    ProviderType.OPENAI_COMPAT -> "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                    ProviderType.OPENAI_RESPONSES -> "data: {\"type\":\"response.output_text.delta\",\"delta\":\"OK\"}\n\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n"
                    ProviderType.ANTHROPIC -> "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"OK\"}}\n\ndata: {\"type\":\"message_stop\"}\n\n"
                    ProviderType.GEMINI -> "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"OK\"}]},\"finishReason\":\"STOP\"}]}\n\n"
                }
                proxy.enqueue(TestResponse().setHeader("Content-Type", "text/event-stream").setBody(success))
                ProviderNetwork.settings = { ProviderProxy(ProviderProxyMode.CUSTOM, host = proxy.hostName, port = proxy.port) }
                RotatingKeyProvider.pools = { ProviderKeyPool(listOf(ProviderApiKey("a", value = "first-key"), ProviderApiKey("b", value = "second-key")), rotate = false) }
                val config = ProviderConfig("proxy-$type", "Fixture", type, "http://provider.invalid/v1", "fixture-model")
                val events = kotlinx.coroutines.withTimeout(8000) {
                    ProviderFactory.create(config).streamChat(config, "stale-key", "", emptyList(), emptyList(), RequestOptions()).toList()
                }
                fun flatten(event: StreamEvent): List<StreamEvent> = if (event is StreamEvent.Batch) event.events.flatMap(::flatten) else listOf(event)
                val flat = events.flatMap(::flatten)
                assertEquals(type.name, "OK", flat.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.text })
                val first = proxy.takeRequest(3, TimeUnit.SECONDS)!!; val second = proxy.takeRequest(3, TimeUnit.SECONDS)!!
                val header = when (type) { ProviderType.ANTHROPIC -> "x-api-key"; ProviderType.GEMINI -> "x-goog-api-key"; else -> "Authorization" }
                val prefix = if (header == "Authorization") "Bearer " else ""
                assertEquals(type.name, prefix + "first-key", first.getHeader(header))
                assertEquals(type.name, prefix + "second-key", second.getHeader(header))
                assertTrue(flat.none { it is StreamEvent.Failure })
            }
        } finally { ProviderNetwork.settings = previousNetwork; RotatingKeyProvider.pools = previousPools }
    }
    @Test fun httpsTunnelKeepsCredentialsSeparateAndStopsWhenProxyLoginIsRejected() {
        TestServer().use { proxy ->
            proxy.enqueue(TestResponse().setResponseCode(407).setHeader("Proxy-Authenticate", "Basic realm=fixture"))
            proxy.start()
            val client = ProviderNetwork.configure(OkHttpClient.Builder().retryOnConnectionFailure(false).callTimeout(3, TimeUnit.SECONDS).build(),
                ProviderProxy(ProviderProxyMode.CUSTOM, host = proxy.hostName, port = proxy.port, username = "user", password = "password"))
            assertThrows(java.io.IOException::class.java) {
                client.newCall(Request.Builder().url("https://provider.invalid/v1/models").header("Authorization", "Bearer provider-key").build()).execute().close()
            }
            val first = requireNotNull(proxy.takeRequest(3, TimeUnit.SECONDS))
            assertEquals("CONNECT provider.invalid:443 HTTP/1.1", first.requestLine)
            assertNull(first.getHeader("Authorization"))
            // OkHttp asks the authenticator before opening an HTTPS tunnel.
            assertEquals(Credentials.basic("user", "password"), first.getHeader("Proxy-Authorization"))
            assertNull(proxy.takeRequest(100, TimeUnit.MILLISECONDS))
            assertEquals(1, proxy.requestCount)
        }
    }

    @Test fun socks5ResolvesTheProviderAtTheProxyAndCarriesTheRequest() {
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5000
            val target = java.util.concurrent.atomic.AtomicReference<String>()
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
            val worker = kotlin.concurrent.thread(isDaemon = true) {
                try { server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = java.io.DataInputStream(socket.getInputStream())
                    val output = socket.getOutputStream()
                    assertEquals(5, input.readUnsignedByte())
                    val methods = ByteArray(input.readUnsignedByte()); input.readFully(methods)
                    assertTrue(methods.contains(0.toByte()))
                    output.write(byteArrayOf(5, 0)); output.flush()
                    assertEquals(5, input.readUnsignedByte()); assertEquals(1, input.readUnsignedByte())
                    input.readUnsignedByte(); assertEquals(3, input.readUnsignedByte())
                    val name = ByteArray(input.readUnsignedByte()); input.readFully(name)
                    target.set(String(name, Charsets.US_ASCII) + ":" + input.readUnsignedShort())
                    output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0)); output.flush()
                    val reader = input.bufferedReader()
                    assertEquals("GET /models HTTP/1.1", reader.readLine())
                    while (!reader.readLine().isNullOrEmpty()) { }
                    output.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK".toByteArray()); output.flush()
                } } catch (error: Throwable) { failure.set(error) }
            }
            val base = OkHttpClient.Builder().dns(object : Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> = throw AssertionError("Origin DNS must be resolved through SOCKS")
            }).callTimeout(5, TimeUnit.SECONDS).build()
            val client = ProviderNetwork.configure(base, ProviderProxy(ProviderProxyMode.CUSTOM, ProviderProxyType.SOCKS5, "127.0.0.1", server.localPort))
            client.newCall(Request.Builder().url("http://provider.invalid/models").build()).execute().use { assertEquals("OK", it.body!!.string()) }
            worker.join(5000)
            failure.get()?.let { throw AssertionError("SOCKS fixture failed", it) }
            assertEquals("provider.invalid:80", target.get())
        }
    }

    private class TestResponse {
        var code = 200
        var body = ""
        val headers = linkedMapOf<String, String>()
        fun setBody(value: String) = apply { body = value }
        fun setResponseCode(value: Int) = apply { code = value }
        fun setHeader(name: String, value: String) = apply { headers[name] = value }
    }
    private data class Recorded(val requestLine: String, val headers: Map<String, String>) {
        fun getHeader(name: String) = headers[name.lowercase()]
    }
    private class TestServer : java.io.Closeable {
        private val server = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        private val responses = java.util.concurrent.LinkedBlockingQueue<TestResponse>()
        private val requests = java.util.concurrent.LinkedBlockingQueue<Recorded>()
        private val count = java.util.concurrent.atomic.AtomicInteger()
        private val sockets = java.util.concurrent.CopyOnWriteArrayList<java.net.Socket>()
        private var worker: Thread? = null
        val requestCount get() = count.get()
        val hostName = "127.0.0.1"
        val port get() = server.localPort
        fun start() {
            worker = kotlin.concurrent.thread(isDaemon = true) {
                try {
                    while (!server.isClosed) {
                        val socket = server.accept(); sockets += socket
                        kotlin.concurrent.thread(isDaemon = true) {
                            socket.use { connection ->
                                connection.soTimeout = 5000
                                val input = connection.getInputStream().buffered()
                                fun line(): String {
                                    val out = java.io.ByteArrayOutputStream()
                                    while (out.size() < 65536) {
                                        val next = input.read(); if (next < 0 || next == 10) break
                                        if (next != 13) out.write(next)
                                    }
                                    return out.toString("UTF-8")
                                }
                                val first = line(); val headers = linkedMapOf<String, String>()
                                while (true) {
                                    val header = line(); if (header.isBlank()) break
                                    headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                                }
                                var remaining = headers["content-length"]?.toIntOrNull() ?: 0
                                val buffer = ByteArray(4096)
                                while (remaining > 0) { val n = input.read(buffer, 0, minOf(remaining, buffer.size)); if (n < 0) break; remaining -= n }
                                requests.add(Recorded(first, headers)); count.incrementAndGet()
                                val response = responses.poll() ?: TestResponse().setResponseCode(500)
                                val bytes = response.body.toByteArray()
                                val head = "HTTP/1.1 ${response.code} Fixture\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n" +
                                    response.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n"
                                connection.getOutputStream().write(head.toByteArray() + bytes)
                                connection.getOutputStream().flush()
                            }
                            sockets.remove(socket)
                        }
                    }
                } catch (_: java.net.SocketException) { }
            }
        }
        fun enqueue(response: TestResponse) { responses.add(response) }
        fun takeRequest(timeout: Long, unit: TimeUnit): Recorded? = requests.poll(timeout, unit)
        fun url(path: String) = "http://$hostName:$port$path".toHttpUrl()
        override fun close() { server.close(); sockets.forEach { runCatching { it.close() } }; worker?.join(500) }
    }

}
