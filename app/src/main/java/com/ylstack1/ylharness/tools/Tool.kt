package com.ylstack1.ylharness.tools

import com.ylstack1.ylharness.workspace.WorkspaceFs
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

data class ToolContext(
    val workspace: WorkspaceFs,
    /**
     * Full access mode: tool-internal sandbox layers (shell denylist re-check,
     * cwd containment) are off. The engine has already gated this; tools only
     * honor it, they never enable it themselves.
     */
    val sandboxOff: Boolean = false,
    val sessionId: String? = null,
    /** Scoped to this call by the engine; never shared across chats or concurrent installs. */
    val onPackageProgress: suspend (com.ylstack1.ylharness.data.env.PackageInstallStatus) -> Unit = {},
    val imageInputAllowed: Boolean = true,
    val configuration: com.ylstack1.ylharness.config.HarnessConfig? = null,
    val skills: com.ylstack1.ylharness.skills.SkillStore? = null,
)

data class ToolResult(
    val ok: Boolean,
    val output: String,
    /**
     * Optional image rendered inline in the chat transcript and resolved into
     * [ChatMessage.imageData] for vision-capable models.
     */
    val image: com.ylstack1.ylharness.core.ImageRef? = null,
    val artifact: com.ylstack1.ylharness.artifacts.ChatArtifact? = null,
    val execution: List<com.ylstack1.ylharness.data.env.ExecutionReceipt> = emptyList(),
    val research: com.ylstack1.ylharness.core.ResearchReceipt? = null,
    val settings: com.ylstack1.ylharness.core.SettingsReceipt? = null,
    val chatHistory: com.ylstack1.ylharness.core.ChatHistoryReceipt? = null,
)

class ToolFailure(message: String) : Exception(message)

interface Tool {
    val name: String
    val description: String
    val parametersSchema: JsonObject
    val isReadOnly: Boolean

    fun isAvailable(ctx: ToolContext): Boolean = true

    suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult
}

class ToolRegistry(private val tools: List<Tool>) {
    private val byName = tools.associateBy { it.name }

    fun get(name: String): Tool? = byName[name]
    fun filter(predicate: (Tool) -> Boolean): ToolRegistry = ToolRegistry(byName.values.filter(predicate))

    fun restrictedTo(names: Set<String>?): ToolRegistry =
        if (names == null) this else ToolRegistry(tools.filter { it.name in names })

    /** Run-scoped extras (MCP tools) merge on top of the static set. */
    fun withExtra(extra: List<Tool>): ToolRegistry =
        if (extra.isEmpty()) this else ToolRegistry(tools + extra)

    fun without(names: Set<String>): ToolRegistry =
        if (names.isEmpty()) this else ToolRegistry(tools.filterNot { it.name in names })

    fun schemas(readOnlyOnly: Boolean = false, context: ToolContext? = null) = byName.values
        .filter { (!readOnlyOnly || it.isReadOnly) && (context == null || WorkspaceToolRouting.available(it, context)) }
        .sortedBy { it.name }
        .map {
            com.ylstack1.ylharness.llm.ToolSchema(it.name, it.description, WorkspaceToolRouting.schema(it, context?.workspace))
        }

