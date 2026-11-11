package com.ylstack1.ylharness.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ylstack1.ylharness.AppContainer
import com.ylstack1.ylharness.R
import com.ylstack1.ylharness.agent.ExternalAgentManager
import com.ylstack1.ylharness.config.ExternalAgentConfig
import com.ylstack1.ylharness.ui.common.Button
import com.ylstack1.ylharness.ui.common.OutlinedButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.outlined.*
import kotlinx.serialization.json.*
import kotlin.collections.getOrPut
/**
 * Settings for external CLI agents (Pi, OpenCode, custom).
 *
 * Follows the app's settings design system: each agent is a
 * [SettingsDisclosure] expandable section with enable toggle, working dir,
 * provider/model fields, and an install row for Pi/OpenCode. Changes are
 * written back to the same `harness.json` `externalAgents` map that the
 * Configuration page edits, so GUI and JSON stay in sync.
 *
 * A custom agent can be added from the page (id, name, binary/command, args,
 * working dir, enabled).
 */
@Composable
internal fun ExternalAgentsSection(container: AppContainer, onOpenConfiguration: () -> Unit = {}) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val fs by container.workspace.current.collectAsStateWithLifecycle(initialValue = null)
    val configRevision by container.configuration.revision.collectAsStateWithLifecycle()
    var agents by remember { mutableStateOf<List<ExternalAgentConfig>>(emptyList()) }
    var installed by remember { mutableStateOf(mapOf<String, Boolean>()) }
    var installing by remember { mutableStateOf<String?>(null) }
    var installMessage by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    /** Per-agent unsaved edits, keyed by agent id. */
    var drafts by remember { mutableStateOf(mapOf<String, ExternalAgentConfig>()) }
    var showCustomAgentDialog by remember { mutableStateOf(false) }

    LaunchedEffect(fs?.displayPath, configRevision) {
        loading = true
        error = null
        try {
            val configured = if (fs != null) withContext(Dispatchers.IO) {
                container.configuration.load(requireNotNull(fs)).externalAgents
            } else emptyMap<String, ExternalAgentConfig>()
            // Built-in agents (Pi, OpenCode) always appear; config values (enabled,
            // workingDir, provider, model, …) override the built-in defaults when present.
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: uiContext.getString(R.string.settings_external_agents_empty)
        }
        loading = false
    }

    /** Persist one agent's draft back to harness.json externalAgents. */
    fun saveDraft(agentId: String) {
        val draft = drafts[agentId] ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val currentFs = requireNotNull(fs) { "No workspace" }
                    val (path, previous) = container.configuration.document(currentFs, false)
                    val text = updateExternalAgentInConfig(previous, draft)
                    container.configuration.save(currentFs, false, path, text, previous, null)
                }
                drafts = drafts - agentId
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message
            }
        }
    }

    /** Install Pi/OpenCode for [agentId], then refresh install state. */
    fun installAgent(agentId: String) {
        scope.launch {
            installing = agentId
            installMessage = null
            error = null
            try {
                withContext(Dispatchers.IO) {
                    if (agentId == "pi") {
                        container.externalAgentManager.installPi()
                    } else {
                        container.externalAgentManager.installOpencode()
                    }
                }
                installed = installed + (agentId to true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                installMessage = e.message
            } finally {
                installing = null
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

            // Builtin agents first (Pi, OpenCode), then any others.
            val builtinIds = listOf("pi", "opencode")
            val builtinAgents = agents.filter { it.id in builtinIds }
            val otherAgents = agents.filter { it.id !in builtinIds }

            // --- Builtin agents (Pi / OpenCode) ---
            if (builtinAgents.isNotEmpty()) {
                SettingsGroupHeading(R.string.settings_external_agents_builtin_group)
                builtinAgents.forEach { agent ->
                    val agentDraft = drafts[agent.id] ?: agent
                    AgentSettingsCard(
                        agent = agent,
                        draft = agentDraft,
                        isInstalled = installed[agent.id] == true,
                        isInstalling = installing == agent.id,
                        installMessage = installMessage,
                        onToggle = { enabled ->
                            val newDrafts = drafts.toMutableMap()
                            newDrafts[agent.id] = agentDraft.copy(enabled = enabled)
                            drafts = newDrafts
                        },
                        onProviderChange = { newProvider ->
                            val newDrafts = drafts.toMutableMap()
                            newDrafts[agent.id] = agentDraft.copy(
                                provider = newProvider?.takeIf { it.isNotEmpty() })
                            drafts = newDrafts
                        },
                        onModelChange = { newModel ->
                            val newDrafts = drafts.toMutableMap()
                            newDrafts[agent.id] = agentDraft.copy(
                                model = newModel?.takeIf { it.isNotEmpty() })
                            drafts = newDrafts
                        },
                        onWorkingDirChange = { newDir ->
                            val newDrafts = drafts.toMutableMap()
                            newDrafts[agent.id] = agentDraft.copy(
                                workingDir = newDir?.takeIf { it.isNotEmpty() })
                            drafts = newDrafts
                        },
                        onInstall = { installAgent(agent.id) },
                        onSave = { saveDraft(agent.id) },
                        onCancel = { drafts = drafts - agent.id; error = null },
                    )
                }
            }

            // --- Other configured agents ---
            if (otherAgents.isNotEmpty()) {
                SettingsGroupHeading(R.string.settings_external_agents_other_group)
                otherAgents.forEach { agent ->
                    val agentDraft = drafts[agent.id] ?: agent
                    AgentSettingsCard(
                        agent = agent,
                        draft = agentDraft,
                        isInstalled = installed[agent.id] == true,
                        isInstalling = installing == agent.id,
                        installMessage = installMessage,
                        onToggle = { enabled ->
                            val newDrafts = drafts.toMutableMap()
                            newDrafts[agent.id] = agentDraft.copy(enabled = enabled)
                            drafts = newDrafts
                        },
                        onProviderChange = null,
                        onModelChange = null,
                        onWorkingDirChange = { newDir ->
                            val newDrafts = drafts.toMutableMap()
                            newDrafts[agent.id] = agentDraft.copy(
                                workingDir = newDir?.takeIf { it.isNotEmpty() })
                            drafts = newDrafts
                        },
                        onInstall = null,
                        onSave = { saveDraft(agent.id) },
                        onCancel = { drafts = drafts - agent.id; error = null },
                    )
                }
            }

            // --- Add custom agent ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { showCustomAgentDialog = true }) {
                    Text(stringResource(R.string.settings_external_agents_add_custom))
                }
            }

            // --- Open raw configuration ---
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
}

/**
 * Per-agent settings card. Expandable disclosure section following the app's
 * [SettingsDisclosure] pattern. Shows enable toggle, provider/model, working
 * dir, and (for Pi/OpenCode) install status + install button.
 */
@Composable
private fun AgentSettingsCard(
    agent: ExternalAgentConfig,
    draft: ExternalAgentConfig,
    isInstalled: Boolean,
    isInstalling: Boolean,
    installMessage: String?,
    onToggle: (Boolean) -> Unit,
    onProviderChange: ((String?) -> Unit)?,
    onModelChange: ((String?) -> Unit)?,
    onWorkingDirChange: ((String?) -> Unit)?,
    onInstall: (() -> Unit)?,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val isChanged = draft != agent
    val isBuiltin = agent.id == "pi" || agent.id == "opencode"

    SettingsDisclosure(
        icon = Icons.Outlined.Extension,
        title = agent.name.ifBlank { agent.id },
        subtitle = if (agent.enabled) uiContext.getString(R.string.settings_external_agents_enabled)
        else uiContext.getString(R.string.settings_external_agents_disabled),
        anchors = setOf(R.string.settings_external_agents_agent_id),
        initiallyExpanded = isChanged,
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Enable toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(
                    checked = draft.enabled,
                    onCheckedChange = onToggle,
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

            // Provider / model (builtin agents that use them)
            if (onProviderChange != null || onModelChange != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (agent.id == "pi") {
                        OutlinedTextField(
                            value = draft.provider.orEmpty(),
                            onValueChange = { newProvider ->
                                onProviderChange?.invoke(newProvider.takeIf { it.isNotEmpty() })
                            },
                            label = { Text(stringResource(R.string.settings_external_agents_provider)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    OutlinedTextField(
                        value = draft.model.orEmpty(),
                        onValueChange = { newModel ->
                            onModelChange?.invoke(newModel.takeIf { it.isNotEmpty() })
                        },
                        label = { Text(stringResource(R.string.settings_external_agents_model)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        enabled = agent.id == "pi" || onProviderChange != null,
                    )
                }
            }

            // Working dir
            if (agent.workingDir != null || isBuiltin) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = draft.workingDir.orEmpty(),
                        onValueChange = { newDir ->
                            onWorkingDirChange?.invoke(newDir.takeIf { it.isNotEmpty() })
                        },
                        label = { Text(stringResource(R.string.settings_external_agents_working_dir)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Install status + action (builtin Pi/OpenCode only)
            if (isBuiltin && onInstall != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        if (isInstalled) stringResource(R.string.ui_installed)
                        else if (isInstalling) stringResource(R.string.settings_external_agents_installing)
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
                        OutlinedButton(enabled = true, onClick = { onInstall?.invoke() }) {
                            Text(stringResource(R.string.settings_external_agents_install))
                        }
                    } else if (isInstalling) {
                        OutlinedButton(enabled = false, onClick = {}) {
                            Text(stringResource(R.string.settings_external_agents_installing))
                        }
                    }
                }
            }

            // Save / cancel changes
            if (isChanged) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(enabled = true, onClick = onSave) {
                        Text(stringResource(R.string.settings_action_save))
                    }
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.settings_action_cancel))
                    }
                }
            }
        }
    }
}

/**
 * Built-in agents that always appear in the External Agents settings.
 * Config values (enabled, workingDir, provider, model, …) override defaults
 * when the agent is present in harness.json externalAgents.
 */
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
        provider = null,
        model = null,
        binary = null,
        workingDir = null,
        enabled = false,
    ),
)

/**
 * Strip // line comments and /* */ block comments from JSONC text, respecting
 * string literals, so the result is valid JSON for kotlinx.serialization.
 */
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

/**
 * Update (or create) one entry inside the `externalAgents` JSON object of a
 * config document, returning the full rewritten document text.
 */
private fun updateExternalAgentInConfig(configText: String?, agent: ExternalAgentConfig): String {
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

/**
 * Add a brand-new custom agent entry to the `externalAgents` JSON object.
 */
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

/**
 * Dialog for adding a custom external agent. Required fields: id and name.
 */
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

    androidx.compose.material3.AlertDialog(
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
