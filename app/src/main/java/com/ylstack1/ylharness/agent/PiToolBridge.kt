package com.ylstack1.ylharness.agent

import com.androidharness.app.tools.Tool
import com.androidharness.app.tools.ToolContext
import com.androidharness.app.tools.ToolRegistry
import com.androidharness.app.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Bridges Pi tool calls to AndroidHarness tools.
 *
 * When Pi (in RPC mode) requests a tool call, this bridge:
 * 1. Maps the Pi tool name to an AndroidHarness tool
 * 2. Executes it through the ToolRegistry
 * 3. Sends the result back to Pi via the adapter
 */
class PiToolBridge(
    private val adapter: PiAgentAdapter,
    private val toolRegistry: ToolRegistry,
) {
    private val toolContext: ToolContext? = null

    init {
        adapter.addListener { record ->
            if (record["type"]?.jsonPrimitive?.content == "tool_call") {
                handlePiToolCall(record)
            }
        }
    }

    private suspend fun handlePiToolCall(record: JsonObject) {
        val toolCall = record["toolCall"]?.jsonObject ?: return
        val toolName = toolCall["name"]?.jsonPrimitive?.content
            ?: return
        val input = toolCall["input"]?.jsonObject ?: JsonObject(emptyMap())
        val callId = toolCall["id"]?.jsonPrimitive?.content ?: ""

        val harnessTool = mapPiToolToHarnessTool(toolName)

        val result = if (harnessTool != null) {
            try {
                // We need a ToolContext; create a minimal one
                // In production this would come from the agent engine context
                withContext(Dispatchers.IO) {
                    ToolResult(
                        ok = false,
                        output = "PiToolBridge requires ToolContext - not yet connected to agent engine",
                    )
                }
            } catch (e: Exception) {
                ToolResult(ok = false, output = e.message ?: "Unknown error")
            }
        } else {
            ToolResult(
                ok = false,
                output = "Pi tool '$toolName' not available in AndroidHarness",
            )
        }

        // Send result back to Pi
        adapter.sendCommand(buildJsonObject {
            put("type", "tool_result")
            put("id", callId)
            putJsonObject("result") {
                put("ok", result.ok)
                put("output", result.output)
            }
        })
    }

    /**
     * Maps Pi tool names to AndroidHarness tool names.
     * Default mapping covers the most common Pi tools.
     */
    private fun mapPiToolToHarnessTool(piToolName: String): String? {
        return when (piToolName) {
            "read", "read_file" -> "read_file"
            "write", "write_file" -> "write_file"
            "edit", "edit_file" -> "edit_file"
            "list", "list_dir" -> "list_dir"
            "grep", "search" -> "grep"
            "bash", "shell", "exec" -> "shell"
            "python" -> "shell" // Python executed through shell
            else -> null
        }
    }
}
