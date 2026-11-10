package com.androidharness.app.local

import kotlinx.serialization.Serializable

@Serializable
enum class LocalBackend { AUTO, CPU, GPU }

/** Runtime capability decides availability, independent of phone brand or chipset. */
object LocalAccelerationPolicy {
    const val ISSUES_URL = "https://github.com/Sanuu7/AndroidHarness/issues/new?title=%5BGGUF%20GPU%5D%20&body=Phone%20model%3A%20%0AAndroid%20version%3A%20%0AModel%20link%3A%20%0AGPU%20error%3A%20"
    fun useGpu(experimental: Boolean, preference: LocalBackend, available: Boolean): Boolean =
        experimental && preference != LocalBackend.CPU && available

    fun retryOnCpu(gpu: Boolean, outputStarted: Boolean, cancelled: Boolean): Boolean =
        gpu && !outputStarted && !cancelled
}
