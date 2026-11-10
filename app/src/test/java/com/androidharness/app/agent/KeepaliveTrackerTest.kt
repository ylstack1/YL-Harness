package com.androidharness.app.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class KeepaliveTrackerTest {
    private val events = mutableListOf<String>()
    private val tracker = KeepaliveTracker(
        { events += "acquire" }, { events += "release" }, { events += "active=$it" },
    )

    @Test fun `background service never signals an active task`() {
        tracker.acquire(false)
        tracker.acquire(false)
        tracker.release(false)
        assertEquals(listOf("acquire"), events)
        tracker.release(false)
        assertEquals(listOf("acquire", "release"), events)
    }

    @Test fun `task finishes while background service keeps its wake lease`() {
        tracker.acquire(false)
        tracker.acquire()
        tracker.release()
        assertEquals(listOf("acquire", "active=true", "active=false"), events)
        tracker.release(false)
        assertEquals("release", events.last())
    }

    @Test fun `background service stopping does not hide active work`() {
        tracker.acquire()
        tracker.acquire(false)
        tracker.release(false)
        assertEquals(listOf("active=true", "acquire"), events)
        tracker.release()
        assertEquals(listOf("active=true", "acquire", "active=false", "release"), events)
    }

    @Test fun `parallel tasks stay active until the last task finishes`() {
        tracker.acquire()
        tracker.acquire()
        tracker.release()
        assertEquals(listOf("active=true", "acquire"), events)
        tracker.release()
        assertEquals(listOf("active=true", "acquire", "active=false", "release"), events)
    }

    @Test fun `unmatched releases do not consume the other kind of lease`() {
        tracker.release()
        tracker.acquire(false)
        tracker.release()
        assertEquals(listOf("acquire"), events)
        tracker.release(false)
        tracker.release(false)
        tracker.acquire()
        tracker.release(false)
        tracker.release()
        assertEquals(listOf("acquire", "release", "active=true", "acquire", "active=false", "release"), events)
    }
}
