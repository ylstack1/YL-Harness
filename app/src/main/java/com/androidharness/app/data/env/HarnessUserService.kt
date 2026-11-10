package com.androidharness.app.data.env

import android.content.Context
import androidx.annotation.Keep
import com.androidharness.app.IHarnessService
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The IHarnessService implementation. Shizuku's server loads this class into
 * its own process (running as the shell uid, or root with Sui) and calls its
 * methods directly; the app never runs this code in its own process.
 *
 * Both the implicit no-arg constructor (Shizuku v12) and the Context
 * constructor (v13) are required. [Keep] stops R8 from stripping the class.
 */
@Keep
class HarnessUserService() : IHarnessService.Stub() {


    /**
     * Exports for the wrapper that runs a command: the deployed prefix paths
     * come from the request environment, because this service runs inside
     * Shizuku's process and cannot tell which build sent the command. The
     * legacy shared directory is only a fallback for clients that predate
     * per-build copies.
     */
    private fun ldPathExport(): String =
        "export LD_LIBRARY_PATH=\"\${LD_LIBRARY_PATH:+\$LD_LIBRARY_PATH:}\${HARNESS_TMP_LIB:-${LinuxEnvironmentManager.LEGACY_TMP_PREFIX}/lib}\""

    private fun binPathExport(): String =
        "export PATH=\"\${HARNESS_TMP_BIN:-${LinuxEnvironmentManager.LEGACY_TMP_PREFIX}/bin}:\$PATH\""

    @Keep
    constructor(context: Context?) : this()

    private val pidToLog = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private val pidToDir = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private val pidStart = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private val commandLock = Any()
    @Volatile private var acceptingCommands = true

