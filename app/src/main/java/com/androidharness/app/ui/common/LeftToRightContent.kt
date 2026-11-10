package com.androidharness.app.ui.common

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection

/** Keep technical text and its scrolling order readable inside an RTL interface. */
@Composable
internal fun LeftToRightContent(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        ProvideTextStyle(LocalTextStyle.current.copy(textDirection = TextDirection.Ltr, textAlign = TextAlign.Left), content)
    }
}
