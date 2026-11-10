package com.androidharness.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchTextTest {
    @Test
    fun `render and parse round trip keeps dates and urls`() {
        val rendered = WebSearchText.render(
            listOf(
                WebSearchResult("First", "https://a.test/1", "one", "2 days ago"),
                WebSearchResult("Second", "https://b.test/2", "two"),
            ),
            10,
        )
        assertTrue(rendered.contains("[2 days ago] one"))
        val parsed = WebSearchText.parse(rendered)
        assertEquals(listOf("First", "Second"), parsed.map { it.title })
        assertEquals(listOf("https://a.test/1", "https://b.test/2"), parsed.map { it.url })
    }

    @Test
    fun `parse ignores budget notes and keeps the old shape readable`() {
        val text = "1. Old result\n   https://old.test/x\n   snippet\n" +
            "[sources this turn: 1/5, 4 remaining; pages read: 0/2 required. Read more.]"
        val parsed = WebSearchText.parse(text)
        assertEquals(1, parsed.size)
        assertEquals("Old result", parsed.single().title)
        assertEquals("https://old.test/x", parsed.single().url)
    }

    @Test
    fun `parse skips malformed entries`() {
        val text = "1. No url\n   not a url\n2. Real\n   https://real.test/a\n   x"
        assertEquals(listOf("https://real.test/a"), WebSearchText.parse(text).map { it.url })
    }
}