    init {
        // PR_SET_CHILD_SUBREAPER (36) so orphaned descendants reparent to this service instead of PID 1
        runCatching {
            val method = android.system.Os::class.java.getMethod(
                "prctl",
                Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
            )
            method.invoke(null, 36, 1L, 0L, 0L, 0L)
        }

        // Background daemon thread to continuously reap any defunct/zombie children
        Thread {
            while (true) {
                try {
                    Thread.sleep(1000)
                    reapZombies()
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.apply {
            isDaemon = true
            name = "ZombieReaper"
            start()
        }
    }

    override fun spawnDetached(
        cmd: Array<String>,
        env: Array<String>?,
        dir: String?,
        logPath: String,
    ): Int = synchronized(commandLock) {
        if (!acceptingCommands) return@synchronized -1
        try {
            val wrappedCmd = if (File("/system/bin/setsid").exists()) {
                val shBin = if (File("/system/bin/sh").exists()) "/system/bin/sh" else "sh"
                arrayOf(
                    "/system/bin/setsid",
                    shBin,
                    "-c",
                    ldPathExport() + "; exec \"\$@\"",
                    "sh",
                    *cmd,
                )
            } else {
                cmd
            }
            val pb = ProcessBuilder(*wrappedCmd)
            pb.directory(dir?.let { File(it) })
            if (env != null) {
                val e = pb.environment()
                for (entry in env) {
                    val i = entry.indexOf('=')
                    if (i > 0) e[entry.substring(0, i)] = entry.substring(i + 1)
                }
            }
            val log = File(logPath)
            log.parentFile?.mkdirs()
            log.createNewFile()
            val canonLog = runCatching { log.canonicalPath }.getOrDefault(logPath)
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            pb.redirectErrorStream(true)
            val p = pb.start()
            val pid = pidOf(p) ?: -1
            if (pid > 0) {
                pidToLog[pid] = canonLog
                processStart(pid)?.let { pidStart[pid] = it }
                dir?.let { pidToDir[pid] = runCatching { File(it).canonicalPath }.getOrDefault(it) }
            }
            pid
        } catch (e: Exception) {
            -1
        }
    }

    override fun isProcessAlive(pid: Int): Boolean =
        runCatching { android.system.Os.kill(pid, 0); true }.getOrDefault(false)

    override fun killProcess(pid: Int): Boolean =
        runCatching {
            // Never signal an unrelated/reused PID, even when running as root.
            val expected = pidStart[pid]
            if (android.os.Process.myUid() == 0 && expected == null) return@runCatching false
            if (expected != null && processStart(pid)?.let { it != expected } == true) return@runCatching false
            val logPath = pidToLog.remove(pid)
            val dir = pidToDir.remove(pid)
            pidStart.remove(pid)

            // 1. Kill process group
            runCatching { android.system.Os.kill(-pid, android.system.OsConstants.SIGKILL) }
            // 2. Kill all child processes by parent pid
            runCatching {
                val pkill = Runtime.getRuntime().exec(arrayOf("/system/bin/pkill", "-9", "-P", pid.toString()))
                pkill.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
            }
            // 3. Recursively kill all descendant processes and processes holding log fds
            killProcessesAssociatedWith(pid, logPath, dir)
            // 4. Kill the target pid
            runCatching { android.system.Os.kill(pid, android.system.OsConstants.SIGKILL) }
            reapZombies()
            true
        }.getOrDefault(false)

    fun stopDetachedCommands(): Int = pidToLog.keys.toList().count { killProcess(it) }

    override fun killTrackedProcess(pid: Int, logPath: String): Boolean = runCatching {
        val start = processStart(pid) ?: return@runCatching false
        val canonical = File(logPath).canonicalPath
        val descriptors = File("/proc/$pid/fd").listFiles().orEmpty()
        if (descriptors.none { runCatching { android.system.Os.readlink(it.path) == canonical }.getOrDefault(false) }) return@runCatching false
        // Capture identity before adopting the recovered job; killProcess checks it again.
        pidStart[pid] = start
        pidToLog[pid] = canonical
        killProcess(pid)
    }.getOrDefault(false)

    private fun processStart(pid: Int): String? = runCatching {
        File("/proc/$pid/stat").readText().substringAfterLast(") ").split(' ')[19]
    }.getOrNull()

    private fun killProcessesAssociatedWith(rootPid: Int, logPath: String?, dir: String?) {
        val proc = File("/proc")
        val pidDirs = proc.listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } } ?: return
        val matchedPids = mutableSetOf<Int>()

        for (pDir in pidDirs) {
            val p = pDir.name.toIntOrNull() ?: continue
            if (p <= 1 || p == rootPid) continue

            // 1. Check parent PID from /proc/<p>/stat
            val statFile = File(pDir, "stat")
            val statContent = runCatching { statFile.readText() }.getOrNull()
            if (statContent != null) {
                val lastParen = statContent.lastIndexOf(')')
                if (lastParen > 0 && lastParen + 2 < statContent.length) {
                    val rest = statContent.substring(lastParen + 2).trimStart()
                    val tokens = rest.split(' ')
                    if (tokens.size >= 2) {
                        val ppid = tokens[1].toIntOrNull()
                        if (ppid == rootPid) {
                            matchedPids += p
                        }
                    }
                }
            }

            // 2. Check open file descriptors in /proc/<p>/fd
            if (!logPath.isNullOrBlank()) {
                val fdDir = File(pDir, "fd")
                val fds = runCatching { fdDir.listFiles() }.getOrNull()
                if (fds != null) {
                    for (fd in fds) {
                        val link = runCatching { android.system.Os.readlink(fd.absolutePath) }.getOrNull()
                        if (link == logPath || link == "$logPath (deleted)") {
                            matchedPids += p
                            break
                        }
                    }
                }
            }
        }

        // Kill all discovered processes (and their process groups if they called setsid)
        for (targetPid in matchedPids) {
            runCatching { killDescendants(targetPid) }
            runCatching { android.system.Os.kill(-targetPid, android.system.OsConstants.SIGKILL) }
            runCatching { android.system.Os.kill(targetPid, android.system.OsConstants.SIGKILL) }
        }
    }

    private fun killDescendants(rootPid: Int) {
        val proc = File("/proc")
        val pidDirs = proc.listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } } ?: return
        val allProcs = mutableListOf<Triple<Int, Int, Pair<Int, Int>>>()
        for (dir in pidDirs) {
            val p = dir.name.toIntOrNull() ?: continue
            if (p <= 1 || p == rootPid) continue
            val statFile = File(dir, "stat")
            val statContent = runCatching { statFile.readText() }.getOrNull() ?: continue
            val lastParen = statContent.lastIndexOf(')')
            if (lastParen > 0 && lastParen + 2 < statContent.length) {
                val rest = statContent.substring(lastParen + 2).trimStart()
                val tokens = rest.split(' ')
                if (tokens.size >= 4) {
                    val ppid = tokens[1].toIntOrNull() ?: -1
                    val pgrp = tokens[2].toIntOrNull() ?: -1
                    val session = tokens[3].toIntOrNull() ?: -1
                    allProcs.add(Triple(p, ppid, Pair(pgrp, session)))
                }
            }
        }
        val toKill = mutableSetOf<Int>()
        val targetRoots = mutableSetOf(rootPid)
        var changed = true
        while (changed) {
            changed = false
            for ((p, ppid, pgSid) in allProcs) {
                val (pgrp, session) = pgSid
                if (p !in toKill && (ppid in targetRoots || pgrp in targetRoots || session in targetRoots)) {
                    toKill.add(p)
                    targetRoots.add(p)
                    changed = true
                }
            }
        }
        for (child in toKill) {
            // Explicit numeric validation: refuse to ever signal PID 0/1 or a
            // negative value, even though `toKill` is already built solely
            // from confirmed descendants of rootPid.
            if (child <= 1) continue
            val killBin = if (File("/system/bin/kill").exists()) "/system/bin/kill" else "kill"
            runCatching {
                val k1 = Runtime.getRuntime().exec(arrayOf(killBin, "-9", "-$child"))
                k1.waitFor(200, TimeUnit.MILLISECONDS)
            }
            runCatching {
                val k2 = Runtime.getRuntime().exec(arrayOf(killBin, "-9", child.toString()))
                k2.waitFor(200, TimeUnit.MILLISECONDS)
            }
            runCatching { android.system.Os.kill(-child, android.system.OsConstants.SIGKILL) }
            runCatching { android.system.Os.kill(child, android.system.OsConstants.SIGKILL) }
        }
    }

    private val waitpidMethod by lazy {
        runCatching {
            android.system.Os::class.java.methods.firstOrNull { it.name == "waitpid" }
        }.getOrNull()
    }

    private fun reapZombies() {
        val method = waitpidMethod ?: return
        val paramTypes = method.parameterTypes
        val dummyStatus = if (paramTypes.size >= 2 && paramTypes[1] != Int::class.javaPrimitiveType) {
            runCatching { paramTypes[1].getDeclaredConstructor().newInstance() }.getOrNull()
        } else null

        val wnohang = 1 // WNOHANG constant
        while (true) {
            val res = runCatching {
                if (paramTypes.size == 3) {
                    (method.invoke(null, -1, dummyStatus, wnohang) as? Number)?.toInt() ?: 0
                } else if (paramTypes.size == 2) {
                    (method.invoke(null, -1, wnohang) as? Number)?.toInt() ?: 0
                } else 0
            }.getOrDefault(0)
            if (res <= 0) break
        }
    }

    /** java.lang.ProcessImpl keeps the pid in a private field. */
    private fun pidOf(p: Process): Int? = runCatching {
        val f = p.javaClass.getDeclaredField("pid")
        f.isAccessible = true
        f.getInt(p)
    }.getOrNull()

    override fun destroy() {
        System.exit(0)
    }

    override fun exit() {
        destroy()
    }

    private val activeCommands = java.util.concurrent.ConcurrentHashMap.newKeySet<Thread>()
    // Protected by commandLock, including signalling: never interrupt a reused Binder worker.
    private val registeredCommands = mutableMapOf<String, Thread?>()

    override fun registerCommand(): String = synchronized(commandLock) {
        check(acceptingCommands) { "Privileged access is paused." }
        java.util.UUID.randomUUID().toString().also { registeredCommands[it] = null }
    }

    override fun cancelCommand(id: String) { synchronized(commandLock) {
        registeredCommands.remove(id)?.interrupt()
    } }

    override fun execRegistered(id: String, cmd: Array<String>, env: Array<String>?, dir: String?, maxBytes: Int, timeoutMs: Int): String {
        synchronized(commandLock) {
            if (!registeredCommands.containsKey(id)) return "exit=-130\ntimeout=0\n$STDERR_SEPARATOR\nCancelled by user."
            registeredCommands[id] = Thread.currentThread()
        }
        return try { exec(cmd, env, dir, maxBytes, timeoutMs) }
        finally { synchronized(commandLock) {
            registeredCommands.remove(id)
            Thread.interrupted()
        } }
    }

    override fun getUid(): Int = android.os.Process.myUid()
    override fun rootList(path: String, offset: Int): String = synchronized(commandLock) {
        check(acceptingCommands) { "Privileged access is paused." }; RootFileOperations.list(path, offset)
    }
    override fun rootRead(path: String): String = synchronized(commandLock) {
        check(acceptingCommands) { "Privileged access is paused." }; RootFileOperations.read(path)
    }
    override fun rootWrite(path: String, expectedHash: String, text: String): String = synchronized(commandLock) {
        check(acceptingCommands) { "Privileged access is paused." }; RootFileOperations.write(path, expectedHash, text)
    }
    override fun activeCommandCount(): Int = activeCommands.size
    override fun cancelActive(): Int {
        val running = synchronized(commandLock) { acceptingCommands = false; activeCommands.toList() }
        running.forEach { it.interrupt() }
        stopDetachedCommands()
        return running.size
    }
    override fun resumeCommands() { synchronized(commandLock) { acceptingCommands = true } }

    override fun exec(cmd: Array<String>, env: Array<String>?, dir: String?, maxBytes: Int, timeoutMs: Int): String {
        val thread = Thread.currentThread()
        synchronized(commandLock) {
            // Readiness probes remain available while execution is paused.
            val identityProbe = cmd.contentEquals(arrayOf("/system/bin/sh", "-c", "id -u"))
            if (!acceptingCommands && !identityProbe) return "exit=-130\ntimeout=0\n$STDERR_SEPARATOR\nPrivileged access is paused."
            activeCommands.add(thread)
        }
        return try { executeTracked(cmd, env, dir, maxBytes, timeoutMs) }
        catch (_: InterruptedException) { "exit=-130\ntimeout=0\n$STDERR_SEPARATOR\nCancelled by user." }
        finally { activeCommands.remove(thread); Thread.interrupted() }
    }

    private fun executeTracked(
        cmd: Array<String>,
        env: Array<String>?,
        dir: String?,
        maxBytes: Int,
        timeoutMs: Int,
    ): String {
        val process = try {
            val wrappedCmd = if (File("/system/bin/setsid").exists()) {
                val shBin = if (File("/system/bin/sh").exists()) "/system/bin/sh" else "sh"
                arrayOf(
                    "/system/bin/setsid",
                    shBin,
                    "-c",
                    ldPathExport() + "; " + binPathExport() + "; exec \"\$@\"",
                    "sh",
                    *cmd,
                )
            } else {
                cmd
            }
            val pb = ProcessBuilder(*wrappedCmd)
            pb.directory(dir?.let { File(it) })
            if (env != null) {
                val e = pb.environment()
                for (entry in env) {
                    val i = entry.indexOf('=')
                    if (i > 0) e[entry.substring(0, i)] = entry.substring(i + 1)
                }
            }
            pb.start()
        } catch (e: Exception) {
            return "exit=-1\ntimeout=0\nfailed to launch: ${e.message}"
        }

        val limit = maxBytes.coerceIn(1024, 512_000)
        val stdout = StringBuffer()
        val stderr = StringBuffer()
        val out = Thread { gobble(process.inputStream, stdout, limit) }
        val err = Thread { gobble(process.errorStream, stderr, limit) }
        listOf(out, err).forEach { it.isDaemon = true; it.start() }

        val startedAt = System.currentTimeMillis()
        var timedOut = false
        var exit = -1
        try {
            while (true) {
                if (process.waitFor(50, TimeUnit.MILLISECONDS)) {
                    exit = process.exitValue()
                    break
                }
                if (System.currentTimeMillis() - startedAt > timeoutMs) {
                    timedOut = true
                    break
                }
            }
        } catch (e: InterruptedException) {
            // The compatibility transport can cancel one request without stopping
            // the service or other commands. Reuse the existing group cleanup.
            pidOf(process)?.let(::killGroup)
            process.destroyForcibly()
            runCatching { process.waitFor(200, TimeUnit.MILLISECONDS) }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
            reapZombies()
            throw e
        }

        val pid = pidOf(process)
        if (pid != null && pid > 0) {
            if (timedOut) {
                killGroup(pid)
                process.destroyForcibly()
                process.waitFor(200, TimeUnit.MILLISECONDS)
            } else {
                // Command finished normally: clean up any detached/orphan grandchildren in this process group
                cleanOrphanGroup(pid)
            }
        }
        reapZombies()

        out.join(300)
        err.join(300)
        reapZombies()

        return buildString {
            append("exit=").append(exit).append('\n')
            append("timeout=").append(if (timedOut) 1 else 0).append('\n')
            append(stdout)
            if (stdout.isNotEmpty() && !stdout.endsWith("\n")) append('\n')
            append(STDERR_SEPARATOR).append('\n')
            append(stderr)
        }
    }

    private fun gobble(stream: java.io.InputStream, into: StringBuffer, max: Int) {
        try {
            val buf = CharArray(4096)
            stream.bufferedReader().use { reader ->
                while (true) {
                    val n = reader.read(buf)
                    if (n <= 0) break
                    synchronized(into) {
                        if (into.length < max) {
                            val toAppend = minOf(n, max - into.length)
                            into.append(buf, 0, toAppend)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun killGroup(pid: Int) {
        runCatching { android.system.Os.kill(-pid, android.system.OsConstants.SIGKILL) }
        val killBin = if (File("/system/bin/kill").exists()) "/system/bin/kill" else "kill"
        runCatching {
            val k = Runtime.getRuntime().exec(arrayOf(killBin, "-9", "-$pid"))
            k.waitFor(100, TimeUnit.MILLISECONDS)
        }
        val pkillBin = if (File("/system/bin/pkill").exists()) "/system/bin/pkill" else "pkill"
        runCatching {
            val pkillS = Runtime.getRuntime().exec(arrayOf(pkillBin, "-9", "-s", pid.toString()))
            pkillS.waitFor(100, TimeUnit.MILLISECONDS)
        }
        runCatching {
            val pkillP = Runtime.getRuntime().exec(arrayOf(pkillBin, "-9", "-P", pid.toString()))
            pkillP.waitFor(100, TimeUnit.MILLISECONDS)
        }
        runCatching { android.system.Os.kill(pid, android.system.OsConstants.SIGKILL) }
        runCatching {
            val k = Runtime.getRuntime().exec(arrayOf(killBin, "-9", pid.toString()))
            k.waitFor(100, TimeUnit.MILLISECONDS)
        }
    }

    private fun cleanOrphanGroup(pid: Int) {
        runCatching { android.system.Os.kill(-pid, android.system.OsConstants.SIGKILL) }
        val killBin = if (File("/system/bin/kill").exists()) "/system/bin/kill" else "kill"
        runCatching {
            val k = Runtime.getRuntime().exec(arrayOf(killBin, "-9", "-$pid"))
            k.waitFor(100, TimeUnit.MILLISECONDS)
        }
        val pkillBin = if (File("/system/bin/pkill").exists()) "/system/bin/pkill" else "pkill"
        runCatching {
            val pkillS = Runtime.getRuntime().exec(arrayOf(pkillBin, "-9", "-s", pid.toString()))
            pkillS.waitFor(100, TimeUnit.MILLISECONDS)
        }
    }

    companion object {
        /** Marks where captured stdout ends and stderr begins in exec() results. */
        const val STDERR_SEPARATOR = "<<<HARNESS_STDERR>>>"
    }
}
