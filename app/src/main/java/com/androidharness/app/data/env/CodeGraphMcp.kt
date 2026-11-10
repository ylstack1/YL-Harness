package com.androidharness.app.data.env

import com.androidharness.app.tools.Tool
import com.androidharness.app.tools.ToolContext
import com.androidharness.app.tools.ToolFailure
import com.androidharness.app.tools.ToolResult
import com.androidharness.app.tools.mcp.McpConnection
import com.androidharness.app.tools.mcp.McpServerConfig
import com.androidharness.app.workspace.WorkspaceFs
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** App-owned stdio server, deliberately absent from user MCP configuration. */
internal class CodeGraphMcp(private val linuxEnv: LinuxEnvironmentManager) {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connection: McpConnection? = null
    private var root: File? = null
    private var idleJob: Job? = null

    private fun close() {
        connection?.close()
        connection = null
        root = null
    }

    suspend fun <T> paused(block: suspend () -> T): T = mutex.withLock {
        idleJob?.cancel()
        close()
        block()
    }

    private fun retireWhenIdle() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(5 * 60_000L)
            mutex.withLock { close() }
        }
    }

    private suspend fun connect(cwd: File): McpConnection {
        connection?.let { if (root == cwd && it.isAlive) return it }
        close()
        val conn = McpConnection(
            serverName = "CodeGraph",
            config = McpServerConfig(name = "CodeGraph", command = "codegraph"),
            processFactory = {
                // Direct mode avoids Node self-spawn through Android's linker.
                linuxEnv.shellProcessBuilder(
                    "exec /system/bin/sh '" + File(linuxEnv.prefix, "bin/codegraph").absolutePath.replace("'", "'\\''") +
                        "' --no-color serve --mcp --path .",
                )
                    .apply {
                        directory(cwd)
                        environment()["CODEGRAPH_NO_DAEMON"] = "1"
                        environment()["CODEGRAPH_NO_WATCHDOG"] = "1"
                        environment()["DO_NOT_TRACK"] = "1"
                        environment()["CODEGRAPH_TELEMETRY"] = "0"
                        redirectError(ProcessBuilder.Redirect.to(File("/dev/null")))
                    }.start()
            },
            handshakeTimeoutMs = 30_000,
        )
        try {
            conn.connect(cwd)
            connection = conn
            root = cwd
            return conn
        } catch (e: Exception) {
            conn.close()
            throw e
        }
    }

    suspend fun tools(workspace: WorkspaceFs, available: () -> Boolean): List<Tool> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val cwd = workspace.shellRoot ?: return@withLock emptyList()
                if (!available()) return@withLock emptyList()
                try {
                    connect(cwd).tools.map { info ->
                        object : Tool {
                            override val name = info.name
                            override val description = info.description
                            override val parametersSchema = info.inputSchema
                            override val isReadOnly = true
                            override fun isAvailable(ctx: ToolContext) =
                                ctx.workspace.shellRoot == cwd && available()

                            override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
                                withContext(Dispatchers.IO) {
                                    mutex.withLock {
                                        if (!isAvailable(ctx)) throw ToolFailure("CodeGraph is not enabled for this workspace.")
                                        idleJob?.cancel()
                                        try {
                                            val (text, error) = connect(cwd).callTool(name, args, 180_000)
                                            ToolResult(!error, text)
                                        } catch (e: Exception) {
                                            close()
                                            throw e
                                        } finally {
                                            retireWhenIdle()
                                        }
                                    }
                                }
                        }
                    }
                } finally {
                    retireWhenIdle()
                }
            }
        }
}
