package com.androidharness.app.ui.chat

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity

/** An expanded disclosure owns its gestures, including drags/flings at the ends of its content. */
internal class ChatDetailScroll(private val pauseTranscriptFollow: () -> Unit) {
    private val readers = mutableStateMapOf<Any, Boolean>()
    val reading: Boolean get() = readers.isNotEmpty()
    fun pause() = pauseTranscriptFollow()
    fun begin(key: Any) { readers[key] = true; pauseTranscriptFollow() }
    fun end(key: Any) { readers.remove(key) }
}
internal val LocalChatDetailScroll = compositionLocalOf<ChatDetailScroll?> { null }

internal object ContainDetailScroll : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = Offset(0f, available.y)
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = Velocity(0f, available.y)
}

@Composable
internal fun Modifier.containChatDetailScroll(): Modifier {
    val owner = LocalChatDetailScroll.current
    // Containment is useful even in standalone previews without a transcript owner.
    if (owner == null) return this.nestedScroll(ContainDetailScroll)
    val boundary = remember(owner) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) owner.pause()
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                ContainDetailScroll.onPostScroll(consumed, available, source)
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                ContainDetailScroll.onPostFling(consumed, available)
        }
    }
    val key = remember { Any() }
    DisposableEffect(owner, key) { onDispose { owner.end(key) } }
    return this.nestedScroll(boundary).pointerInput(owner, key) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            owner.begin(key)
            try {
                do { val event = awaitPointerEvent(PointerEventPass.Initial) } while (event.changes.any { it.pressed })
            } finally { owner.end(key) }
        }
    }
}