    companion object {
        fun default(
            httpClient: okhttp3.OkHttpClient,
            todoStore: com.ylstack1.ylharness.agent.TodoStore,
            bgStore: com.ylstack1.ylharness.data.BgProcessStore,
            linuxEnv: com.ylstack1.ylharness.data.env.LinuxEnvironmentManager,
            shizuku: com.ylstack1.ylharness.data.env.ShizukuManager,
            shellRouter: com.ylstack1.ylharness.data.env.ShellTierRouter,
            skills: com.ylstack1.ylharness.skills.SkillStore,
            codeGraph: com.ylstack1.ylharness.data.env.CodeGraphManager,
            imageStore: com.ylstack1.ylharness.data.ImageStore? = null,
            browserController: com.ylstack1.ylharness.browser.BrowserController? = null,
            searchApi: () -> com.ylstack1.ylharness.tools.SearchApiConfig? = { null },
            artifactLoader: ArtifactLoader? = null,
            toolOutputLoader: ToolOutputLoader? = null,
            fileHistory: com.ylstack1.ylharness.data.WorkspaceFileHistory? = null,
            memory: com.ylstack1.ylharness.data.MemoryRepository? = null,
            searchApiRefresh: suspend () -> SearchApiConfig? = { searchApi() },
            dhizuku: com.ylstack1.ylharness.data.env.DhizukuManager? = null,
            deviceAccess: com.ylstack1.ylharness.data.env.DeviceAccessManager? = null,
            pullRequests: (() -> com.ylstack1.ylharness.github.PullRequestPublisher?)? = null,
            sessionMessages: suspend (String) -> List<com.ylstack1.ylharness.core.ChatMessage> = { emptyList() },
            appSettings: com.ylstack1.ylharness.data.SettingsRepository? = null,
            externalAgentManager: com.ylstack1.ylharness.agent.ExternalAgentManager? = null,
        ): ToolRegistry {
            val baseTools = mutableListOf<Tool>(
                ListDirTool(),
                ReadFileTool(),
                FileInfoTool(),
                WriteFileTool(),
                EditFileTool(),
                MultiEditTool(),
                ApplyPatchTool(),
                SearchFilesTool(),
                GrepTool(),
                ShellTool(linuxEnv, shellRouter),
                ShellBackgroundTool(bgStore, linuxEnv, shellRouter),
                EnvStatusTool(shizuku, linuxEnv, shellRouter),
                DoctorTool(linuxEnv, shizuku, shellRouter, httpClient),
                ReadLogcatTool(shizuku, router = shellRouter),
                PkgInstallTool(linuxEnv),
                AndroidSdkInstallTool(linuxEnv),
                PkgSearchTool(linuxEnv),
                PkgListTool(linuxEnv),
                BgListTool(bgStore),
                BgKillTool(bgStore),
                GitStatusTool(shellRouter, linuxEnv),
                GitDiffTool(shellRouter, linuxEnv),
                GitLogTool(shellRouter, linuxEnv),
                GitShowTool(shellRouter, linuxEnv),
                GitCommitTool(shellRouter, linuxEnv),
                GitBranchTool(shellRouter, linuxEnv),
                GitBranchManageTool(shellRouter, linuxEnv),
                GitCheckoutTool(shellRouter, linuxEnv),
                GitPushTool(shellRouter, linuxEnv),
                GitPullTool(shellRouter, linuxEnv),
                CreateDirTool(),
                DeleteFileTool(),
                MoveFileTool(),
                WebFetchTool(httpClient, searchApi = searchApi, refreshApi = searchApiRefresh,
                    renderedReader = browserController?.let { browser -> { budget -> BrowserReadTool(browser, budget) } }),
                WebSearchTool(httpClient, searchApi, refreshApi = searchApiRefresh),
                HttpRequestTool(httpClient) { linuxEnv.githubToken() },
                AskUserTool(),
                VisualGuideTool(),
                TaskTool(),
                MemoryWriteTool(),
                MemoryReadTool(),
                MemorySearchTool(),
                TodoWriteTool(todoStore),
                SkillViewTool(skills),
                SkillsListTool(skills),
                SkillManageTool(skills),
                CodeGraphExploreTool(codeGraph),
                CodeGraphNodeTool(codeGraph),
                CodeGraphImpactTool(codeGraph),
                CodeGraphAffectedTool(codeGraph),
                CodeGraphSyncTool(codeGraph),
            )
            externalAgentManager?.let {
                baseTools += ExternalAgentTool(it)
            }
            if (appSettings != null) {
                val read: suspend () -> com.ylstack1.ylharness.data.AppSettings = { appSettings.settings.first() }
                baseTools += AppSettingsFindTool(read)
                baseTools += AppSettingsChangeTool(appSettings, read)
            }
            if (dhizuku != null && deviceAccess != null) baseTools += DeviceOwnerShellTool(dhizuku, deviceAccess)
            if (pullRequests != null) baseTools += GitHubCreatePullRequestTool(pullRequests, sessionMessages)
            if (artifactLoader != null) {
                baseTools.add(CreateArtifactTool(artifactLoader))
                baseTools.add(ReadArtifactTool(artifactLoader))
            }
            if (toolOutputLoader != null) baseTools.add(ReadToolOutputTool(toolOutputLoader))
            if (fileHistory != null) {
                baseTools.add(FileHistoryTool(fileHistory))
                baseTools.add(FileRestoreTool(fileHistory))
            }
            if (memory != null) {
                baseTools.add(RememberTool(memory))
                baseTools.add(RecallTool(memory))
            }
            if (imageStore != null) {
                baseTools.add(ReadImageTool(imageStore))
            }
            if (browserController != null) {
                baseTools.addAll(
                    listOf(
                        BrowserNavigateTool(browserController),
                        BrowserClickTool(browserController),
                        BrowserTypeTool(browserController),
                        BrowserScrollTool(browserController),
                        BrowserEvalTool(browserController),
                        BrowserGetDomTool(browserController),
                        BrowserGetLogsTool(browserController),
                        BrowserScreenshotTool(browserController),
                        BrowserWaitForTool(browserController),
                        BrowserBackTool(browserController),
                        BrowserForwardTool(browserController),
                        BrowserRefreshTool(browserController),
                        BrowserGetUrlTool(browserController),
                        BrowserOpenExternalTool(browserController),
                    )
                )
            }
            return ToolRegistry(baseTools)
        }
    }
}

object Schema {
    fun string(description: String): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    fun integer(description: String): JsonObject = buildJsonObject {
        put("type", "integer")
        put("description", description)
    }

    fun boolean(description: String): JsonObject = buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }

    fun array(items: JsonObject, description: String): JsonObject = buildJsonObject {
        put("type", "array")
        put("description", description)
        put("items", items)
    }

    fun obj(
        properties: Map<String, JsonObject>,
        required: List<String> = emptyList(),
    ): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            properties.forEach { (k, v) -> put(k, v) }
        }
        if (required.isNotEmpty()) {
            putJsonArray("required") { required.forEach { add(it) } }
        }
    }
}
