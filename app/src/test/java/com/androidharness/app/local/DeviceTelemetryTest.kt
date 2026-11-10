package com.androidharness.app.local

import org.junit.Assert.*
import org.junit.Test

class DeviceTelemetryTest {
    @Test fun cpuRangesSupportSparseOnlineCores() {
        assertEquals(setOf(0, 1, 2, 3, 6, 8, 9), parseCpuRange("0-3,6,8-9"))
        assertEquals(emptySet<Int>(), parseCpuRange("invalid,9-2,-1"))
    }
    @Test fun cpuTicksExcludeGuestCountersFromTotal() {
        val ticks = parseCpuTicks("cpu 100 20 30 400 10 5 6 7 50 20\ncpu0 10 0 5 20\nintr 9\ncpu1 broken data")
        assertEquals(578L to 410L, ticks["cpu"])
        assertEquals(35L to 20L, ticks["cpu0"])
        assertFalse(ticks.containsKey("cpu1"))
        assertTrue(parseCpuTicks("").isEmpty())
    }
}
