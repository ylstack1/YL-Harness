package com.androidharness.app.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64
import java.util.concurrent.TimeUnit

private const val API_BASE = "https://api.github.com"
private const val MAX_OUTPUT_CHARS = 24_000
private const val MAX_RESPONSE_BYTES = 4_000_000L
private const val MAX_FILE_CHARS = 24_000
private const val MAX_FILE_BYTES = 2_000_000L

internal data class GitHubReadResponse(
    val path: String,
    val body: String,
    val next: String?,
    val rateRemaining: Int?,
    val authenticated: Boolean,
)

/**
 * Read-only GitHub REST client. The token never leaves api.github.com and is
 * never copied into a result: callers only see the response body.
 */
internal class GitHubReadClient(
    client: OkHttpClient,
    private val token: suspend () -> String?,
) {
    private val client = client.newBuilder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun get(rawPath: String, accept: String = "application/vnd.github+json"): GitHubReadResponse =
        withContext(Dispatchers.IO) {
            val path = normalizeGitHubApiPath(rawPath)
            val saved = try {
                token()?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                throw ToolFailure(e.message ?: "Could not read the saved GitHub login. Reconnect GitHub in Settings.")
            }
            val request = Request.Builder()
                .url(API_BASE + path)
                .header("Accept", accept)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "AndroidHarness")
                .apply { if (saved != null) header("Authorization", "Bearer $saved") }
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.peekBody(MAX_RESPONSE_BYTES).string()
                val remaining = response.header("X-RateLimit-Remaining")?.toIntOrNull()
                if (!response.isSuccessful) throw ToolFailure(githubError(response.code, body, remaining, saved != null))
                GitHubReadResponse(
                    path = path,
                    body = body,
                    next = nextPage(response.header("Link")),
                    rateRemaining = remaining,
                    authenticated = saved != null,
                )
            }
        }

    private fun githubError(code: Int, body: String, remaining: Int?, signedIn: Boolean): String {
        val message = runCatching { Json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.contentOrNull }
            .getOrNull()?.takeIf { it.isNotBlank() }
        val detail = message?.let { ": $it" }.orEmpty()
        return when (code) {
            401 -> if (signedIn) {
                "GitHub rejected the saved credentials. Sign in again or update the personal access token in Settings."
            } else {
                "GitHub requires a connected account for this read. Sign in or add a personal access token in Settings."
            }
            403 -> when {
                remaining == 0 -> "GitHub rate limit reached. Sign in or add a personal access token in Settings to raise the hourly limit."
                signedIn -> "GitHub refused this read (HTTP 403$detail). The connected account may not have access to it."
                else -> "GitHub refused this public read (HTTP 403$detail). Sign in or add a personal access token in Settings if this resource is private."
            }
            404 -> if (signedIn) {
                "GitHub returned 404$detail. The repository, path, or item does not exist, or the connected account cannot see it."
            } else {
                "GitHub returned 404$detail. The repository, path, or item does not exist, or it is private. Sign in or add a personal access token in Settings for private data."
            }
            422 -> "GitHub could not run this request (HTTP 422$detail). Check the path or search query."
            in 500..599 -> "GitHub is temporarily unavailable (HTTP $code). Try again."
            else -> "GitHub returned HTTP $code$detail."
        }
    }

    private fun nextPage(link: String?): String? = link?.split(',')?.firstNotNullOfOrNull { part ->
        Regex("<([^>]+)>;\\s*rel=\"next\"").find(part)?.groupValues?.get(1)?.let { raw ->
            val url = raw.toHttpUrlOrNull() ?: return@let null
            if (!url.isHttps || url.host != "api.github.com") null
            else url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
        }
    }
}

/** Accepts /owner/repo style paths and verified api.github.com URLs only. */
internal fun normalizeGitHubApiPath(raw: String): String {
    val trimmed = raw.trim().replace(" ", "%20")
    if (trimmed.isEmpty()) throw ToolFailure("Enter a GitHub API path such as /repos/owner/repository.")
    val url: HttpUrl = when {
        trimmed.startsWith("https://") || trimmed.startsWith("http://") -> trimmed.toHttpUrlOrNull()
        else -> (API_BASE + if (trimmed.startsWith("/")) trimmed else "/$trimmed").toHttpUrlOrNull()
    } ?: throw ToolFailure("Enter a valid GitHub API path, for example /repos/owner/repository.")
    if (!url.isHttps || url.host != "api.github.com" || url.username.isNotEmpty() || url.password.isNotEmpty()) {
        throw ToolFailure("Only read-only https://api.github.com requests are allowed.")
    }
    return url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
}

