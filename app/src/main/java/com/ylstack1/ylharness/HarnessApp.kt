package com.ylstack1.ylharness

import android.app.Application
import android.content.Context
import android.webkit.WebView
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.room.Room
import com.ylstack1.ylharness.agent.AgentEngine
import com.ylstack1.ylharness.agent.TodoStore
import com.ylstack1.ylharness.data.ChatBackupManager
import com.ylstack1.ylharness.data.CheckpointStore
import com.ylstack1.ylharness.data.ImageStore
import com.ylstack1.ylharness.data.KeyStoreManager
import com.ylstack1.ylharness.data.ProviderRepository
import com.ylstack1.ylharness.data.SessionRepository
import com.ylstack1.ylharness.data.SettingsRepository
import com.ylstack1.ylharness.data.db.AppDatabase
import com.ylstack1.ylharness.llm.ProviderFactory
import com.ylstack1.ylharness.llm.effectiveApiKey
import com.ylstack1.ylharness.tools.ToolRegistry
import com.ylstack1.ylharness.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class HarnessApp : Application() {
    private val memoryScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        com.ylstack1.ylharness.local.GpuDeliveryFactory.attach(this)
    }
    lateinit var container: AppContainer
        private set

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate() {
        super.onCreate()
        // Paused LazyColumn prefetch crashes in LayoutNode.onChildRemoved when chat items change.
        ComposeFoundationFlags.isPausableCompositionInPrefetchEnabled = false
        WebView.enableSlowWholeDocumentDraw()
        container = AppContainer(this)
        memoryScope.launch { container.settings.ensureRumbleCombo() }
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::container.isInitialized && level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            memoryScope.launch { container.localModels.releaseCachedModel() }
        }
    }
    override fun onLowMemory() {
        super.onLowMemory()
        if (::container.isInitialized) memoryScope.launch { container.localModels.releaseCachedModel() }
    }
}

