package com.androidharness.app.ui.theme

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** Native dialog focus, sizing and actions with the same readable glass as the picker. */
@Composable
internal fun HarnessGlassAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    val glass = LocalHarnessGlass.current.mode != HarnessGlassMode.OFF
    val shape = MaterialTheme.shapes.extraLarge
    AlertDialog(onDismissRequest = onDismissRequest, confirmButton = confirmButton,
        dismissButton = dismissButton, title = title, text = text, shape = shape,
        modifier = if (glass) Modifier.harnessGlassOverlay(shape) else Modifier,
        containerColor = if (glass) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant)
}
