package com.androidharness.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkPreviewParserTest {
    @Test fun `open graph tags in either order become a card`() {
        val html = """
            <html><head>
              <meta property="og:title" content="Kotlin 2.0">
              <meta content="A language update" name="og:description">
              <meta name="og:image" content="/hero.png">
              <meta property="og:site_name" content="Kotlin">
            </head></html>
        """.trimIndent()
        val preview = LinkPreviewParser.parse(html, "https://kotlinlang.org/news/2-0")
        assertEquals("Kotlin 2.0", preview.title)
        assertEquals("A language update", preview.description)
        assertEquals("https://kotlinlang.org/hero.png", preview.imageUrl)
        assertEquals("Kotlin", preview.siteName)
    }

    @Test fun `title tag is the fallback and entities decode`() {
        val preview = LinkPreviewParser.parse(
            "<title>AT&amp;T &#39;news&#39;</title>",
            "https://example.com/a",
        )
        assertEquals("AT&T 'news'", preview.title)
        assertEquals("example.com", preview.siteName)
    }

    @Test fun `youtube urls get a thumbnail and embed without a page fetch`() {
        val preview = LinkPreviewParser.parse("", "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertEquals("YouTube", preview.siteName)
        assertTrue(preview.imageUrl!!.contains("dQw4w9WgXcQ"))
        assertEquals("https://www.youtube.com/embed/dQw4w9WgXcQ", preview.embedUrl)
    }

    @Test fun `short youtube urls resolve the video id`() {
        assertNotNull(LinkPreviewParser.youtubeVideoId("https://youtu.be/dQw4w9WgXcQ?t=1"))
    }

    @Test fun `non youtube urls have no embed`() {
        val preview = LinkPreviewParser.parse("", "https://example.com/story")
        assertEquals(null, preview.embedUrl)
    }
}
