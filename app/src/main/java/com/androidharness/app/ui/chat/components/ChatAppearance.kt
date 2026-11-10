package com.androidharness.app.ui.chat.components

import androidx.compose.material3.MaterialTheme
import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Compact conversation typography that preserves the app's Material You palette. */
@Composable
internal fun ChatAppearance(content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val dark = base.surface.luminance() < 0.5f
    val background = base.surface
    val view = LocalView.current
    val window = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>().firstOrNull()?.window
    DisposableEffect(window, dark, background) {
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val oldBackground = window?.decorView?.background
        window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(background.toArgb()))
        val oldStatus = controller?.isAppearanceLightStatusBars
        val oldNavigation = controller?.isAppearanceLightNavigationBars
        @Suppress("DEPRECATION") val oldNavigationColor = window?.navigationBarColor
        // Match the native window too: Android 15 derives navigation contrast from its background.
        @Suppress("DEPRECATION")
        window?.navigationBarColor = background.toArgb()
        val oldContrast = if (android.os.Build.VERSION.SDK_INT >= 29) window?.isNavigationBarContrastEnforced else null
        if (android.os.Build.VERSION.SDK_INT >= 29) window?.isNavigationBarContrastEnforced = false
        controller?.isAppearanceLightStatusBars = !dark
        controller?.isAppearanceLightNavigationBars = !dark
        onDispose {
            window?.setBackgroundDrawable(oldBackground)
            @Suppress("DEPRECATION")
            if (oldNavigationColor != null) window?.navigationBarColor = oldNavigationColor
            if (oldStatus != null) controller?.isAppearanceLightStatusBars = oldStatus
            if (oldNavigation != null) controller?.isAppearanceLightNavigationBars = oldNavigation
            if (android.os.Build.VERSION.SDK_INT >= 29 && oldContrast != null) window?.isNavigationBarContrastEnforced = oldContrast
        }
    }
    val type = MaterialTheme.typography
    MaterialTheme(
        colorScheme = base,
        typography = type.copy(
            bodyLarge = type.bodyLarge.copy(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
            bodyMedium = type.bodyMedium.copy(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
            titleSmall = type.titleSmall.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
            labelMedium = type.labelMedium.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
            labelSmall = type.labelSmall.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
        ),
        content = content,
    )
}
