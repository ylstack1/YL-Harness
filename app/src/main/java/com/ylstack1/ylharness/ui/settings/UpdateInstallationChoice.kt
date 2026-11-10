package com.ylstack1.ylharness.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ylstack1.ylharness.AppContainer
import com.ylstack1.ylharness.R
import com.ylstack1.ylharness.data.update.UpdateInstallMethod
import com.ylstack1.ylharness.data.update.UpdateManager
import com.ylstack1.ylharness.ui.common.ExpressiveDropdownMenu
import com.ylstack1.ylharness.ui.common.ExpressiveDropdownMenuItem
import com.ylstack1.ylharness.ui.common.OutlinedButton

@Composable
internal fun UpdateInstallationChoice(c: AppContainer) {
    val method by c.updates.installMethod.collectAsStateWithLifecycle()
    val step by c.updates.step.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    fun label(value: UpdateInstallMethod) = when (value) {
        UpdateInstallMethod.AUTO -> R.string.update_install_auto
        UpdateInstallMethod.ROOT -> R.string.update_install_root
        UpdateInstallMethod.SHIZUKU -> R.string.update_install_shizuku
        UpdateInstallMethod.SYSTEM -> R.string.update_install_system
    }
    Text(stringResource(R.string.update_install_method), style = MaterialTheme.typography.labelMedium)
    Box {
        OutlinedButton(enabled = step !is UpdateManager.Step.Downloading && step !is UpdateManager.Step.Installing, onClick = { open = true }) { Text(stringResource(label(method))) }
        ExpressiveDropdownMenu(open, { open = false }) {
            UpdateInstallMethod.entries.forEach { choice -> ExpressiveDropdownMenuItem(text = { Text(stringResource(label(choice))) },
                onClick = { c.updates.setInstallMethod(choice); open = false }) }
        }
    }
    Text(stringResource(R.string.update_install_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
