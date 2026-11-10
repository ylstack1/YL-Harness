package com.androidharness.app.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.androidharness.app.R

@Composable
internal fun AnswerTableSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val glass = com.androidharness.app.ui.theme.LocalHarnessGlass.current.mode != com.androidharness.app.ui.theme.HarnessGlassMode.OFF
    if (glass) com.androidharness.app.ui.theme.HarnessGlassSurface(modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) { Column(content = content) }
    else Column(modifier.fillMaxWidth(), content = content)
}

/** A scroll cue and position indicator are shared by Markdown and interactive tables. */
@Composable
internal fun AnswerTableScrollHint(scroll: ScrollState) {
    if (scroll.maxValue <= 0 || scroll.maxValue == Int.MAX_VALUE) return
    val colors = MaterialTheme.colorScheme
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.fillMaxWidth().height(3.dp)) {
            val visible = size.width / (size.width + scroll.maxValue)
            val thumb = (size.width * visible).coerceIn(18.dp.toPx().coerceAtMost(size.width), size.width)
            val progress = scroll.value.toFloat() / scroll.maxValue
            val x = (size.width - thumb) * if (rtl) 1f - progress else progress
            drawRoundRect(colors.outlineVariant.copy(alpha = .5f), cornerRadius = CornerRadius(size.height))
            drawRoundRect(colors.primary.copy(alpha = .8f), topLeft = Offset(x, 0f), size = Size(thumb, size.height), cornerRadius = CornerRadius(size.height))
        }
        Text(stringResource(R.string.answer_table_swipe_hint), style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant)
    }
}
