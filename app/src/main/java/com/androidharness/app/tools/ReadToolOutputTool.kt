package com.androidharness.app.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

typealias ToolOutputLoader = suspend (sessionId: String, toolCallId: String) -> String?

/** Retrieves persisted output without expanding the entire chat context again. */
class ReadToolOutputTool(private val loader: ToolOutputLoader) : Tool {
    override val name = "read_tool_output"
    override val description = "Read an earlier tool result from this chat, including text omitted from model context. Page by character offset/limit."
    override val parametersSchema = Schema.obj(mapOf(
        "tool_call_id" to Schema.string("Tool call ID shown in the truncated-output notice."),
        "offset" to Schema.integer("1-based character offset. Default 1."),
        "limit" to Schema.integer("Characters per page (1..12000). Default 4000."),
    ), required = listOf("tool_call_id"))
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val sessionId = ctx.sessionId ?: throw ToolFailure("Tool output is only available inside its chat.")
        val id = args["tool_call_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: throw ToolFailure("Missing required argument: tool_call_id")
        val offset = args["offset"]?.jsonPrimitive?.intOrNull ?: 1
        val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 4000
        if (offset < 1) throw ToolFailure("offset must be greater than 0.")
        if (limit !in 1..12000) throw ToolFailure("limit must be between 1 and 12000.")
        val output = loader(sessionId, id) ?: throw ToolFailure("No tool output with that ID exists in this chat.")
        if (offset > output.length) return ToolResult(true, "[offset $offset is beyond end of output: ${output.length} chars]")
        var start = offset - 1
        if (start > 0 && output[start].isLowSurrogate() && output[start - 1].isHighSurrogate()) start--
        var end = (start.toLong() + limit).coerceAtMost(output.length.toLong()).toInt()
        if (end < output.length && output[end - 1].isHighSurrogate() && output[end].isLowSurrogate()) end++
        return ToolResult(true, output.substring(start, end) +
            "\n[showing chars ${start + 1}..$end of ${output.length}" +
            (if (end < output.length) "; next offset=${end + 1}" else "") + "]")
    }
}
