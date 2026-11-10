package com.androidharness.app.local

import androidx.annotation.Keep

@Keep
object LocalNative {
    init {
        System.loadLibrary("harness_local")
        if (android.os.Process.is64Bit()) check(loadBackend("libggml-cpu.so")) { "CPU inference backend could not load." }
    }
    external fun loadBackend(path: String): Boolean
    external fun gpuDevice(): String
    external fun releaseCachedModel()
    external fun isModelCached(path: ByteArray): Boolean
    external fun cachedModelPath(): String
    external fun loadModel(handle: Long, path: ByteArray, gpu: Boolean)
    external fun benchmark(handle: Long, path: ByteArray, gpu: Boolean, threads: Int, promptThreads: Int, callback: BenchmarkCallback, prompt: ByteArray = "What is 17 + 25? Reply with only the number.".toByteArray(Charsets.UTF_8)): ByteArray
    external fun create(): Long
    external fun isCancelled(handle: Long): Boolean
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
    external fun generateChat(handle: Long, path: ByteArray, projector: ByteArray, request: ByteArray,
        images: Array<ByteArray>, context: Int, input: Int, output: Int, threads: Int, promptThreads: Int, gpu: Boolean, cacheKey: ByteArray, callback: ChatCallback): ByteArray
    external fun convertSafetensors(handle: Long, directory: ByteArray, output: ByteArray, threads: Int)
    external fun generate(
        handle: Long,
        path: ByteArray,
        roles: Array<String>,
        contents: Array<ByteArray>,
        context: Int,
        input: Int,
        output: Int,
        threads: Int,
        callback: Callback,
    ): IntArray

    @Keep
    interface Callback {
        fun onToken(bytes: ByteArray): Boolean
    }

    @Keep
    interface ChatCallback {
        fun onEvent(bytes: ByteArray): Boolean
        fun onWarning(bytes: ByteArray): Boolean
    }
    @Keep
    interface BenchmarkCallback {
        fun onOutput(bytes: ByteArray): Boolean = true
        fun onProgress(phase: String, tokens: Int, seconds: Double): Boolean
    }
}
