package com.androidharness.app.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.androidharness.app.ui.theme.expressiveSpatialSpec
import com.androidharness.app.ui.theme.harnessGlassOverlay
import com.androidharness.app.ui.theme.LocalHarnessGlass
import com.androidharness.app.ui.theme.HarnessGlassMode
import androidx.compose.ui.graphics.Color

/** Native anchored menus retain edge avoidance, scrolling, keyboard focus and back dismissal. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ExpressiveDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 8.dp),
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val maxWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() - 32.dp }
        .coerceAtLeast(48.dp).coerceAtMost(360.dp)
    val glass = LocalHarnessGlass.current.mode != HarnessGlassMode.OFF
    MaterialTheme(motionScheme = MotionScheme.expressive()) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = modifier.widthIn(min = 208.dp.coerceAtMost(maxWidth), max = maxWidth)
                .then(if (glass) Modifier.harnessGlassOverlay(RoundedCornerShape(28.dp)) else Modifier),
            offset = offset,
            scrollState = scrollState,
            properties = properties,
            shape = RoundedCornerShape(if (glass) 28.dp else 24.dp),
            containerColor = if (glass) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 0.dp,
            shadowElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f)),
            content = content,
        )
    }
}

@Composable
internal fun ExpressiveDropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
) {
    val interactions = remember { MutableInteractionSource() }
    DropdownMenuItem(
        text = text, onClick = onClick,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            .expressivePressFeedback(interactions).clip(RoundedCornerShape(16.dp)).then(modifier),
        leadingIcon = leadingIcon, trailingIcon = trailingIcon, enabled = enabled,
        contentPadding = contentPadding, interactionSource = interactions,
    )
}

/** Paint-only feedback keeps the hit target and neighboring rows stationary. */
@Composable
internal fun Modifier.expressivePressFeedback(interactions: MutableInteractionSource): Modifier {
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .985f else 1f, expressiveSpatialSpec(), label = "pressScale")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}
