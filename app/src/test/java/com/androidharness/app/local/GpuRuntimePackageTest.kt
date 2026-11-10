package com.androidharness.app.local

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GpuRuntimePackageTest {
    private val hash = "a".repeat(64)
    private fun metadata(abi: String = "arm64-v8a", asset: String = "gpu-$abi-$hash.so.gz", size: Long = 1024) =
        """{"schema":1,"abi":"$abi","sha256":"$hash","bytes":$size,"downloadBytes":256,"asset":"$asset"}"""
    @Test fun wrongArchitectureAndUntrustedPathsAreRejected() {
        for (value in listOf(metadata("x86_64"), metadata(asset = "../../library.so"), metadata(size = 134217729))) {
            assertTrue(runCatching { GpuRuntimePackage.parse(value, "arm64-v8a") }.isFailure)
        }
    }
    @Test fun savedRuntimeMustMatchBothSizeAndBundledHash() {
        val file = File.createTempFile("gpu-runtime", ".so")
        try {
            file.writeText("trusted backend")
            val digest = GpuRuntimePackage.digest(file)
            val pack = GpuRuntimePackage("arm64-v8a", digest, file.length(), "gpu-arm64-v8a-$digest.so.gz", 20)
            assertTrue(pack.verified(file))
            file.writeText("changed backend") // Same length; size alone must never authorize native code.
            assertFalse(pack.verified(file))
            file.delete()
            assertFalse(pack.verified(file))
        } finally { file.delete() }
    }
}
