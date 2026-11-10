package com.androidharness.app.local

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.UUID

object GpuDeliveryFactory {
    fun create(context: Context): GpuDelivery = GithubGpuDelivery(context)
    fun attach(context: Context) = Unit
}
internal class GithubGpuDelivery(private val context: Context) : GpuDelivery {
    private val mutable = MutableStateFlow(GpuInstallState())
    override val state = mutable.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val work = WorkManager.getInstance(context)
    private val preferences = context.getSharedPreferences("gpu-delivery", Context.MODE_PRIVATE)
    private var observation: Job? = null
    private val spec = runCatching { gpuPackage(context) }.getOrNull()
    init { observe(preferences.getString("work", null)?.let { runCatching { UUID.fromString(it) }.getOrNull() }) }
    private fun observe(id: UUID?) {
        observation?.cancel()
        observation = scope.launch {
            val pack = spec
            if (pack == null) { mutable.value = GpuInstallState(GpuInstallPhase.UNSUPPORTED); return@launch }
            if (id == null) { mutable.value = installed(pack) ?: GpuInstallState(GpuInstallPhase.NOT_INSTALLED); return@launch }
            work.getWorkInfoByIdFlow(id).collect { info ->
                mutable.value = when {
                    info == null -> installed(pack) ?: GpuInstallState(GpuInstallPhase.NOT_INSTALLED)
                    info.state == WorkInfo.State.SUCCEEDED -> installed(pack) ?: GpuInstallState(GpuInstallPhase.FAILED)
                    info.state == WorkInfo.State.FAILED -> installed(pack) ?: GpuInstallState(GpuInstallPhase.FAILED, error = info.outputData.getString("error"))
                    info.state == WorkInfo.State.CANCELLED -> installed(pack) ?: GpuInstallState(GpuInstallPhase.CANCELLED)
                    info.state == WorkInfo.State.RUNNING -> GpuInstallState(
                        if (info.progress.getBoolean("installing", false)) GpuInstallPhase.INSTALLING else GpuInstallPhase.DOWNLOADING,
                        info.progress.getLong("downloaded", 0), pack.downloadBytes)
                    else -> GpuInstallState(GpuInstallPhase.WAITING, total = pack.downloadBytes)
                }
            }
        }
    }
    private fun installed(pack: GpuRuntimePackage) = gpuLibrary(context, pack).takeIf(pack::verified)?.let {
        GpuInstallState(GpuInstallPhase.READY, total = pack.downloadBytes, downloaded = pack.downloadBytes, libraryPath = it.path)
    }
    override fun install() {
        if (spec == null || state.value.busy || state.value.phase == GpuInstallPhase.READY) return
        val request = OneTimeWorkRequestBuilder<GpuDownloadWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        mutable.value = GpuInstallState(GpuInstallPhase.WAITING, total = spec.downloadBytes)
        preferences.edit().putString("work", request.id.toString()).apply()
        work.enqueueUniqueWork("local-gpu-runtime", ExistingWorkPolicy.KEEP, request)
        observe(request.id)
    }
    override fun cancel() {
        GpuDownloadWorker.cancelActive()
        work.cancelUniqueWork("local-gpu-runtime")
    }
    override fun confirm(activity: Activity) = Unit
}
internal fun gpuPackage(context: Context): GpuRuntimePackage {
    check(Process.is64Bit())
    val abi = Build.SUPPORTED_ABIS.first()
    return context.assets.open("gpu-runtime/$abi.json").bufferedReader().use { GpuRuntimePackage.parse(it.readText(), abi) }
}
internal fun gpuLibrary(context: Context, spec: GpuRuntimePackage) = File(context.noBackupFilesDir, "gpu-runtime/${spec.sha256}.so")
