package com.androidharness.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveUpdateShortTextTest {

    @Test
    fun `known run states map to concise chip labels`() {
        assertEquals(R.string.ui_live_short_approval, liveUpdateShortLabel("Waiting for your approval"))
        assertEquals(R.string.ui_live_short_answer, liveUpdateShortLabel("Waiting for your answer"))
        assertEquals(R.string.ui_live_short_installing, liveUpdateShortLabel("Installing Linux environment"))
        assertEquals(R.string.ui_live_short_attention, liveUpdateShortLabel("Linux environment needs your attention"))
        assertEquals(R.string.ui_live_short_retrying, liveUpdateShortLabel("Retrying in 5s (attempt 2): timeout"))
        assertEquals(R.string.ui_live_short_thinking, liveUpdateShortLabel("Thinking…"))
        assertEquals(R.string.ui_live_short_writing, liveUpdateShortLabel("Writing response…"))
        assertEquals(R.string.ui_live_short_generating, liveUpdateShortLabel("Generating code…"))
        assertEquals(R.string.ui_live_short_working, liveUpdateShortLabel("Working…"))
        assertEquals(R.string.ui_live_short_running, liveUpdateShortLabel("Running in background"))
        assertEquals(R.string.ui_live_short_running, liveUpdateShortLabel(""))
    }

    @Test
    fun `tool actions fall back to a truncated first line`() {
        assertNull(liveUpdateShortLabel("Reading build.gradle.kts"))
        assertEquals("Reading build.gradle.kts", liveUpdateFallbackText("Reading build.gradle.kts\nsecond line"))
        assertEquals("123456789012345678901234", liveUpdateFallbackText("123456789012345678901234567890"))
    }
}
