package com.androidharness.app.local

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalBenchmarkTest {
    @Test fun threadCandidatesAreUniqueAndRespectDeviceCores() {
        assertEquals(listOf(1), localBenchmarkThreadCounts(1, 8))
        assertEquals(listOf(1, 2, 4, 8), localBenchmarkThreadCounts(8, 4))
        assertEquals(listOf(1, 2, 4, 8, 12), localBenchmarkThreadCounts(12, 12))
        assertEquals(listOf(1, 2, 4, 6), localBenchmarkThreadCounts(6, 100))
        assertEquals(listOf(1, 2, 3, 4, 8), localBenchmarkThreadCounts(8, 4, 3))
    }
}
