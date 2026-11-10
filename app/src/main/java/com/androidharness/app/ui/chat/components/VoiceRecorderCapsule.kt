package com.androidharness.app.ui.chat.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.ui.chat.GroqRecordState
import com.androidharness.app.ui.theme.expressiveEffectsSpec
import kotlin.math.sqrt

/** Recording and processing occupy the typing area, without opening another panel. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun VoiceRecorderCapsule(
    state: GroqRecordState,
    durationMs: Long,
    levels: List<Float>,
    cancelArmed: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val accent by animateColorAsState(
        if (cancelArmed) scheme.onErrorContainer else scheme.primary,
        expressiveEffectsSpec(), label = "voice capsule accent",
    )
    val surface by animateColorAsState(
        if (cancelArmed) scheme.errorContainer else scheme.primary.copy(alpha = .14f).compositeOver(scheme.surfaceContainerHigh),
        expressiveEffectsSpec(), label = "voice capsule surface",
    )
    Crossfade(
        targetState = state == GroqRecordState.TRANSCRIBING,
        modifier = modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).background(surface),
        animationSpec = expressiveEffectsSpec(), label = "voice capsule content",
    ) { processing ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (processing) {
                LoadingIndicator(Modifier.size(20.dp), color = accent)
                Text(
                    stringResource(R.string.voice_transcribing),
                    Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyMedium, color = accent,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            } else {
                VoiceWaveform(levels, accent, Modifier.weight(1f))
                val seconds = durationMs.coerceAtLeast(0) / 1000
                Text(
                    "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}",
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = accent, maxLines = 1,
                )
            }
        }
    }
}

/** Interpolate the displayed history, rather than snapping every bar at the recorder's sample rate. */
@Composable
private fun VoiceWaveform(levels: List<Float>, color: Color, modifier: Modifier) {
    BoxWithConstraints(modifier.height(24.dp)) {
        val count = (maxWidth / 6.dp).toInt().coerceIn(8, 96)
        var from by remember(count) { mutableStateOf(List(count) { 0f }) }
        var target by remember(count) { mutableStateOf(List(count) { 0f }) }
        val blend = remember(count) { Animatable(1f) }
        LaunchedEffect(levels, count) {
            val position = blend.value
            from = List(count) { from[it] + (target[it] - from[it]) * position }
            val samples = levels.takeLast(count)
            target = List(count) { index ->
                val source = index - (count - samples.size)
                val current = samples.getOrNull(source) ?: 0f
                val before = samples.getOrNull(source - 1) ?: current
                sqrt(((before + current * 2) / 3).coerceIn(0f, 1f))
            }
            blend.snapTo(0f)
            blend.animateTo(1f, tween(90, easing = LinearEasing))
        }
        Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))) {
            val step = size.width / count
            for (index in 0 until count) {
                val amplitude = from[index] + (target[index] - from[index]) * blend.value
                val height = 3.dp.toPx() + amplitude * (size.height - 8.dp.toPx())
                val x = (index + .5f) * step
                drawLine(color, Offset(x, (size.height - height) / 2), Offset(x, (size.height + height) / 2),
                    strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round,
                    alpha = .30f + .70f * (index + 1f) / count)
            }
        }
    }
}
