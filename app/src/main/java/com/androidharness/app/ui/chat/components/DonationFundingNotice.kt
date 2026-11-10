package com.androidharness.app.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androidharness.app.HarnessApp
import com.androidharness.app.R
import com.androidharness.app.ui.common.ExpressiveIconButton
import com.androidharness.app.ui.theme.expressiveEffectsSpec
import com.androidharness.app.ui.theme.expressiveSpatialSpec
import kotlinx.coroutines.launch

@Composable
internal fun DonationFundingNotice() {
    val context = LocalContext.current
    val settings = remember(context) { (context.applicationContext as HarnessApp).container.settings }
    // Wait for the saved preference, so a dismissed message never flashes on a new chat.
    val dismissed by settings.donationNoticeDismissed.collectAsState(initial = true)
    val scope = rememberCoroutineScope()
    AnimatedVisibility(
        visible = !dismissed,
        enter = expandVertically(animationSpec = expressiveSpatialSpec()) + fadeIn(animationSpec = expressiveEffectsSpec()),
        exit = shrinkVertically(animationSpec = expressiveSpatialSpec()) + fadeOut(animationSpec = expressiveEffectsSpec()),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Top) {
            Text(stringResource(R.string.donate_funding_message),
                Modifier.weight(1f).padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            ExpressiveIconButton(onClick = { scope.launch { settings.setDonationNoticeDismissed(true) } }) {
                Icon(Icons.Outlined.Close, stringResource(R.string.donate_dismiss_message))
            }
        }
    }
}
