package com.ylstack1.ylharness.agent

import android.content.Context
import com.ylstack1.ylharness.data.env.LinuxEnvironmentManager
import com.ylstack1.ylharness.data.env.ShellTierRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Manages the Pi coding agent binary lifecycle: download, start, stop, restart.
 * Pi runs as a long-lived subprocess controlled through JSON-RPC over stdin/stdout.
 */
class PiProcessManager(
    private val context: Context,
    private val linuxEnv: LinuxEnvironmentManager,
    private val shellRouter: ShellTierRouter,
    private val httpClient: OkHttpClient,
) {
    private var process: Process? = null
    private var stdin: OutputStream? = null

    companion object {
        private const val PI_RELEASE_URL =
            "https://github.com/earendil-works/pi/releases/latest/download/pi-linux-arm64.tar.gz"
    }

    /** The directory where the Pi binary is installed. */
    val piDir: File = File(context.filesDir, "pi")

    /** Path to the Pi executable. */
    val piBinary: File = File(piDir, "pi")

    /** Check if Pi binary is already installed and executable. */
    fun isInstalled(): Boolean = piBinary.exists() && piBinary.canExecute()

    /**
     * Download and install the Pi binary if not already present.
     * Returns the path to the installed binary.
     */
    suspend fun ensureInstalled(): Result<File> {
        if (isInstalled()) return Result.success(piBinary)

        return withContext(Dispatchers.IO) {
            piDir.mkdirs()

            try {
                val request = Request.Builder().url(PI_RELEASE_URL).build()
                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("Pi download failed: HTTP ${response.code}")
                    )
                }

                response.body?.byteStream()?.use { input ->
                    // Extract tar.gz - look for the 'pi' binary inside
                    var found = false
                    java.util.zip.GZIPInputStream(input).use { gzis ->
                        org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gzis).use { taris ->
                            var entry: org.apache.commons.compress.archivers.tar.TarArchiveEntry?
                            while (taris.nextEntry != null) {
                                entry = taris.nextEntry
                                val entryName = entry.name
                                if (entryName == "pi" || entryName.endsWith("/pi")) {
                                    val target = piBinary
                                    taris.copyTo(java.io.FileOutputStream(target))
                                    target.setExecutable(true)
                                    found = true
                                    break
                                }
                            }
                        }
                    }

                    if (!found) {
                        return@withContext Result.failure(
                            Exception("Pi binary not found in archive")
                        )
                    }

                    return@withContext Result.success(piBinary)
                } ?: return@withContext Result.failure(
                    Exception("Pi download returned empty body")
                )
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }
        }
    }

    /**
     * Start Pi in RPC mode as a subprocess.
     * Returns the process and its stdin stream for sending commands.
     */
    suspend fun startSession(
        workingDir: File,
        provider: String? = null,
        model: String? = null,
        extraArgs: List<String> = emptyList(),
    ): Result<Process> {
        val binary = ensureInstalled().getOrNull() ?: return Result.failure(
            Exception("Pi binary not available")
        )

        return withContext(Dispatchers.IO) {
            try {
                val command = java.util.ArrayList<String>()
                command.add(binary.absolutePath)
                command.add("--mode")
                command.add("rpc")
                command.add("--no-session")
                provider?.let { command.add("--provider"); command.add(it) }
                model?.let { command.add("--model"); command.add(it) }
                command.addAll(extraArgs)
                command.add(workingDir.absolutePath)

                val proc = java.lang.ProcessBuilder(command)
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

    /** Stop the Pi subprocess if running. */
    fun stopSession() {
        stdin?.use { it.close() }
        stdin = null
        process?.destroyForcibly()
        process = null
    }

    /** Get the running process, or null if not started. */
    fun getProcess(): Process? = process

    /** Get the stdin stream for writing commands. */
    fun getStdin(): OutputStream? = stdin
}
