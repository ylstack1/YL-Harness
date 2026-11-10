package com.androidharness.app.ui.files

import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.rosemoe.sora.widget.component.EditorCompletionAdapter
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlin.math.ceil

/** Two-line Material text sizing; Sora's fixed 45dp rows clip at larger accessibility fonts. */
internal class MaterialCompletionAdapter : EditorCompletionAdapter() {
    private fun dp(value: Float) = value * context.resources.displayMetrics.density
    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, context.resources.displayMetrics)
    override fun getItemHeight() = ceil(dp(20f) + (sp(16f) + sp(12f)) * 1.5f).toInt()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup, isCurrentCursorPosition: Boolean): View {
        val row = convertView as? LinearLayout ?: LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f).toInt(), dp(8f).toInt(), dp(16f).toInt(), dp(8f).toInt())
            for (size in listOf(16f, 12f)) addView(TextView(context).apply {
                textSize = size
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        row.layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, itemHeight)
        val item = getItem(position)
        (row.getChildAt(0) as TextView).apply { text = item.label; setTextColor(getThemeColor(EditorColorScheme.COMPLETION_WND_TEXT_PRIMARY)) }
        (row.getChildAt(1) as TextView).apply { text = item.desc; setTextColor(getThemeColor(EditorColorScheme.COMPLETION_WND_TEXT_SECONDARY)) }
        row.setBackgroundColor(if(isCurrentCursorPosition) getThemeColor(EditorColorScheme.COMPLETION_WND_ITEM_CURRENT) else android.graphics.Color.TRANSPARENT)
        row.tag = position
        return row
    }
}
