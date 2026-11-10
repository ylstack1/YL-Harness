package com.androidharness.app.ui.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChatDetailScrollTest {
    @Test fun detailTouchesPauseFollowWithoutLosingOwnershipWhenOneOfTwoReadersCloses() {
        var pauses = 0
        val owner = ChatDetailScroll { pauses++ }
        val first = Any(); val second = Any()
        assertFalse(owner.reading)
        owner.begin(first); owner.begin(second)
        owner.end(first)
        assertTrue(owner.reading)
        owner.end(second)
        assertFalse(owner.reading)
        owner.pause()
        assertFalse(owner.reading)
        assertEquals(3, pauses)
    }
    @Test fun disclosureEdgesAbsorbOnlyRemainingVerticalDragAndFling() = runBlocking {
        assertEquals(Offset(0f, -17f), ContainDetailScroll.onPostScroll(Offset(0f, -20f), Offset(5f, -17f), NestedScrollSource.UserInput))
        assertEquals(Offset(0f, 8f), ContainDetailScroll.onPostScroll(Offset.Zero, Offset(0f, 8f), NestedScrollSource.SideEffect))
        assertEquals(Velocity(0f, 1200f), ContainDetailScroll.onPostFling(Velocity(0f, 100f), Velocity(9f, 1200f)))
    }
}
