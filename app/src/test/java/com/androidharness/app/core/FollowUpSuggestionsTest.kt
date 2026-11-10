package com.androidharness.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowUpSuggestionsTest {
    @Test fun extractsAndRemovesTheBlock() {
        val raw = """
            The answer.

            ::followups
            - What about the DLC?
            - How does it compare to Dark Souls 3?
            - Is it good for new players?
            ::
        """.trimIndent()
        val result = FollowUpSuggestions.extract(raw)
        assertEquals("The answer.", result.text)
        assertEquals(
            listOf(
                "What about the DLC?",
                "How does it compare to Dark Souls 3?",
                "Is it good for new players?",
            ),
            result.items,
        )
        assertEquals("The answer.", FollowUpSuggestions.stripForDisplay(raw))
    }

    @Test fun hidesAnIncompleteBlockWhileStreaming() {
        val raw = "The answer.\n\n::followups\n- First"
        assertEquals("The answer.", FollowUpSuggestions.stripForDisplay(raw))
        assertEquals(emptyList<String>(), FollowUpSuggestions.extract(raw).items)
    }

    @Test fun ignoresMarkersInsideCodeFencesAndCapsTheList() {
        val raw = """
            Intro
            ```
            ::followups
            - Not a suggestion
            ::
            ```
            ::followups
            - One
            - One
            - Two
            - Three
            - Four
            ::
        """.trimIndent()
        val result = FollowUpSuggestions.extract(raw)
        assertTrue(result.text.contains("::followups"))
        assertEquals(listOf("One", "Two", "Three"), result.items)
    }
}
