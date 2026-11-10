package com.androidharness.app.ui.common

import com.androidharness.app.R
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.androidharness.app.ui.theme.harnessGlass
import com.androidharness.app.ui.theme.harnessGlassChrome
import com.androidharness.app.ui.theme.LocalHarnessGlass
import com.androidharness.app.ui.theme.HarnessGlassMode
import androidx.compose.ui.graphics.Color

/** Native press morphing inside the caller's existing bounds and touch target. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ExpressiveIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = CircleShape,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    glassChrome: Boolean = false,
    preserveFill: Boolean = false,
    glassDecoration: Boolean = true,
    content: @Composable () -> Unit,
) {
    val mode = LocalHarnessGlass.current.mode
    val glass = glassDecoration && mode != HarnessGlassMode.OFF && (colors.containerColor.alpha > 0f || mode == HarnessGlassMode.LIQUID)
    MaterialTheme(motionScheme = MotionScheme.expressive()) {
        IconButton(
            onClick = onClick,
            shapes = IconButtonDefaults.shapes(shape = shape, pressedShape = RoundedCornerShape(12.dp)),
            modifier = modifier,
            enabled = enabled,
            colors = if (glass) colors.copy(containerColor = Color.Transparent, disabledContainerColor = Color.Transparent) else colors,
        ) {
            if (glass) {
                val tint = if (enabled) colors.containerColor else colors.disabledContainerColor
                val material = if (glassChrome) Modifier.harnessGlassChrome(tint, shape) else Modifier.harnessGlass(tint, shape)
                Box(Modifier.size(40.dp).then(material).then(if (preserveFill)
                    Modifier.background(tint.copy(alpha = if (enabled) .88f else .6f), shape) else Modifier),
                    contentAlignment = Alignment.Center, content = { content() })
            } else content()
        }
    }
}

/** Avoid flashing for instant refreshes; long waits keep a progress indicator in the same slot. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun CatalogLoadingIndicator(modifier: Modifier = Modifier) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    var phase by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        delay(200)
        phase = 1
        delay(4_800)
        phase = 2
    }
    Box(modifier.semantics { contentDescription = uiContext.getString(R.string.ui_refreshing_models) }, contentAlignment = Alignment.Center) {
        when (phase) {
            1 -> LoadingIndicator(Modifier.fillMaxSize())
            2 -> CircularProgressIndicator(Modifier.fillMaxSize(), strokeWidth = 2.dp)
        }
    }
}