internal fun githubRepositoryName(raw: String): String {
    val clean = raw.trim()
        .removePrefix("https://github.com/")
        .removePrefix("git@github.com:")
        .trimEnd('/')
        .removeSuffix(".git")
    require(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(clean) &&
        clean.split('/').none { it == "." || it == ".." }) {
        "Enter a GitHub repository as owner/repository or its GitHub URL."
    }
    return clean
}

internal fun githubContentsUrl(repository: String, path: String, ref: String?): String {
    val segments = path.split('/').filter { it.isNotBlank() }
    require(segments.none { it == "." || it == ".." }) { "Use a repository-relative file path." }
    val builder = "$API_BASE/repos/$repository/contents".toHttpUrl().newBuilder()
    segments.forEach(builder::addPathSegment)
    if (!ref.isNullOrBlank()) builder.addQueryParameter("ref", ref.trim())
    return builder.build().toString()
}

internal fun formatGitHubJson(response: GitHubReadResponse): String {
    val formatted = runCatching {
        val compact = Json.parseToJsonElement(response.body)
        Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), compact)
    }.getOrElse { response.body }
    val body = when {
        formatted.isBlank() -> "(empty response)"
        formatted.length > MAX_OUTPUT_CHARS -> formatted.take(MAX_OUTPUT_CHARS) + "\n[truncated at $MAX_OUTPUT_CHARS characters]"
        else -> formatted
    }
    return buildString {
        append("GitHub API ").append(response.path)
        append(if (response.authenticated) " (connected account)" else " (public, no account)")
        append('\n').append(body)
        response.next?.let { append("\n[next page: ").append(it).append(']') }
        if (response.rateRemaining != null && response.rateRemaining <= 5) {
            append("\n[GitHub API requests remaining: ").append(response.rateRemaining).append(']')
        }
    }
}

internal fun formatGitHubSearch(
    kind: String,
    query: String,
    body: String,
    next: String?,
): String {
    val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrElse {
        throw ToolFailure("GitHub returned an unreadable search response. Try again.")
    }
    val total = root["total_count"]?.jsonPrimitive?.intOrNull
    val items = root["items"]?.jsonArray.orEmpty()
    if (items.isEmpty()) return "No GitHub $kind matched \"$query\"." + (next?.let { "\n[next page: $it]" } ?: "")
    val lines = items.mapIndexedNotNull { index, element -> (element as? JsonObject)?.let { item ->
        when (kind) {
            "repositories" -> repoLine(index, item)
            "code" -> codeLine(index, item)
            "issues" -> issueLine(index, item)
            "commits" -> commitLine(index, item)
            else -> userLine(index, item)
        }
    } }
    return buildString {
        append(total?.let { "$it results" } ?: "${items.size} results")
        append(" for \"").append(query).append("\" (").append(kind).append(", showing ").append(lines.size).append("):\n")
        append(lines.joinToString("\n"))
        next?.let { append("\n[next page: ").append(it).append(']') }
    }
}

private fun repoLine(index: Int, item: JsonObject): String = buildString {
    append(index + 1).append(". ").append(item.string("full_name") ?: "repository")
    if (item["private"]?.jsonPrimitive?.contentOrNull == "true") append(" (private)")
    if (item["archived"]?.jsonPrimitive?.contentOrNull == "true") append(" (archived)")
    item["stargazers_count"]?.jsonPrimitive?.intOrNull?.let { append(" · ").append(it).append(" stars") }
    item.string("language")?.let { append(" · ").append(it) }
    item.string("description")?.take(300)?.let { append("\n   ").append(it) }
    item.string("html_url")?.let { append("\n   ").append(it) }
}

private fun codeLine(index: Int, item: JsonObject): String = buildString {
    val repo = (item["repository"] as? JsonObject)?.string("full_name")
    append(index + 1).append(". ").append(repo?.plus("/") ?: "").append(item.string("path") ?: "file")
    item.string("html_url")?.let { append("\n   ").append(it) }
}

private fun issueLine(index: Int, item: JsonObject): String = buildString {
    val repo = item.string("repository_url")?.substringAfter("/repos/")
    append(index + 1).append(". ")
    if (repo != null) append(repo).append('#')
    append(item["number"]?.jsonPrimitive?.intOrNull ?: "?")
    append(if (item["pull_request"] != null) " · pull request" else " · issue")
    item.string("state")?.let { append(" · ").append(it) }
    item.string("title")?.take(300)?.let { append("\n   ").append(it) }
    item.string("html_url")?.let { append("\n   ").append(it) }
}

