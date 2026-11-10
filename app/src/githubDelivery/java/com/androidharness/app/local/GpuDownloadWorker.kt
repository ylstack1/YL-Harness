package com.androidharness.app.local

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.*
import java.io.File
import java.io.FilterInputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

class GpuDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        var partial: File? = null
        try {
            val spec = gpuPackage(applicationContext)
            val target = gpuLibrary(applicationContext, spec)
            if (spec.verified(target)) return Result.success()
            target.parentFile!!.mkdirs()
            val staging = File(target.parentFile, "$id.part")
            partial = staging
            val call = client.newCall(Request.Builder().url("$RELEASE/${spec.asset}").header("Accept-Encoding", "identity").build())
            active = call
            try {
                call.execute().use { response ->
                    check(response.isSuccessful) { "GPU download failed (HTTP ${response.code}). Please retry when this app's GPU package is available." }
                    val body = checkNotNull(response.body)
                    check(body.contentLength() == -1L || body.contentLength() == spec.downloadBytes) { "GPU package size does not match this app version." }
                    val input = object : FilterInputStream(body.byteStream()) {
                        var count = 0L
                        override fun read(): Int = super.read().also { if (it >= 0) count++; check(count <= spec.downloadBytes) }
                        override fun read(b: ByteArray, off: Int, len: Int): Int = `in`.read(b, off, len).also { if (it > 0) count += it; check(count <= spec.downloadBytes) }
                    }
                    var written = 0L; var lastProgress = 0L
                    GZIPInputStream(input).use { source -> staging.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = source.read(buffer); if (n < 0) break
                            written += n; check(written <= spec.bytes) { "GPU package exceeds its expected size." }
                            output.write(buffer, 0, n)
                            if (System.nanoTime() - lastProgress > 250_000_000) {
                                setProgress(workDataOf("downloaded" to input.count)); lastProgress = System.nanoTime()
                            }
                        }
                        output.fd.sync()
                    } }
                    check(written == spec.bytes && input.count == spec.downloadBytes) { "GPU download is incomplete." }
                }
                setProgress(workDataOf("downloaded" to spec.downloadBytes, "installing" to true))
                currentCoroutineContext().ensureActive()
                check(spec.verified(staging)) { "GPU package verification failed. Please retry the download." }
                check(staging.setReadOnly()) { "Could not protect the GPU runtime." }
                check(staging.renameTo(target)) { "Could not install the GPU runtime." }
                return Result.success()
            } finally { call.cancel(); if (active === call) active = null }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { return Result.failure(workDataOf("error" to (e.message ?: "GPU download failed."))) }
        finally { partial?.delete() }
    }
    companion object {
        // Content-addressed assets are generated alongside the APK by prepareGpuRuntimeRelease.
        const val RELEASE = "https://github.com/Sanuu7/AH-GPU-Runtime/releases/download/1"
        private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).followSslRedirects(false).build()
        @Volatile private var active: Call? = null
        fun cancelActive() { active?.cancel() }
    }
}
