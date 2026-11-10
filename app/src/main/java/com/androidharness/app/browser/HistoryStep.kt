package com.androidharness.app.browser

/**
 * Where the WebView is in its own back/forward list.
 *
 * [index] and [url] come straight from `WebView.copyBackForwardList()`, which is
 * why they can be trusted where reading `location.href` could not: the page's
 * own URL only changes when a document COMMITS, so a read taken while a
 * navigation was still in flight returned the document being left behind, and
 * the history step looked like it had failed to move.
 */
internal data class HistoryPosition(val index: Int, val size: Int, val url: String?)

/** What to do after one step through WebView history. */
internal sealed interface HistoryStepOutcome {
    /** Landed on the entry the step aimed for. */
    data class Landed(val index: Int, val url: String?) : HistoryStepOutcome

    /** The list moved somewhere else entirely: step back to [index] and use it. */
    data class Correct(val index: Int) : HistoryStepOutcome

    /**
     * A completed step returned to the entry it started from: a redirect
     * bounced us forward, so the entry behind that one is the real destination.
     */
    data object Bounce : HistoryStepOutcome

    /** Nothing provable happened; report the current state without stepping again. */
    data class Stalled(val reason: String) : HistoryStepOutcome
}

/**
 * Decides the next move after a history step.
 *
 * [reachedTarget] means the back/forward list was OBSERVED at the target entry,
 * even if it has since moved off it. That distinction is what makes a redirect
 * bounce safe to retry: the entry really was visited and the browser came back
 * on its own, so stepping again cannot skip anything. Without the observation a
 * retry would be a guess, and on-device QA (2026-09-17, four trials) showed
 * what a guessed retry costs: `back()` after a click-driven navigation landed
 * three entries behind every time, on the entry from an earlier control test,
 * because each step's landing could not be read and every step was retried.
 */
internal fun decideHistoryStep(
    startUrl: String,
    targetIndex: Int,
    position: HistoryPosition,
    reachedTarget: Boolean,
    back: Boolean,
    attempts: Int,
    maxAttempts: Int,
    canStepFurther: Boolean,
): HistoryStepOutcome {
    if (!reachedTarget) {
        // Past the entry it was aiming for: a WebView that skipped entries gets
        // walked back to the intended one rather than silently returning a page
        // the caller never asked to land on.
        val overshot = if (back) position.index < targetIndex else position.index > targetIndex
        return when {
            position.index == targetIndex -> HistoryStepOutcome.Landed(position.index, position.url)
            overshot -> HistoryStepOutcome.Correct(targetIndex)
            else -> HistoryStepOutcome.Stalled(
                "the history step did not complete in time (still at entry ${position.index} of ${position.size})",
            )
        }
    }

    val landed = position.url
    if (landed.isNullOrBlank()) {
        return HistoryStepOutcome.Stalled("the browser did not report a URL for history entry ${position.index}")
    }
    // Standing on the entry the step started from, having been observed at the
    // target in between: the browser bounced back on its own, so stepping again
    // targets the entry behind this one without skipping anything.
    if (!sameDocument(landed, startUrl)) {
        return HistoryStepOutcome.Landed(position.index, landed)
    }
    if (attempts >= maxAttempts) {
        return HistoryStepOutcome.Stalled("the page kept returning to '$startUrl' after $attempts attempts")
    }
    if (!canStepFurther) {
        return HistoryStepOutcome.Stalled("there is no further entry to step to before '$startUrl'")
    }
    return HistoryStepOutcome.Bounce
}

/**
 * True when two URLs address the same document. The query string and fragment
 * are part of a document's identity: `/Apage.html?p=T1` and `/Apage.html?p=CTL`
 * are different history entries, and treating them as one made every step look
 * like it had failed to move. Only a trailing slash and an empty query are
 * ignored.
 */
internal fun sameDocument(urlA: String, urlB: String): Boolean {
    fun normalize(url: String): String {
        val trimmed = url.trim().removeSuffix("/")
        return if (trimmed.endsWith("?")) trimmed.dropLast(1) else trimmed
    }
    return normalize(urlA).equals(normalize(urlB), ignoreCase = true)
}

/**
 * The entry a step from [start] aims for: one back for "back", one forward for
 * "forward". Null when there is no such entry, which is what `canStepFurther`
 * already reports.
 */
internal fun targetHistoryIndex(startIndex: Int, back: Boolean): Int =
    if (back) startIndex - 1 else startIndex + 1