private fun commitLine(index: Int, item: JsonObject): String = buildString {
    val repo = (item["repository"] as? JsonObject)?.string("full_name")
    append(index + 1).append(". ")
    if (repo != null) append(repo).append(" · ")
    append(item.string("sha")?.take(7) ?: "commit")
    val message = ((item["commit"] as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
        ?.lineSequence()?.firstOrNull()
    message?.take(200)?.let { append("\n   ").append(it) }
    item.string("html_url")?.let { append("\n   ").append(it) }
}

private fun userLine(index: Int, item: JsonObject): String = buildString {
    append(index + 1).append(". ").append(item.string("login") ?: "user")
    item.string("type")?.let { append(" · ").append(it) }
    item.string("html_url")?.let { append("\n   ").append(it) }
}

internal fun formatGitHubDirectory(repository: String, path: String, entries: JsonArray): String = buildString {
    append("Directory ").append(repository)
    if (path.isNotBlank()) append('/').append(path.trim('/'))
    append(":\n")
    entries.take(200).forEach { element ->
        val entry = element as? JsonObject ?: return@forEach
        val name = entry.string("name") ?: return@forEach
        val type = entry.string("type") ?: "file"
        append("- ").append(name).append(if (type == "dir") "/" else "")
        append(" (").append(type)
        if (type == "file") entry["size"]?.jsonPrimitive?.intOrNull?.let { append(", ").append(sizeLabel(it.toLong())) }
        append(")\n")
    }
    if (entries.size > 200) append("[showing the first 200 of ${entries.size} entries]\n")
}

internal fun formatGitHubFile(
    repository: String,
    path: String,
    file: JsonObject,
    bytes: ByteArray,
    ref: String?,
): String {
    val size = file["size"]?.jsonPrimitive?.intOrNull?.toLong() ?: bytes.size.toLong()
    val location = buildString {
        append(repository).append('/').append(path.trim('/'))
        if (!ref.isNullOrBlank()) append(" @ ").append(ref.trim())
    }
    if (file.string("type") == "submodule") {
        return "Submodule $location -> ${file.string("submodule_git_url") ?: "unknown URL"}"
    }
    if (size > MAX_FILE_BYTES) {
        return "$location is ${sizeLabel(size)}, too large to read here. Open ${file.string("html_url") ?: "the file on GitHub"} to view it."
    }
    if (bytes.any { it == 0.toByte() }) {
        return "Binary file $location (${sizeLabel(size)}). Open ${file.string("html_url") ?: "the file on GitHub"} to view it."
    }
    val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
    val body = if (text.length > MAX_FILE_CHARS) {
        text.take(MAX_FILE_CHARS) + "\n[truncated at $MAX_FILE_CHARS of ${text.length} characters]"
    } else text
    return "$location (${sizeLabel(size)}):\n$body"
}

internal fun sizeLabel(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1_000.0)
    else -> "$bytes bytes"
}

/** Generic read-only GET for repositories, issues, pull requests, commits, releases and users. */
class GitHubApiTool(
    client: OkHttpClient,
    token: suspend () -> String? = { null },
) : Tool {
    private val api = GitHubReadClient(client, token)
    override val name = "github_api"
    override val description =
        "Read-only GitHub REST GET. Use it for repository details, issues, pull requests, comments, commits, " +
            "releases, branches, users, notifications, or any api.github.com GET path. It uses the connected " +
            "GitHub account (sign-in or personal access token) when available; otherwise public data only. " +
            "It can only read: creating, editing, commenting, merging and pushing are impossible through this tool."
    override val parametersSchema = Schema.obj(mapOf(
        "path" to Schema.string(
            "GitHub API GET path beginning with /, for example /repos/owner/repository, " +
                "/repos/owner/repository/issues?state=open&per_page=20, " +
                "/repos/owner/repository/pulls/12, /repos/owner/repository/pulls/12/files, " +
                "/repos/owner/repository/issues/5/comments, /repos/owner/repository/commits?per_page=10, " +
                "/repos/owner/repository/releases, /repos/owner/repository/contents, /users/owner. " +
                "A full https://api.github.com URL also works.",
        ),
    ), required = listOf("path"))
    override val isReadOnly = true
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args["path"]?.jsonPrimitive?.contentOrNull
            ?: throw ToolFailure("Missing required argument: path")
        return ToolResult(true, formatGitHubJson(api.get(path)))
    }
}

