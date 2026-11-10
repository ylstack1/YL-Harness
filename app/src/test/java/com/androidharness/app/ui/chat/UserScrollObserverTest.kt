package com.androidharness.app.ui.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import org.junit.Assert.*
import org.junit.Test

class UserScrollObserverTest {
    @Test fun `child drag pauses follow before scrolling without consuming its movement`() {
        var following = true
        val observer = UserScrollObserver { following = false }
        assertEquals(Offset.Zero, observer.onPreScroll(Offset(0f, 8f), NestedScrollSource.UserInput))
        assertFalse(following)
        following = true
        assertEquals(Offset.Zero, observer.onPreScroll(Offset(0f, -8f), NestedScrollSource.UserInput))
        assertFalse(following)
    }

    @Test fun `drag direction distinguishes reading older content from returning to the bottom`() {
        val deltas = mutableListOf<Float>()
        val observer = UserScrollObserver { deltas += it }
        observer.onPreScroll(Offset(0f, 8f), NestedScrollSource.UserInput)
        observer.onPreScroll(Offset(0f, -3f), NestedScrollSource.UserInput)
        assertEquals(listOf(8f, -3f), deltas)
    }

    @Test fun `automatic following flings and horizontal swipes do not trigger a new user drag`() {
        var calls = 0
        val observer = UserScrollObserver { calls++ }
        observer.onPreScroll(Offset(0f, 8f), NestedScrollSource.SideEffect)
        observer.onPreScroll(Offset(8f, 0f), NestedScrollSource.UserInput)
        observer.onPreScroll(Offset.Zero, NestedScrollSource.UserInput)
        assertEquals(0, calls)
    }
    @Test fun `applied input reports the reached edge but ignores animations and horizontal motion`() {
        var calls = 0
        val observer = UserScrollObserver {}.apply { onUserScrollApplied = { calls++ } }
        assertEquals(Offset.Zero, observer.onPostScroll(Offset(0f, -8f), Offset.Zero, NestedScrollSource.UserInput))
        assertEquals(1, calls)
        // An attempted drag at the edge still records that the user returned there.
        observer.onPostScroll(Offset.Zero, Offset(0f, -8f), NestedScrollSource.UserInput)
        assertEquals(2, calls)
        observer.onPostScroll(Offset(0f, -8f), Offset.Zero, NestedScrollSource.SideEffect)
        observer.onPostScroll(Offset(8f, 0f), Offset(4f, 0f), NestedScrollSource.UserInput)
        observer.onPostScroll(Offset.Zero, Offset.Zero, NestedScrollSource.UserInput)
        assertEquals(2, calls)
    }

}
