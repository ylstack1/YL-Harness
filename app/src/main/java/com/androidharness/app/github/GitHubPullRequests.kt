package com.androidharness.app.github

import com.androidharness.app.agent.CheckStatus
import com.androidharness.app.agent.VerificationCheck
import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl

data class PullRequestFile(val path: String, val additions: Int?, val deletions: Int?)

/** The checked-out branch compared with its base on GitHub, read from git without changing refs. */
data class PullRequestBranch(
    val repository: String,
    val branch: String,
    val head: String,
    val base: String,
    val baseHead: String,
    /** The branch's commit on GitHub, or null when it was never pushed. */
    val remoteHead: String?,
    val ahead: Int,
    val behind: Int,
    val commits: List<String>,
    val files: List<PullRequestFile>,
    val uncommitted: Int,
) {
    val pushed: Boolean get() = remoteHead == head
    val additions: Int get() = files.sumOf { it.additions ?: 0 }
    val deletions: Int get() = files.sumOf { it.deletions ?: 0 }
}

data class PullRequestInfo(
    val number: Int,
    val url: String,
    val title: String,
    val draft: Boolean,
    val base: String,
    val head: String,
)

data class PullRequestPlan(
    val branch: PullRequestBranch,
    val defaultBranch: String,
    /** An open pull request from this branch, which is returned instead of opening a duplicate. */
    val existing: PullRequestInfo?,
)

/** Shared by the agent tool and the Create pull request sheet so both follow the same checks. */
interface PullRequestPublisher {
    suspend fun preparePullRequest(fs: WorkspaceFs, base: String? = null): PullRequestPlan
    suspend fun openPullRequest(plan: PullRequestPlan, title: String, body: String, draft: Boolean): PullRequestInfo
    suspend fun baseBranches(repository: String): List<String>
}

/** GitHub's pull request endpoints. Never merges, closes or edits an existing pull request. */
class GitHubPullRequests(private val api: GitHubApi) {
    suspend fun defaultBranch(repository: String, token: String): String =
        (api.get("/repos/$repository", token) as? JsonObject)?.string("default_branch")?.takeIf { it.isNotBlank() }
            ?: error("GitHub did not report a default branch for $repository.")

    suspend fun branches(repository: String, token: String): List<String> =
        (api.get("/repos/$repository/branches?per_page=100", token) as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.string("name") }

    suspend fun findOpen(repository: String, branch: String, token: String): PullRequestInfo? {
        val owner = repository.substringBefore('/')
        val url = "https://api.github.com/repos/$repository/pulls".toHttpUrl().newBuilder()
            .addQueryParameter("state", "open").addQueryParameter("head", "$owner:$branch")
            .addQueryParameter("per_page", "1").build()
        return (api.get(url.encodedPath + "?" + url.encodedQuery, token) as? JsonArray)
            ?.firstNotNullOfOrNull { (it as? JsonObject)?.let(::pullRequestInfo) }
    }

    suspend fun create(repository: String, branch: String, base: String, title: String, body: String,
        draft: Boolean, token: String): PullRequestInfo {
        val payload = buildJsonObject {
            put("title", title.trim())
            put("head", branch)
            put("base", base)
            put("body", body.take(MAX_BODY_CHARS))
            put("draft", draft)
            put("maintainer_can_modify", true)
        }
        val created = try { api.post("/repos/$repository/pulls", token, payload) }
        catch (e: GitHubHttpError) {
            if (e.code == 422 && e.details.any { it.contains("already exists", ignoreCase = true) }) {
                findOpen(repository, branch, token)?.let { return it }
            }
            throw IllegalStateException(pullRequestFailure(e), e)
        }
        return (created as? JsonObject)?.let(::pullRequestInfo) ?: error("GitHub returned an unreadable pull request. Check GitHub before retrying.")
    }

    private companion object { const val MAX_BODY_CHARS = 60_000 }
}

