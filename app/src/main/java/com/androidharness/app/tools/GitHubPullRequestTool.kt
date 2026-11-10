package com.androidharness.app.tools

import com.androidharness.app.agent.VerificationEvidence
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.github.GitHubOperationFailure
import com.androidharness.app.github.PullRequestInfo
import com.androidharness.app.github.PullRequestPlan
import com.androidharness.app.github.PullRequestPublisher
import com.androidharness.app.github.buildPullRequestBody
import com.androidharness.app.github.redactGitHubSecrets
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** First line of every result; the chat's pull request card reads the fields after it. */
internal const val PULL_REQUEST_RESULT_MARKER = "[pull-request]"

internal data class PullRequestResult(val number: Int, val url: String, val title: String, val base: String,
    val head: String, val draft: Boolean, val existing: Boolean, val commits: Int, val files: Int)

internal fun formatPullRequestResult(info: PullRequestInfo, plan: PullRequestPlan, existing: Boolean): String = buildString {
    val branch = plan.branch
    append(PULL_REQUEST_RESULT_MARKER).append(" number=").append(info.number)
        .append(" draft=").append(info.draft).append(" existing=").append(existing)
        .append(" commits=").append(branch.ahead).append(" files=").append(branch.files.size).append('\n')
    append("url: ").append(info.url).append('\n')
    append("title: ").append(info.title.replace('\n', ' ')).append('\n')
    append("base: ").append(info.base.ifBlank { branch.base }).append('\n')
    append("head: ").append(info.head.ifBlank { branch.branch }).append('\n')
    append(if (existing) "A pull request from this branch was already open, so no new one was created."
        else if (info.draft) "Opened a draft pull request." else "Opened the pull request.")
    if (branch.uncommitted > 0) append("\nNote: ").append(branch.uncommitted)
        .append(" uncommitted change(s) in the workspace are not part of this pull request.")
    if (branch.behind > 0) append("\nNote: ").append(branch.base).append(" has ").append(branch.behind)
        .append(" newer commit(s) that this branch doesn't include yet.")
}

internal fun parsePullRequestResult(text: String): PullRequestResult? {
    val lines = text.lineSequence().toList()
    val header = lines.firstOrNull()?.takeIf { it.startsWith(PULL_REQUEST_RESULT_MARKER) } ?: return null
    val flags = header.removePrefix(PULL_REQUEST_RESULT_MARKER).trim().split(' ')
        .mapNotNull { it.split('=', limit = 2).takeIf { pair -> pair.size == 2 }?.let { (k, v) -> k to v } }.toMap()
    fun field(name: String) = lines.firstOrNull { it.startsWith("$name: ") }?.removePrefix("$name: ")?.trim()
    val url = field("url")?.takeIf { it.startsWith("https://github.com/") } ?: return null
    return PullRequestResult(flags["number"]?.toIntOrNull() ?: return null, url, field("title").orEmpty(),
        field("base").orEmpty(), field("head").orEmpty(), flags["draft"] == "true", flags["existing"] == "true",
        flags["commits"]?.toIntOrNull() ?: 0, flags["files"]?.toIntOrNull() ?: 0)
}

/**
 * Opens a GitHub pull request from the pushed, checked-out branch. It never pushes, merges or edits
 * an existing pull request: an open one for the branch is returned instead of a duplicate.
 */
class GitHubCreatePullRequestTool(
    private val publisher: () -> PullRequestPublisher?,
    private val messages: suspend (String) -> List<ChatMessage>,
) : Tool {
    override val name = "github_create_pull_request"
    override val description =
        "Open a GitHub pull request from the current, already-pushed branch of the workspace repository " +
            "(origin must be on GitHub; uses the connected GitHub account). Commit with git_commit and push " +
            "with git_push first. The description is written automatically from the branch's commits, " +
            "changed files and the build/test checks recorded in this chat; pass summary for a short " +
            "reviewer overview on top. If a pull request from this branch is already open, it is returned " +
            "instead of opening a duplicate. Never merges. Runs as a modifying operation, so the user approves it first."
    override val parametersSchema = Schema.obj(
        mapOf(
            "title" to Schema.string("Pull request title: a short imperative sentence describing the change."),
            "summary" to Schema.string("Optional 1-3 sentence overview for reviewers: why, and anything to look at closely. Commits, files and checks are added automatically."),
            "body" to Schema.string("Optional full Markdown description that replaces the generated one. Prefer summary."),
            "base" to Schema.string("Branch to merge into (default: the repository's default branch)."),
            "draft" to Schema.boolean("Open as a draft pull request (default false)."),
        ),
        required = listOf("title"),
    )
    override val isReadOnly = false

    // Credentials live on this device, so SSH and folder-only workspaces cannot be inspected for a PR.
    override fun isAvailable(ctx: ToolContext): Boolean = ctx.workspace.shellRoot != null

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val service = publisher() ?: throw ToolFailure("GitHub pull requests are unavailable right now.")
        val title = args["title"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
            ?: throw ToolFailure("Missing required argument: title")
        val base = args["base"]?.jsonPrimitive?.contentOrNull
        val draft = args["draft"]?.jsonPrimitive?.let { it.booleanOrNull ?: it.contentOrNull.equals("true", true) } == true
        val plan = try { service.preparePullRequest(ctx.workspace, base) }
            catch (e: CancellationException) { throw e }
            catch (e: GitHubOperationFailure) { throw ToolFailure(redactGitHubSecrets(e.message.orEmpty())) }
            catch (e: Exception) { throw ToolFailure(redactGitHubSecrets(e.message ?: "Could not reach GitHub.")) }
        plan.existing?.let { return ToolResult(true, formatPullRequestResult(it, plan, existing = true)) }
        val branch = plan.branch
        if (branch.ahead == 0) throw ToolFailure("${branch.branch} has no commits that aren't already on ${branch.base}. Commit work on this branch first.")
        if (!branch.pushed) throw ToolFailure(
            if (branch.remoteHead == null) "${branch.branch} isn't on GitHub yet. Push it with git_push, then call this again."
            else "GitHub's copy of ${branch.branch} doesn't match the latest local commit. Push with git_push (pull first if GitHub has newer commits), then call this again.",
        )
        val body = args["body"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
            ?: buildPullRequestBody(args["summary"]?.jsonPrimitive?.contentOrNull, branch,
                ctx.sessionId?.let { id -> runCatching { VerificationEvidence.collect(messages(id)) }.getOrNull() })
        val info = try { service.openPullRequest(plan, title, body, draft) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { throw ToolFailure(redactGitHubSecrets(e.message ?: "GitHub couldn't open the pull request.")) }
        return ToolResult(true, formatPullRequestResult(info, plan, existing = false))
    }
}
