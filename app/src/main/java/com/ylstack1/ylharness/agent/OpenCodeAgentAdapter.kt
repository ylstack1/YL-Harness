package com.ylstack1.ylharness.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Client side of the Agent Client Protocol (ACP) v1 over newline-delimited
 * JSON-RPC 2.0 on the agent's stdio. Used to drive `opencode acp` as a
 * subprocess, the same way Zed and JetBrains IDEs do.
 *
 * Threading model: [start] launches one reader thread that parses incoming
 * lines and dispatches them — responses complete the deferred registered for
 * their id, `session/update` notifications feed the turn collectors, and
 * agent→client requests (fs methods, session/request_permission) are answered
 * asynchronously through the [AcpBridge]. Requests the client does not
 * understand are answered with -32601 per the ACP error spec.
 */
class OpenCodeAgentAdapter(
    /** The agent's stdout stream (we read what the agent writes). */
    private val agentOutput: InputStream,
    /** The agent's stdin stream (we write requests to the agent). */
    private val agentInput: OutputStream,
    private val bridge: AcpBridge? = null,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private val writeLock = Any()
    private val nextRequestId = AtomicInteger(1)
    private val bufferLock = Any()
    private val textBuffer = StringBuilder()
    private val thinkingBuffer = StringBuilder()
    private val toolCalls = LinkedHashMap<String, AcpToolCall>()

    @Volatile private var running = false

    /** Session id returned by [newSession]; null before that. */
    @Volatile
    var sessionId: String? = null
        private set

    fun start() {
        if (running) return
        running = true
        thread(name = "acp-reader", isDaemon = true) { readLoop() }
    }

    private fun readLoop() {
        try {
            val reader = BufferedReader(InputStreamReader(agentOutput, Charsets.UTF_8))
            while (running) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()?.let(::dispatch)
            }
        } catch (_: Exception) {
            // Stream closed or malformed input: fail everything outstanding below.
        } finally {
            running = false
            failPending("ACP connection closed")
        }
    }

    private fun dispatch(record: JsonObject) {
        val method = record["method"]?.jsonPrimitive?.contentOrNull
        if (method == null) {
            // A response to one of our requests.
            val id = record["id"]?.jsonPrimitive?.intOrNull ?: return
            pending.remove(id)?.let { deferred ->
                val error = record["error"]?.jsonObject
                if (error != null) {
                    deferred.completeExceptionally(
                        AcpAgentException(
                            error["code"]?.jsonPrimitive?.intOrNull ?: -32603,
                            error["message"]?.jsonPrimitive?.contentOrNull ?: "agent error",
                        ),
                    )
                } else {
                    deferred.complete(record["result"]?.jsonObject ?: JsonObject(emptyMap()))
                }
            }
            return
        }
        when (method) {
            "session/update" ->
                record["params"]?.jsonObject?.get("update")?.jsonObject?.let(::handleUpdate)
            "fs/read_text_file" -> scope.launch {
                answerAgentRequest(record) { params ->
                    val b = bridge ?: throw AcpAgentException(-32601, "fs capability not available")
                    buildJsonObject {
                        put(
                            "content",
                            b.fsRead(
                                params.string("path") ?: throw AcpAgentException(-32602, "fs/read_text_file requires path"),
                                params["line"]?.jsonPrimitive?.intOrNull,
                                params["limit"]?.jsonPrimitive?.intOrNull,
                            ),
                        )
                    }
                }
            }
            "fs/write_text_file" -> scope.launch {
                answerAgentRequest(record) { params ->
                    val b = bridge ?: throw AcpAgentException(-32601, "fs capability not available")
                    b.fsWrite(
                        params.string("path") ?: throw AcpAgentException(-32602, "fs/write_text_file requires path"),
                        params.string("content") ?: throw AcpAgentException(-32602, "fs/write_text_file requires content"),
                    )
                    JsonObject(emptyMap())
                }
            }
            "session/request_permission" -> scope.launch {
                answerAgentRequest(record) { params ->
                    val request = parsePermissionRequest(params)
                    val outcome = bridge?.permission(request) ?: AcpPermissionOutcome.Cancelled
                    buildJsonObject {
                        putJsonObject("outcome") {
                            when (outcome) {
                                is AcpPermissionOutcome.Selected -> {
                                    put("outcome", "selected")
                                    put("optionId", outcome.optionId)
                                }
                                AcpPermissionOutcome.Cancelled -> put("outcome", "cancelled")
                            }
                        }
                    }
                }
            }
            else -> record["id"]?.jsonPrimitive?.intOrNull?.let { id ->
                sendError(id, -32601, "Method not found: $method")
            }
        }
    }

    private suspend fun answerAgentRequest(
        record: JsonObject,
        handler: suspend (JsonObject) -> JsonObject,
    ) {
        val id = record["id"]?.jsonPrimitive?.intOrNull ?: return
        try {
            val params = record["params"]?.jsonObject ?: JsonObject(emptyMap())
            sendResult(id, handler(params))
        } catch (e: AcpAgentException) {
            sendError(id, e.code, e.message ?: "error")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            sendError(id, -32603, e.message ?: "internal error")
        }
    }

    private fun parsePermissionRequest(params: JsonObject): AcpPermissionRequest {
        val toolCall = params["toolCall"]?.jsonObject
        return AcpPermissionRequest(
            toolCallId = toolCall.string("toolCallId"),
            title = toolCall.string("title"),
            kind = toolCall.string("kind"),
            locations = toolCall?.get("locations")?.jsonArray
                ?.mapNotNull { it.jsonObject["path"]?.jsonPrimitive?.contentOrNull }
                .orEmpty(),
            rawInput = toolCall?.get("rawInput")?.jsonObject,
            options = params["options"]?.jsonArray?.mapNotNull { option ->
                val obj = option.jsonObject
                AcpPermissionOption(
                    optionId = obj.string("optionId") ?: return@mapNotNull null,
                    name = obj.string("name") ?: return@mapNotNull null,
                    kind = obj.string("kind") ?: return@mapNotNull null,
                )
            }.orEmpty(),
        )
    }

    private fun handleUpdate(update: JsonObject) {
        when (update["sessionUpdate"]?.jsonPrimitive?.contentOrNull) {
            "agent_message_chunk" -> appendChunk(textBuffer, update)
            "agent_thought_chunk" -> appendChunk(thinkingBuffer, update)
            "tool_call" -> {
                val id = update.string("toolCallId") ?: return
                synchronized(bufferLock) {
                    toolCalls[id] = AcpToolCall(
                        id,
                        update.string("title"),
                        update.string("kind"),
                        update.string("status") ?: "pending",
                    )
                }
            }
            "tool_call_update" -> {
                val id = update.string("toolCallId") ?: return
                synchronized(bufferLock) {
                    val existing = toolCalls[id]
                    toolCalls[id] = AcpToolCall(
                        id,
                        update.string("title") ?: existing?.title,
                        update.string("kind") ?: existing?.kind,
                        update.string("status") ?: existing?.status,
                    )
                }
            }
            // plan, usage_update, available_commands_update, user_message_chunk,
            // current_mode_update, config_option_update and future variants are
            // tolerated but not consumed yet.
            else -> Unit
        }
    }

    private fun appendChunk(buffer: StringBuilder, update: JsonObject) {
        val text = update["content"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: return
        synchronized(bufferLock) { buffer.append(text) }
    }

    suspend fun initialize(): JsonObject {
        val result = request(
            "initialize",
            buildJsonObject {
                put("protocolVersion", 1)
                putJsonObject("clientCapabilities") {
                    putJsonObject("fs") {
                        put("readTextFile", true)
                        put("writeTextFile", true)
                    }
                }
                putJsonObject("clientInfo") {
                    put("name", "yl-harness")
                    put("title", "YL Harness")
                    put("version", "1.0.0")
                }
            },
        )
        val version = result["protocolVersion"]?.jsonPrimitive?.intOrNull
        if (version != null && version != 1) {
            throw AcpAgentException(-32600, "Unsupported ACP protocol version: $version")
        }
        return result
    }

    suspend fun newSession(cwd: String): String {
        val result = request(
            "session/new",
            buildJsonObject {
                put("cwd", cwd)
                putJsonArray("mcpServers") { /* none: the agent's own config applies */ }
            },
        )
        val id = result.string("sessionId")
            ?: throw AcpAgentException(-32603, "session/new returned no sessionId")
        sessionId = id
        return id
    }

    /** Send one text prompt and wait for the turn to complete. */
    suspend fun prompt(text: String): AcpTurn {
        val session = sessionId ?: throw IllegalStateException("session not created; call newSession first")
        synchronized(bufferLock) {
            textBuffer.setLength(0)
            thinkingBuffer.setLength(0)
            toolCalls.clear()
        }
        val result = request(
            "session/prompt",
            buildJsonObject {
                put("sessionId", session)
                putJsonArray("prompt") {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    })
                }
            },
        )
        val stopReason = result["stopReason"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        synchronized(bufferLock) {
            return AcpTurn(stopReason, textBuffer.toString(), thinkingBuffer.toString(), toolCalls.values.toList())
        }
    }

    /** Cancel the ongoing turn (notification, no id). Pending permission
     *  requests are answered by the bridge with [AcpPermissionOutcome.Cancelled]. */
    fun cancel() {
        val session = sessionId ?: return
        write(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "session/cancel")
                putJsonObject("params") { put("sessionId", session) }
            },
        )
    }

    fun close() {
        running = false
        scope.cancel()
        runCatching { agentOutput.close() }
        runCatching { agentInput.close() }
        failPending("adapter closed")
    }

    private suspend fun request(method: String, params: JsonObject): JsonObject {
        val id = nextRequestId.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        write(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            },
        )
        try {
            return deferred.await()
        } finally {
            pending.remove(id)
        }
    }

    private fun write(record: JsonObject) {
        synchronized(writeLock) {
            agentInput.write((record.toString() + "\n").toByteArray(Charsets.UTF_8))
            agentInput.flush()
        }
    }

    private fun sendResult(id: Int, result: JsonObject) = write(
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        },
    )

    private fun sendError(id: Int, code: Int, message: String) = write(
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        },
    )

    private fun failPending(reason: String) {
        for ((id, deferred) in pending) {
            if (pending.remove(id, deferred)) {
                deferred.completeExceptionally(AcpAgentException(-32603, reason))
            }
        }
    }
}

private fun JsonObject?.string(key: String): String? =
    this?.get(key)?.jsonPrimitive?.contentOrNull
