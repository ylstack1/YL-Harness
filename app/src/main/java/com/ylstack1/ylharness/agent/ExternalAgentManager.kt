package com.ylstack1.ylharness.agent

import android.content.Context
import com.androidharness.app.tools.Tool
import com.androidharness.app.tools.ToolContext
import com.androidharness.app.tools.ToolRegistry
import com.androidharness.app.tools.ToolResult
import com.androidharness.app.config.ExternalAgentConfig
import com.androidharness.app.data.env.LinuxEnvironmentManager
import com.androidharness.app.data.env.ShellTierRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient

/**
 * Orchestrates external CLI agents (Pi, OpenCode, Claude Code, etc.)
 * Manages lifecycle, configuration, and execution routing.
 *
 * The ExternalAgentManager holds a [ToolRegistry] so that when an external agent
 * (e.g. Pi in RPC mode) requests tool calls, they can be executed through
 * AndroidHarness's own tool system.
 */
class ExternalAgentManager(
    private val context: Context,
    private val linuxEnv: LinuxEnvironmentManager,
    private val shellRouter: ShellTierRouter,
    private val httpClient: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val piProcessManager = PiProcessManager(context, linuxEnv, shellRouter, httpClient)
    private var piAdapter: PiAgentAdapter? = null

    /** Set after ToolRegistry is created (circular dependency). */
    internal var toolRegistry: ToolRegistry? = null

    /**
     * Execute a task using an external agent.
     *
     * @param agentId The agent id from harness.json externalAgents config
     * @param prompt The task prompt for the agent
     * @param background Run in background (not yet implemented)
     * @param ctx ToolContext from the agent engine (workspace, sessionId, etc.)
     * @return ToolResult with the agent's output
     */
    suspend fun executeAgent(
        agentId: String,
        prompt: String,
        background: Boolean = false,
        ctx: ToolContext? = null,
    ): ToolResult {
        return withContext(Dispatchers.IO) {
            val config = getConfig(agentId)
                ?: return@withContext ToolResult(
                    ok = false,
                    output = "External agent '$agentId' not configured. Add it to harness.json externalAgents section.",
                )

            if (!config.enabled) {
                return@withContext ToolResult(
                    ok = false,
                    output = "External agent '$agentId' is disabled. Enable it in harness.json or Settings.",
                )
            }

            when (agentId) {
                "pi" -> runPiAgent(config, prompt, ctx)
                else -> ToolResult(
                    ok = false,
                    output = "External agent '$agentId' not yet implemented.",
                )
            }
        }
    }

    private fun getConfig(agentId: String): ExternalAgentConfig? {
        // In production this would read from harness.json via HarnessConfig
        // For now, return a default Pi config
        if (agentId == "pi") {
            return ExternalAgentConfig(
                id = "pi",
                name = "Pi Coding Agent",
                enabled = true,
                provider = null,
                model = null,
                toolMapping = mapOf(
                    "read" to "read_file",
                    "write" to "write_file",
                    "edit" to "edit_file",
                    "list" to "list_dir",
                    "grep" to "grep",
                    "bash" to "shell",
                ),
            )
        }
        return null
    }

    private suspend fun runPiAgent(
        config: ExternalAgentConfig,
        prompt: String,
        ctx: ToolContext?,
    ): ToolResult {
        // Ensure Pi is running
        if (piAdapter == null) {
            val workingDir = config.workingDir?.let { java.io.File(it) }
                ?: ctx?.workspace?.shellRoot?.let { java.io.File(it) }
                ?: context.filesDir

            val startResult = piProcessManager.startSession(
                workingDir = workingDir,
                provider = config.provider,
                model = config.model,
                extraArgs = config.extraArgs,
            )

            if (startResult is kotlinx.coroutines.Result.failure) {
                return ToolResult(
                    ok = false,
                    output = "Failed to start Pi agent: ${startResult.exceptionOrNull()?.message ?: "unknown error"}",
                )
            }

            val proc = startResult.getOrNull()!!

            val toolRegistry = toolRegistry ?: return ToolResult(
                ok = false,
                output = "External agent manager not fully initialized (missing ToolRegistry).",
            )

            // Create tool bridge with access to ToolRegistry and ToolContext
            val toolBridge = PiToolBridge(toolRegistry, ctx)

            piAdapter = PiAgentAdapter(proc, json)
            piAdapter?.addListener { record ->
                if (record["type"]?.jsonPrimitive?.content == "tool_call") {
                    handlePiToolCall(record, toolBridge)
                }
            }
        }

        val adapter = piAdapter ?: return ToolResult(
            ok = false,
            output = "Pi adapter not available",
        )

        try {
            // Send prompt to Pi
            val response = adapter.prompt(prompt)

            // Extract output from response
            val output = response["output"]?.jsonPrimitive?.content
                ?: response["result"]?.jsonPrimitive?.content
                ?: response.toString()

            return ToolResult(ok = true, output = output)
        } catch (e: Exception) {
            return ToolResult(ok = false, output = "Pi agent error: ${e.message ?: "unknown"}")
        }
    }

    /**
     * Handle a tool call request from Pi.
     * Maps the Pi tool name to an AndroidHarness tool and executes it.
     */
    private suspend fun handlePiToolCall(
        record: JsonObject,
        toolBridge: PiToolBridge,
    ) {
        try {
            val result = toolBridge.executePiToolCall(record, json)
            // Send result back to Pi via adapter
            val callId = record["toolCall"]?.jsonObject?.get("id")?.jsonPrimitive?.content ?: ""
            piAdapter?.sendCommand(buildJsonObject {
                put("type", "tool_result")
                put("id", callId)
                putJsonObject("result") {
                    put("ok", result.ok)
                    put("output", result.output)
                }
            })
        } catch (e: Exception) {
            val callId = record["toolCall"]?.jsonObject?.get("id")?.jsonPrimitive?.content ?: ""
            piAdapter?.sendCommand(buildJsonObject {
                put("type", "tool_result")
                put("id", callId)
                putJsonObject("result") {
                    put("ok", false)
                    put("output", e.message ?: "Tool execution failed")
                }
            })
        }
    }

    /** Stop the Pi agent session if running. */
    fun stopAgent(agentId: String) {
        if (agentId == "pi") {
            piAdapter?.close()
            piAdapter = null
            piProcessManager.stopSession()
        }
    }

    /** Check if an agent is available and enabled. */
    fun isAgentAvailable(agentId: String): Boolean {
        val config = getConfig(agentId)
        return config?.enabled == true
    }

    /** List all configured external agents. */
    fun listAgents(): List<ExternalAgentConfig> {
        return listOf(
            ExternalAgentConfig(
                id = "pi",
                name = "Pi Coding Agent",
                enabled = true,
            ),
        )
    }
}
