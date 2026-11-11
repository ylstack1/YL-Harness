package com.ylstack1.ylharness.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ylstack1.ylharness.AppContainer
import com.ylstack1.ylharness.R
import com.ylstack1.ylharness.agent.ExternalAgentManager
import com.ylstack1.ylharness.config.ExternalAgentConfig
import com.ylstack1.ylharness.ui.common.OutlinedButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings for external CLI agents configured in harness.json (Pi, OpenCode,
 * Claude Code, ...). The page is read-only on purpose: the agent list, its
 * enabled state and tool mapping live in the workspace configuration document,
 * edited on the Configuration page. What this page adds is visibility — which
 * agents a workspace would expose — and the install state of the bundled Pi
 * binary, which is downloaded from its release rather than configured.
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
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(fs?.displayPath, configRevision) {
        loading = true
        error = null
        try {
            agents = if (fs != null) withContext(Dispatchers.IO) {
                container.configuration.load(requireNotNull(fs)).externalAgents.values.toList()
            } else emptyList()
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

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (agents.isEmpty() && !loading && error == null) {
            Text(stringResource(R.string.settings_external_agents_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        agents.forEach { agent ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(agent.name.ifBlank { agent.id },
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (agent.enabled) stringResource(R.string.settings_external_agents_enabled)
                            else stringResource(R.string.settings_external_agents_disabled),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (agent.enabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(agent.id, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (agent.provider != null || agent.model != null) {
                        Text(listOfNotNull(agent.provider, agent.model).joinToString("  ·  "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (agent.workingDir != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.settings_external_agents_working_dir),
                                style = MaterialTheme.typography.bodySmall)
                            Text(" " + agent.workingDir.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (agent.id == "pi" || agent.id == "opencode") {
                        InstallRow(
                            installed = installed[agent.id] == true,
                            installing = installing == agent.id,
                            onInstall = {
                                scope.launch {
                                    installing = agent.id
                                    error = null
                                    try {
                                        if (agent.id == "pi") container.externalAgentManager.installPi()
                                        else container.externalAgentManager.installOpencode()
                                        installed = installed + (agent.id to true)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        error = e.message
                                    }
                                    installing = null
                                }
                            },
                        )
                    }
                }
            }
        }
        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Text(stringResource(R.string.settings_external_agents_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onOpenConfiguration) {
            Text(stringResource(R.string.settings_external_agents_open_config))
        }
    }
}

/**
 * Install status + action for an agent binary that is downloaded from its
 * release (Pi, OpenCode) rather than configured.
 */
@Composable
private fun InstallRow(installed: Boolean, installing: Boolean, onInstall: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(if (installed) R.string.ui_installed else R.string.ui_not_installed),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = if (installed) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!installed) {
            OutlinedButton(enabled = !installing, onClick = onInstall) {
                Text(stringResource(R.string.settings_external_agents_install))
            }
        }
    }
}
