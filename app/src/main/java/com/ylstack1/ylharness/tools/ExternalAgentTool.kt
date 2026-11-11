package com.ylstack1.ylharness.tools

import com.ylstack1.ylharness.agent.ExternalAgentManager
import com.ylstack1.ylharness.tools.Tool
import com.ylstack1.ylharness.tools.ToolContext
import com.ylstack1.ylharness.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tool that delegates tasks to external CLI agents (Pi, OpenCode, Claude Code).
 *
 * Usage from agent:
 *   external_agent({
 *     "agent": "pi",
 *     "prompt": "Explain this codebase structure",
 *     "background": false
 *   })
 */
class ExternalAgentTool(
    private val manager: com.ylstack1.ylharness.agent.ExternalAgentManager,
) : Tool {

    override val name = "external_agent"
    override val description =
        "Delegate a coding task to an external agent (Pi, OpenCode, Claude Code). " +
        "The agent runs as a subprocess and can read/write files, run shell commands, " +
        "and return results. Specify the agent id and a prompt describing the task."

    override val parametersSchema = Schema.obj(
        mapOf(
            "agent" to Schema.string(
                "External agent id from harness.json externalAgents section (e.g. 'pi').",
            ),
            "prompt" to Schema.string(
                "Task description for the external agent.",
            ),
            "background" to Schema.boolean(
                "Run in background and return immediately (default false).",
            ),
        ),
        required = listOf("agent", "prompt"),
    )

    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        return withContext(Dispatchers.IO) {
            val agentId = args["agent"]?.jsonPrimitive?.content
                ?: return@withContext ToolResult(
                    ok = false,
                    output = "Missing required argument: agent",
                )
            val prompt = args["prompt"]?.jsonPrimitive?.content
                ?: return@withContext ToolResult(
                    ok = false,
                    output = "Missing required argument: prompt",
                )
            val background = args["background"]?.jsonPrimitive?.boolean ?: false

            manager.executeAgent(agentId, prompt, background, ctx)
        }
    }
}
