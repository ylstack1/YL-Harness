package com.androidharness.app.local

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class LocalAccelerationPolicyTest {
    @Test fun `GPU is capability based and requires experimental consent`() {
        for (experimental in listOf(false, true)) for (driver in listOf(false, true)) {
            assertEquals(experimental && driver, LocalAccelerationPolicy.useGpu(experimental, LocalBackend.AUTO, driver))
            assertEquals(experimental && driver, LocalAccelerationPolicy.useGpu(experimental, LocalBackend.GPU, driver))
            assertFalse(LocalAccelerationPolicy.useGpu(experimental, LocalBackend.CPU, driver))
        }
    }
    @Test fun `GPU startup failure may retry on CPU`() {
        assertTrue(LocalAccelerationPolicy.retryOnCpu(true, false, false))
        assertFalse(LocalAccelerationPolicy.retryOnCpu(false, false, false))
    }
    @Test fun `partial output and cancellation prohibit fallback`() {
        for (gpu in listOf(false, true)) {
            assertFalse(LocalAccelerationPolicy.retryOnCpu(gpu, true, false))
            assertFalse(LocalAccelerationPolicy.retryOnCpu(gpu, false, true))
            assertFalse(LocalAccelerationPolicy.retryOnCpu(gpu, true, true))
        }
    }
    @Test fun `saved CPU limits remain compatible`() {
        assertEquals(LocalBackend.AUTO, Json.decodeFromString<LocalModelLimits>("{\"threads\":2}").backend)
    }
}