/** Search repositories, code, issues and pull requests, commits, or users. */
class GitHubSearchTool(
    client: OkHttpClient,
    token: suspend () -> String? = { null },
) : Tool {
    private val api = GitHubReadClient(client, token)
    override val name = "github_search"
    override val description =
        "Search GitHub and return compact results with URLs. Kinds: repositories (default), code, issues " +
            "(includes pull requests), commits, users. Supports GitHub qualifiers such as repo:owner/name, " +
            "is:issue, state:open, language:kotlin, org:name. Code search requires a connected GitHub account."
    override val parametersSchema = Schema.obj(mapOf(
        "query" to Schema.string("Search text and optional GitHub qualifiers."),
        "kind" to Schema.string("repositories (default), code, issues, commits, or users."),
        "limit" to Schema.integer("Results to return, 1..30, default 10."),
    ), required = listOf("query"))
    override val isReadOnly = true
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (query.isBlank()) throw ToolFailure("Enter a GitHub search query.")
        val kind = args["kind"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()?.ifBlank { "repositories" }
            ?: "repositories"
        if (kind !in setOf("repositories", "code", "issues", "commits", "users")) {
            throw ToolFailure("Use kind repositories, code, issues, commits, or users.")
        }
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 30)
        val url = "$API_BASE/search/$kind".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("per_page", limit.toString())
            .build()
        val response = api.get(url.toString())
        return ToolResult(true, formatGitHubSearch(kind, query, response.body, response.next))
    }
}

/** Read a repository file or list a directory, decoding file contents. */
class GitHubFileTool(
    client: OkHttpClient,
    token: suspend () -> String? = { null },
) : Tool {
    private val api = GitHubReadClient(client, token)
    override val name = "github_file"
    override val description =
        "Read a file or list a directory from a GitHub repository through the connected account when available. " +
            "Read-only: returns text, directory entries, or a clear note for binary and oversized files."
    override val parametersSchema = Schema.obj(mapOf(
        "repository" to Schema.string("Repository as owner/name or a GitHub URL."),
        "path" to Schema.string("File or directory path inside the repository. Omit or use / for the root."),
        "ref" to Schema.string("Optional branch, tag, or commit SHA. Defaults to the repository's default branch."),
    ), required = listOf("repository"))
    override val isReadOnly = true
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val repository = try {
            githubRepositoryName(args["repository"]?.jsonPrimitive?.contentOrNull.orEmpty())
        } catch (e: IllegalArgumentException) {
            throw ToolFailure(e.message ?: "Enter a repository as owner/name.")
        }
        val path = args["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val ref = args["ref"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
        val url = try {
            githubContentsUrl(repository, path, ref)
        } catch (e: IllegalArgumentException) {
            throw ToolFailure(e.message ?: "Use a repository-relative file path.")
        }
        val response = api.get(url)
        return when (val parsed = runCatching { Json.parseToJsonElement(response.body) }.getOrNull()) {
            is JsonArray -> ToolResult(true, formatGitHubDirectory(repository, path, parsed))
            is JsonObject -> ToolResult(true, readFile(repository, path, ref, parsed))
            else -> throw ToolFailure("GitHub returned an unreadable file or directory response. Try again.")
        }
    }

    private suspend fun readFile(repository: String, path: String, ref: String?, file: JsonObject): String {
        // Refuse before downloading: a blob can be far larger than the contents API preview.
        val size = file["size"]?.jsonPrimitive?.intOrNull?.toLong()
        if (size != null && size > MAX_FILE_BYTES) {
            return formatGitHubFile(repository, path, file, ByteArray(0), ref)
        }
        val encoded = file.string("content")
        val bytes = when {
            encoded != null && file.string("encoding") == "base64" -> Base64.getMimeDecoder().decode(encoded)
            else -> {
                val sha = file.string("sha") ?: return "GitHub returned no content for ${repository}/${path.trim('/')}."
                val blob = api.get("$API_BASE/repos/$repository/git/blobs/$sha")
                val payload = runCatching { Json.parseToJsonElement(blob.body).jsonObject }.getOrElse {
                    throw ToolFailure("GitHub returned an unreadable file response. Try again.")
                }
                val blobContent = payload.string("content")
                    ?: return "GitHub returned no content for ${repository}/${path.trim('/')}."
                Base64.getMimeDecoder().decode(blobContent)
            }
        }
        return formatGitHubFile(repository, path, file, bytes, ref)
    }
}

internal fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
