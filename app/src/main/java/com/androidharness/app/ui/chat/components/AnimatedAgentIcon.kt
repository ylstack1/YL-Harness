package com.androidharness.app.ui.chat.components

import androidx.compose.animation.core.Animatable
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.androidharness.app.ui.theme.defaultEffectsSpec
import com.androidharness.app.ui.theme.fastEffectsSpec

/** A brief selection gesture, never an idle loop. Compose honors the system animation scale. */
@Composable
internal fun AnimatedAgentIcon(key: String, selected: Boolean, description: String?, modifier: Modifier = Modifier) {
    val pulse = remember { Animatable(0f) }
    var previousSelected by remember { mutableStateOf(selected) }
    val nudge = with(LocalDensity.current) { 2.dp.toPx() }
    LaunchedEffect(selected) {
        val newlySelected = selected && !previousSelected
        previousSelected = selected
        if (newlySelected) {
            pulse.animateTo(1f, fastEffectsSpec())
            pulse.animateTo(0f, defaultEffectsSpec())
        } else if (!selected) pulse.animateTo(0f, fastEffectsSpec())
    }
    Icon(agentIcon(key), description, modifier.graphicsLayer {
        val amount = pulse.value
        val growth = when (key) { "spark", "idea" -> .12f; "research" -> .07f; else -> .04f }
        scaleX = 1f + growth * amount
        scaleY = scaleX
        rotationZ = when (key) {
            "spark" -> -9f; "study" -> -6f; "write" -> -5f; "research" -> 10f; "travel" -> 16f; else -> 0f
        } * amount
        translationX = if (key == "write") nudge * amount else 0f
        translationY = if (key in setOf("study", "write", "idea")) -nudge * amount else 0f
    })
}
