package com.ylstack1.ylharness.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ylstack1.ylharness.AppContainer
import com.ylstack1.ylharness.agent.ExternalAgentManager
import com.ylstack1.ylharness.config.ExternalAgentConfig
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExternalAgentsSection(
    container: AppContainer,
    externalAgentManager: ExternalAgentManager,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val agents by remember {
        mutableStateOf<List<ExternalAgentConfig>>(emptyList())
    }

    val refresh = remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                externalAgentManager.listAgents()
            }
            agents.value = loaded
            refresh.value += 1
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "External Agents",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp),
        )
        Text(
            text = "Connect CLI coding agents (Pi, OpenCode, Claude Code) as external agents. Configure them in harness.json under externalAgents.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp, 0.dp, 16.dp, 16.dp),
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(agents, key = { it.id }) { agent ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = agent.name,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = "ID: ${agent.id} • ${if (agent.enabled) "Enabled" else "Disabled"}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (agent.provider != null || agent.model != null) {
                                Text(
                                    text = "${agent.provider ?: "default"} • ${agent.model ?: "default model"}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Row {
                            val isRunning = agent.id == "pi" // simplified check
                            if (agent.enabled) {
                                IconButton(
                                    onClick = { scope.launch {
                                        // toggle would go here
                                    } },
                                    enabled = true,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Stop,
                                        contentDescription = "Stop agent",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            } else {
                                IconButton(
                                    onClick = { scope.launch {
                                        // enable would go here
                                    } },
                                    enabled = true,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayCircleOutline,
                                        contentDescription = "Enable agent",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            IconButton(
                                onClick = { },
                                enabled = true,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Edit agent",
                                )
                            }
                        }
                    }
                }
            }
        }

        if (agents.isEmpty()) {
            Text(
                text = "No external agents configured. Add them to harness.json under externalAgents.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }

        TextButton(
            onClick = { scope.launch {
                // Open configuration editor
            } },
            modifier = Modifier.padding(16.dp, 8.dp, 16.dp, 16.dp),
        ) {
            Text(text = "Edit harness.json")
        }
    }
}
