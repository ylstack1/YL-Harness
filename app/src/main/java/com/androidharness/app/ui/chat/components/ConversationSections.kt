package com.androidharness.app.ui.chat.components

import com.androidharness.app.ui.theme.HarnessGlassSurface as Surface
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.data.ConversationConfig
import com.androidharness.app.data.ConversationKind

@Composable
internal fun ConversationSectionSwitcher(
    config: ConversationConfig,
    enabled: Boolean,
    onSelect: (ConversationKind) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        BoxWithConstraints {
            FlowRow(Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                maxItemsInEachRow = if (maxWidth < 180.dp * LocalDensity.current.fontScale) 1 else 2) {
                ConversationKind.entries.forEach { kind ->
                    val active = config.kind == kind
                    Surface(onClick = { if (!active) onSelect(kind) }, enabled = enabled,
                        color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.weight(1f).semantics { role = Role.Tab; selected = active }) {
                        Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            Icon(if (kind == ConversationKind.CODE) Icons.Outlined.Code else Icons.Outlined.ChatBubbleOutline,
                                null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(if (kind == ConversationKind.CODE) R.string.conversation_code else R.string.conversation_chat),
                                style = MaterialTheme.typography.labelLarge, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ConversationModeLabel(config: ConversationConfig, workspaceName: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(if (config.isChat) Icons.Outlined.ChatBubbleOutline else Icons.Outlined.Code, null,
            Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        val section = stringResource(if (config.isChat) R.string.conversation_chat else R.string.conversation_code)
        val detail = workspaceName?.takeIf { !config.isChat && it.isNotBlank() }
        Text(section + detail?.let { " · $it" }.orEmpty(), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}
