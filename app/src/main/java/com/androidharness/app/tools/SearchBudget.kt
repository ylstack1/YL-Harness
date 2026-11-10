package com.androidharness.app.tools

import com.androidharness.app.data.SearchDepth
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Results to request from one search. Research levels pull a full batch and
 * ignore a smaller model count so the selected depth can actually reach its
 * budget; Instant never exceeds its five-source allowance.
 */
internal fun searchBatchCount(depth: SearchDepth?, rawCount: Int?, remaining: Int): Int {
    val maxPerCall = if (depth != null) 20 else 15
    val requested = when (depth) {
        SearchDepth.INSTANT -> (rawCount ?: 5).coerceAtMost(5)
        SearchDepth.DEEP,
        SearchDepth.DEEP_RESEARCH,
        SearchDepth.MAX_RESEARCH -> (rawCount ?: 20).coerceAtLeast(20)
        else -> rawCount ?: 8
    }
    return requested.coerceIn(1, maxPerCall).coerceAtMost(remaining)
}

/** Pages a research level may read before the turn has to answer. */
internal fun researchReadLimit(depth: SearchDepth?): Int? = when (depth) {
    SearchDepth.DEEP -> 10
    SearchDepth.DEEP_RESEARCH -> 25
    SearchDepth.MAX_RESEARCH -> 40
    else -> null
}

/** Pages a research level should read before answering; 0 means no requirement. */
internal fun researchReadMinimum(depth: SearchDepth?): Int = when (depth) {
    SearchDepth.DEEP -> 2
    SearchDepth.DEEP_RESEARCH -> 5
    SearchDepth.MAX_RESEARCH -> 8
    else -> 0
}

/**
 * Keeps a batch from being dominated by one site. The first [maxPerHost]
 * results per host survive in their original order.
 */
internal fun diversifyResults(results: List<WebSearchResult>, maxPerHost: Int = 3): List<WebSearchResult> {
    if (maxPerHost <= 0) return results
    val perHost = HashMap<String, Int>()
    return results.filter { result ->
        val host = runCatching { result.url.toHttpUrlOrNull()?.host }.getOrNull() ?: return@filter false
        val seen = perHost[host] ?: 0
        if (seen >= maxPerHost) return@filter false
        perHost[host] = seen + 1
        true
    }
}

/**
 * Per-turn research allowance for Chat. Counts distinct result URLs and pages
 * read, so repeated hits from overlapping queries do not consume the budget
 * twice. A read budget bounds context growth; [minReads] is the level's
 * minimum before answering.
 */
class SearchSourceBudget(
    val maxSources: Int,
    val maxReads: Int? = null,
    val minReads: Int = 0,
) {
    private val seen = LinkedHashSet<String>()
    private var reads = 0
    private val readUrls = HashSet<String>()
    private val lock = Any()

    val used: Int get() = synchronized(lock) { seen.size }
    val remaining: Int get() = (maxSources - used).coerceAtLeast(0)
    val pagesRead: Int get() = synchronized(lock) { reads }

    /** True while another page read fits the limit. */
    fun canRead(url: String? = null): Boolean = synchronized(lock) {
        url?.let(::canonicalResultUrl) in readUrls || maxReads == null || reads < maxReads
    }

    /** Claims one page read, or false when the read limit is already used. */
    fun claimRead(url: String? = null): Boolean = synchronized(lock) {
        val key = url?.let(::canonicalResultUrl)
        if (key != null && key in readUrls) return true
        if (maxReads != null && reads >= maxReads) return false
        reads++
        if (key != null) readUrls += key
        true
    }

    fun receipt(url: String? = null, backend: String? = null) = synchronized(lock) {
        com.androidharness.app.core.ResearchReceipt(seen.size, maxSources, reads, minReads,
            pageUrl = url, pageRead = url != null, backend = backend)
    }

    /** Results that still fit the budget, with their URLs recorded as used. */
    fun claim(results: List<WebSearchResult>): List<WebSearchResult> = synchronized(lock) {
        if (seen.size >= maxSources) return emptyList()
        val room = maxSources - seen.size
        val out = ArrayList<WebSearchResult>(minOf(room, results.size))
        for (result in results) {
            if (out.size >= room) break
            val key = canonicalResultUrl(result.url)
            if (key.isEmpty() || !seen.add(key)) continue
            out += result
        }
        out
    }
}
