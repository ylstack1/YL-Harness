package com.ylstack1.ylharness.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * Settings for external CLI agents (Pi, OpenCode, custom).
 *
 * Follows the app's settings design system: each agent is a
 * [SettingsDisclosure] expandable section. Built-in agents (Pi, OpenCode)
 * always appear; custom agents can be added via the "Add custom agent" button.
 *
 * Changes to enable/provider/model/workingDir are saved to harness.json
 * `externalAgents` immediately — no separate Save step required for toggles.
 * Install downloads the Pi/OpenCode binary to the app's private storage
 * (no workspace required) and auto-enables the agent when done.
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
    var installMessage by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var drafts by remember { mutableStateOf(mapOf<String, ExternalAgentConfig>()) }
    var showCustomAgentDialog by remember { mutableStateOf(false) }

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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: uiContext.getString(R.string.settings_external_agents_empty)
        }
        loading = false
    }

    /**
     * Persist one agent's draft back to harness.json externalAgents.
     * Includes validation and proper error handling.
     */
    fun saveDraft(agentId: String) {
        val draft = drafts[agentId] ?: return
        // Validate before saving.
        if (draft.id.isBlank()) {
            error = "Agent id is required."
            return
        }
        if (draft.name.isBlank()) {
            error = "Agent name is required."
            return
        }
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
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message
            }
        }
    }

    /**
     * Install Pi/OpenCode for [agentId], then enable the agent and save.
     */
    fun installAgent(agentId: String) {
        scope.launch {
            installing = agentId
            installMessage = null
            error = null
            try {
                withContext(Dispatchers.IO) {
                    val currentFs = fs
                    val workingDir = currentFs?.shellRoot
                        ?: appFilesDir
                    if (agentId == "pi") {
                        container.externalAgentManager.installPi()
                    } else {
                        container.externalAgentManager.installOpencode()
                    }
                }
                installed = installed + (agentId to true)
                // Auto-enable the agent after a successful install.
                val agent = agents.find { it.id == agentId }
                    ?: return@launch
                val enabledDraft = agent.copy(enabled = true)
                drafts = drafts + mapOf(agentId to enabledDraft)
                saveDraft(agentId)
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

            val builtinIds = listOf("pi", "opencode")
            val builtinAgentsList = agents.filter { it.id in builtinIds }
            val otherAgents = agents.filter { it.id !in builtinIds }

            if (builtinAgentsList.isNotEmpty()) {
                SettingsGroupHeading(R.string.settings_external_agents_builtin_group)
                builtinAgentsList.forEach { agent ->
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
                                )
                            }

                            val isInstalled = installed[agent.id] == true
                            val isInstalling = installing == agent.id
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
                                    TextButton(onClick = { installAgent(agent.id) }) {
                                        Text(stringResource(R.string.settings_external_agents_install))
                                    }
                                } else if (isInstalling) {
                                    TextButton(enabled = false, onClick = {}) {
                                        Text(stringResource(R.string.settings_external_agents_installing))
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