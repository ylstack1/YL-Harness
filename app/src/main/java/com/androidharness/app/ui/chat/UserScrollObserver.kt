package com.androidharness.app.ui.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource

/** Observe child drags before they move anything, without stealing scroll or edge handoff. */
internal class UserScrollObserver(private val onUserScroll: (Float) -> Unit) : NestedScrollConnection {
    /** Runs after the input has been applied, before a later stream update can move the end. */
    var onUserScrollApplied: (() -> Unit)? = null

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput && (consumed.y != 0f || available.y != 0f)) onUserScrollApplied?.invoke()
        return Offset.Zero
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput && available.y != 0f) onUserScroll(available.y)
        return Offset.Zero
    }
}
