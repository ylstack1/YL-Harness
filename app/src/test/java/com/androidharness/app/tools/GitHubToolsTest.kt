package com.androidharness.app.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GitHubToolsTest {
    private fun stubClient(
        status: Int = 200,
        body: String = "{}",
        headers: Map<String, String> = emptyMap(),
        capture: (Request) -> Unit = {},
    ) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        capture(request)
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("stub")
            .body(body.toResponseBody("application/json".toMediaType()))
            .apply { headers.forEach { (key, value) -> header(key, value) } }
            .build()
    }.build()

    private fun failure(block: suspend () -> Unit): ToolFailure = runBlocking {
        try {
            block()
            fail("Expected ToolFailure")
            error("unreachable")
        } catch (e: ToolFailure) {
            e
        }
    }

    @Test fun apiPathsStayOnHttpsApiHost() {
        assertEquals("/repos/o/r?x=1", normalizeGitHubApiPath("https://api.github.com/repos/o/r?x=1"))
        assertEquals("/repos/o/r", normalizeGitHubApiPath("repos/o/r"))
        assertEquals("/search/issues", normalizeGitHubApiPath("/search/issues"))
        for (bad in listOf("https://github.com/o/r", "http://api.github.com/repos/o/r",
            "https://api.github.com.evil.test/repos/o/r", "https://evil.test/repos/o/r")) {
            try { normalizeGitHubApiPath(bad); fail(bad) } catch (_: ToolFailure) { }
        }
    }

    @Test fun repositoryNamesAcceptOwnerRepoAndGithubUrls() {
        assertEquals("o/r", githubRepositoryName("o/r"))
        assertEquals("o/r", githubRepositoryName("https://github.com/o/r"))
        assertEquals("o/r", githubRepositoryName("https://github.com/o/r.git"))
        assertEquals("o/r", githubRepositoryName("git@github.com:o/r.git"))
        for (bad in listOf("o", "o/r/tree/main", "../evil", "https://gitlab.com/o/r")) {
            try { githubRepositoryName(bad); fail(bad) } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun contentsUrlEncodesPathsAndRefs() {
        val url = githubContentsUrl("o/r", "src/My File.kt", "feature/x").toHttpUrl()
        assertEquals("/repos/o/r/contents/src/My%20File.kt", url.encodedPath)
        assertEquals("feature/x", url.queryParameter("ref"))
        assertEquals("/repos/o/r/contents", githubContentsUrl("o/r", "", null).toHttpUrl().encodedPath)
        try { githubContentsUrl("o/r", "../secret", null); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun apiToolUsesSavedTokenAndNeverEchoesIt() = runBlocking {
        var auth: String? = null
        val client = stubClient(body = """{"full_name":"o/r","stargazers_count":5}""") { request ->
            auth = request.header("Authorization")
        }
        val result = GitHubApiTool(client) { "test-token" }.execute(buildJsonObject { put("path", "/repos/o/r") },
            ToolContext(com.androidharness.app.workspace.FileFs(java.io.File("/tmp"))))
        assertEquals("Bearer test-token", auth)
        assertTrue(result.ok)
        assertTrue(result.output.contains("GitHub API /repos/o/r (connected account)"))
        assertTrue(result.output.contains("\"full_name\": \"o/r\""))
        assertFalse(result.output.contains("test-token"))
    }

    @Test fun apiToolReadsPublicDataWithoutALogin() = runBlocking {
        var auth: String? = "unset"
        val client = stubClient(body = """{"login":"octocat"}""") { request -> auth = request.header("Authorization") }
        val result = GitHubApiTool(client) { null }.execute(buildJsonObject { put("path", "/users/octocat") },
            ToolContext(com.androidharness.app.workspace.FileFs(java.io.File("/tmp"))))
        assertNull(auth)
        assertTrue(result.output.contains("(public, no account)"))
    }

    @Test fun apiErrorsAreClearAndHideCredentials() {
        val client = stubClient(status = 403, body = """{"message":"API rate limit exceeded"}""",
            headers = mapOf("X-RateLimit-Remaining" to "0"))
        val error = failure {
            GitHubApiTool(client) { "secret-token" }.execute(buildJsonObject { put("path", "/repos/o/r") },
                ToolContext(com.androidharness.app.workspace.FileFs(java.io.File("/tmp"))))
        }
        assertTrue(error.message.orEmpty().contains("rate limit"))
        assertFalse(error.message.orEmpty().contains("secret-token"))
    }

    @Test fun searchFormatsCompactResults() = runBlocking {
        var requested: Request? = null
        val client = stubClient(body = """{"total_count":2,"items":[
            {"full_name":"o/r","description":"A repo","stargazers_count":10,"language":"Kotlin","html_url":"https://github.com/o/r"},
            {"full_name":"o/s","stargazers_count":2,"html_url":"https://github.com/o/s"}]}""") { requested = it }
        val result = GitHubSearchTool(client) { null }.execute(
            buildJsonObject { put("query", "android harness"); put("kind", "repositories") },
            ToolContext(com.androidharness.app.workspace.FileFs(java.io.File("/tmp"))))
        assertEquals("/search/repositories", requested!!.url.encodedPath)
        assertEquals("android harness", requested!!.url.queryParameter("q"))
        assertEquals("10", requested!!.url.queryParameter("per_page"))
        assertTrue(result.output.contains("2 results"))
        assertTrue(result.output.contains("1. o/r · 10 stars · Kotlin"))
        assertTrue(result.output.contains("https://github.com/o/r"))
    }

    @Test fun fileToolDecodesTextAndListsDirectories() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            val body = if (path.endsWith("/contents/src")) {
                """[{"name":"A.kt","type":"file","size":2048},{"name":"Sub.kt","type":"dir"}]"""
            } else {
                """{"type":"file","name":"README.md","path":"README.md","size":5,"encoding":"base64","content":"aGVsbG8=","html_url":"https://github.com/o/r/blob/main/README.md"}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val context = ToolContext(com.androidharness.app.workspace.FileFs(java.io.File("/tmp")))
        val file = GitHubFileTool(client) { null }.execute(
            buildJsonObject { put("repository", "o/r"); put("path", "README.md") }, context)
        assertTrue(file.output.contains("o/r/README.md (5 bytes):"))
        assertTrue(file.output.contains("hello"))
        val directory = GitHubFileTool(client) { null }.execute(
            buildJsonObject { put("repository", "o/r"); put("path", "src") }, context)
        assertTrue(directory.output.contains("Directory o/r/src:"))
        assertTrue(directory.output.contains("A.kt (file, 2.0 KB)"))
        assertTrue(directory.output.contains("Sub.kt/ (dir)"))
    }

    @Test fun blobsFallbackHandlesLargeFiles() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            val body = if (path.contains("/git/blobs/")) {
                """{"encoding":"base64","content":"d29ybGQ="}"""
            } else {
                """{"type":"file","name":"big.txt","path":"big.txt","size":5,"encoding":"none","content":"","sha":"abc","html_url":"https://github.com/o/r/blob/main/big.txt"}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val result = GitHubFileTool(client) { null }.execute(
            buildJsonObject { put("repository", "o/r"); put("path", "big.txt") },
            ToolContext(com.androidharness.app.workspace.FileFs(java.io.File("/tmp"))))
        assertTrue(result.output.contains("world"))
    }

    @Test fun nextPageIsOfferedWithoutLeavingTheApiHost() = runBlocking {
        val client = stubClient(body = "[]",
            headers = mapOf("Link" to "<https://api.github.com/repos/o/r/issues?page=2>; rel=\"next\", <https://api.github.com/repos/o/r/issues?page=3>; rel=\"last\""))
        val result = GitHubApiTool(client) { null }.execute(
            buildJsonObject { put("path", "/repos/o/r/issues") },
            ToolContext(com.androidharness.app.workspace.FileFs(java.io.File("/tmp"))))
        assertTrue(result.output.contains("[next page: /repos/o/r/issues?page=2]"))
    }
}
