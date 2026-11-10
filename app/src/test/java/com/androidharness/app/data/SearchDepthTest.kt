package com.androidharness.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchDepthTest {
    @Test fun autoIsTheDefaultAndHasNoLimit() {
        assertEquals(SearchDepth.AUTO, SearchDepth.fromStored(null))
        assertEquals(SearchDepth.AUTO, SearchDepth.fromStored("unknown"))
        assertNull(SearchDepth.AUTO.maxSources)
    }

    @Test fun everyNamedLevelKeepsItsSourceCap() {
        assertEquals(0, SearchDepth.OFF.maxSources)
        assertEquals(5, SearchDepth.INSTANT.maxSources)
        assertEquals(20, SearchDepth.DEEP.maxSources)
        assertEquals(50, SearchDepth.DEEP_RESEARCH.maxSources)
        assertEquals(100, SearchDepth.MAX_RESEARCH.maxSources)
    }

    @Test fun storedNamesRoundTrip() {
        for (depth in SearchDepth.entries) {
            assertEquals(depth, SearchDepth.fromStored(depth.name))
        }
    }

    @Test fun settingsDefaultToAuto() {
        assertEquals(SearchDepth.AUTO, AppSettings().searchDepth)
        assertTrue(AppSettings().memoryEnabled)
    }
}
