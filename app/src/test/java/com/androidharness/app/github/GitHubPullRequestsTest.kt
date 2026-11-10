package com.androidharness.app.github

import com.androidharness.app.agent.CheckStatus
import com.androidharness.app.agent.VerificationCheck
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GitHubPullRequestsTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var folder: File

    private fun git(vararg args: String, cwd: File = folder): GitCommandResult {
        val process = ProcessBuilder(listOf("git") + args).directory(cwd).apply {
            environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
            environment()["GIT_CONFIG_NOSYSTEM"] = "1"
        }.start()
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        return GitCommandResult(process.waitFor(), out, err)
    }

    private fun setup(): GitHubRepository {
        folder = temp.newFolder("work")
        val remote = temp.newFolder("remote.git")
        assertEquals(0, git("init", "--bare", cwd = remote).code)
        assertEquals(0, git("init", "-b", "main").code)
        git("config", "user.name", "Test")
        git("config", "user.email", "test@example.com")
        git("remote", "add", "origin", "git@github.com:test/repo.git")
        // Transport fixture only, as in GitHubRepositoryTest: an SSH-style GitHub origin backed by a local bare repo.
        git("config", "url.${remote.absolutePath}.insteadOf", "git@github.com:test/repo.git")
        File(folder, "base.txt").writeText("base\n")
        git("add", "base.txt"); git("commit", "-m", "Initial")
        assertEquals(0, git("push", "origin", "main").code)
        return GitHubRepository { args -> git(*args.toTypedArray()) }
    }

    private fun stub(vararg responses: Pair<Int, String>, seen: MutableList<Request> = mutableListOf()): GitHubApi {
        var index = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request().also(seen::add)
            val (code, body) = responses[minOf(index++, responses.lastIndex)]
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("stub")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return GitHubApi(client)
    }

    private val pr = """{"number":7,"html_url":"https://github.com/test/repo/pull/7","title":"Add login","draft":false,
        "base":{"ref":"main"},"head":{"ref":"feature/login"}}"""

    @Test fun `ssh and https GitHub origins name the same repository`() = runBlocking {
        val repo = setup()
        assertEquals("test/repo", repo.originRepository())
        git("remote", "set-url", "origin", "https://gitlab.com/test/repo.git")
        val failure = runCatching { repo.originRepository() }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty(), failure?.message.orEmpty().contains("not a GitHub repository"))
    }

    @Test fun `feature branch is compared with base and push state without moving refs`() = runBlocking {
        val repo = setup()
        git("checkout", "-b", "feature/login")
        File(folder, "login.kt").writeText("fun login() = true\n")
        File(folder, ".harness").mkdirs()
        File(folder, ".harness/history.json").writeText("private")
        git("add", "login.kt"); git("commit", "-m", "Add login screen")
        File(folder, "notes.txt").writeText("draft")
        val refsBefore = git("for-each-ref").out

        val local = repo.pullRequestBranch("test/repo", "main")
        assertEquals("feature/login", local.branch)
        assertEquals(1, local.ahead)
        assertEquals(0, local.behind)
        assertEquals(listOf("Add login screen"), local.commits)
        assertEquals(listOf(PullRequestFile("login.kt", 1, 0)), local.files)
        assertEquals(1, local.uncommitted) // notes.txt; .harness is private
        assertNull(local.remoteHead)
        assertFalse(local.pushed)
        assertEquals(refsBefore, git("for-each-ref").out)

        assertEquals(0, git("push", "origin", "feature/login").code)
        val pushed = repo.pullRequestBranch("test/repo", "main")
        assertTrue(pushed.pushed)
        File(folder, "login.kt").appendText("// more\n")
        git("commit", "-am", "Tweak login")
        val stale = repo.pullRequestBranch("test/repo", "main")
        assertFalse(stale.pushed)
        assertNotNull(stale.remoteHead)
        assertEquals(2, stale.ahead)
    }

    @Test fun `pull requests cannot start from the base branch or a detached head`() = runBlocking {
        val repo = setup()
        val onBase = runCatching { repo.pullRequestBranch("test/repo", "main") }.exceptionOrNull()
        assertTrue(onBase?.message.orEmpty(), onBase?.message.orEmpty().contains("feature branch"))
        git("checkout", "--detach")
        val detached = runCatching { repo.pullRequestBranch("test/repo", "main") }.exceptionOrNull()
        assertTrue(detached?.message.orEmpty(), detached?.message.orEmpty().contains("detached"))
    }

    @Test fun `numstat parses renames binaries and skips app history`() {
        val files = parseNumstat("3\t1\tsrc/a.kt\u0000-\t-\tlogo.png\u00000\t0\t\u0000old.kt\u0000new.kt\u00001\t0\t.harness/x\u0000")
        assertEquals(listOf(PullRequestFile("src/a.kt", 3, 1), PullRequestFile("logo.png", null, null),
            PullRequestFile("new.kt", 0, 0)), files)
    }

    @Test fun `create posts the reviewed pull request and reads the result`() = runBlocking {
        val seen = mutableListOf<Request>()
        val info = GitHubPullRequests(stub(201 to pr, seen = seen))
            .create("test/repo", "feature/login", "main", " Add login ", "Body", draft = true, token = "tok")
        assertEquals(PullRequestInfo(7, "https://github.com/test/repo/pull/7", "Add login", false, "main", "feature/login"), info)
        val request = seen.single()
        assertEquals("POST", request.method)
        assertEquals("/repos/test/repo/pulls", request.url.encodedPath)
        assertEquals("Bearer tok", request.header("Authorization"))
        val payload = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
        assertEquals("Add login", payload["title"]!!.jsonPrimitive.content)
        assertEquals("feature/login", payload["head"]!!.jsonPrimitive.content)
        assertEquals("true", payload["draft"]!!.jsonPrimitive.content)
    }

    @Test fun `an existing pull request is returned instead of a duplicate`() = runBlocking {
        val seen = mutableListOf<Request>()
        val api = stub(422 to """{"message":"Validation Failed","errors":[{"message":"A pull request already exists for test:feature/login."}]}""",
            200 to "[$pr]", seen = seen)
        val info = GitHubPullRequests(api).create("test/repo", "feature/login", "main", "Add login", "", false, "tok")
        assertEquals(7, info.number)
        assertEquals("test:feature/login", seen.last().url.queryParameter("head"))
        assertEquals("open", seen.last().url.queryParameter("state"))
    }

    @Test fun `GitHub refusals become actionable messages`() = runBlocking {
        val draft = runCatching {
            GitHubPullRequests(stub(422 to """{"message":"Validation Failed","errors":["Draft pull requests are not supported in this repository."]}"""))
                .create("test/repo", "b", "main", "T", "", true, "tok")
        }.exceptionOrNull()
        assertTrue(draft?.message.orEmpty(), draft?.message.orEmpty().contains("Turn off Draft"))
        val denied = runCatching {
            GitHubPullRequests(stub(403 to """{"message":"Resource not accessible by integration"}""")).create("test/repo", "b", "main", "T", "", false, "tok")
        }.exceptionOrNull()
        assertTrue(denied?.message.orEmpty(), denied?.message.orEmpty().contains("can't open pull requests"))
    }

    private fun branch(commits: List<String> = listOf("Add login screen"), ahead: Int = commits.size) = PullRequestBranch(
        "test/repo", "feature/login-flow", "abc", "main", "def", "abc", ahead, 0, commits,
        listOf(PullRequestFile("login.kt", 10, 2), PullRequestFile("logo.png", null, null)), 0)

    @Test fun `description lists commits files and real check outcomes`() {
        val body = buildPullRequestBody("Adds a login screen.", branch(), listOf(
            VerificationCheck("./gradlew test", CheckStatus.PASSED),
            VerificationCheck("npm run lint", CheckStatus.FAILED),
            VerificationCheck("pytest", CheckStatus.STALE)))
        assertTrue(body.startsWith("Adds a login screen.\n\n## Changes\n- Add login screen"))
        assertTrue(body.contains("2 files · +10 −2"))
        assertTrue(body.contains("- `logo.png` (binary)"))
        assertTrue(body.contains("- ✅ `./gradlew test` passed"))
        assertTrue(body.contains("- ❌ `npm run lint` failed"))
        assertTrue(body.contains("needs a recheck"))
    }

    @Test fun `verification is omitted when unknown and honest when nothing ran`() {
        assertFalse(buildPullRequestBody(null, branch(), null).contains("## Verification"))
        assertTrue(buildPullRequestBody(null, branch(), emptyList()).contains("No build, test or lint checks were recorded"))
        val many = buildPullRequestBody(null, branch((1..25).map { "Commit $it" }, ahead = 30), null)
        assertTrue(many.contains("- …and 10 more"))
    }

    @Test fun `titles come from a single commit or the branch name`() {
        assertEquals("Add login screen", suggestPullRequestTitle(branch()))
        assertEquals("Login flow", suggestPullRequestTitle(branch(listOf("a", "b"))))
    }
}
