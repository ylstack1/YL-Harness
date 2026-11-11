package com.ylstack1.ylharness.agent

import android.content.Context
import com.ylstack1.ylharness.data.env.LinuxEnvironmentManager
import com.ylstack1.ylharness.data.env.ShellTierRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Manages the OpenCode coding agent binary lifecycle: download, start, stop.
 * OpenCode runs as an ACP (Agent Client Protocol v1) subprocess controlled
 * through JSON-RPC over stdin/stdout (`opencode acp`).
 *
 * The binary ships as a npm platform package (`opencode-linux-arm64`), whose
 * tarball contains the executable at `package/opencode`. Mirrors the download
 * pattern of [PiProcessManager].
 */
class OpenCodeProcessManager(
    private val context: Context,
    private val linuxEnv: LinuxEnvironmentManager,
    private val shellRouter: ShellTierRouter,
    private val httpClient: OkHttpClient,
) {
    private var process: Process? = null
    private var stdin: OutputStream? = null

    companion object {
        /** Pinned npm release of the arm64 Linux build (updated deliberately). */
        private const val OPENCODE_VERSION = "1.19.5"
        private const val OPENCODE_RELEASE_URL =
            "https://registry.npmjs.org/opencode-linux-arm64/-/opencode-linux-arm64-$OPENCODE_VERSION.tgz"

        /** Entry name inside the npm tarball, under the `package/` root. */
        private const val BINARY_ENTRY = "opencode"

        /**
         * Extract the `package/opencode` binary from an npm tar.gz stream into
         * [target]. Returns the target on success, null when no binary entry
         * exists. Pure function so it is testable on the JVM.
         */
        internal fun extractBinary(input: InputStream, target: File): File? {
            var found = false
            java.util.zip.GZIPInputStream(input).use { gzis ->
                org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gzis).use { taris ->
                    var entry: org.apache.commons.compress.archivers.tar.TarArchiveEntry? = taris.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (name == BINARY_ENTRY || name.endsWith("/$BINARY_ENTRY")) {
                            target.outputStream().use { out -> taris.copyTo(out) }
                            target.setExecutable(true)
                            found = true
                            break
                        }
                        entry = taris.nextEntry
                    }
                }
            }
            return if (found) target else null
        }
    }

    /** The directory where the OpenCode binary is installed. */
    val opencodeDir: File = File(context.filesDir, "opencode")

    /** Path to the OpenCode executable. */
    val opencodeBinary: File = File(opencodeDir, "opencode")

    /** Check if the OpenCode binary is already installed and executable. */
    fun isInstalled(): Boolean = opencodeBinary.exists() && opencodeBinary.canExecute()

    /**
     * Download and install the OpenCode binary if not already present.
     * Returns the path to the installed binary.
     */
    suspend fun ensureInstalled(): Result<File> {
        if (isInstalled()) return Result.success(opencodeBinary)

        return withContext(Dispatchers.IO) {
            opencodeDir.mkdirs()

            try {
                val request = Request.Builder().url(OPENCODE_RELEASE_URL).build()
                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("OpenCode download failed: HTTP ${response.code}"),
                    )
                }

                response.body?.byteStream()?.use { input ->
                    val target = extractBinary(input, opencodeBinary)
                        ?: return@withContext Result.failure(
                            Exception("OpenCode binary not found in npm archive"),
                        )
                    Result.success(target)
                } ?: Result.failure(Exception("OpenCode download returned empty body"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Start OpenCode in ACP mode as a subprocess speaking JSON-RPC over
     * stdin/stdout. [workingDir] must be the absolute workspace path the agent
     * should operate on; it is passed again as the session cwd in
     * [OpenCodeAgentAdapter.newSession] so both agree.
     */
    suspend fun startSession(
        workingDir: File,
        extraArgs: List<String> = emptyList(),
    ): Result<Process> {
        val binary = ensureInstalled().getOrNull() ?: return Result.failure(
            Exception("OpenCode binary not available"),
        )

        return withContext(Dispatchers.IO) {
            try {
                val command = java.util.ArrayList<String>()
                command.add(binary.absolutePath)
                command.add("acp")
                command.addAll(extraArgs)

                val proc = ProcessBuilder(command)
                    .directory(workingDir)
                    .redirectErrorStream(false)
                    .start()

                process = proc
                stdin = proc.outputStream

                Result.success(proc)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /** Stop the OpenCode subprocess if running. */
    fun stopSession() {
        stdin?.use { it.close() }
        stdin = null
        process?.destroyForcibly()
        process = null
    }

    /** Get the running process, or null if not started. */
    fun getProcess(): Process? = process

    /** Get the stdin stream for writing requests. */
    fun getStdin(): OutputStream? = stdin
}
