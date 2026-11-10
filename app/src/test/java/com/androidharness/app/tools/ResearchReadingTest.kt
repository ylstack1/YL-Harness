package com.androidharness.app.tools

import com.androidharness.app.data.SearchDepth
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class ResearchReadingTest {
    private val ctx = ToolContext(FileFs(File("/tmp")))
    private fun url() = "https://${UUID.randomUUID()}.test/article"
    private fun args(url: String, offset: Int = 0) = buildJsonObject { put("url", url); put("offset", offset) }
    private fun client(block: (Request) -> Response) = OkHttpClient.Builder().addInterceptor { block(it.request()) }.build()
    private fun response(req: Request, text: String, code: Int = 200, retryAfter: String? = null): Response =
        Response.Builder().request(req).code(code).message("fixture").protocol(Protocol.HTTP_1_1)
            .apply { retryAfter?.let { header("Retry-After", it) } }
            .body(text.toResponseBody("text/html".toMediaType())).build()
    @Before fun clear() { WebResultCache.clear() }

    @Test fun successfulReadsAndOffsetCacheHitsCountOneUniquePage() = runBlocking {
        val requests = AtomicInteger()
        val client = client { requests.incrementAndGet(); response(it, "<article>Actual useful page content.</article>") }
        val budget = SearchSourceBudget(100, 1, 8)
        val reader = WebFetchTool(client).forBudget(budget)
        val url = url()
        val first = reader.execute(args(url), ctx)
        val again = reader.execute(args("$url?utm_source=feed#top", 5), ctx)
        assertTrue(first.ok); assertTrue(again.ok)
        assertEquals(1, requests.get()); assertEquals(1, budget.pagesRead)
        assertTrue(again.research?.pageRead == true)
    }

    @Test fun emptyAndJavascriptShellsDoNotSpendTheReadAllowanceOrEnterTheCache() = runBlocking {
        for (body in listOf("<html><body></body></html>", "<html><body>Please enable JavaScript to continue.</body></html>")) {
            val url = url()
            val budget = SearchSourceBudget(100, 40, 8)
            val result = WebFetchTool(client { response(it, body) }, budget).execute(args(url), ctx)
            assertFalse(result.ok); assertEquals(0, budget.pagesRead)
            assertNull(WebResultCache.getPage(url))
        }
    }

    @Test fun serverCooldownIsSharedAcrossPagesAndDoesNotCountFailures() = runBlocking {
        val requests = AtomicInteger()
        val client = client { requests.incrementAndGet(); response(it, "Too many requests", 429, "120") }
        val url = url()
        val budget = SearchSourceBudget(100, 40, 8)
        val reader = WebFetchTool(client, budget)
        val first = reader.execute(args(url), ctx)
        val other = reader.execute(args("$url/other"), ctx)
        assertFalse(first.ok); assertFalse(other.ok)
        assertEquals(1, requests.get()); assertEquals(0, budget.pagesRead)
        assertTrue(first.output.contains("retry after 120 seconds"))
        assertTrue(other.output.contains("cooldown"))
    }

    @Test fun aShortTransientLimitCanRecoverOnceWithoutInflatingReads() = runBlocking {
        val requests = AtomicInteger()
        val client = client { if (requests.incrementAndGet() == 1) response(it, "Busy", 429, "0") else response(it, "<article>Recovered article content.</article>") }
        val budget = SearchSourceBudget(20, 10, 2)
        assertTrue(WebFetchTool(client, budget).execute(args(url()), ctx).ok)
        assertEquals(2, requests.get()); assertEquals(1, budget.pagesRead)
    }

    @Test fun accessCheckPagesReturnedWith200AreNotCachedAsArticles() = runBlocking {
        val url = url()
        val result = WebFetchTool(client { response(it, "<html><title>Just a moment...</title><body>Verify you are human</body></html>") },
            SearchSourceBudget(20, 10, 2)).execute(args(url), ctx)
        assertFalse(result.ok); assertNull(WebResultCache.getPage(url))
        assertNull(result.research)
        assertNull(ReadableWebPage.problem("This article explains HTTP 429 rate limits and how to handle them."))
        assertNotNull(ReadableWebPage.problem("reddit\nYou’ve been blocked by network security.\nTo continue, log in to your Reddit account or use your developer token."))
    }

    @Test fun tavilyKeyAutomaticallyReadsRedditThroughExtraction() = runBlocking {
        val key = "tvly-${UUID.randomUUID()}"
        val requested = mutableListOf<Request>()
        val source = "https://www.reddit.com/r/android/comments/fixture/post/"
        val client = client { request ->
            requested += request
            assertEquals("api.tavily.com", request.url.host)
            assertEquals("/extract", request.url.encodedPath)
            assertEquals("Bearer $key", request.header("Authorization"))
            val posted = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            assertTrue(posted.contains(source)); assertFalse(posted.contains(key))
            response(request, """{"results":[{"url":"$source","raw_content":"The actual public discussion text and replies.","images":[]}],"failed_results":[]}""")
        }
        val budget = SearchSourceBudget(100, 40, 8)
        val reader = WebFetchTool(client, searchApi = { SearchApiConfig("tavily", key) }).forBudget(budget)
        val read = reader.execute(args(source), ctx)
        assertTrue(read.output, read.ok)
        assertEquals("Tavily Extract", read.research?.backend)
        assertTrue(read.output.contains("actual public discussion"))
        assertEquals(1, budget.pagesRead); assertEquals(1, requested.size)
    }

    @Test fun tavilySearchRawContentIsReusedWithoutAnotherPaidExtraction() = runBlocking {
        val calls = AtomicInteger()
        val source = "https://www.reddit.com/r/android/comments/${UUID.randomUUID()}/post/"
        val config = SearchApiConfig("tavily", "tvly-${UUID.randomUUID()}")
        val client = client { request ->
            calls.incrementAndGet()
            assertEquals("/search", request.url.encodedPath)
            val posted = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            assertTrue(posted.contains("\"include_raw_content\":true"))
            response(request, """{"results":[{"title":"Discussion","url":"$source","content":"Snippet only","raw_content":"Full extracted discussion with enough actual detail."}]}""")
        }
        val search = WebSearchTool(client, { config }).forDepth(SearchDepth.MAX_RESEARCH)
        val result = search.execute(buildJsonObject { put("query", "unique ${UUID.randomUUID()}") }, ctx)
        assertTrue(result.ok); assertEquals(0, search.budget()!!.pagesRead)
        val read = WebFetchTool(client, searchApi = { config }).forBudget(search.budget()).execute(args(source), ctx)
        assertTrue(read.ok); assertTrue(read.output.contains("Full extracted discussion"))
        assertEquals(1, search.budget()!!.pagesRead); assertEquals(1, calls.get())
    }

    @Test fun freshSavedProviderIsUsedInsteadOfAStaleStartupSnapshotAndItsKeyIsNotExposed() = runBlocking {
        val key = "tvly-${UUID.randomUUID()}"
        val client = client { request ->
            assertEquals("api.tavily.com", request.url.host)
            assertEquals("Bearer $key", request.header("Authorization"))
            response(request, """{"results":[{"title":"Result","url":"https://example.org/page","content":"Text"}]}""")
        }
        val search = WebSearchTool(client, searchApi = { null }, refreshApi = { SearchApiConfig("tavily", key) })
            .forDepth(SearchDepth.MAX_RESEARCH)
        assertTrue(search.configurationNote().contains("saved API key is available"))
        assertFalse(search.configurationNote().contains(key))
        val result = search.execute(buildJsonObject { put("query", "unique ${UUID.randomUUID()}") }, ctx)
        assertTrue(result.output, result.ok); assertTrue(result.output.contains("Tavily"))
    }

    @Test fun exhaustedTavilyCreditsDoNotTriggerRepeatedRequestsOrAnonymousFallback() = runBlocking {
        val calls = AtomicInteger()
        val config = SearchApiConfig("tavily", "tvly-${UUID.randomUUID()}")
        val search = WebSearchTool(client { request ->
            calls.incrementAndGet(); assertEquals("api.tavily.com", request.url.host)
            response(request, "Quota exhausted", 432)
        }, { config }).forDepth(SearchDepth.MAX_RESEARCH)
        for (query in listOf("angle one", "angle two")) {
            val result = search.execute(buildJsonObject { put("query", query) }, ctx)
            assertFalse(result.ok); assertTrue(result.output.contains("credit or plan limit"))
        }
        assertEquals(1, calls.get())
    }

    @Test fun retryAfterSupportsHttpDatesAndRedditSubdomainsShareTheSameGate() = runBlocking {
        val now = 1600000000000L
        val date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(java.time.Instant.ofEpochMilli(now + 60000).atZone(java.time.ZoneOffset.UTC))
        assertEquals(60000L, ResearchRequestPolicy.retryAfterMillis(date, now))
        var time = 0L
        val policy = ResearchRequestPolicy(spacingMs = 1000, clock = { time }, wallClock = { now }, pause = { time += it })
        policy.access("https://www.reddit.com/a".toHttpUrl()) { policy.cooldown("https://www.reddit.com/a".toHttpUrl(), 429, "60") }
        try { policy.access("https://old.reddit.com/b".toHttpUrl()) { fail("Cooldown must prevent a second Reddit request") }; fail("Expected a cooldown") }
        catch (e: ResearchHttpFailure) { assertEquals(429, e.status) }
        policy.access("https://independent.example.org".toHttpUrl()) { Unit }
    }

    @Test fun privateNetworkPagesAreNeverSentToTavily() {
        for (url in listOf("http://localhost/", "http://127.0.0.1/", "http://192.168.1.1/", "http://10.0.0.1/", "http://[::1]/", "http://router.local/"))
            assertFalse(url, TavilyPageReader.publicUrl(url))
    }
}
