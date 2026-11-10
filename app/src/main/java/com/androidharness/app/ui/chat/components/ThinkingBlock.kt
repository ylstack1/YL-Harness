package com.androidharness.app.ui.chat.components

import com.androidharness.app.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.androidharness.app.ui.chat.UserScrollObserver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import com.androidharness.app.ui.common.DotLoading
import com.androidharness.app.ui.theme.defaultEffectsSpec
import com.androidharness.app.ui.theme.fastEffectsSpec
import com.androidharness.app.ui.theme.fastSpatialSpec
import com.androidharness.app.agent.ThinkingTiming
import com.androidharness.app.agent.thinkingTokenCount
import kotlinx.coroutines.delay

/**
 * Collapsible reasoning transcript. Deliberately quiet: a label, a chevron, and
 * a hairline rail next to the body text. No brain icons, no purple, reasoning
 * is supporting evidence, not the main character.
 *
 * While live the block stays collapsed with compact metrics (fast streams would
 * otherwise fight the layout); expanding mid-stream shows a height-capped,
 * auto-following view that stops following if the user scrolls up.
 */
@Composable
internal fun ThinkingBlock(
    thinking: String,
    live: Boolean = false,
    thinkingMs: Long = 0,
    reasoningTokens: Int? = null,
    timing: ThinkingTiming? = null,
    autoExpand: Boolean = true,
) {
    if (thinking.isBlank() && !live && (reasoningTokens ?: 0) <= 0) return
    ChatActivity(
        messages = if (live) emptyList() else listOf(com.androidharness.app.core.ChatMessage(
            role = com.androidharness.app.core.Role.ASSISTANT,
            thinking = thinking,
            thinkingMs = timing?.durationMs(ThinkingTiming.now()) ?: thinkingMs,
            generation = com.androidharness.app.core.GenerationMetadata(reasoningTokens = reasoningTokens),
        )),
        live = live,
        streamingThinking = if (live) thinking else "",
        showThinkingMetrics = true,
        thinkingTiming = timing.takeIf { live },
        reasoningTokens = reasoningTokens.takeIf { live },
        autoExpand = autoExpand,
    )
}

@Composable
private fun ThinkingText(text: String, modifier: Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontStyle = FontStyle.Italic,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
