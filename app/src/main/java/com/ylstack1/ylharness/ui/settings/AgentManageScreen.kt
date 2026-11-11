package com.ylstack1.ylharness.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.ylstack1.ylharness.AppContainer
import com.ylstack1.ylharness.R
import com.ylstack1.ylharness.config.ExternalAgentConfig
import com.ylstack1.ylharness.ui.common.Button as AppButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * Per-agent management screen for Pi, OpenCode, or any external agent.
 *
 * Opens from the "Manage" button on [ExternalAgentsSection]. Shows agent-specific
 * configuration: enable toggle, provider, model, auth key, default model, skills,
 * MCP servers, working dir, install status, reinstall, and test connection.
 */
@Composable
internal fun AgentManageScreen(
    container: AppContainer,
    agent: ExternalAgentConfig,
    onDismiss: () -> Unit,
    onSave: (ExternalAgentConfig) -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val fs by container.workspace.current.collectAsStateWithLifecycle(initialValue = null)
    var config by remember { mutableStateOf(agent) }
    var loading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var showAuthField by remember { mutableStateOf(false) }

    /** Persist this agent's config to harness.json externalAgents. */
    fun saveConfig() {
        saving = true
        error = null
        message = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val currentFs = requireNotNull(fs) { "No workspace selected" }
                    val (path, previous) = container.configuration.document(currentFs, false)
                        ?: run {
                            throw IllegalStateException("No harness.json found. Open Configuration to create one.")
                        }
                    val text = updateExternalAgentInConfig(previous, config)
                    container.configuration.save(currentFs, false, path, text, previous, null)
                }
                saving = false
                message = uiContext.getString(R.string.settings_external_agents_save_saved)
                onSave(config)
            } catch (e: Exception) {
                saving = false
                error = e.message
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.settings_external_agents_manage_title,
                        agent.name.ifBlank { agent.id }))
                },
                navigationIcon = {
                    IconButton(onClick = { onDismiss() }) {
                        Text("✕")
                    }
                },
            )
        },
        modifier = Modifier.fillMaxWidth(),
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxWidth().padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (error != null) {
                Text(error!!, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
            message?.let { msg ->
                Text(msg, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${stringResource(R.string.settings_external_agents_agent_id)}: ${agent.id}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Switch(
                            checked = config.enabled,
                            onCheckedChange = { enabled ->
                                config = config.copy(enabled = enabled)
                                saveConfig()
                            },
                        )
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(if (config.enabled)
                                uiContext.getString(R.string.settings_external_agents_enabled)
                            else uiContext.getString(R.string.settings_external_agents_disabled),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (config.enabled) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            if (container.externalAgentManager.isPiInstalled() ||
                                container.externalAgentManager.isOpencodeInstalled())
                                stringResource(R.string.settings_external_agents_installed)
                            else stringResource(R.string.settings_external_agents_not_installed),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        AppButton(enabled = !loading, onClick = {
                            scope.launch {
                                loading = true
                                error = null
                                try {
                                    withContext(Dispatchers.IO) {
                                        if (agent.id == "pi") {
                                            container.externalAgentManager.installPi()
                                        } else {
                                            container.externalAgentManager.installOpencode()
                                        }
                                    }
                                    message = "Installed."
                                } catch (e: Exception) {
                                    error = e.message
                                } finally {
                                    loading = false
                                }
                            }
                        }) {
                            Text(stringResource(R.string.settings_external_agents_install))
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.settings_external_agents_provider),
                        style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = config.provider.orEmpty(),
                        onValueChange = {
                            config = config.copy(provider = it.takeIf { it.isNotEmpty() })
                        },
                        label = { Text(stringResource(R.string.settings_external_agents_provider)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            if (agent.id == "pi") Text("optional — Pi picks via /model")
                            else Text("e.g. anthropic")
                        },
                    )

                    Text(stringResource(R.string.settings_external_agents_model),
                        style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = config.model.orEmpty(),
                        onValueChange = {
                            config = config.copy(model = it.takeIf { it.isNotEmpty() })
                        },
                        label = { Text(stringResource(R.string.settings_external_agents_model)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            if (agent.id == "pi") Text("optional — Pi picks via /model")
                            else Text("e.g. claude-sonnet-5-5")
                        },
                    )

                    Text(stringResource(R.string.settings_external_agents_default_model),
                        style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = config.model.orEmpty(),
                        onValueChange = {
                            config = config.copy(model = it.takeIf { it.isNotEmpty() })
                        },
                        label = { Text(stringResource(R.string.settings_external_agents_default_model)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("e.g. anthropic/claude-sonnet-5-5") },
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(stringResource(R.string.settings_external_agents_auth_key),
                            style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { showAuthField = !showAuthField }) {
                            Text(if (showAuthField) "Hide" else "Show")
                        }
                    }
                    if (showAuthField) {
                        OutlinedTextField(
                            value = config.extraArgs.joinToString(" "),
                            onValueChange = {
                                config = config.copy(
                                    extraArgs = it.split(" ").filter { it.isNotEmpty() })
                            },
                            label = { Text("API key / auth (passed as extra args)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("sk-…") },
                        )
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.settings_external_agents_working_dir),
                        style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = config.workingDir.orEmpty(),
                        onValueChange = {
                            config = config.copy(workingDir = it.takeIf { it.isNotEmpty() })
                        },
                        label = { Text(stringResource(R.string.settings_external_agents_working_dir)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("optional — uses workspace or app dir") },
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.settings_external_agents_skills),
                        style = MaterialTheme.typography.titleSmall)
                    Text("Agent skills (comma-separated, e.g. code,read,write)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = config.extraArgs.joinToString(","),
                        onValueChange = {
                            config = config.copy(
                                extraArgs = it.split(",").filter { it.isNotEmpty() })
                        },
                        label = { Text(stringResource(R.string.settings_external_agents_skills)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("code,read,write") },
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.settings_external_agents_mcp),
                        style = MaterialTheme.typography.titleSmall)
                    Text("MCP server config via Configuration page",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = "",
                        onValueChange = { },
                        label = { Text(stringResource(R.string.settings_external_agents_mcp)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = false,
                        placeholder = { Text("MCP config via Configuration page") },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                AppButton(enabled = !saving && error == null, onClick = { saveConfig() }) {
                    Text(stringResource(R.string.settings_action_save))
                }
                TextButton(onClick = { onDismiss() }) {
                    Text(stringResource(R.string.settings_action_cancel))
                }
            }
        }
    }
}
