package com.androidharness.app.ui.theme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.androidharness.app.R

/** Paint inside the sheet's moving surface. Decorating its outer modifier clips
 * content before Material applies its slide offset, hiding the bottom actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HarnessGlassModalBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    modifier: Modifier = Modifier,
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    dragHandle: (@Composable () -> Unit)? = { BottomSheetDefaults.DragHandle() },
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    properties: ModalBottomSheetProperties = ModalBottomSheetProperties(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val glass = LocalHarnessGlass.current.mode != HarnessGlassMode.OFF
    ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState, modifier = modifier,
        shape = shape, dragHandle = if (glass) null else dragHandle,
        containerColor = if (glass) Color.Transparent else containerColor,
        contentColor = contentColor, properties = properties) {
        if (glass) {
        val panelBackdrop = rememberLayerBackdrop()
        Column(Modifier.fillMaxWidth().harnessGlassOverlay(shape, exportedBackdrop = panelBackdrop)) {
            CompositionLocalProvider(LocalHarnessGlassBackdrop provides panelBackdrop) {
            dragHandle?.let { handle ->
                val close = stringResource(R.string.settings_action_close)
                Box(Modifier.align(Alignment.CenterHorizontally).semantics { contentDescription = close }
                    .clickable(role = Role.Button, onClick = onDismissRequest)) { handle() }
            }
            content()
            }
        }
        } else content()
    }
}
