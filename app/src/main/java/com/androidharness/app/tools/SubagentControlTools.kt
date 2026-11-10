package com.androidharness.app.tools

import com.androidharness.app.agent.SubagentRuns
import kotlinx.serialization.json.*

/** Bound to one session; ids from another chat never expose or cancel its delegates. */
fun subagentControlTools(runs: SubagentRuns, sessionId: String): List<Tool> =
    listOf("subagent_status", "subagent_wait", "subagent_stop").map { action ->
        object : Tool {
            override val name = action
            override val description = when (action) {
                "subagent_status" -> "Check this chat's subagents: names, task IDs, real status, latest activity and completed answers. Omit task_id to list all."
                "subagent_wait" -> "Wait up to 30 seconds for a subagent when its answer is needed. Returns its actual status and answer if finished. Do other independent work before waiting."
                else -> "Stop one subagent in this chat without stopping the main agent or other subagents."
            }
            override val parametersSchema = Schema.obj(mapOf(
                "task_id" to Schema.string("The task_id returned by task, from this chat."),
                "timeout_seconds" to Schema.integer("For subagent_wait only: 1 to 30 seconds, default 15."),
            ), required = if (action == "subagent_status") emptyList() else listOf("task_id"))
            override val isReadOnly = true
            override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
                val id = args["task_id"]?.jsonPrimitive?.contentOrNull
                val list = runs.list(sessionId)
                val run = id?.let { wanted -> list.firstOrNull { it.id == wanted } }
                if (id != null && run == null) return ToolResult(false, "No subagent with that task_id in this chat.")
                return when (action) {
                    "subagent_status" -> ToolResult(true, (if (id == null) list else listOfNotNull(run))
                        .takeLast(20).joinToString("\n\n") { it.report() }.ifBlank { "No subagents in this chat." })
                    "subagent_wait" -> {
                        if (id == null) return ToolResult(false, "task_id is required.")
                        val seconds = args["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 15
                        if (seconds !in 1..30) return ToolResult(false, "timeout_seconds must be between 1 and 30.")
                        ToolResult(true, runs.waitFor(sessionId, id, seconds * 1000L)?.report() ?: "No subagent with that task_id in this chat.")
                    }
                    else -> {
                        if (id == null) return ToolResult(false, "task_id is required.")
                        runs.stop(sessionId, id)
                        ToolResult(true, "Stop requested for ${run?.name}. Check subagent_status for the final state.")
                    }
                }
            }
        }
    }
