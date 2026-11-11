package com.ylstack1.ylharness.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Switch
import com.ylstack1.ylharness.AppContainer
import com.ylstack1.ylharness.R
import com.ylstack1.ylharness.agent.ExternalAgentManager
import com.ylstack1.ylharness.config.ExternalAgentConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * Settings for external CLI agents (Pi, OpenCode, custom).
 *
 * Built-in agents (Pi, OpenCode) always appear. Changes to enable/provider/model/workingDir
 * are saved to harness.json `externalAgents` immediately. Install downloads the Pi/OpenCode
 * binary to the app's private storage — NO workspace required.
 *
 * Each installed agent has a "Manage" button opening [AgentManageScreen] for skills/MCP/
 * provider/model/auth configuration.
 */
@Composable
internal fun ExternalAgentsSection(container: AppContainer, onOpenConfiguration: () -> Unit = {}) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val appFilesDir = uiContext.filesDir
    val scope = rememberCoroutineScope()
    val fs by container.workspace.current.collectAsStateWithLifecycle(initialValue = null)
    val configRevision by container.configuration.revision.collectAsStateWithLifecycle()
    var agents by remember { mutableStateOf<List<ExternalAgentConfig>>(emptyList()) }
    var installed by remember { mutableStateOf(mapOf<String, Boolean>()) }
    var installing by remember { mutableStateOf<String?>(null) }
    var installProgress by remember { mutableStateOf<String?>(null) }
    var installMessage by remember { mutableStateOf<String?>(null) }
    var installPercent by remember { mutableStateOf(0f) }
    var installStatus by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var drafts by remember { mutableStateOf(mapOf<String, ExternalAgentConfig>()) }
    var showCustomAgentDialog by remember { mutableStateOf(false) }
    var pendingDrafts by remember { mutableStateOf(mapOf<String, ExternalAgentConfig>()) }
    var showManageScreen by remember { mutableStateOf<String?>(null) }
    var showInstallDialog by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(fs?.displayPath, configRevision) {
        loading = true
        error = null
        try {
            val configured = if (fs != null) withContext(Dispatchers.IO) {
                container.configuration.load(requireNotNull(fs)).externalAgents
            } else emptyMap<String, ExternalAgentConfig>()
            agents = builtinAgents().map { builtin ->
                configured[builtin.id]?.let { configured ->
                    builtin.copy(
                        enabled = configured.enabled,
                        autoStart = configured.autoStart,
                        provider = configured.provider,
                        model = configured.model,
                        workingDir = configured.workingDir,
                        tier = configured.tier,
                        toolMapping = configured.toolMapping,
                        extraArgs = configured.extraArgs,
                    )
                } ?: builtin
            }
            installed = mapOf(
                "pi" to container.externalAgentManager.isPiInstalled(),
                "opencode" to container.externalAgentManager.isOpencodeInstalled(),
            )
            // Flush any pending drafts now that a workspace is available.
            if (fs != null && pendingDrafts.isNotEmpty()) {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val currentFs = requireNotNull(fs) { "No workspace selected" }
                            val (path, previous) = container.configuration.document(currentFs, false)
                                ?: run {
                                    throw IllegalStateException("No harness.json found.")
                                }
                            pendingDrafts.entries.forEach { (agentId, draft) ->
                                val text = updateExternalAgentInConfig(previous, draft)
                                container.configuration.save(currentFs, false, path, text, previous, null)
                            }
                        }
                        pendingDrafts = emptyMap()
                    } catch (e: Exception) {
                        // Keep pending for next workspace selection.
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: uiContext.getString(R.string.settings_external_agents_empty)
        }
        loading = false
    }

    /**
     * Persist one agent's draft back to harness.json externalAgents.
     * Updates agents list immediately so UI reflects change without delay.
     * If no workspace is selected, keeps the change in-memory and marks it pending.
     */
    fun saveDraft(agentId: String) {
        val draft = drafts[agentId] ?: return
        if (draft.id.isBlank()) {
            error = "Agent id is required."
            return
        }
        if (draft.name.isBlank()) {
            error = "Agent name is required."
            return
        }
        error = null
        // Update agents list immediately so the UI reflects the change without delay.
        agents = agents.map { if (it.id == agentId) draft else it }
        if (fs != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val currentFs = requireNotNull(fs) { "No workspace selected" }
                        val (path, previous) = container.configuration.document(currentFs, false)
                            ?: run {
                                throw IllegalStateException("No harness.json found. Open Configuration to create one.")
                            }
                        val text = updateExternalAgentInConfig(previous, draft)
                        container.configuration.save(currentFs, false, path, text, previous, null)
                    }
                    drafts = drafts - agentId
                    pendingDrafts = pendingDrafts - agentId
                    error = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e.message
                }
            }
        } else {
            // No workspace: keep in-memory, mark pending for when workspace is selected.
            drafts = drafts - agentId
            pendingDrafts = pendingDrafts + (agentId to draft)
        }
    }

    /**
     * Install Pi/OpenCode for [agentId], then auto-enable and save.
     * Shows a progress dialog during download. No workspace required.
     */
    fun installAgent(agentId: String) {
        showInstallDialog = agentId
        installProgress = null
        installMessage = null
        installPercent = 0f
        installStatus = null
        error = null
        scope.launch {
            installing = agentId
            try {
                withContext(Dispatchers.IO) {
                    if (agentId == "pi") {
                        installProgress = "Downloading Pi binary…"
                        val result = container.externalAgentManager.installPi() { downloaded, total ->
                            installPercent = if (total > 0) (downloaded * 100f / total) else 0f
                            installStatus = if (total > 0) {
                                "%.0f MB / %.0f MB".format(downloaded / 1024f / 1024f, total / 1024f / 1024f)
                            } else {
                                "%.0f MB".format(downloaded / 1024f / 1024f)
                            }
                        }
                        if (result.isFailure) {
                            throw result.exceptionOrNull() ?: Exception("Pi install failed")
                        }
                    } else {
                        installProgress = "Downloading OpenCode binary…"
                        val result = container.externalAgentManager.installOpencode() { downloaded, total ->
                            installPercent = if (total > 0) (downloaded * 100f / total) else 0f
                            installStatus = if (total > 0) {
                                "%.0f MB / %.0f MB".format(downloaded / 1024f / 1024f, total / 1024f / 1024f)
                            } else {
                                "%.0f MB".format(downloaded / 1024f / 1024f)
                            }
                        }
                        if (result.isFailure) {
                            throw result.exceptionOrNull() ?: Exception("OpenCode install failed")
                        }
                    }
                }
                installed = installed + (agentId to true)
                // Auto-enable after install.
                val agent = agents.find { it.id == agentId }
                if (agent != null) {
                    val enabledDraft = agent.copy(enabled = true)
                    drafts = drafts + mapOf(agentId to enabledDraft)
                    saveDraft(agentId)
                }
                installProgress = "Installed."
                installPercent = 100f
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                installMessage = e.message
            } finally {
                installing = null
                showInstallDialog = null
            }
        }
    }

    /** Reinstall an already-installed agent (overwrites the binary). */
    fun reinstallAgent(agentId: String) {
        showInstallDialog = agentId
        installProgress = null
        installMessage = null
        installPercent = 0f
        installStatus = null
        error = null
        scope.launch {
            installing = agentId
            try {
                withContext(Dispatchers.IO) {
                    if (agentId == "pi") {
                        installProgress = "Reinstalling Pi binary…"
                        val piDir = java.io.File(appFilesDir, "pi")
                        piDir.deleteRecursively()
                        val result = container.externalAgentManager.installPi() { downloaded, total ->
                            installPercent = if (total > 0) (downloaded * 100f / total) else 0f
                            installStatus = if (total > 0) {
                                "%.0f MB / %.0f MB".format(downloaded / 1024f / 1024f, total / 1024f / 1024f)
                            } else {
                                "%.0f MB".format(downloaded / 1024f / 1024f)
                            }
                        }
                        if (result.isFailure) {
                            throw result.exceptionOrNull() ?: Exception("Pi reinstall failed")
                        }
                    } else {
                        installProgress = "Reinstalling OpenCode binary…"
                        val opencodeDir = java.io.File(appFilesDir, "opencode")
                        opencodeDir.deleteRecursively()
                        val result = container.externalAgentManager.installOpencode() { downloaded, total ->
                            installPercent = if (total > 0) (downloaded * 100f / total) else 0f
                            installStatus = if (total > 0) {
                                "%.0f MB / %.0f MB".format(downloaded / 1024f / 1024f, total / 1024f / 1024f)
                            } else {
                                "%.0f MB".format(downloaded / 1024f / 1024f)
                            }
                        }
                        if (result.isFailure) {
                            throw result.exceptionOrNull() ?: Exception("OpenCode reinstall failed")
                        }
                    }
                }
                installed = installed + (agentId to true)
                installProgress = "Reinstalled."
                installPercent = 100f
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                installMessage = e.message
            } finally {
                installing = null
                showInstallDialog = null
            }
        }
    }

    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (loading) {
                Text(stringResource(R.string.settings_external_agents_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (agents.isEmpty() && error == null) {
                Text(stringResource(R.string.settings_external_agents_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            val builtinIds = listOf("pi", "opencode")
            val builtinAgentsList = agents.filter { it.id in builtinIds }
            val otherAgents = agents.filter { it.id !in builtinIds }

            if (builtinAgentsList.isNotEmpty()) {
                SettingsGroupHeading(R.string.settings_external_agents_builtin_group)
                builtinAgentsList.forEach { agent ->
                    val agentDraft = drafts[agent.id] ?: agent
                    val isInstalled = installed[agent.id] == true
                    val isInstalling = installing == agent.id

                    SettingsDisclosure(
                        icon = Icons.Outlined.Code,
                        title = agent.name.ifBlank { agent.id },
                        subtitle = if (agent.enabled) uiContext.getString(R.string.settings_external_agents_enabled)
                        else uiContext.getString(R.string.settings_external_agents_disabled),
                        anchors = setOf(R.string.settings_external_agents_agent_id),
                        initiallyExpanded = drafts.containsKey(agent.id) || !isInstalled,
                    ) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            val draft = agentDraft
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Switch(
                                    checked = draft.enabled,
                                    onCheckedChange = { enabled ->
                                        val newDraft = draft.copy(enabled = enabled)
                                        drafts = drafts + (agent.id to newDraft)
                                        saveDraft(agent.id)
                                    },
                                )
                                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                    Text(agent.id, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(if (draft.enabled) uiContext.getString(R.string.settings_external_agents_enabled)
                                    else uiContext.getString(R.string.settings_external_agents_disabled),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (draft.enabled) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            if (agent.id == "pi" || agent.id == "opencode") {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (agent.id == "pi") {
                                        OutlinedTextField(
                                            value = draft.provider.orEmpty(),
                                            onValueChange = { newProvider ->
                                                val newDraft = draft.copy(
                                                    provider = newProvider.takeIf { it.isNotEmpty() })
                                                drafts = drafts + (agent.id to newDraft)
                                                saveDraft(agent.id)
                                            },
                                            label = { Text(stringResource(R.string.settings_external_agents_provider)) },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f),
                                            placeholder = { Text("optional — Pi picks via /model") },
                                        )
                                    }
                                    OutlinedTextField(
                                        value = draft.model.orEmpty(),
                                        onValueChange = { newModel ->
                                            val newDraft = draft.copy(
                                                model = newModel.takeIf { it.isNotEmpty() })
                                            drafts = drafts + (agent.id to newDraft)
                                            saveDraft(agent.id)
                                        },
                                        label = { Text(stringResource(R.string.settings_external_agents_model)) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        placeholder = {
                                            if (agent.id == "pi") Text("optional — Pi picks via /model")
                                            else Text("e.g. anthropic/claude-sonnet-5-5")
                                        },
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedTextField(
                                    value = draft.workingDir.orEmpty(),
                                    onValueChange = { newDir ->
                                        val newDraft = draft.copy(
                                            workingDir = newDir.takeIf { it.isNotEmpty() })
                                        drafts = drafts + (agent.id to newDraft)
                                        saveDraft(agent.id)
                                    },
                                    label = { Text(stringResource(R.string.settings_external_agents_working_dir)) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    placeholder = { Text("optional — uses workspace or app dir") },
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    if (isInstalled) stringResource(R.string.ui_installed)
                                    else if (isInstalling) installProgress
                                        ?: stringResource(R.string.settings_external_agents_installing)
                                    else stringResource(R.string.ui_not_installed),
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isInstalled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                installMessage?.let { msg ->
                                    Text(msg, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error)
                                }
                                if (!isInstalled && !isInstalling) {
                                    TextButton(onClick = { installAgent(agent.id) }) {
                                        Text(stringResource(R.string.settings_external_agents_install))
                                    }
                                } else if (isInstalling) {
                                    TextButton(enabled = false, onClick = {}) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            CircularProgressIndicator(Modifier.size(16.dp))
                                            Text(stringResource(R.string.settings_external_agents_installing),
                                                Modifier.padding(start = 8.dp))
                                        }
                                    }
                                } else if (isInstalled) {
                                    TextButton(onClick = { showManageScreen = agent.id }) {
                                        Text(stringResource(R.string.settings_external_agents_manage))
                                    }
                                    TextButton(onClick = { reinstallAgent(agent.id) }) {
                                        Text(stringResource(R.string.settings_external_agents_reinstall))
                                    }
                                }
                            }

                            val nonToggleChanged = draft.provider != agent.provider ||
                                draft.model != agent.model ||
                                draft.workingDir != agent.workingDir
                            if (nonToggleChanged) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    Button(enabled = error == null, onClick = { saveDraft(agent.id) }) {
                                        Text(stringResource(R.string.settings_action_save))
                                    }
                                    TextButton(onClick = {
                                        drafts = drafts - agent.id
                                        error = null
                                    }) {
                                        Text(stringResource(R.string.settings_action_cancel))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (otherAgents.isNotEmpty()) {
                SettingsGroupHeading(R.string.settings_external_agents_other_group)
                otherAgents.forEach { agent ->
                    val agentDraft = drafts[agent.id] ?: agent

                    SettingsDisclosure(
                        icon = Icons.Outlined.Code,
                        title = agent.name.ifBlank { agent.id },
                        subtitle = if (agent.enabled) uiContext.getString(R.string.settings_external_agents_enabled)
                        else uiContext.getString(R.string.settings_external_agents_disabled),
                        anchors = setOf(R.string.settings_external_agents_agent_id),
                        initiallyExpanded = drafts.containsKey(agent.id),
                    ) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            val draft = agentDraft
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Switch(
                                    checked = draft.enabled,
                                    onCheckedChange = { enabled ->
                                        val newDraft = draft.copy(enabled = enabled)
                                        drafts = drafts + (agent.id to newDraft)
                                        saveDraft(agent.id)
                                    },
                                )
                                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                    Text(agent.id, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(if (draft.enabled) uiContext.getString(R.string.settings_external_agents_enabled)
                                    else uiContext.getString(R.string.settings_external_agents_disabled),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (draft.enabled) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            if (agent.workingDir != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    OutlinedTextField(
                                        value = draft.workingDir.orEmpty(),
                                        onValueChange = { newDir ->
                                            val newDraft = draft.copy(
                                                workingDir = newDir.takeIf { it.isNotEmpty() })
                                            drafts = drafts + (agent.id to newDraft)
                                            saveDraft(agent.id)
                                        },
                                        label = { Text(stringResource(R.string.settings_external_agents_working_dir)) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }

                            val nonToggleChanged = draft.workingDir != agent.workingDir
                            if (nonToggleChanged) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    Button(enabled = error == null, onClick = { saveDraft(agent.id) }) {
                                        Text(stringResource(R.string.settings_action_save))
                                    }
                                    TextButton(onClick = {
                                        drafts = drafts - agent.id
                                        error = null
                                    }) {
                                        Text(stringResource(R.string.settings_action_cancel))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { showCustomAgentDialog = true }) {
                    Text(stringResource(R.string.settings_external_agents_add_custom))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(onClick = onOpenConfiguration) {
                    Text(stringResource(R.string.settings_external_agents_open_config))
                }
            }
        }
    }

    // Install progress dialog
    if (showInstallDialog != null) {
        val agentId = showInstallDialog
        val agentName = agents.find { it.id == agentId }?.name ?: agentId
        AlertDialog(
            onDismissRequest = { showInstallDialog = null },
            title = { Text("Installing $agentName…") },
            text = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (installPercent > 0f && installPercent < 100f) {
                            CircularProgressIndicator(
                                progress = installPercent / 100f,
                                Modifier.size(24.dp),
                            )
                        } else {
                            CircularProgressIndicator(
                                Modifier.size(24.dp),
                            )
                        }
                        Column {
                            Text(installProgress ?: stringResource(R.string.settings_external_agents_installing),
                                style = MaterialTheme.typography.bodyMedium)
                            if (installPercent > 0f) {
                                Text("${installPercent.toInt()}%",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            installStatus?.let { status ->
                                Text(status, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    installMessage?.let { msg ->
                        Text(msg, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = { },
            dismissButton = { },
        )
    }

    // Custom agent dialog
    if (showCustomAgentDialog) {
        CustomAgentDialog(
            container = container,
            onDismiss = { showCustomAgentDialog = false },
            onSave = { id, name, binary, args, workingDir, enabled ->
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val currentFs = requireNotNull(fs) { "No workspace" }
                            val (path, previous) = container.configuration.document(currentFs, false)
                                ?: run {
                                    throw IllegalStateException("No harness.json found. Open Configuration to create one.")
                                }
                            val text = addCustomAgentToConfig(
                                previous, id, name, binary, args, workingDir, enabled,
                            )
                            container.configuration.save(currentFs, false, path, text, previous, null)
                        }
                        showCustomAgentDialog = false
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message
                    }
                }
            },
        )
    }

    // Per-agent manage screen
    if (showManageScreen != null) {
        val agent = agents.find { it.id == showManageScreen }
        if (agent != null) {
            AgentManageScreen(
                container = container,
                agent = agent,
                onDismiss = { showManageScreen = null },
                onSave = { updated ->
                    drafts = drafts + (agent.id to updated)
                    saveDraft(agent.id)
                    showManageScreen = null
                },
            )
        }
    }
}

private fun builtinAgents(): List<ExternalAgentConfig> = listOf(
    ExternalAgentConfig(
        id = "pi",
        name = "Pi",
        provider = null,
        model = null,
        binary = null,
        workingDir = null,
        enabled = false,
    ),
    ExternalAgentConfig(
        id = "opencode",
        name = "OpenCode",
        provider = "anthropic",
        model = "claude-sonnet-5-5",
        binary = null,
        workingDir = null,
        enabled = false,
    ),
)

private fun stripJsonComments(text: String): String {
    val result = StringBuilder(text.length)
    var inString = false
    var escaped = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (inString) {
            result.append(c)
            if (escaped) escaped = false
            else if (c == '\\') escaped = true
            else if (c == '"') inString = false
            i++
        } else {
            if (c == '"') {
                inString = true
                result.append(c)
                i++
            } else if (i + 1 < text.length && c == '/' && text[i + 1] == '/') {
                while (i < text.length && text[i] != '\n') i++
            } else if (i + 1 < text.length && c == '/' && text[i + 1] == '*') {
                i += 2
                while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                i += 2
            } else {
                result.append(c)
                i++
            }
        }
    }
    return result.toString()
}

internal fun updateExternalAgentInConfig(configText: String?, agent: ExternalAgentConfig): String {
    val json = Json { ignoreUnknownKeys = true }
    val clean = stripJsonComments(configText ?: "{}")
    val root = json.parseToJsonElement(clean).jsonObject
    val existing = root["externalAgents"]?.jsonObject
    val newExternalAgents = buildJsonObject {
        existing?.forEach { (k, v) -> put(k, v) }
        put(agent.id, buildJsonObject {
            put("name", agent.name)
            put("enabled", agent.enabled)
            agent.autoStart?.let { put("autoStart", it) }
            agent.provider?.let { put("provider", it) }
            agent.model?.let { put("model", it) }
            agent.binary?.let { put("binary", it) }
            agent.workingDir?.let { put("workingDir", it) }
            agent.tier?.let { put("tier", it) }
            if (agent.toolMapping.isNotEmpty()) {
                putJsonObject("toolMapping") {
                    agent.toolMapping.forEach { (k, v) -> put(k, v) }
                }
            }
            if (agent.extraArgs.isNotEmpty()) {
                putJsonArray("extraArgs") { agent.extraArgs.forEach { add(it) } }
            }
        })
    }
    val newRoot = buildJsonObject {
        root.forEach { (k, v) ->
            if (k == "externalAgents") put(k, newExternalAgents)
            else put(k, v)
        }
    }
    return json.encodeToString(newRoot)
}

private fun addCustomAgentToConfig(
    configText: String?,
    id: String, name: String, binary: String?, args: List<String>,
    workingDir: String?, enabled: Boolean,
): String {
    val json = Json { ignoreUnknownKeys = true }
    val clean = stripJsonComments(configText ?: "{}")
    val root = json.parseToJsonElement(clean).jsonObject
    val existing = root["externalAgents"]?.jsonObject
    val newExternalAgents = buildJsonObject {
        existing?.forEach { (k, v) -> put(k, v) }
        put(id, buildJsonObject {
            put("name", name)
            put("enabled", enabled)
            binary?.let { put("binary", it) }
            if (args.isNotEmpty()) putJsonArray("args") { args.forEach { add(it) } }
            workingDir?.let { put("workingDir", it) }
        })
    }
    val newRoot = buildJsonObject {
        root.forEach { (k, v) ->
            if (k == "externalAgents") put(k, newExternalAgents)
            else put(k, v)
        }
    }
    return json.encodeToString(newRoot)
}

@Composable
private fun CustomAgentDialog(
    container: AppContainer,
    onDismiss: () -> Unit,
    onSave: (id: String, name: String, binary: String?, args: List<String>,
        workingDir: String?, enabled: Boolean) -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var binary by remember { mutableStateOf("") }
    var args by remember { mutableStateOf("") }
    var workingDir by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_external_agents_add_custom_title)) },
        text = {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = id, onValueChange = { id = it },
                    label = { Text(stringResource(R.string.settings_external_agents_agent_id)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_external_agents_agent_name)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = binary, onValueChange = { binary = it },
                    label = { Text(stringResource(R.string.settings_external_agents_binary)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = args, onValueChange = { args = it },
                    label = { Text(stringResource(R.string.settings_external_agents_args)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = workingDir, onValueChange = { workingDir = it },
                    label = { Text(stringResource(R.string.settings_external_agents_working_dir)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                    )
                    Text(stringResource(R.string.settings_external_agents_enabled),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = id.isNotBlank() && name.isNotBlank(),
                onClick = {
                    val argList = args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                    onSave(id.trim(), name.trim(),
                        binary.trim().takeIf { it.isNotEmpty() },
                        argList,
                        workingDir.trim().takeIf { it.isNotEmpty() },
                        enabled)
                    onDismiss()
                },
            ) { Text(stringResource(R.string.settings_action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_action_cancel))
            }
        },
    )
}
