package com.ylstack1.ylharness.agent

import com.ylstack1.ylharness.tools.Tool
import com.ylstack1.ylharness.tools.ToolContext
import com.ylstack1.ylharness.tools.ToolRegistry
import com.ylstack1.ylharness.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Bridges Pi tool calls to AndroidHarness tools.
 *
 * When Pi (in RPC mode) requests a tool call, this bridge:
 * 1. Maps the Pi tool name to an AndroidHarness tool name
 * 2. Looks up the tool in [ToolRegistry]
 * 3. Executes it via [Tool.execute()] with the provided [ToolContext]
 * 4. Returns the result as a JSON object for sending back to Pi
 */
class PiToolBridge(
    private val toolRegistry: ToolRegistry,
    private val toolContext: ToolContext?,
) {

    /**
     * Execute a tool call requested by Pi.
     * Returns a [ToolResult] that can be sent back to Pi.
     */
    suspend fun executePiToolCall(
        record: JsonObject,
        json: Json,
    ): ToolResult {
        return withContext(Dispatchers.IO) {
            val toolCall = record["toolCall"]?.jsonObject ?: return@withContext ToolResult(
                ok = false,
                output = "Invalid tool call: missing toolCall object",
            )
            val toolName = toolCall["name"]?.jsonPrimitive?.content
                ?: return@withContext ToolResult(ok = false, output = "Invalid tool call: missing name")
            val input = toolCall["input"]?.jsonObject ?: JsonObject(emptyMap())
            val callId = toolCall["id"]?.jsonPrimitive?.content ?: ""

            val harnessToolName = mapPiToolToHarnessTool(toolName)
            val harnessTool = harnessToolName?.let { toolRegistry.get(it) }

            val result = if (harnessTool != null && toolContext != null) {
                try {
                    harnessTool.execute(input, toolContext)
                } catch (e: Exception) {
                    ToolResult(ok = false, output = e.message ?: "Tool '$toolName' failed.")
                }
            } else if (harnessTool == null) {
                ToolResult(
                    ok = false,
                    output = "Pi tool '$toolName' not available in AndroidHarness. Mapped name: $harnessToolName",
                )
            } else {
                ToolResult(
                    ok = false,
                    output = "No ToolContext available for tool execution",
                )
            }

            result
        }
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
            "multi_edit", "multiEdit" -> "multi_edit"
            "list", "list_dir" -> "list_dir"
            "grep", "search" -> "grep"
            "bash", "shell", "exec" -> "shell"
            "python" -> "shell" // Python executed through shell
            "patch", "apply_patch" -> "apply_patch"
            "move", "move_file" -> "move_file"
            "delete", "delete_file" -> "delete_file"
            "file_info" -> "file_info"
            else -> null
        }
    }
}
