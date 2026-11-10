package com.androidharness.app.local

import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest

/** Metadata is generated from this build's backend and bundled in the base APK. */
data class GpuRuntimePackage(val abi: String, val sha256: String, val bytes: Long, val asset: String, val downloadBytes: Long) {
    init {
        require(abi in setOf("arm64-v8a", "x86_64"))
        require(sha256.matches(Regex("[a-f0-9]{64}")))
        require(bytes in 1..134_217_728 && downloadBytes in 1..67_108_864)
        require(asset == "gpu-$abi-$sha256.so.gz")
    }
    fun verified(file: File): Boolean = file.isFile && file.length() == bytes && digest(file) == sha256
    companion object {
        fun parse(text: String, abi: String): GpuRuntimePackage {
            val item = Json.parseToJsonElement(text).jsonObject
            require(item.getValue("schema").jsonPrimitive.int == 1)
            return GpuRuntimePackage(item.getValue("abi").jsonPrimitive.content, item.getValue("sha256").jsonPrimitive.content,
                item.getValue("bytes").jsonPrimitive.long, item.getValue("asset").jsonPrimitive.content,
                item.getValue("downloadBytes").jsonPrimitive.long).also { require(it.abi == abi) }
        }
        fun digest(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
