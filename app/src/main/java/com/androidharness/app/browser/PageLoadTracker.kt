package com.androidharness.app.browser

/**
 * Navigation lifecycle of the WebView the agent is driving, fed by the
 * WebViewClient callbacks of whichever view is active (the headless one, or the
 * preview sheet's, which forwards here).
 *
 * Every mutating action used to answer "is the new page there yet?" from
 * `WebView.url` plus fixed delays and a URL-stability guess. `WebView.url` goes
 * provisional the moment a navigation STARTS, so a slow endpoint looked like a
 * finished load: on-device QA pointed browser_navigate at a 6s endpoint, got
 * the previous document's URL and DOM back in 0.9s, and only the server log
 * showed that the navigation had really happened.
 *
 * The generation counter below separates the two facts an action needs:
 * a navigation BEGAN ([didStart]), and the document it produced has COMMITTED
 * and finished loading ([isSettled]). Callbacks supply both; when a WebView's
 * client is not ours the page itself is asked instead, because the in-page
 * `location.href` changes at commit while `WebView.url` changes at start.
 */
internal class PageLoadTracker {

    /** The page's own view of its document, read from inside the page. */
    data class DocumentProbe(val url: String?, val ready: Boolean)

    /** Everything the settle decision needs, so it can be tested as pure logic. */
    data class Snapshot(
        val generation: Int,
        val finishedGeneration: Int,
        val loading: Boolean,
        val committedUrl: String?,
        val documentUrl: String? = null,
        val documentReady: Boolean = false,
        val error: String? = null,
    )

    @Volatile
    private var generation = 0

    @Volatile
    private var finishedGeneration = 0

    @Volatile
    private var loading = false

    @Volatile
    private var committedUrl: String? = null

    /** The URL the in-flight navigation was started for, from onPageStarted. */
    @Volatile
    private var startedUrl: String? = null

    @Volatile
    private var error: String? = null

    @Volatile
    private var download: String? = null

    /** Baseline an action captures before it triggers a navigation. */
    val currentGeneration: Int get() = generation

    val isLoading: Boolean get() = loading

    /** A main-frame navigation began: onPageStarted. */
    fun onStarted() {
        generation++
        loading = true
        error = null
        // A download report is NOT cleared here: a main-frame navigation starts
        // first and only then does the response turn out to be an attachment,
        // so clearing on start would throw away the only explanation the action
        // gets. It is cleared when the next action targets the WebView.
    }

    /** Forgets the previous action's download report. */
    fun clearDownload() {
        download = null
    }

/**
     * The main-frame document finished loading: onPageFinished.
     *
     * Only counted while a load is in flight, so the duplicate finishes WebView
     * emits for one navigation cannot each advance the marker and settle a
     * navigation that has not happened.
     *
     * A finish belonging to an already-superseded navigation cannot be told
     * apart from a real one here: the URL it carries is also the URL a redirect
     * legitimately ends on, so matching on it would refuse to settle redirecting
     * pages. Callers that must know where the browser really is therefore use
     * the WebView's own back/forward list rather than this flag (see
     * [HistoryStep]).
     */
    fun onFinished(url: String?) {
        if (!loading) return
        finishedGeneration = generation
        loading = false
        if (!url.isNullOrBlank()) committedUrl = url
    }

    /** The main-frame navigation failed: onReceivedError(isForMainFrame). */
    fun onError(description: String?) {
        error = description
        loading = false
        // An error page is still a committed document; without this the caller
        // would wait out the whole finish timeout for a page that never loads.
        finishedGeneration = generation
    }

    /**
     * The main-frame response is a download rather than a document, so no page
     * will ever appear. Reported verbatim: "no page is coming" is only useful
     * with the reason attached. Terminal for the wait, because some WebViews
     * still fire onPageStarted first and would otherwise leave the action
     * waiting out its whole finish timeout for a page that cannot arrive.
     */
    fun onDownload(description: String) {
        download = description
        loading = false
        finishedGeneration = generation
    }

    /** True while an unexplained download is on record. */
    val hasDownload: Boolean get() = download != null

    /** Reads and clears the pending download report, so it cannot leak across actions. */
    fun takeDownload(): String? {
        val reported = download
        download = null
        return reported
    }

    fun snapshot(probe: DocumentProbe? = null): Snapshot = Snapshot(
        generation = generation,
        finishedGeneration = finishedGeneration,
        loading = loading,
        committedUrl = committedUrl,
        documentUrl = probe?.url,
        documentReady = probe?.ready == true,
        error = error,
    )

    companion object {
        /**
         * True when the action's navigation actually began. The client callback
         * is the fast signal; a committed document that differs from the one
         * the action started on covers a WebView whose client is not ours.
         */
        fun didStart(snapshot: Snapshot, generationBefore: Int, urlBefore: String?): Boolean =
            snapshot.generation > generationBefore ||
                documentChanged(snapshot, urlBefore)

        /**
         * True when the document the action triggered is on screen and done
         * loading. A committed-then-finished callback is authoritative. The
         * in-page fallback requires a DIFFERENT document that reports
         * readyState complete: while a page is still loading the previous
         * document keeps reporting its own href, which is exactly the stale
         * state that used to be published as a successful navigation.
         */
        fun isSettled(snapshot: Snapshot, generationBefore: Int, urlBefore: String?): Boolean {
            if (snapshot.finishedGeneration > generationBefore) return true
            if (snapshot.documentUrl == null || !snapshot.documentReady) return false
            return urlBefore == null || documentChanged(snapshot, urlBefore)
        }

        private fun documentChanged(snapshot: Snapshot, urlBefore: String?): Boolean =
            urlBefore != null &&
                snapshot.documentUrl != null &&
                snapshot.documentUrl != urlBefore
    }
}