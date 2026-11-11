package com.ylstack1.ylharness.agent

import android.content.Context
import com.ylstack1.ylharness.config.ExternalAgentConfig
import com.ylstack1.ylharness.config.HarnessConfig
import com.ylstack1.ylharness.tools.Tool
import com.ylstack1.ylharness.tools.ToolContext
import com.ylstack1.ylharness.tools.ToolRegistry
import com.ylstack1.ylharness.tools.ToolResult
import com.ylstack1.ylharness.data.env.LinuxEnvironmentManager
import com.ylstack1.ylharness.data.env.ShellTierRouter
import com.ylstack1.ylharness.workspace.WorkspaceFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 *
 * Agent configuration is read from [resolveConfig] which returns the current
 * [HarnessConfig] with its [externalAgents] map.
 */
class ExternalAgentManager(
    private val context: Context,
    private val linuxEnv: LinuxEnvironmentManager,
    private val shellRouter: ShellTierRouter,
    private val httpClient: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
    /** Resolves the current HarnessConfig for a workspace; can be updated by Settings UI. */
    internal var resolveConfig: (WorkspaceFs?) -> HarnessConfig = { HarnessConfig() },
) {
    private val piProcessManager = PiProcessManager(context, linuxEnv, shellRouter, httpClient)
    private var piAdapter: PiAgentAdapter? = null
    private val opencodeProcessManager = OpenCodeProcessManager(context, linuxEnv, shellRouter, httpClient)
    private var opencodeAdapter: OpenCodeAgentAdapter? = null

    /**
     * Policy applied to ACP `session/request_permission` calls from OpenCode
     * (and future ACP agents). Defaults to allow-once: the `external_agent`
     * invocation itself already passes the app's tool-approval flow, matching
     * how Pi executes tools directly through the registry today. The chat UI
     * can install a richer policy (dialog, remember-always) later.
     */
    internal var acpPermissionPolicy: suspend (AcpPermissionRequest) -> AcpPermissionOutcome =
        { _ -> AcpPermissionOutcome.Selected("once") }

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
            val config = getConfig(agentId, ctx?.workspace)
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
                "opencode" -> runOpenCodeAgent(config, prompt, ctx)
                else -> ToolResult(
                    ok = false,
                    output = "External agent '$agentId' not yet implemented.",
                )
            }
        }
    }

    /** Read agent config from the current HarnessConfig.externalAgents map. */
    private fun getConfig(agentId: String, workspace: WorkspaceFs?): ExternalAgentConfig? {
        return resolveConfig(workspace).externalAgents[agentId]
    }

    private suspend fun runPiAgent(
        config: ExternalAgentConfig,
        prompt: String,
        ctx: ToolContext?,
    ): ToolResult {
        agentNotInstalled("pi", piProcessManager.isInstalled())?.let { return it }

        // Ensure Pi is running
        if (piAdapter == null) {
            val workingDir = config.workingDir?.let { java.io.File(it) }
                ?: ctx?.workspace?.shellRoot
                ?: context.filesDir

            val startResult = piProcessManager.startSession(
                workingDir = workingDir,
                provider = config.provider,
                model = config.model,
                extraArgs = config.extraArgs,
            )

            val proc: Process
            if (startResult.isSuccess) {
                proc = startResult.getOrThrow()
            } else {
                return ToolResult(
                    ok = false,
                    output = "Failed to start Pi agent: unknown error",
                )
            }

            val registry = toolRegistry ?: return ToolResult(
                ok = false,
                output = "External agent manager not fully initialized (missing ToolRegistry).",
            )

            // Create tool bridge with access to ToolRegistry and ToolContext
            val toolBridge = PiToolBridge(registry, ctx)

            piAdapter = PiAgentAdapter(proc, json)
            piAdapter?.addListener { record ->
                if (record["type"]?.jsonPrimitive?.content == "tool_call") {
                    GlobalScope.launch(Dispatchers.IO) {
                        handlePiToolCall(record, toolBridge)
                    }
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

    /**
     * Run one prompt against OpenCode over ACP. The subprocess speaks JSON-RPC
     * on stdio; the adapter negotiates initialize → session/new → session/prompt
     * and answers agent→client requests (fs, permissions) through the bridge.
     * The working directory is the workspace shell root — the same filesystem
     * the harness tools operate on — and is passed as the ACP session cwd so
     * OpenCode edits files in place.
     */
    private suspend fun runOpenCodeAgent(
        config: ExternalAgentConfig,
        prompt: String,
        ctx: ToolContext?,
    ): ToolResult {
        agentNotInstalled("opencode", opencodeProcessManager.isInstalled())?.let { return it }

        val adapter = opencodeAdapter ?: run {
            val workingDir = config.workingDir?.let { java.io.File(it) }
                ?: ctx?.workspace?.shellRoot
                ?: context.filesDir

            val startResult = opencodeProcessManager.startSession(
                workingDir = workingDir,
                extraArgs = config.extraArgs,
            )
            val proc = startResult.getOrElse { e ->
                return ToolResult(
                    ok = false,
                    output = "Failed to start OpenCode agent: ${e.message ?: "unknown error"}",
                )
            }

            val newAdapter = OpenCodeAgentAdapter(
                agentOutput = proc.inputStream,
                agentInput = proc.outputStream,
                bridge = AcpWorkspaceBridge(workingDir, acpPermissionPolicy),
            )
            newAdapter.start()
            try {
                newAdapter.initialize()
                newAdapter.newSession(workingDir.absolutePath)
            } catch (e: Exception) {
                newAdapter.close()
                opencodeProcessManager.stopSession()
                return ToolResult(
                    ok = false,
                    output = "OpenCode session failed: ${e.message ?: "unknown error"}",
                )
            }
            opencodeAdapter = newAdapter
            newAdapter
        }

        return try {
            val turn = adapter.prompt(prompt)
            val output = turn.text.ifBlank {
                "OpenCode completed without text output " +
                    "(stopReason: ${turn.stopReason}, ${turn.toolCalls.size} tool call(s))."
            }
            ToolResult(ok = turn.stopReason !in setOf("refusal", "cancelled"), output = output)
        } catch (e: AcpAgentException) {
            if (e.code == -32000) {
                ToolResult(
                    ok = false,
                    output = "OpenCode needs authentication. Run `opencode auth login` in the workspace " +
                        "(details: ${e.message ?: "auth_required"}).",
                )
            } else {
                ToolResult(ok = false, output = "OpenCode agent error: ${e.message ?: "unknown"}")
            }
        } catch (e: Exception) {
            // Broken stream/process: drop the adapter so the next call restarts.
            opencodeAdapter = null
            opencodeProcessManager.stopSession()
            ToolResult(ok = false, output = "OpenCode agent error: ${e.message ?: "unknown"}")
        }
    }

    /** Stop the running agent session (Pi or OpenCode). */
    fun stopAgent(agentId: String) {
        when (agentId) {
            "pi" -> {
                piAdapter?.close()
                piAdapter = null
                piProcessManager.stopSession()
            }
            "opencode" -> {
                opencodeAdapter?.close()
                opencodeAdapter = null
                opencodeProcessManager.stopSession()
            }
        }
    }

    /** Whether the Pi binary is downloaded and executable. */
    fun isPiInstalled(): Boolean = piProcessManager.isInstalled()

    /** Download and unpack the Pi binary if it is missing. */
    suspend fun installPi(): Result<java.io.File> = piProcessManager.ensureInstalled()

    /** Whether the OpenCode binary is downloaded and executable. */
    fun isOpencodeInstalled(): Boolean = opencodeProcessManager.isInstalled()

    /** Download and unpack the OpenCode binary if it is missing. */
    suspend fun installOpencode(): Result<java.io.File> = opencodeProcessManager.ensureInstalled()

    /** Check if an agent is available and enabled. */
    suspend fun isAgentAvailable(agentId: String, workspace: WorkspaceFs? = null): Boolean {
        val config = getConfig(agentId, workspace)
        return config?.enabled == true
    }

    /** List all configured external agents from current HarnessConfig. */
    suspend fun listAgents(workspace: WorkspaceFs? = null): List<ExternalAgentConfig> {
        return resolveConfig(workspace).externalAgents.values.toList()
    }
}

/**
 * Returns a not-installed ToolResult when the agent's binary is missing, else null.
 * Top-level so it can be unit-tested without an Android Context.
 */
internal fun agentNotInstalled(agentId: String, installed: Boolean): ToolResult? {
    return if (installed) null else ToolResult(
        ok = false,
        output = "External agent '$agentId' is not installed. Install it in Settings first, then run the task again.",
    )
}

/**
 * ACP bridge rooted at the session working directory. Filesystem requests are
 * constrained to paths under [root] (OpenCode works on its cwd, so a contained
 * agent should never escape it), and permission decisions are delegated to the
 * configurable policy — allow-once by default.
 */
private class AcpWorkspaceBridge(
    private val root: java.io.File,
    private val policy: suspend (AcpPermissionRequest) -> AcpPermissionOutcome,
) : AcpBridge {

    private fun resolve(path: String): java.io.File {
        val file = if (java.io.File(path).isAbsolute) java.io.File(path)
        else java.io.File(root, path)
        val rootPath = root.canonicalPath
        if (!file.canonicalPath.startsWith(rootPath + java.io.File.separator) &&
            file.canonicalPath != rootPath
        ) {
            throw AcpAgentException(-32602, "Path outside workspace root: $path")
        }
        return file
    }

    override suspend fun fsRead(path: String, line: Int?, limit: Int?): String {
        val content = resolve(path).readText()
        if (line == null && limit == null) return content
        val lines = content.split('\n')
        val start = ((line ?: 1) - 1).coerceIn(0, lines.size)
        val end = if (limit != null) (start + limit).coerceAtMost(lines.size) else lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    override suspend fun fsWrite(path: String, content: String) {
        val file = resolve(path)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    override suspend fun permission(request: AcpPermissionRequest): AcpPermissionOutcome =
        policy(request)
}
