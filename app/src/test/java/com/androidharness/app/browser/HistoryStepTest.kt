package com.androidharness.app.browser

import com.androidharness.app.browser.HistoryStepOutcome.Bounce
import com.androidharness.app.browser.HistoryStepOutcome.Correct
import com.androidharness.app.browser.HistoryStepOutcome.Landed
import com.androidharness.app.browser.HistoryStepOutcome.Stalled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The history-step decision, from the on-device failure of 2026-09-17.
 *
 * Fixture: `Apage.html?X` links to `Bpage.html`, which links back. Four trials
 * of navigate A → click link → back landed on the entry from an EARLIER control
 * test (`A?CTL`) every time, three entries back rather than one, while the
 * navigate → navigate → back control passed. History depth went 3 → 4 during
 * the click, so the list behaviour was right and the STEP was over-applied.
 *
 * What produced that, pinned here:
 *  - the landing was read from the page's own URL, which while a navigation is
 *    in flight still reports the document being left, so every step looked
 *    unmoved;
 *  - an unmoved-looking landing was retried, and each retry ate an entry;
 *  - the URL comparison dropped the query, so `?p=T1` and `?p=CTL` were "the
 *    same page".
 */
class HistoryStepTest {

    private val aT1 = "http://localhost:8790/Apage.html?p=T1"
    private val aCtl = "http://localhost:8790/Apage.html?p=CTL"
    private val b = "http://localhost:8790/Bpage.html"

    @Test
    fun `a query string distinguishes two history entries`() {
        assertFalse(sameDocument(aT1, aCtl))
        assertFalse(sameDocument(aT1, "http://localhost:8790/Apage.html"))
        assertFalse(sameDocument(aT1, "$aT1#section"))
    }

    @Test
    fun `the same document is still recognised across cosmetic differences`() {
        assertTrue(sameDocument(aT1, aT1))
        assertTrue(sameDocument(aT1, "$aT1/"))
        assertTrue(sameDocument(aT1, "http://localhost:8790/Apage.html?p=T1?"))
        assertTrue(sameDocument("HTTP://LOCALHOST:8790/Apage.html?p=T1", aT1))
        assertTrue(sameDocument("  $aT1  ", aT1))
    }

    @Test
    fun `the old query-stripping comparison would have collapsed the failing pair`() {
        assertTrue("this pair is what the old code treated as one page", aT1.substringBefore('?') == aCtl.substringBefore('?'))
        assertFalse("the new comparison must keep them apart", sameDocument(aT1, aCtl))
    }

    /** Navigate A → click B → back: the WebView reports entry 2, the page just left. */
    @Test
    fun `a completed step onto the previous entry is the landing`() {
        val decision = decideHistoryStep(
            startUrl = b,
            targetIndex = 2,
            position = HistoryPosition(index = 2, size = 4, url = aT1),
            reachedTarget = true,
            back = true,
            attempts = 1,
            maxAttempts = 5,
            canStepFurther = true,
        )
        assertEquals(Landed(2, aT1), decision)
    }

    /**
     * The reported failure was the step being repeated. A repeat is only allowed
     * when the WebView positively reports it is standing on the entry the step
     * began from AND that landing finished loading.
     */
    @Test
    fun `a settled return to the starting entry is the only retry`() {
        val decision = decideHistoryStep(
            startUrl = aCtl,
            targetIndex = 2,
            position = HistoryPosition(index = 3, size = 4, url = aCtl),
            reachedTarget = true,
            back = true,
            attempts = 1,
            maxAttempts = 5,
            canStepFurther = true,
        )
        assertEquals(Bounce, decision)
    }

    /**
     * A landing too old to tell whether it finished must NOT be retried: this is
     * the path that walked the list from entry 3 to entry 0 in four trials.
     */
    @Test
    fun `a landing that never arrived is stalled not retried`() {
        val decision = decideHistoryStep(
            startUrl = b,
            targetIndex = 2,
            position = HistoryPosition(index = 3, size = 4, url = b),
            reachedTarget = false,
            back = true,
            attempts = 1,
            maxAttempts = 5,
            canStepFurther = true,
        )
        assertTrue(decision is Stalled)
    }

    /** The whole reported symptom: never stepping again once the target is reached. */
    @Test
    fun `stalling does not consume further entries`() {
        for (attempts in 1..5) {
            val decision = decideHistoryStep(
                startUrl = b,
                targetIndex = 2,
                position = HistoryPosition(index = 3, size = 4, url = b),
                reachedTarget = false,
                back = true,
                attempts = attempts,
                maxAttempts = 5,
                canStepFurther = true,
            )
            assertTrue("attempt $attempts must not step again", decision is Stalled)
        }
    }

    @Test
    fun `a WebView that skipped entries is walked back to the intended one`() {
        val decision = decideHistoryStep(
            startUrl = b,
            targetIndex = 2,
            position = HistoryPosition(index = 0, size = 4, url = aCtl),
            reachedTarget = false,
            back = true,
            attempts = 1,
            maxAttempts = 5,
            canStepFurther = true,
        )
        assertEquals(Correct(2), decision)
    }

    @Test
    fun `a forward step that overshot is corrected the other way`() {
        val decision = decideHistoryStep(
            startUrl = aT1,
            targetIndex = 2,
            position = HistoryPosition(index = 4, size = 5, url = aCtl),
            reachedTarget = false,
            back = false,
            attempts = 1,
            maxAttempts = 5,
            canStepFurther = true,
        )
        assertEquals(Correct(2), decision)
    }

    @Test
    fun `a bounce stops at the attempt limit`() {
        val decision = decideHistoryStep(
            startUrl = aCtl,
            targetIndex = 2,
            position = HistoryPosition(index = 3, size = 4, url = aCtl),
            reachedTarget = true,
            back = true,
            attempts = 5,
            maxAttempts = 5,
            canStepFurther = true,
        )
        assertTrue(decision is Stalled)
    }

    @Test
    fun `no further entry to bounce to ends the loop`() {
        val decision = decideHistoryStep(
            startUrl = aCtl,
            targetIndex = 0,
            position = HistoryPosition(index = 1, size = 4, url = aCtl),
            reachedTarget = true,
            back = true,
            attempts = 1,
            maxAttempts = 5,
            canStepFurther = false,
        )
        assertTrue(decision is Stalled)
    }

    @Test
    fun `a landing with no URL is stalled rather than reported as success`() {
        val decision = decideHistoryStep(
            startUrl = b,
            targetIndex = 2,
            position = HistoryPosition(index = 2, size = 4, url = null),
            reachedTarget = true,
            back = true,
            attempts = 1,
            maxAttempts = 5,
            canStepFurther = true,
        )
        assertTrue(decision is Stalled)
    }

    @Test
    fun `the target index is one entry in the direction of travel`() {
        assertEquals(2, targetHistoryIndex(3, back = true))
        assertEquals(4, targetHistoryIndex(3, back = false))
        assertEquals(-1, targetHistoryIndex(0, back = true))
    }
}