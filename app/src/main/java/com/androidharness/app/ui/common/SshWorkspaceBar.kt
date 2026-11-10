package com.androidharness.app.ui.common

import com.androidharness.app.R
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.workspace.SshFs
import com.androidharness.app.workspace.SshStatus
import kotlinx.coroutines.*

/** One workspace-level control shared by chat, files, terminal and build screens. */
@Composable
fun SshWorkspaceBar(container: AppContainer, workspace: SshFs?, includeStatusBarPadding: Boolean = true) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    if (workspace == null) return
    val id = workspace.location.connectionId
    val statuses by container.sshConnections.statuses.collectAsStateWithLifecycle()
    val status = statuses[id] ?: SshStatus()
    var menu by remember(id) { mutableStateOf(false) }
    var settings by remember(id) { mutableStateOf(false) }
    var checking by remember(id) { mutableStateOf(false) }
    var result by remember(id) { mutableStateOf<String?>(null) }
    var forget by remember(id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (settings) SshWorkspaceDialog(container, workspace) { settings = false }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth()
            .then(if (includeStatusBarPadding) Modifier.statusBarsPadding() else Modifier)
            .padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(if (status.connected) uiContext.getString(R.string.ui_ssh_connected) else uiContext.getString(R.string.ui_ssh_disconnected), modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (status.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                if (!status.connected) TextButton(enabled = !checking, onClick = {
                    checking = true
                    scope.launch {
                        try { withContext(Dispatchers.IO) { container.sshConnections.reconnect(id) } }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { result = e.message }
                        finally { checking = false }
                    }
                }) { Text(uiContext.getString(R.string.ui_reconnect)) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, uiContext.getString(R.string.ui_workspace_connection)) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(uiContext.getString(R.string.ui_connection_settings)) }, onClick = { menu = false; settings = true })
                        DropdownMenuItem(text = { Text(uiContext.getString(R.string.settings_action_disconnect)) }, onClick = { menu = false; container.sshConnections.disconnect(id) })
                        DropdownMenuItem(text = { Text(uiContext.getString(R.string.ui_forget_credentials)) }, onClick = { menu = false; forget = true })
                    }
                }
            }
            if (status.uncertain) {
                Text(uiContext.getString(R.string.ui_a_command_or_file_transfer_was_interrupted), style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = !checking, onClick = {
                    checking = true
                    scope.launch {
                        try {
                            val response = workspace.run("git status --short --branch; git log -1 --oneline; ps -u \"\$(id -u)\"", timeoutMs = 15_000, maxOutput = 8_000)
                            result = response.rawOutput + "\n" + response.rawStderr
                        } finally { checking = false }
                    }
                }) { Text(uiContext.getString(R.string.ui_check_command_status)) }
                TextButton(onClick = { container.sshConnections.acknowledge(id) }) { Text(uiContext.getString(R.string.ui_i_checked_the_outcome)) }
            } else if (!status.connected) Text(status.message, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
    }
    result?.let { text -> AlertDialog(onDismissRequest = { result = null }, title = { Text(uiContext.getString(R.string.ui_connection_status)) },
        text = { androidx.compose.foundation.text.selection.SelectionContainer { Text(text.take(8_000)) } },
        confirmButton = { TextButton(onClick = { result = null }) { Text(uiContext.getString(R.string.settings_action_close)) } }) }
    if (forget) AlertDialog(onDismissRequest = { forget = false }, title = { Text(uiContext.getString(R.string.ui_forget_ssh_credentials)) },
        text = { Text(uiContext.getString(R.string.ui_this_disconnects_the_workspace_and_removes_its)) },
        confirmButton = { TextButton(onClick = { container.sshConnections.forget(id); forget = false }) { Text(uiContext.getString(R.string.ui_forget)) } },
        dismissButton = { TextButton(onClick = { forget = false }) { Text(uiContext.getString(R.string.settings_action_cancel)) } })
}
