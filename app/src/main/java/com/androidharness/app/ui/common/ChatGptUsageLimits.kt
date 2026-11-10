package com.androidharness.app.ui.common

import com.androidharness.app.R
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.androidharness.app.chatgpt.ChatGptProtocol

/** Account-limit reads are separate from inference; this sign-in currently opens the usage page. */
@Composable
fun ChatGptUsageLimits(compact: Boolean = false) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val context = LocalContext.current
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(uiContext.getString(R.string.ui_limits_resets), style = MaterialTheme.typography.titleSmall)
                    Text(uiContext.getString(R.string.ui_view_live_limits_in_chatgpt), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { openOAuthBrowser(context, Uri.parse(ChatGptProtocol.USAGE_URL)) }) {
                    Text(uiContext.getString(R.string.ui_view_usage))
                }
            }
            if (!compact) Text(uiContext.getString(R.string.ui_check_remaining_usage_reset_times_and_banked),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
