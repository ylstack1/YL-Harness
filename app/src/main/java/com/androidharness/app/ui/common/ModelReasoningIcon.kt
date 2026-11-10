package com.androidharness.app.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.llm.ModelEntry
import com.androidharness.app.llm.ModelReasoning
import com.androidharness.app.llm.ProviderType

@Composable
fun ModelReasoningIcon(entry: ModelEntry, providerType: ProviderType? = null, directory: Boolean = false) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val status = ModelReasoning.of(entry, providerType)
    if (status == ModelReasoning.UNKNOWN || status == ModelReasoning.UNSUPPORTED) return
    var details by remember { mutableStateOf(false) }
    val description = when (status) {
        ModelReasoning.REPORTED -> if (directory) "Reasoning listed in model directory" else "Reasoning reported by provider"
        ModelReasoning.CUSTOM -> "Reasoning set by you"
        else -> "Reasoning inferred from model name"
    }
    IconButton(onClick = { details = true }) {
        Icon(painterResource(R.drawable.ic_brain),
            contentDescription = description, modifier = Modifier.size(20.dp),
            tint = if (status == ModelReasoning.INFERRED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary)
    }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text(description) },
        text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) { Text(when (status) {
            ModelReasoning.REPORTED -> if (directory) uiContext.getString(R.string.ui_the_model_directory_lists_reasoning_support_this)
                else uiContext.getString(R.string.ui_this_connection_s_catalog_reports_reasoning_support)
            ModelReasoning.CUSTOM -> uiContext.getString(R.string.ui_you_marked_this_custom_model_as_a)
            else -> uiContext.getString(R.string.ui_this_model_family_may_support_reasoning_but)
        }) } }, confirmButton = { TextButton(onClick = { details = false }) { Text(uiContext.getString(R.string.settings_action_got_it)) } })
}
