package com.androidharness.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.androidharness.app.data.CustomThemePalette
import com.materialkolor.dynamicColorScheme

internal fun CustomThemePalette.colorScheme(dark: Boolean): ColorScheme = dynamicColorScheme(
    seedColor = Color(primary), isDark = dark,
    secondary = secondary?.let { Color(it) }, tertiary = tertiary?.let { Color(it) },
)

internal fun canvasColorScheme(seed: Long, dark: Boolean): ColorScheme = dynamicColorScheme(Color(seed), isDark = dark)
