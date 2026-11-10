package com.androidharness.app.browser

import com.androidharness.app.browser.PageLoadTracker.Companion.didStart
import com.androidharness.app.browser.PageLoadTracker.Companion.isSettled
import com.androidharness.app.browser.PageLoadTracker.DocumentProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision the browser actions depend on: did my navigation begin, and is
 * its document on screen yet?
 *
 * Both used to be answered from WebView.url, which goes provisional the moment
 * a navigation STARTS, plus fixed delays. On-device QA (2026-09-17) drove a 6s
 * endpoint and browser_navigate reported the PREVIOUS page (URL and full DOM)
 * as a success after 0.9s. The cases below pin that apart from a real commit.
 */
class PageLoadTrackerTest {

    private val slowPage = "http://localhost:8080/slow"
    private val previousPage = "http://localhost:8080/"

    @Test
    fun `a started but unfinished navigation is not settled`() {
        val tracker = PageLoadTracker()
        val generation = tracker.currentGeneration
        tracker.onStarted()

        val snapshot = tracker.snapshot(DocumentProbe(url = previousPage, ready = true))
        assertTrue(didStart(snapshot, generation, previousPage))
        // The previous document is still the one on screen: this is exactly the
        // state that used to be published as a successful navigation.
        assertFalse(isSettled(snapshot, generation, previousPage))
    }

    @Test
    fun `the finished callback settles the navigation`() {
        val tracker = PageLoadTracker()
        val generation = tracker.currentGeneration
        tracker.onStarted()
        tracker.onFinished(slowPage)

        val snapshot = tracker.snapshot(DocumentProbe(url = slowPage, ready = true))
        assertTrue(isSettled(snapshot, generation, previousPage))
        assertEquals(slowPage, snapshot.committedUrl)
    }

    @Test
    fun `an unstarted action is detected as no navigation`() {
        val tracker = PageLoadTracker()
        val generation = tracker.currentGeneration
        val snapshot = tracker.snapshot(DocumentProbe(url = previousPage, ready = true))
        assertFalse(didStart(snapshot, generation, previousPage))
        // A download or a refused scheme ends here: no document ever came.
        assertFalse(isSettled(snapshot, generation, previousPage))
    }

    /**
     * A WebView whose client is not ours reports nothing, so the page itself is
     * the only witness. While the new document is still loading, the OLD
     * document answers the probe (its own href, readyState complete), so the
     * wait must continue; only a different, complete document counts.
     */
    @Test
    fun `a page probe settles only once a different document is complete`() {
        val tracker = PageLoadTracker()
        val generation = tracker.currentGeneration

        val stale = tracker.snapshot(DocumentProbe(url = previousPage, ready = true))
        assertFalse(didStart(stale, generation, previousPage))
        assertFalse(isSettled(stale, generation, previousPage))

        val partial = tracker.snapshot(DocumentProbe(url = slowPage, ready = false))
        assertTrue(didStart(partial, generation, previousPage))
        assertFalse("a document that is still loading is not settled", isSettled(partial, generation, previousPage))

        val committed = tracker.snapshot(DocumentProbe(url = slowPage, ready = true))
        assertTrue(isSettled(committed, generation, previousPage))
    }

    @Test
    fun `a main frame error finishes the wait instead of timing out`() {
        val tracker = PageLoadTracker()
        val generation = tracker.currentGeneration
        tracker.onStarted()
        tracker.onError("net::ERR_NAME_NOT_RESOLVED")

        val snapshot = tracker.snapshot()
        assertTrue(isSettled(snapshot, generation, previousPage))
        assertEquals("net::ERR_NAME_NOT_RESOLVED", snapshot.error)
        assertFalse(snapshot.loading)
    }

    @Test
    fun `a download report is consumed once so it cannot leak into the next action`() {
        val tracker = PageLoadTracker()
        tracker.onDownload("application/octet-stream named \"download.bin\" from http://localhost:8080/download.bin")
        assertEquals(
            "application/octet-stream named \"download.bin\" from http://localhost:8080/download.bin",
            tracker.takeDownload(),
        )
        assertNull(tracker.takeDownload())
    }

    /**
     * Some WebViews fire onPageStarted before the response turns out to be an
     * attachment, so a download has to end the wait on its own: otherwise the
     * action sits out its whole finish timeout for a document that can never
     * arrive.
     */
    @Test
    fun `a download ends the wait even after a navigation started`() {
        val tracker = PageLoadTracker()
        val generation = tracker.currentGeneration
        tracker.onStarted()
        tracker.onDownload("application/octet-stream")

        assertTrue(tracker.hasDownload)
        assertFalse(tracker.isLoading)
        assertTrue("a download must settle the wait", isSettled(tracker.snapshot(), generation, previousPage))
    }

    @Test
    fun `a new navigation clears the previous error but keeps a new download report`() {
        val tracker = PageLoadTracker()
        tracker.onStarted()
        tracker.onError("boom")
        tracker.clearDownload()
        tracker.onStarted()

        assertNull(tracker.snapshot().error)

        // The download analogue of the same ordering: navigation starts, and
        // only then does the response turn out to be an attachment. Clearing on
        // start would discard the only explanation the action ever gets.
        tracker.onStarted()
        tracker.onDownload("attachment")
        assertEquals("attachment", tracker.takeDownload())
        assertNull(tracker.takeDownload())
    }

    /**
     * back() must not act while a navigation is still in flight: a page that has
     * not committed yet has no history entry, so goBack() from it lands one
     * entry too far. The tracker is the signal that says "not yet".
     */
    @Test
    fun `an in-flight click navigation is visible to a history step`() {
        val tracker = PageLoadTracker()
        tracker.onStarted() // the click's navigation: still loading
        assertTrue(tracker.isLoading)

        val snapshot = tracker.snapshot(DocumentProbe(url = previousPage, ready = true))
        assertFalse("must wait: the clicked page owns no history entry yet", isSettled(snapshot, 0, previousPage))

        tracker.onFinished(slowPage)
        assertFalse(tracker.isLoading)
    }

    /**
     * WebView emits duplicate finishes for a single navigation. Each one used to
     * advance the marker, so a duplicate could settle a navigation that had not
     * happened yet and callers read the document being left behind.
     *
     * A late finish for an already-superseded navigation is NOT distinguished
     * here: the URL it carries is also the URL a redirect legitimately ends on.
     * That is why the history decision reads the WebView's own back/forward list
     * instead of this flag.
     */
    @Test
    fun `a duplicate finish does not settle the next navigation`() {
        val tracker = PageLoadTracker()
        tracker.onStarted()
        tracker.onFinished(previousPage)
        val generation = tracker.currentGeneration

        // A duplicate finish for the load that just completed.
        tracker.onFinished(previousPage)
        tracker.onFinished(previousPage)
        assertFalse(
            "a duplicate finish must not settle a navigation that has not happened",
            isSettled(tracker.snapshot(), generation, previousPage),
        )
        assertEquals(generation, tracker.snapshot().finishedGeneration)
    }

    @Test
    fun `generations advance per navigation so a stale finish cannot settle a new one`() {
        val tracker = PageLoadTracker()
        tracker.onStarted()
        tracker.onFinished("http://localhost:8080/first")
        val generation = tracker.currentGeneration

        tracker.onStarted()
        val snapshot = tracker.snapshot(DocumentProbe(url = "http://localhost:8080/first", ready = true))
        assertFalse(
            "the first page's finish must not settle the second navigation",
            isSettled(snapshot, generation, "http://localhost:8080/first"),
        )
    }
}
