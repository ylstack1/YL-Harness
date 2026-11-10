package com.androidharness.app.tools

import com.androidharness.app.core.ResearchReceipt
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class WebFetchFallbackTest {
    private val ctx = ToolContext(FileFs(File("/tmp")))
    private fun url() = "https://${UUID.randomUUID()}.com/page"
    private fun response(request: Request, body: String, code: Int = 200) = Response.Builder().request(request).code(code).message("fixture")
        .protocol(Protocol.HTTP_1_1).body(body.toResponseBody("text/html".toMediaType())).build()
    private fun client(body: String, code: Int = 200) = OkHttpClient.Builder().addInterceptor { response(it.request(), body, code) }.build()

    @Test fun javascriptPageAutomaticallyRendersOutsideTheDirectLockAndKeepsPagingAndReadCount(): Unit = runBlocking {
        val source = url()
        val budget = SearchSourceBudget(20, 1, 1)
        var calls = 0
        val text = "Introduction\nUseful browser-rendered detail about astronomy.\n" + "More detail. ".repeat(200)
        val reader = WebFetchTool(client("<html><body>Please enable JavaScript to continue.</body></html>"), renderedReader = { supplied ->
            assertSame(budget, supplied)
            object : Tool {
                override val name = "browser_read"
                override val description = "Fixture"
                override val parametersSchema = Schema.obj(emptyMap())
                override val isReadOnly = true
                override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = ResearchHttp.page(source) {
                    calls++; WebResultCache.putPage(source, text, "browser_read")
                    WebResultCache.putPageImages(source, emptyList())
                    assertTrue(supplied!!.claimRead(source))
                    ToolResult(true, "rendered", research = supplied.receipt(source, "browser_read"))
                }
            }
        }).forBudget(budget)
        val first = withTimeout(5000) { reader.execute(buildJsonObject { put("url", source); put("find", "astronomy") }, ctx) }
        assertTrue(first.output, first.ok); assertTrue(first.output.contains("astronomy")); assertFalse(first.output.contains("enable JavaScript"))
        assertEquals("browser_read", first.research?.backend); assertEquals(1, calls); assertEquals(1, budget.pagesRead)
        val again = reader.execute(buildJsonObject { put("url", source); put("offset", 10) }, ctx)
        assertTrue(again.ok); assertEquals(1, calls); assertEquals(1, budget.pagesRead)
    }

    @Test fun deniedAndRateLimitedSitesNeverTriggerAutomaticBrowserRetries(): Unit = runBlocking {
        for (code in listOf(401, 403, 429)) {
            var rendered = false
            val budget = SearchSourceBudget(20, 5, 1)
            val result = WebFetchTool(client("Blocked", code), budget, renderedReader = { rendered = true; error("Must not render") })
                .execute(buildJsonObject { put("url", url()) }, ctx)
            assertFalse(result.ok); assertFalse(rendered); assertEquals(0, budget.pagesRead)
        }
    }

    @Test fun structuredDiscussionIsReadWithoutRenderingAndBlockedHtmlIsNeverSalvaged(): Unit = runBlocking {
        val data = """<script type="application/ld+json">{"@type":"DiscussionForumPosting","text":"The actual detailed discussion explains how to preserve a local project and restore saved work safely before making edits."}</script>"""
        var rendered = false
        val reader = WebFetchTool(client("<body>Please enable JavaScript</body>$data"), renderedReader = { rendered = true; error("Must not render") })
        val good = reader.execute(buildJsonObject { put("url", url()) }, ctx)
        assertTrue(good.output, good.ok); assertTrue(good.output.contains("actual detailed discussion")); assertFalse(rendered)
        val blocked = WebFetchTool(client("<html><title>Access denied</title><body>Access denied</body>$data</html>"), renderedReader = { rendered = true; error("Must not render") })
            .execute(buildJsonObject { put("url", url()) }, ctx)
        assertFalse(blocked.ok); assertFalse(rendered)
    }

    @Test fun failedRenderingDoesNotClaimAReadAndSuggestsAnotherSource(): Unit = runBlocking {
        val budget = SearchSourceBudget(20, 2, 1)
        val fallback = object : Tool {
            override val name = "browser_read"
            override val description = "Fixture"
            override val parametersSchema = Schema.obj(emptyMap())
            override val isReadOnly = true
            override suspend fun execute(args: JsonObject, ctx: ToolContext) = ToolResult(false, "Still no readable content.")
        }
        val result = WebFetchTool(client("<body>Loading…</body>"), budget, renderedReader = { fallback })
            .execute(buildJsonObject { put("url", url()) }, ctx)
        assertFalse(result.ok); assertEquals(0, budget.pagesRead); assertNull(result.research)
        assertTrue(result.output.contains("another accessible source"))
    }

    @Test fun unavailableConfiguredExtractorFallsBackToTheOriginalPublicPage(): Unit = runBlocking {
        var apiCalls = 0
        var directCalls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.host == "api.tavily.com") { apiCalls++; response(chain.request(), "Unauthorized", 401) }
            else { directCalls++; response(chain.request(), "<article>The original public source is still readable without the unavailable extractor.</article>") }
        }.build()
        val result = WebFetchTool(client, searchApi = { SearchApiConfig("tavily", "fixture-${UUID.randomUUID()}") })
            .execute(buildJsonObject { put("url", url()) }, ctx)
        assertTrue(result.output, result.ok); assertTrue(result.output.contains("original public source"))
        assertEquals(1, apiCalls); assertEquals(1, directCalls)
    }
}
