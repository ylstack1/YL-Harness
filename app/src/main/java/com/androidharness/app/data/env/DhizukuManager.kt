package com.androidharness.app.data.env

import android.content.Context
import android.content.pm.PackageManager
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

internal enum class DhizukuStatus { NOT_INSTALLED, NOT_RUNNING, NEEDS_PERMISSION, REQUESTING, GRANTED, DENIED, ERROR }
internal data class DhizukuConnection(val status: DhizukuStatus = DhizukuStatus.NOT_INSTALLED, val ownerPackage: String? = null)

/** Device Owner is its own permission domain; it is never selected as an ADB/root fallback. */
internal interface DeviceOwnerApi {
    fun init(): Boolean
    fun installed(): Boolean
    fun granted(): Boolean
    fun ownerPackage(): String
    fun requestPermission(callback: (Int) -> Unit)
    fun newProcess(command: String): Process
}

private class AndroidDeviceOwnerApi(private val context: Context) : DeviceOwnerApi {
    override fun init() = Dhizuku.init(context)
    override fun installed() = runCatching { context.packageManager.getApplicationInfo("com.rosan.dhizuku", 0).enabled }.getOrDefault(false)
    override fun granted() = Dhizuku.isPermissionGranted()
    override fun ownerPackage() = Dhizuku.getOwnerPackageName()
    override fun requestPermission(callback: (Int) -> Unit) {
        Dhizuku.requestPermission(object : DhizukuRequestPermissionListener() {
            override fun onRequestPermission(grantResult: Int) = callback(grantResult)
        })
    }
    override fun newProcess(command: String): Process = Dhizuku.newProcess(arrayOf("/system/bin/sh", "-c", command), null, java.io.File("/"))
}

class DhizukuManager internal constructor(
    private val api: DeviceOwnerApi,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    constructor(context: Context) : this(AndroidDeviceOwnerApi(context.applicationContext))
    private val lock = Mutex()
    private val mutableState = MutableStateFlow(DhizukuConnection())
    internal val state = mutableState.asStateFlow()
    private val processes = ConcurrentHashMap.newKeySet<Process>()
    @Volatile private var paused = false

    init { refresh() }

    fun refresh() { scope.launch { checkConnection() } }
    internal suspend fun checkConnection() = lock.withLock { refreshLocked() }

    private fun refreshLocked() {
        if (mutableState.value.status == DhizukuStatus.REQUESTING) return
        mutableState.value = try {
            if (api.init()) {
                DhizukuConnection(if (api.granted()) DhizukuStatus.GRANTED else DhizukuStatus.NEEDS_PERMISSION,
                    api.ownerPackage())
            } else {
                val installed = api.installed()
                DhizukuConnection(if (installed) DhizukuStatus.NOT_RUNNING else DhizukuStatus.NOT_INSTALLED)
            }
        } catch (_: Exception) { DhizukuConnection(DhizukuStatus.ERROR) }
    }

    internal fun requestPermission() {
        scope.launch {
            lock.withLock {
                if (mutableState.value.status == DhizukuStatus.REQUESTING) return@withLock
                refreshLocked()
                if (mutableState.value.status !in setOf(DhizukuStatus.NEEDS_PERMISSION, DhizukuStatus.DENIED)) return@withLock
                mutableState.value = mutableState.value.copy(status = DhizukuStatus.REQUESTING)
                try {
                    val result = withTimeoutOrNull(30_000) {
                        suspendCancellableCoroutine<Int> { continuation ->
                            api.requestPermission { grantResult ->
                                if (continuation.isActive) continuation.resumeWith(Result.success(grantResult))
                            }
                        }
                    }
                    mutableState.value = mutableState.value.copy(status = DhizukuStatus.NEEDS_PERMISSION)
                    refreshLocked()
                    if (result != PackageManager.PERMISSION_GRANTED && mutableState.value.status != DhizukuStatus.GRANTED)
                        mutableState.value = mutableState.value.copy(status = DhizukuStatus.DENIED)
                } catch (e: CancellationException) {
                    mutableState.value = mutableState.value.copy(status = DhizukuStatus.NEEDS_PERMISSION)
                    throw e
                } catch (_: Exception) { mutableState.value = DhizukuConnection(DhizukuStatus.ERROR) }
            }
        }
    }

    internal suspend fun run(command: String, timeoutMs: Long): PrivilegedResult = withContext(Dispatchers.IO) {
        check(!paused) { "Privileged access is paused" }
        check(api.init() && api.granted()) { "Connect and grant Dhizuku access in Settings first" }
        // Dispatch exactly once. Any subsequent failure is an uncertain result, never a fallback.
        val process = synchronized(processes) {
            check(!paused) { "Privileged access is paused" }
            api.newProcess(command).also { processes.add(it) }
        }
        val out = LimitedCapture()
        val err = LimitedCapture()
        var stdout: InputStream? = null
        var stderr: InputStream? = null
        try {
            process.outputStream.close()
            stdout = process.inputStream
            stderr = process.errorStream
            val outThread = out.read(stdout)
            val errThread = err.read(stderr)
            val finished = withTimeoutOrNull(timeoutMs) {
                while (process.isAlive) delay(50)
                true
            } ?: false
            if (!finished) runCatching { process.destroy() }
            // Bound stream drain time, including commands that leave a descendant holding a pipe.
            outThread.join(500)
            errThread.join(500)
            PrivilegedResult(if (finished) process.exitValue() else -1, !finished, out.text(), err.text())
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            mutableState.value = DhizukuConnection(DhizukuStatus.ERROR)
            PrivilegedResult(-1, false, out.text(), "Dhizuku connection lost after dispatch. The command may have run; it was not retried.")
        } finally {
            runCatching { process.destroy() }
            runCatching { stdout?.close() }
            runCatching { stderr?.close() }
            processes.remove(process)
        }
    }

    internal fun stopCommands(): String = synchronized(processes) {
        paused = true
        val stopped = processes.count { runCatching { it.destroy(); true }.getOrDefault(false) }
        "Dhizuku: stop requested for $stopped owner-app commands."
    }
    internal fun resumeCommands() { paused = false }

    private class LimitedCapture {
        private val bytes = ByteArrayOutputStream()
        fun read(stream: InputStream) = thread(isDaemon = true, name = "Dhizuku-output") {
            runCatching {
                val buffer = ByteArray(4096)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    synchronized(bytes) { bytes.write(buffer, 0, minOf(count, 60_000 - bytes.size())) }
                }
            }
        }
        fun text(): String = synchronized(bytes) { bytes.toString(Charsets.UTF_8.name()) }
    }
}