class AppContainer(val appContext: Context) {
    /** Deep-link channel: run-result notifications deliver a session id here. */
    val pendingSessionId = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)
    val pendingSubagentChat = kotlinx.coroutines.flow.MutableStateFlow<Pair<String, String>?>(null)
    /** Successful repository imports open their own files, independent of the old chat's workspace. */
    val pendingImportedWorkspace = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Share-target channel: content awaiting a regular Chat destination and draft import. */
    val pendingShare = kotlinx.coroutines.flow.MutableStateFlow<com.ylstack1.ylharness.data.PendingShare?>(null)

    /** Selected-code prompt waiting for its specific chat composer. */
    val pendingCodeDraft = kotlinx.coroutines.flow.MutableStateFlow<com.ylstack1.ylharness.ui.files.CodeChatDraft?>(null)

    /** Build/test failure prompt waiting for a fresh chat to run it. */
    val pendingAgentPrompt = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** In-app nav channel: a screen asking Settings to scroll to a section. */
    val pendingSettingsScroll = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    val keys = KeyStoreManager(appContext)
    init { com.ylstack1.ylharness.llm.ProviderNetwork.settings = keys::providerProxy }
    val settings = SettingsRepository(appContext)
    private val configurationRevision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val pluginManager = com.ylstack1.ylharness.plugins.PluginManager(appContext, configurationRevision,
        com.ylstack1.ylharness.plugins.PluginDownloads(githubToken = { github.accessToken() })) { pluginRuntime }
    private val pluginRuntime by lazy {
        object : com.ylstack1.ylharness.plugins.PluginRuntime {
            override val ready get() = linuxEnv.isReady
            override suspend fun available(wanted: List<String>): List<String> {
                val index = linuxEnv.getPackageIndex()
                // A fresh environment needs its core shell before any runtime can start.
                val core = if (linuxEnv.bashExecutable() == null) linuxEnv.corePackages else emptyList()
                return (core + wanted).distinct().filter { it in index }
            }
            override suspend fun install(packages: List<String>, requester: String,
                progress: suspend (com.ylstack1.ylharness.data.env.PackageInstallStatus) -> Unit) {
                if (packages.isNotEmpty()) linuxEnv.install(packages, requester, progress).getOrThrow()
            }
            override fun process(command: String) = linuxEnv.shellProcessBuilder(command)
        }
    }
    val configuration = com.ylstack1.ylharness.config.HarnessConfigRepository(appContext, keys, pluginManager, configurationRevision)
    val pluginHooks = com.ylstack1.ylharness.config.PluginHooks(appContext) { linuxEnv.shellProcessBuilder(it) }
    internal val runtimePolicy = AppRuntimePolicy(appContext, settings)
    /** Reads assistant replies with Android TTS or Groq PlayAI. */
    val tts = com.ylstack1.ylharness.data.audio.TtsController(appContext, settings, keys)
    internal val editorScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val editorDrafts = com.ylstack1.ylharness.ui.files.EditorDraftStore(
        java.io.File(appContext.noBackupFilesDir, "editor-drafts"), editorScope)
    internal val editorTabs = com.ylstack1.ylharness.ui.files.EditorTabsStore(
        java.io.File(appContext.noBackupFilesDir, "editor-tabs"), editorScope)
    val localModels = com.ylstack1.ylharness.local.LocalModelManager(appContext)
    val chatGpt = com.ylstack1.ylharness.chatgpt.ChatGptAccounts(keys::chatGptCredentials, keys::putChatGptCredentials,
        onConnected = { config ->
            settings.setActiveSelection(config.id, config.model)
        }, onDisconnected = { id ->
            runManager.runningSessionIds.value.filter {
                val saved = runManager.controls.flow(it).value
                saved.provider?.id == id || id in saved.usedProviderIds ||
                    runManager.subagents.runs.value.any { run -> run.sessionId == it && run.status.active && run.providerId == id }
            }
                .forEach { runManager.stopAndJoin(it) }
        })
    val providers: ProviderRepository = ProviderRepository(appContext, keys, localModels, chatGpt, settings)

    init {
        com.ylstack1.ylharness.llm.RotatingKeyProvider.pools = providers::controls
        ProviderFactory.chatGptProvider = com.ylstack1.ylharness.chatgpt.ChatGptProvider(chatGpt::accessToken,
            modelEntry = { id, model -> chatGpt.state.value.accounts.firstOrNull { it.providerId == id }?.models?.firstOrNull { it.id == model } })
        com.ylstack1.ylharness.llm.ModelCatalog.chatGptModels = { chatGpt.refreshModels(it) }
        ProviderFactory.localProvider = com.ylstack1.ylharness.local.LocalModelProvider(localModels) {
            settings.settings.first().localModelAgentContext
        }
    }
    val screenshotPolicy = com.ylstack1.ylharness.data.ScreenshotPolicy()

    private val db = Room.databaseBuilder(appContext, AppDatabase::class.java, "harness.db")
        .addMigrations(
            AppDatabase.MIGRATION_24_25, AppDatabase.MIGRATION_23_24, AppDatabase.MIGRATION_22_23, AppDatabase.MIGRATION_21_22, AppDatabase.MIGRATION_20_21, AppDatabase.MIGRATION_19_20, AppDatabase.MIGRATION_18_19, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6,
            AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8,
            AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12, AppDatabase.MIGRATION_12_13, AppDatabase.MIGRATION_13_14, AppDatabase.MIGRATION_14_15, AppDatabase.MIGRATION_15_16, AppDatabase.MIGRATION_16_17, AppDatabase.MIGRATION_17_18,
        )
        .addCallback(AppDatabase.OVERSIZED_ROW_SANITIZER)
        // Only kicks in when no migration path exists (pre-v4 databases);
        // the v4→v5 path above preserves sessions and usage totals.
        .fallbackToDestructiveMigration(true)
        .build()
    val images = ImageStore(appContext)
    val sessions = SessionRepository(db, images)
    val memory = com.ylstack1.ylharness.data.MemoryRepository(db, settings)
    val chatModels = com.ylstack1.ylharness.data.ChatModelRepository(settings, providers, sessions)
    suspend fun startChatAgent(profile: com.ylstack1.ylharness.data.ChatAgentProfile): String {
        profile.validated()
        val id = sessions.createSession(profile.name, workspace.currentProjectOnce().id,
            com.ylstack1.ylharness.data.ConversationConfig(kind = com.ylstack1.ylharness.data.ConversationKind.CHAT, agent = profile))
        chatModels.initialize(id)
        settings.setLastActiveSessionId(id)
        return id
    }

    private suspend fun canRecallChat(from: String?, target: String): Boolean {
        val source = from?.let { sessions.session(it) } ?: return false
        val destination = sessions.session(target) ?: return false
        val config = com.ylstack1.ylharness.data.ConversationConfig.decode(source.conversationJson)
        return com.ylstack1.ylharness.data.ChatHistoryPolicy.canRead(config,
            com.ylstack1.ylharness.data.ConversationConfig.decode(destination.conversationJson), settings.settings.first())
    }
    val chatBackup by lazy { ChatBackupManager(db, appContext, workspace) }
    val snippets = com.ylstack1.ylharness.data.SnippetRepository(db.dao())

    val sshConnections = com.ylstack1.ylharness.workspace.SshConnections(keys)
    val workspace = WorkspaceManager(appContext, db.dao(), sshConnections)
    val fileHistory = com.ylstack1.ylharness.data.WorkspaceFileHistory(java.io.File(appContext.filesDir, "workspace-file-history"))
    val checkpoints = CheckpointStore(db.dao(), fileHistory)

    val todoStore = TodoStore()
    val repoMap = com.ylstack1.ylharness.repomap.RepoMapCache()
    private val fetchClient = OkHttpClient()
    val linuxEnv =
        com.ylstack1.ylharness.data.env.LinuxEnvironmentManager(appContext) { keys.githubToken() }
    val wirelessAdb = com.ylstack1.ylharness.data.env.adb.WirelessAdbManager(appContext)
    val shizuku = com.ylstack1.ylharness.data.env.ShizukuManager(appContext, wireless = wirelessAdb)
    val dhizuku = com.ylstack1.ylharness.data.env.DhizukuManager(appContext)
    val deviceAccess = com.ylstack1.ylharness.data.env.DeviceAccessManager(appContext, shizuku, linuxEnv,
        projectForSession = { sessions.session(it)?.projectId }, dhizuku = dhizuku)
    val shellRouter = com.ylstack1.ylharness.data.env.ShellTierRouter(appContext, shizuku, linuxEnv, deviceAccess)
    val codeGraph = com.ylstack1.ylharness.data.env.CodeGraphManager(linuxEnv, shellRouter)

    init {
        // Package-set changes must invalidate the deployed-copy cache, or the
        // privileged tier keeps serving the old toolchain until a restart.
        linuxEnv.deployStateListener = { shizuku.invalidateDeployState() }
    }

    val updates = com.ylstack1.ylharness.data.update.UpdateManager(appContext, deviceAccess)
    internal val sync by lazy { com.ylstack1.ylharness.data.transfer.SyncManager(keys, sshConnections) { fs, action ->
        runManager.withIdleWorkspace(fs, "Stop running tasks in this workspace before syncing files.", action)
    } }
    internal val ftpShare by lazy { com.ylstack1.ylharness.data.transfer.FtpShareManager(appContext) { fs, action ->
        runManager.withIdleWorkspace(fs, "Stop running tasks in this workspace before changing shared files.", action)
    } }
    val backgroundProcesses = com.ylstack1.ylharness.data.BgProcessStore(
        appContext, linuxEnv, shizuku, workspaceRoot = workspace.appPrivateRoot, deviceAccess = deviceAccess,
    )

    init { deviceAccess.stopBackgroundTasks = backgroundProcesses::stopPrivilegedTasks }

    @Volatile
    private var projectSkillsDir: java.io.File? = null
    private val disabledSkills = AtomicReference<Set<String>>(emptySet())
    private val webSearchProvider = AtomicReference("keyless")
    private val searchKeyMigrated = AtomicBoolean(false)

    /**
     * web_search backend config, resolved per call so Settings changes apply
     * to the very next search without a restart. Null = keyless engines.
     */
    val searchApiConfig: com.ylstack1.ylharness.tools.SearchApiConfig?
        get() {
            val provider = webSearchProvider.get()
            if (provider == "keyless") return null
            return searchApiConfigFor(provider, rotate = false).takeIf { it.keys.isNotEmpty() }
        }

    /**
     * Enabled keys in this call's order: rotation moves the start key, the rest are
     * failover. Tool descriptions read it without [rotate] so they do not skip a turn.
     */
    private fun searchApiConfigFor(provider: String, rotate: Boolean = true): com.ylstack1.ylharness.tools.SearchApiConfig {
        val pool = runCatching { keys.searchKeyPool(provider) }.getOrNull()
            ?: return com.ylstack1.ylharness.tools.SearchApiConfig(provider, keys.searchApiKey(provider).orEmpty())
        val ordered = (if (rotate) com.ylstack1.ylharness.llm.RotatingKeyProvider.orderedKeys("search:$provider", pool)
            else pool.keys.filter { it.enabled }).map { it.value }
        return com.ylstack1.ylharness.tools.SearchApiConfig(provider, ordered.firstOrNull().orEmpty(), ordered.drop(1))
    }

    private suspend fun resolveSearchApiConfig(): com.ylstack1.ylharness.tools.SearchApiConfig? {
        val provider = settings.settings.first().webSearchProvider
        keys.migrateLegacySearchKey(provider)
        webSearchProvider.set(provider)
        if (provider == "keyless") return null
        return searchApiConfigFor(provider)
    }

    private val cavemanSettings = AtomicReference(com.ylstack1.ylharness.data.AppSettings())
    val skills = com.ylstack1.ylharness.skills.SkillStore(
        bundled = com.ylstack1.ylharness.skills.SkillAssets.load(appContext.assets),
        userDir = java.io.File(appContext.filesDir, "skills").apply { mkdirs() },
        projectDir = { projectSkillsDir },
        disabled = { disabledSkills.get() },
        optionalBundled = {
            if (cavemanSettings.get().cavemanInstalled) com.ylstack1.ylharness.caveman.CavemanPolicy.skills else emptyMap()
        },
    )
    val browser = com.ylstack1.ylharness.browser.BrowserController(appContext, images)
    val registry = ToolRegistry.default(
        fetchClient, todoStore, backgroundProcesses, linuxEnv, shizuku, shellRouter, skills, codeGraph,
        imageStore = images,
        browserController = browser,
        searchApi = { searchApiConfig },
        searchApiRefresh = ::resolveSearchApiConfig,
        artifactLoader = sessions::artifacts,
        toolOutputLoader = sessions::toolOutput,
        fileHistory = fileHistory,
        memory = memory,
        dhizuku = dhizuku,
        deviceAccess = deviceAccess,
        // Declared below; resolved on each call, after construction.
        pullRequests = { githubRepositories },
        sessionMessages = { sessions.messages(it) },
        appSettings = settings,
    )
    val mcp = com.ylstack1.ylharness.tools.mcp.McpManager(appContext, linuxEnv, keys, codeGraph, configuration)
    val github = com.ylstack1.ylharness.github.GitHubAuth(BuildConfig.GITHUB_CLIENT_ID,
        keys::githubCredential, keys::putGitHubCredential, { refreshGitHubAuth() })
    fun routedProvider(): com.ylstack1.ylharness.llm.LlmProvider =
        com.ylstack1.ylharness.llm.FallbackProvider(
                factory = ProviderFactory::create,
                resolve = { target ->
                    providers.providers.first().firstOrNull { it.id == target.providerId && it.enabled }
                        ?.takeIf { provider -> com.ylstack1.ylharness.llm.ProviderModelSearch.models(provider, providers.catalog(target.providerId)).any { it.id == target.model } }
                        ?.copy(model = target.model)
                },
                keyFor = { config ->
                    if (config.id == com.ylstack1.ylharness.llm.HarnessProvider.ID) providers.harnessApiKey()
                    else {
                        val live = providers.providers.first().firstOrNull { it.id == config.id }
                        if (live == null || live.baseUrl != config.baseUrl || live.type != config.type) ""
                        else config.effectiveApiKey(providers.apiKey(config.id))
                    }
                },
                destination = { config ->
                    if (config.id == com.ylstack1.ylharness.llm.HarnessProvider.ID)
                        com.ylstack1.ylharness.llm.HarnessProvider.pool[config.model]?.baseUrl ?: config.baseUrl
                    else config.baseUrl
                },
        )

    val engine: AgentEngine = AgentEngine(
        providerFactory = { routedProvider() },
        registry = registry,
        checkpointer = checkpoints,
        imageStore = images,
        linuxEnv = linuxEnv,
        shizuku = shizuku,
        skills = skills,
        resolveConfiguration = { configuration.load(it) },
        pluginHooks = pluginHooks,
        todoStore = todoStore,
        repoMap = repoMap,
        cavemanSettings = { settings.settings.first().also { cavemanSettings.set(it); disabledSkills.set(it.disabledSkills) } },
        configuredSubagent = {
            com.ylstack1.ylharness.agent.SubagentConfiguration.configured(
                settings.settings.first(), providers.providers.first(), providers::apiKey,
            )?.let { connection ->
                com.ylstack1.ylharness.agent.SubagentConnection(
                    providers.resolveHarnessConfig(connection.config), connection.apiKey,
                )
            }
        },
        prepareGitHub = { github.refreshIfNeeded() },
        deviceAccess = deviceAccess,
        universalMemory = memory,
        chatOnlyTools = listOf(com.ylstack1.ylharness.tools.ReadChatTextTool(sessions::messages),
            com.ylstack1.ylharness.tools.ViewChatImageTool(images),
            com.ylstack1.ylharness.tools.BrowserReadTool(browser),
            com.ylstack1.ylharness.tools.SearchChatsTool { query, fuzzy, limit, offset ->
                sessions.searchRecallMessages(query, fuzzy, limit, offset)
            }.apply { canAccess = ::canRecallChat },
            com.ylstack1.ylharness.tools.RecentChatsTool(sessions.sessions).apply { canAccess = ::canRecallChat },
            com.ylstack1.ylharness.tools.ReadChatSnippetTool(sessions::messages, sessions::session).apply { canAccess = ::canRecallChat },
            com.ylstack1.ylharness.tools.OfferCodeHandoffTool(),
            com.ylstack1.ylharness.tools.GitHubApiTool(fetchClient) { github.accessToken() },
            com.ylstack1.ylharness.tools.GitHubSearchTool(fetchClient) { github.accessToken() },
            com.ylstack1.ylharness.tools.GitHubFileTool(fetchClient) { github.accessToken() }),
    )
    val runManager = com.ylstack1.ylharness.agent.RunManager(
        context = appContext,
        engine = engine,
        sessions = sessions,
        checkpoints = checkpoints,
        workspace = workspace,
        linuxEnv = linuxEnv,
        settings = settings,
        todoStore = todoStore,
        providers = providers,
        mcp = mcp,
        repoMap = repoMap,
        chatModels = chatModels,
        canRun = runtimePolicy::canRun,
    )
    val githubRepositories = com.ylstack1.ylharness.github.GitHubWorkspaces(this)
    val automation = com.ylstack1.ylharness.automation.AutomationManager(this)
    val projectDeletion = com.ylstack1.ylharness.workspace.ProjectDeletion(this)
    internal var telegram = com.ylstack1.ylharness.telegram.TelegramManager(this)
    val interactiveTerminal = com.ylstack1.ylharness.terminal.InteractiveTerminalManager(linuxEnv, runManager)
    val terminal = com.ylstack1.ylharness.data.TerminalManager(appContext, linuxEnv, shizuku, runManager, shellRouter, interactiveTerminal)
    val storage = com.ylstack1.ylharness.data.storage.StorageManager(this)
    val projectChecks = com.ylstack1.ylharness.data.ProjectCheckStore(appContext, terminal)

    /**
     * Applies a GitHub token change end-to-end: re-materialize the prefix
     * copies (token file + gitconfig rewrite), then redeploy the shell-tier
     * toolchain so the new auth is live without an app restart.
     */
    val nineRouter by lazy { com.ylstack1.ylharness.router.NineRouterManager(appContext, linuxEnv,
        runManager::acquireBackgroundKeepalive, runManager::releaseBackgroundKeepalive, providers, settings) }

    suspend fun refreshGitHubAuth() = linuxEnv.refreshGitHub(shizuku, strict = true)

    init {
        runtimePolicy.start(onBackgroundDisabled = {
            runManager.stopIfBackgroundDisabled()
            if (!runtimePolicy.canRun()) {
                // One unavailable device backend must not disable the policy observer.
                runCatching { telegram.suspendForBackground() }
                runCatching { ftpShare.stop() }
                runCatching { terminal.stopTerminal() }
                runCatching { backgroundProcesses.killAll() }
                AgentService.stop(appContext)
            }
        }, onAvailable = { telegram.restoreIfEnabled() })
        // models.dev thinking-capability catalog: serve the cached copy
        // synchronously, then refresh in the background (daily cadence).
        com.ylstack1.ylharness.llm.ModelsDev.load(appContext)
        kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            combine(settings.settings, providers.providers) { saved, available -> saved to available.map { it.id }.toSet() }
                .collect { (saved, availableIds) ->
                    val selected = listOf(saved.activeProviderId, saved.planningProviderId, saved.executionProviderId, saved.subagentProviderId)
                    if (selected.any { it != null && (com.ylstack1.ylharness.local.LocalModelCatalog.isLocal(it) || com.ylstack1.ylharness.chatgpt.ChatGptProtocol.isProvider(it)) && it !in availableIds }) {
                        settings.clearMissingLocalProviderSelections(availableIds)
                    }
                }
        }
        kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // Learned wire per Harness model (chat vs anthropic vs responses),
            // persisted by the first-request probe; mirrored into memory so
            // every request path reads it synchronously.
            providers.harnessWires.collect { com.ylstack1.ylharness.llm.HarnessProvider.pins = it }
        }
        kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // Restore routes before fetching; failed refreshes leave the cache intact.
            providers.providers.first()
            when (val result = com.ylstack1.ylharness.llm.ModelCatalog.listModels(
                com.ylstack1.ylharness.llm.HarnessProvider.config,
                com.ylstack1.ylharness.llm.HarnessProvider.KEYLESS,
            )) {
                is com.ylstack1.ylharness.llm.ModelCatalog.Result.Models ->
                    providers.saveCatalog(com.ylstack1.ylharness.llm.HarnessProvider.ID, result.models)
                is com.ylstack1.ylharness.llm.ModelCatalog.Result.Failed -> Unit
            }
        }
        kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            com.ylstack1.ylharness.llm.ModelsDev.refresh(appContext)
        }
        kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            settings.settings.collect {
                disabledSkills.set(it.disabledSkills)
                cavemanSettings.set(it)
                // The search key store moved to per-provider slots; attribute
                // the pre-split key to whichever provider was active first.
                if (searchKeyMigrated.compareAndSet(false, true)) {
                    keys.migrateLegacySearchKey(it.webSearchProvider)
                }
                webSearchProvider.set(it.webSearchProvider)
            }
        }
        kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            workspace.current.collect { fs ->
                projectSkillsDir = fs.shellRoot?.resolve(".harness/skills")
            }
        }
        // Regenerate the linker shims the moment the environment is ready: an
        // older format (broken argv[0] for coreutils applets) must not survive
        // an app update, and the header check makes this a no-op when current.
        kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            linuxEnv.state.collect {
                if (it is com.ylstack1.ylharness.data.env.EnvState.Ready) linuxEnv.ensureShims()
            }
        }
        // Eagerly deploy the shell-user toolchain copy the moment both the
        // Linux environment and Shizuku are ready, so every tier works from
        // the first command (foreground shell, background spawn, terminal).
        kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // include serviceState so a retry fires once the user service binds
            combine(linuxEnv.state, shizuku.state, shizuku.serviceState) { e, s, v -> Triple(e, s, v) }
                .collect { (e, s, v) ->
                    if (e is com.ylstack1.ylharness.data.env.EnvState.Ready &&
                        s == com.ylstack1.ylharness.data.env.ShizukuState.GRANTED &&
                        v.isReady
                    ) {
                        linuxEnv.ensureShellDeploy(shizuku)
                    }
                }
        }
        // Self-heal prefixes installed by older builds: relink dangling
        // termux-absolute symlinks, rewrite Termux shebangs, resume installs
        // that were interrupted and reinstall packages whose binaries
        // vanished. Silent when the prefix is already healthy; network
        // failures never demote a Ready environment (retried next launch).
        kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val repaired = linuxEnv.repairIfNeeded()
            // A repair changes prefix content (reinstalled pip, rewritten
            // shebangs) without necessarily changing the package-set hash, and
            // StateFlow conflates the unchanged Ready state so the deploy
            // collector above never re-fires, re-stage the shell-tier copy
            // explicitly so it picks the fixes up.
            if (repaired != null &&
                shizuku.state.value == com.ylstack1.ylharness.data.env.ShizukuState.GRANTED &&
                shizuku.serviceState.value.isReady
            ) {
                linuxEnv.ensureShellDeploy(shizuku)
            }
        }
    }
}