internal fun pullRequestInfo(item: JsonObject): PullRequestInfo? {
    val number = item["number"]?.jsonPrimitive?.intOrNull ?: return null
    val url = item.string("html_url")?.takeIf { it.startsWith("https://github.com/") } ?: return null
    return PullRequestInfo(number, url, item.string("title").orEmpty(),
        item["draft"]?.jsonPrimitive?.booleanOrNull == true,
        (item["base"] as? JsonObject)?.string("ref").orEmpty(), (item["head"] as? JsonObject)?.string("ref").orEmpty())
}

internal fun pullRequestFailure(e: GitHubHttpError): String {
    val detail = e.details.joinToString(" ").ifBlank { e.message.orEmpty() }
    val lower = detail.lowercase()
    return when {
        "draft" in lower -> "This repository doesn't support draft pull requests. Turn off Draft and try again."
        "no commits between" in lower -> "GitHub sees no commits between the branches. Push your latest commits first."
        "head" in lower && "invalid" in lower -> "GitHub can't find this branch. Push it first, then try again."
        "base" in lower && "invalid" in lower -> "GitHub can't find the base branch. Choose another base branch."
        e.code == 403 || e.code == 404 -> "This GitHub account can't open pull requests here. Check its access to the repository, or reconnect GitHub with repository permission."
        e.code == 401 -> "Reconnect GitHub. The token may be expired or revoked."
        else -> "GitHub couldn't open the pull request: $detail"
    }
}

/** A short title from the branch's only commit, or from the branch name. */
fun suggestPullRequestTitle(branch: PullRequestBranch): String =
    branch.commits.singleOrNull()?.take(120) ?: branch.branch.substringAfterLast('/')
        .replace(Regex("[-_]+"), " ").trim().replaceFirstChar { it.uppercase() }.ifBlank { branch.branch }

/**
 * Reviewer-facing Markdown. [checks] null means verification is unknown here (no chat), so the section is
 * omitted rather than claiming nothing ran.
 */
internal fun buildPullRequestBody(summary: String?, branch: PullRequestBranch, checks: List<VerificationCheck>?): String = buildString {
    summary?.trim()?.takeIf { it.isNotEmpty() }?.let { append(it).append("\n\n") }
    if (branch.commits.isNotEmpty()) {
        append("## Changes\n")
        branch.commits.take(20).forEach { append("- ").append(it).append('\n') }
        val hidden = branch.ahead - minOf(20, branch.commits.size)
        if (hidden > 0) append("- …and ").append(hidden).append(" more\n")
        append('\n')
    }
    if (branch.files.isNotEmpty()) {
        append("## Files changed\n")
        append(branch.files.size).append(if (branch.files.size == 1) " file" else " files")
            .append(" · +").append(branch.additions).append(" −").append(branch.deletions).append("\n\n")
        append("<details><summary>Show files</summary>\n\n")
        branch.files.forEach { file ->
            append("- `").append(file.path.replace("`", "'")).append('`')
            if (file.additions == null || file.deletions == null) append(" (binary)")
            else append(" (+").append(file.additions).append(" −").append(file.deletions).append(')')
            append('\n')
        }
        append("\n</details>\n\n")
    }
    if (checks != null) {
        append("## Verification\n")
        if (checks.isEmpty()) append("No build, test or lint checks were recorded for these changes.\n")
        checks.forEach { check ->
            append(when (check.status) {
                CheckStatus.PASSED -> "- ✅ `"
                CheckStatus.FAILED -> "- ❌ `"
                CheckStatus.UNKNOWN -> "- ⚠️ `"
                CheckStatus.STALE -> "- 🔁 `"
            }).append(check.command.replace("`", "'").take(200)).append("` ")
            append(when (check.status) {
                CheckStatus.PASSED -> "passed"
                CheckStatus.FAILED -> "failed"
                CheckStatus.UNKNOWN -> "result unconfirmed"
                CheckStatus.STALE -> "passed before later edits; needs a recheck"
            }).append('\n')
        }
    }
}.trimEnd()
