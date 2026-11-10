package com.androidharness.app.local

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import androidx.annotation.Keep
import java.io.File

@Keep
internal object DeviceHardware {
    init { System.loadLibrary("harness_device") }
    external fun neuralDevices(): Array<String>
}
internal data class NeuralDriver(val type: Int, val name: String, val version: String)
internal data class HardwareSnapshot(val device: String, val chipset: String, val manufacturer: String,
    val board: String, val abi: String, val androidVersion: String, val coreIds: List<Int>,
    val gpu: String?, val gpuVendor: String?, val graphicsVersion: String?, val neuralDrivers: List<NeuralDriver>)
internal data class CoreSnapshot(val id: Int, val online: Boolean?, val currentKhz: Long?, val maxKhz: Long?, val load: Float?)
internal data class TelemetrySnapshot(val totalRam: Long, val availableRam: Long, val freeRam: Long?, val totalStorage: Long,
    val availableStorage: Long, val appPss: Long, val appCpu: Float?, val systemCpu: Float?, val cores: List<CoreSnapshot>,
    val thermalStatus: Int?, val batteryPercent: Int?, val batteryCelsius: Float?, val sampledAt: Long)

/** Access is best effort: Android and vendor SELinux policies can hide individual counters. */
internal class DeviceTelemetry(private val context: Context) {
    private var previousTicks: Map<String, Pair<Long, Long>> = emptyMap()
    private var previousAppCpu: Long? = null
    private var previousTime: Long? = null
    private fun read(path: String) = runCatching { File(path).readText().trim() }.getOrNull()
    private fun number(path: String) = read(path)?.toLongOrNull()?.takeIf { it > 0 }
    private fun coreIds() = runCatching { File("/sys/devices/system/cpu").listFiles()?.mapNotNull {
        it.name.takeIf { name -> name.matches(Regex("cpu[0-9]+")) }?.removePrefix("cpu")?.toIntOrNull()
    }?.sorted()?.takeIf { it.isNotEmpty() } }.getOrNull() ?: (0 until Runtime.getRuntime().availableProcessors()).toList()

    fun hardware(): HardwareSnapshot {
        val graphics = graphics()
        val drivers = if (Build.VERSION.SDK_INT >= 29) runCatching { DeviceHardware.neuralDevices().mapNotNull {
            val fields = it.split('\t', limit = 3)
            if (fields.size < 2) null else NeuralDriver(fields[0].toIntOrNull() ?: 0, fields[1], fields.getOrElse(2) { "" })
        } }.getOrDefault(emptyList()) else emptyList()
        return HardwareSnapshot("${Build.MANUFACTURER} ${Build.MODEL}",
            if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeUnless { it.isBlank() || it == Build.UNKNOWN } ?: Build.HARDWARE else Build.HARDWARE,
            if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else Build.MANUFACTURER,
            Build.BOARD, Build.SUPPORTED_ABIS.joinToString(), "${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}",
            coreIds(), graphics.getOrNull(0), graphics.getOrNull(1), graphics.getOrNull(2), drivers)
    }

    fun sample(): TelemetrySnapshot {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        val storage = StatFs(context.filesDir.path)
        val now = SystemClock.elapsedRealtime()
        val appTime = Process.getElapsedCpuTime()
        val appLoad = previousTime?.let { before -> previousAppCpu?.let { cpu ->
            if (now > before) ((appTime - cpu).toFloat() / (now - before) / Runtime.getRuntime().availableProcessors().coerceAtLeast(1) * 100).coerceIn(0f, 100f) else null
        } }
        previousAppCpu = appTime; previousTime = now
        val ticks = parseCpuTicks(read("/proc/stat").orEmpty())
        fun load(name: String): Float? {
            val current = ticks[name] ?: return null
            val old = previousTicks[name] ?: return null
            val total = current.first - old.first
            if (total <= 0) return null
            return ((total - (current.second - old.second)).toFloat() / total * 100).coerceIn(0f, 100f)
        }
        val cores = coreIds().map { id ->
            val base = "/sys/devices/system/cpu/cpu$id"
            val online = read("$base/online")?.let { if (it == "1") true else if (it == "0") false else null }
                ?: read("/sys/devices/system/cpu/online")?.let { id in parseCpuRange(it) }
            CoreSnapshot(id, online, number("$base/cpufreq/scaling_cur_freq") ?: number("$base/cpufreq/cpuinfo_cur_freq"),
                number("$base/cpufreq/cpuinfo_max_freq"), load("cpu$id"))
        }
        val system = load("cpu")
        previousTicks = ticks
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val temp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val thermal = if (Build.VERSION.SDK_INT >= 29) runCatching { (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus }.getOrNull() else null
        val free = read("/proc/meminfo")?.lineSequence()?.firstOrNull { it.startsWith("MemFree:") }
            ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()?.times(1024)
        return TelemetrySnapshot(info.totalMem, info.availMem, free, storage.totalBytes, storage.availableBytes,
            Debug.getPss().toLong() * 1024, appLoad, system, cores, thermal,
            if (level >= 0 && scale > 0) level * 100 / scale else null,
            temp?.takeUnless { it == Int.MIN_VALUE }?.div(10f), now)
    }

    private fun graphics(): List<String?> = runCatching {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, version, 0, version, 1)) return@runCatching emptyList()
        val oldDisplay = EGL14.eglGetCurrentDisplay()
        val oldContext = EGL14.eglGetCurrentContext()
        val oldDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val oldRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        var eglContext = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        try {
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            if (!EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE), 0, configs, 0, 1, count, 0) || count[0] < 1) return@runCatching emptyList()
            eglContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            if (!EGL14.eglMakeCurrent(display, surface, surface, eglContext)) return@runCatching emptyList()
            listOf(GLES20.glGetString(GLES20.GL_RENDERER), GLES20.glGetString(GLES20.GL_VENDOR), GLES20.glGetString(GLES20.GL_VERSION))
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
            if (oldDisplay != EGL14.EGL_NO_DISPLAY) EGL14.eglMakeCurrent(oldDisplay, oldDraw, oldRead, oldContext)
            EGL14.eglTerminate(display)
        }
    }.getOrDefault(emptyList())
}

internal fun parseCpuRange(text: String): Set<Int> = text.split(',').flatMap { section ->
    val pieces = section.trim().split('-')
    val first = pieces.firstOrNull()?.toIntOrNull()
    val last = pieces.getOrNull(1)?.toIntOrNull() ?: first
    if (pieces.size > 2 || first == null || last == null || first < 0 || last < first || last - first > 1024) emptyList() else (first..last).toList()
}.toSet()

internal fun parseCpuTicks(text: String): Map<String, Pair<Long, Long>> = text.lineSequence().mapNotNull { line ->
    val parts = line.trim().split(Regex("\\s+"))
    if (!parts.first().matches(Regex("cpu[0-9]*")) || parts.size < 5) return@mapNotNull null
    val values = parts.drop(1).take(8).map { it.toLongOrNull() ?: return@mapNotNull null }
    parts[0] to (values.sum() to (values[3] + values.getOrElse(4) { 0 }))
}.toMap()
