package com.ylstack1.ylharness.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Drives [OpenCodeAgentAdapter] against a fake ACP agent speaking JSON-RPC 2.0
 * over stdio, exactly like `opencode acp` does (Agent Client Protocol v1,
 * verified against OpenCode's packages/opencode/src/acp implementation).
 */
class OpenCodeAgentAdapterTest {

    /**
     * Fake ACP agent: reads adapter requests from one pipe, writes responses,
     * notifications and agent→client requests to the other.
     */
    private class FakeAcpAgent(
        adapterToAgent: PipedInputStream,
        private val toAdapter: PipedOutputStream,
        private val onInitialize: (FakeAcpAgent, id: Int) -> Unit = { a, id ->
            a.respondResult(id, """{"protocolVersion":1,"agentCapabilities":{"loadSession":true},"authMethods":[]}""")
        },
        private val onNewSession: (FakeAcpAgent, id: Int) -> Unit = { a, id ->
            a.respondResult(id, """{"sessionId":"s1"}""")
        },
        private val onPrompt: (FakeAcpAgent, id: Int) -> Unit = { a, id ->
            a.respondResult(id, """{"stopReason":"end_turn"}""")
        },
    ) : Thread("fake-acp-agent") {
        private val reader = BufferedReader(InputStreamReader(adapterToAgent, Charsets.UTF_8))
        val promptStarted = CountDownLatch(1)
        private val requestAnswer = CompletableDeferred<JsonObject>()

        fun respondResult(id: Int, resultJson: String) {
            toAdapter.write(("""{"jsonrpc":"2.0","id":$id,"result":$resultJson}""" + "\n").toByteArray(Charsets.UTF_8))
            toAdapter.flush()
        }

        fun respondError(id: Int, code: Int, message: String) {
            toAdapter.write(("""{"jsonrpc":"2.0","id":$id,"error":{"code":$code,"message":"$message"}}""" + "\n").toByteArray(Charsets.UTF_8))
            toAdapter.flush()
        }

        fun notify(updateJson: String, sessionId: String = "s1") {
            toAdapter.write(("""{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"$sessionId","update":$updateJson}}""" + "\n").toByteArray(Charsets.UTF_8))
            toAdapter.flush()
        }

        fun notifyRaw(rawJson: String) {
            toAdapter.write((rawJson + "\n").toByteArray(Charsets.UTF_8))
            toAdapter.flush()
        }

        /** Blocks until the adapter answers an agent→client request, then returns it. */
        fun awaitRequestResponse(): JsonObject = runBlocking {
            withTimeout(20_000) { requestAnswer.await() }
        }

        override fun run() {
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val record = Json.parseToJsonElement(line).jsonObject
                    val method = record["method"]?.jsonPrimitive?.content
                    if (method == null) {
                        // An answer from the adapter to an agent→client request.
                        if (!requestAnswer.isCompleted) requestAnswer.complete(record)
                        continue
                    }
                    val id = record["id"]?.jsonPrimitive?.content?.toInt()
                    when (method) {
                        "initialize" -> id?.let { onInitialize(this, it) }
                        "session/new" -> id?.let { onNewSession(this, it) }
                        "session/prompt" -> {
                            promptStarted.countDown()
                            id?.let { onPrompt(this, it) }
                        }
                        "session/cancel" -> Unit // notification; script decides the stop reason
                        else -> id?.let { respondError(it, -32601, "Method not found: $method") }
                    }
                }
            } catch (_: Exception) {
                // streams closed; agent exits
            }
        }
    }

    private class Pipes {
        // adapter → agent
        val adapterToAgentIn = PipedInputStream(65536)
        val adapterToAgentOut = PipedOutputStream(adapterToAgentIn)
        // agent → adapter
        val agentToAdapterIn = PipedInputStream(65536)
        val agentToAdapterOut = PipedOutputStream(agentToAdapterIn)
    }

    @Test(timeout = 30_000)
    fun `initialize negotiates the protocol version`() {
        runBlocking {
            withTimeout(20_000) {
                val pipes = Pipes()
                val agent = FakeAcpAgent(pipes.adapterToAgentIn, pipes.agentToAdapterOut)
                agent.start()
                val adapter = OpenCodeAgentAdapter(pipes.agentToAdapterIn, pipes.adapterToAgentOut)
                adapter.start()
                val result = adapter.initialize()
                assertEquals(1, result["protocolVersion"]?.jsonPrimitive?.content?.toInt())
                adapter.close()
            }
        }
    }

    @Test(timeout = 30_000)
    fun `prompt collects text thinking and tool calls until the turn completes`() {
        runBlocking {
            withTimeout(20_000) {
                val onPrompt: (FakeAcpAgent, Int) -> Unit = { a, id ->
                    a.notify("""{"sessionUpdate":"agent_thought_chunk","messageId":"m1","content":{"type":"text","text":"thinking step"}}""")
                    a.notify("""{"sessionUpdate":"agent_message_chunk","messageId":"m2","content":{"type":"text","text":"Working"}}""")
                    a.notify("""{"sessionUpdate":"tool_call","toolCallId":"t1","title":"Read main.kt","kind":"read","status":"pending"}""")
                    a.notify("""{"sessionUpdate":"tool_call_update","toolCallId":"t1","status":"completed","content":[{"type":"content","content":{"type":"text","text":"file body"}}]}""")
                    a.notify("""{"sessionUpdate":"agent_message_chunk","messageId":"m2","content":{"type":"text","text":" done"}}""")
                    a.notify("""{"sessionUpdate":"unknown_future_variant","data":{}}""") // must be tolerated
                    a.respondResult(id, """{"stopReason":"end_turn"}""")
                }
                val pipes = Pipes()
                val agent = FakeAcpAgent(pipes.adapterToAgentIn, pipes.agentToAdapterOut, onPrompt = onPrompt)
                agent.start()
                val adapter = OpenCodeAgentAdapter(pipes.agentToAdapterIn, pipes.adapterToAgentOut)
                adapter.start()
                adapter.initialize()
                assertEquals("s1", adapter.newSession("/workspace"))
                val turn = adapter.prompt("fix the bug")
                assertEquals("end_turn", turn.stopReason)
                assertEquals("Working done", turn.text)
                assertEquals("thinking step", turn.thinking)
                assertEquals(1, turn.toolCalls.size)
                assertEquals("Read main.kt", turn.toolCalls.first().title)
                assertEquals("read", turn.toolCalls.first().kind)
                assertEquals("completed", turn.toolCalls.first().status)
                adapter.close()
            }
        }
    }

    @Test(timeout = 30_000)
    fun `agent file requests route through the bridge`() {
        runBlocking {
            withTimeout(20_000) {
                val onPrompt: (FakeAcpAgent, Int) -> Unit = { a, id ->
                    // Agent→client requests, serialized like OpenCode does.
                    a.notifyRaw("""{"jsonrpc":"2.0","id":900,"method":"fs/read_text_file","params":{"sessionId":"s1","path":"/workspace/notes.md","line":2,"limit":5}}""")
                    a.notifyRaw("""{"jsonrpc":"2.0","id":901,"method":"fs/write_text_file","params":{"sessionId":"s1","path":"/workspace/new.md","content":"written by agent"}}""")
                    a.respondResult(id, """{"stopReason":"end_turn"}""")
                }
                val pipes = Pipes()
                val agent = FakeAcpAgent(pipes.adapterToAgentIn, pipes.agentToAdapterOut, onPrompt = onPrompt)
                agent.start()
                val readPaths = mutableListOf<String>()
                val written = mutableListOf<Pair<String, String>>()
                val bridge = object : AcpBridge {
                    override suspend fun fsRead(path: String, line: Int?, limit: Int?): String {
                        readPaths.add("$path:$line:$limit")
                        return "bridge content"
                    }
                    override suspend fun fsWrite(path: String, content: String) {
                        written.add(path to content)
                    }
                    override suspend fun permission(request: AcpPermissionRequest): AcpPermissionOutcome =
                        AcpPermissionOutcome.Selected("once")
                }
                val adapter = OpenCodeAgentAdapter(pipes.agentToAdapterIn, pipes.adapterToAgentOut, bridge = bridge)
                adapter.start()
                adapter.initialize()
                adapter.newSession("/workspace")
                adapter.prompt("go")
                // The bridge answers arrive asynchronously; wait for both to land.
                val deadline = System.currentTimeMillis() + 10_000
                while ((readPaths.size < 1 || written.size < 1) && System.currentTimeMillis() < deadline) {
                    kotlinx.coroutines.delay(25)
                }
                // Wait until both agent→client requests were answered.
                assertEquals(listOf("/workspace/notes.md:2:5"), readPaths)
                assertEquals(listOf("/workspace/new.md" to "written by agent"), written)
                adapter.close()
            }
        }
    }

    @Test(timeout = 30_000)
    fun `permission request returns the bridge selection to the agent`() {
        runBlocking {
            withTimeout(20_000) {
                val onPrompt: (FakeAcpAgent, Int) -> Unit = { a, id ->
                    a.notify("""{"sessionUpdate":"tool_call","toolCallId":"t9","title":"Edit main.kt","kind":"edit","status":"pending"}""")
                    a.notifyRaw("""{"jsonrpc":"2.0","id":700,"method":"session/request_permission","params":{"sessionId":"s1","toolCall":{"toolCallId":"t9","title":"Edit main.kt"},"options":[{"optionId":"once","name":"Allow once","kind":"allow_once"},{"optionId":"always","name":"Always allow","kind":"allow_always"},{"optionId":"reject","name":"Reject","kind":"reject_once"}]}}""")
                    a.respondResult(id, """{"stopReason":"end_turn"}""")
                }
                val pipes = Pipes()
                val agent = FakeAcpAgent(pipes.adapterToAgentIn, pipes.agentToAdapterOut, onPrompt = onPrompt)
                agent.start()
                var askedTitle: String? = null
                val bridge = object : AcpBridge {
                    override suspend fun fsRead(path: String, line: Int?, limit: Int?): String = ""
                    override suspend fun fsWrite(path: String, content: String) {}
                    override suspend fun permission(request: AcpPermissionRequest): AcpPermissionOutcome {
                        askedTitle = request.title
                        return AcpPermissionOutcome.Selected("once")
                    }
                }
                val adapter = OpenCodeAgentAdapter(pipes.agentToAdapterIn, pipes.adapterToAgentOut, bridge = bridge)
                adapter.start()
                adapter.initialize()
                adapter.newSession("/workspace")
                adapter.prompt("edit the file")
                // The fake agent captured the adapter's answer to the permission request.
                val answer = agent.awaitRequestResponse()
                assertEquals("Edit main.kt", askedTitle)
                val outcome = answer["result"]?.jsonObject?.get("outcome")?.jsonObject
                assertEquals("selected", outcome?.get("outcome")?.jsonPrimitive?.content)
                assertEquals("once", outcome?.get("optionId")?.jsonPrimitive?.content)
                adapter.close()
            }
        }
    }

    @Test(timeout = 30_000)
    fun `cancel answers pending permission with cancelled outcome`() {
        runBlocking {
            withTimeout(20_000) {
                val permissionArrived = CountDownLatch(1)
                val onPrompt: (FakeAcpAgent, Int) -> Unit = { a, id ->
                    permissionArrived.countDown()
                    a.notifyRaw("""{"jsonrpc":"2.0","id":710,"method":"session/request_permission","params":{"sessionId":"s1","toolCall":{"toolCallId":"t10","title":"Run command"},"options":[{"optionId":"once","name":"Allow once","kind":"allow_once"}]}}""")
                    // After the cancelled outcome, OpenCode aborts and reports cancelled.
                    a.respondResult(id, """{"stopReason":"cancelled"}""")
                }
                val pipes = Pipes()
                val agent = FakeAcpAgent(pipes.adapterToAgentIn, pipes.agentToAdapterOut, onPrompt = onPrompt)
                agent.start()
                var requested = false
                val bridge = object : AcpBridge {
                    override suspend fun fsRead(path: String, line: Int?, limit: Int?): String = ""
                    override suspend fun fsWrite(path: String, content: String) {}
                    override suspend fun permission(request: AcpPermissionRequest): AcpPermissionOutcome {
                        requested = true
                        // Simulate the user cancelling while the dialog is open.
                        return AcpPermissionOutcome.Cancelled
                    }
                }
                val adapter = OpenCodeAgentAdapter(pipes.agentToAdapterIn, pipes.adapterToAgentOut, bridge = bridge)
                adapter.start()
                adapter.initialize()
                adapter.newSession("/workspace")
                val promptDeferred = CompletableDeferred<AcpTurn>()
                launch(Dispatchers.IO) {
                    try { promptDeferred.complete(adapter.prompt("run it")) }
                    catch (e: Exception) { promptDeferred.completeExceptionally(e) }
                }
                assertTrue(permissionArrived.await(10, TimeUnit.SECONDS))
                // Give the permission request time to reach the bridge, then cancel the turn.
                while (!requested) kotlinx.coroutines.delay(25)
                adapter.cancel()
                val turn = promptDeferred.await()
                assertEquals("cancelled", turn.stopReason)
                val answer = agent.awaitRequestResponse()
                val outcome = answer["result"]?.jsonObject?.get("outcome")?.jsonObject
                assertEquals("cancelled", outcome?.get("outcome")?.jsonPrimitive?.content)
                adapter.close()
            }
        }
    }

    @Test(timeout = 30_000)
    fun `agent error responses surface as AcpAgentException`() {
        runBlocking {
            withTimeout(20_000) {
                val pipes = Pipes()
                val agent = FakeAcpAgent(pipes.adapterToAgentIn, pipes.agentToAdapterOut,
                    onNewSession = { a, id -> a.respondError(id, -32000, "auth_required") })
                agent.start()
                val adapter = OpenCodeAgentAdapter(pipes.agentToAdapterIn, pipes.adapterToAgentOut)
                adapter.start()
                adapter.initialize()
                try {
                    adapter.newSession("/workspace")
                    fail("Expected AcpAgentException")
                } catch (e: AcpAgentException) {
                    assertEquals(-32000, e.code)
                    assertEquals("auth_required", e.message)
                }
                adapter.close()
            }
        }
    }
}
