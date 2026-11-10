package com.androidharness.app.tools

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.github.PullRequestBranch
import com.androidharness.app.github.PullRequestFile
import com.androidharness.app.github.PullRequestInfo
import com.androidharness.app.github.PullRequestPlan
import com.androidharness.app.github.PullRequestPublisher
import com.androidharness.app.workspace.FileFs
import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GitHubPullRequestToolTest {
    @get:Rule val temp = TemporaryFolder()

    private val info = PullRequestInfo(12, "https://github.com/o/r/pull/12", "Add login", false, "main", "feature")

    private fun plan(remoteHead: String? = "abc", ahead: Int = 2, existing: PullRequestInfo? = null, uncommitted: Int = 0) = PullRequestPlan(
        PullRequestBranch("o/r", "feature", "abc", "main", "def", remoteHead, ahead, 1, listOf("Add login", "Fix test"),
            listOf(PullRequestFile("a.kt", 3, 1)), uncommitted), "main", existing)

    private class FakePublisher(val plan: PullRequestPlan, val info: PullRequestInfo) : PullRequestPublisher {
        var base: String? = "unset"
        var opened: Triple<String, String, Boolean>? = null
        override suspend fun preparePullRequest(fs: WorkspaceFs, base: String?): PullRequestPlan { this.base = base; return plan }
        override suspend fun openPullRequest(plan: PullRequestPlan, title: String, body: String, draft: Boolean): PullRequestInfo {
            opened = Triple(title, body, draft); return info.copy(draft = draft)
        }
        override suspend fun baseBranches(repository: String) = listOf("main")
    }

    private val checks = listOf(
        ChatMessage(Role.ASSISTANT, toolCalls = listOf(ToolCallData("t", "shell", buildJsonObject { put("command", JsonPrimitive("./gradlew test")) }.toString()))),
        ChatMessage(Role.TOOL, "exit code: 0", toolCallId = "t"),
    )

    private fun tool(publisher: FakePublisher) = GitHubCreatePullRequestTool({ publisher }) { id -> if (id == "chat") checks else emptyList() }
    private fun ctx() = ToolContext(FileFs(temp.root), sessionId = "chat")
    private fun args(vararg pairs: Pair<String, Any>) = buildJsonObject {
        pairs.forEach { (k, v) -> when (v) { is Boolean -> put(k, v); else -> put(k, v.toString()) } }
    }

    @Test fun `opens a pull request with a generated description and a card-readable result`() = runBlocking {
        val publisher = FakePublisher(plan(uncommitted = 2), info)
        val result = tool(publisher).execute(args("title" to "Add login", "summary" to "Adds login.", "base" to "develop", "draft" to true), ctx())
        assertTrue(result.ok)
        assertEquals("develop", publisher.base)
        val (title, body, draft) = publisher.opened!!
        assertEquals("Add login", title)
        assertTrue(draft)
        assertTrue(body.startsWith("Adds login."))
        assertTrue(body.contains("- ✅ `./gradlew test` passed"))
        val parsed = parsePullRequestResult(result.output)!!
        assertEquals(PullRequestResult(12, "https://github.com/o/r/pull/12", "Add login", "main", "feature", true, false, 2, 1), parsed)
        assertTrue(result.output.contains("2 uncommitted change(s)"))
        assertTrue(result.output.contains("main has 1 newer commit(s)"))
    }

    @Test fun `an explicit body replaces the generated description`() = runBlocking {
        val publisher = FakePublisher(plan(), info)
        tool(publisher).execute(args("title" to "T", "body" to "Custom body"), ctx())
        assertEquals("Custom body", publisher.opened!!.second)
    }

    @Test fun `unpushed or stale branches are refused with the next step`() = runBlocking {
        val never = runCatching { tool(FakePublisher(plan(remoteHead = null), info)).execute(args("title" to "T"), ctx()) }.exceptionOrNull()
        assertTrue(never is ToolFailure && never.message!!.contains("git_push"))
        val stale = runCatching { tool(FakePublisher(plan(remoteHead = "old"), info)).execute(args("title" to "T"), ctx()) }.exceptionOrNull()
        assertTrue(stale is ToolFailure && stale.message!!.contains("doesn't match"))
        val empty = runCatching { tool(FakePublisher(plan(ahead = 0), info)).execute(args("title" to "T"), ctx()) }.exceptionOrNull()
        assertTrue(empty is ToolFailure && empty.message!!.contains("no commits"))
    }

    @Test fun `an already open pull request is returned without opening another`() = runBlocking {
        val publisher = FakePublisher(plan(existing = info), info)
        val result = tool(publisher).execute(args("title" to "T"), ctx())
        assertNull(publisher.opened)
        assertTrue(parsePullRequestResult(result.output)!!.existing)
    }

    @Test fun `title is required and the tool needs a device workspace`() = runBlocking {
        val missing = runCatching { tool(FakePublisher(plan(), info)).execute(args(), ctx()) }.exceptionOrNull()
        assertTrue(missing is ToolFailure)
        val tool = tool(FakePublisher(plan(), info))
        assertFalse(tool.isReadOnly)
        assertTrue(tool.isAvailable(ctx()))
    }

    @Test fun `results without the marker or a GitHub URL are not shown as pull requests`() {
        assertNull(parsePullRequestResult("Opened pull request"))
        assertNull(parsePullRequestResult("[pull-request] number=1\nurl: https://evil.test/pull/1"))
    }
}
