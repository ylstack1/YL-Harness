package com.androidharness.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageImagesTest {
    @Test fun `page image block round trips and ignores trailing notes`() {
        val images = listOf(
            PageImage("Red panda", "https://site.test/hero.jpg", "https://site.test/red-panda", "site.test"),
            PageImage("Sleeping red panda", "https://cdn.test/photo.webp", "https://site.test/red-panda", "site.test"),
        )
        val rendered = PageImageText.render(images, 8) + "\n[pages read this turn: 1/10]"
        assertEquals(images, PageImageText.parse(rendered))
    }

    @Test fun `parse needs the marker and skips unsafe image urls`() {
        assertEquals(emptyList<PageImage>(), PageImageText.parse("1. Plain text\n   image: https://site.test/a.jpg"))
        val text = """
            [images on this page]
            1. Safe
               image: https://site.test/a.jpg
               page: https://site.test/story
               source: site.test
            2. SVG
               image: https://site.test/logo.svg
               page: https://site.test/story
               source: site.test
            3. Script
               image: javascript:alert(1)
               page: https://site.test/story
               source: site.test
        """.trimIndent()
        val parsed = PageImageText.parse(text)
        assertEquals(1, parsed.size)
        assertEquals("Safe", parsed[0].title)
        assertTrue(parsed[0].imageUrl.startsWith("https://"))
    }
}
