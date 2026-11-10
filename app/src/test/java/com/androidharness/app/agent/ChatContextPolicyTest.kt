package com.androidharness.app.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatContextPolicyTest {
    @Test fun `chat ceiling ignores the code setting and respects smaller models`() {
        assertEquals(250_000, ChatContextPolicy.limit(64_000, null, false))
        assertEquals(250_000, ChatContextPolicy.limit(1_000_000, 500_000, false))
        assertEquals(128_000, ChatContextPolicy.limit(1_000_000, 128_000, false))
        assertEquals(32_768, ChatContextPolicy.limit(32_768, null, true))
    }

    @Test fun `compaction reserves space for the reply`() {
        assertEquals(237_500, ChatContextPolicy.compactAt(250_000, 2_000))
        assertEquals(218_000, ChatContextPolicy.compactAt(250_000, 32_000))
    }
}
