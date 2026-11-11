package com.ylstack1.ylharness.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * JSON-RPC protocol adapter for Pi coding agent.
 * Communicates with Pi subprocess over stdin/stdout using JSONL records.
 *
 * Protocol (from pi.dev/docs/latest/rpc):
 * - stdin: Commands ({"id": "...", "type": "prompt", "message": "..."})
 * - stdout: Responses ({"id": "...", "type": "response", ...})
 * - stdout: Session events ({"type": "message_update", ...})
 * - Every command with an id gets a matching response with the same id
 */
class PiAgentAdapter(
    private val process: Process,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val stdin: OutputStream = process.outputStream
    private val stdout: BufferedReader = BufferedReader(InputStreamReader(process.inputStream))

    /** Pending command requests awaiting response, keyed by command id. */
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()

    /** Listeners for streaming session events (no matching command id). */
    private val listeners = CopyOnWriteArrayList<(JsonObject) -> Unit>()

    private val lock = Any()

    init {
        // Reader thread: parse JSONL responses and events from stdout
        thread(name = "pi-rpc-reader", isDaemon = true) {
            var line: String? = null
            try {
                while (process.isAlive) {
                    line = stdout.readLine()
                    if (line == null) break
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    try {
                        val record = json.parseToJsonElement(trimmed).jsonObject
                        handleRecord(record)
                    } catch (e: Exception) {
                        // Silently skip malformed lines; Pi may emit non-JSON debug output
                    }
                }
            } catch (e: Exception) {
                // Stream ended or process died
            } finally {
                // Complete all pending commands with an error
                pending.values.forEach { deferred ->
                    deferred.completeExceptionally(PiProcessClosedException())
                }
                pending.clear()
            }
        }
    }

    private fun handleRecord(record: JsonObject) {
        val id = record["id"]?.jsonPrimitive?.content
        if (id != null) {
            // Response to a command we sent
            pending[id]?.let { deferred ->
                deferred.complete(record)
                pending.remove(id)
            }
        } else {
            // Session event - broadcast to listeners
            listeners.forEach { listener ->
                try {
                    listener(record)
                } catch (_: Exception) {
                    // Listener errors shouldn't crash the reader thread
                }
            }
        }
    }

    /**
     * Send a JSON-RPC command to Pi and wait for the matching response.
     * Throws PiProcessClosedException if the process has died.
     */
    suspend fun sendCommand(command: JsonObject): JsonObject {
        val id = command["id"]?.jsonPrimitive?.content
            ?: UUID.randomUUID().toString()
        val cmd = buildJsonObject {
            command.entries.forEach { (k, v) -> put(k, v) }
            put("id", id)
        }

        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred

        synchronized(lock) {
            if (!process.isAlive) {
                pending.remove(id)
                throw PiProcessClosedException("Pi process is not running")
            }
            val bytes = json.encodeToString(cmd)
            stdin.write((bytes + "\n").toByteArray(Charsets.UTF_8))
            stdin.flush()
        }

        return withContext(Dispatchers.IO) {
            deferred.await()
        }
    }

    /** Send a prompt command to Pi. */
    suspend fun prompt(message: String, id: String? = null): JsonObject {
        return sendCommand(buildJsonObject {
            put("type", "prompt")
            put("message", message)
            id?.let { put("id", it) }
        })
    }

    /** Get current Pi session state. */
    suspend fun getState(id: String? = null): JsonObject {
        return sendCommand(buildJsonObject {
            put("type", "get_state")
            id?.let { put("id", it) }
        })
    }

    /** Add a listener for streaming session events. */
    fun addListener(listener: (JsonObject) -> Unit) {
        listeners.add(listener)
    }

    /** Remove a previously added listener. */
    fun removeListener(listener: (JsonObject) -> Unit) {
        listeners.remove(listener)
    }

    /** Close the adapter and underlying process. */
    fun close() {
        pending.values.forEach { deferred ->
            deferred.completeExceptionally(PiProcessClosedException("Adapter closed"))
        }
        pending.clear()
        try { stdin.close() } catch (_: Exception) {}
        process.destroyForcibly()
    }
}

/** Thrown when the Pi subprocess is not running or has died. */
class PiProcessClosedException(message: String = "Pi process closed") :
    Exception(message)
