package com.androidharness.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * web_fetch promises text with the HTML stripped. The trigger used to be
 * "contains <html or <body", so every fragment-only document came back as raw
 * markup even when the response said text/html (on-device QA, 2026-09-17).
 */
class WebFetchHtmlTest {

    @Test
    fun `doctype-less fragment with a text html content type is stripped`() {
        val body = """<!doctype html><title>Next 731</title><a href="/">Home</a><p>NEXT_731</p>"""
        val text = htmlToText(body, "html")
        assertFalse("Markup must be gone, got: $text", text.contains("<"))
        assertTrue(text.contains("NEXT_731"))
        assertTrue(text.contains("Next 731"))
        // Block boundaries keep their own lines instead of running the page
        // together ("Next 731HomeNEXT_731"). Inline <a> stays in the flow.
        assertEquals("Next 731\nHome\nNEXT_731", text)
    }

    @Test
    fun `block elements break lines while inline elements do not`() {
        val body = "<div>one</div><div>two</div><span>inline</span><b>bold</b>"
        assertEquals("one\ntwo\ninlinebold", htmlToText(body, "html"))

        // List and table structure survives as lines, attributes are dropped.
        val list = """<ul><li>alpha</li><li class="x">beta</li></ul>"""
        assertEquals("alpha\nbeta", htmlToText(list, "html"))

        val row = "<table><tr><td>a</td><td>b</td></tr></table>"
        assertEquals("a\nb", htmlToText(row, "html"))

        // Runs of spaces and tabs collapse, and a blank line between elements
        // collapses to one newline rather than padding the output.
        val loose = "<p>  spaced   out  </p>\n\n\n<p></p><p></p><p>next</p>"
        val looseText = htmlToText(loose, "html")
        assertEquals("spaced out\nnext", looseText)
    }

    @Test
    fun `fragment sniffing works without a content type`() {
        val text = htmlToText("<p>NEXT_731</p>")
        assertEquals("NEXT_731", text)
    }

    @Test
    fun `full documents keep working`() {
        val body = "<html><head><title>T</title><style>p{color:red}</style></head>" +
            "<body><h1>Hello</h1><script>bad()</script><p>World&nbsp;now</p></body></html>"
        val text = htmlToText(body, "html")
        assertTrue(text.contains("Hello"))
        assertTrue(text.contains("World now"))
        assertFalse(text.contains("bad()"))
        assertFalse(text.contains("color:red"))
    }

    @Test
    fun `plain text json and xml are left alone`() {
        val json = """{"next": 731, "html": "<p>not a document</p>"}"""
        // JSON has no html-ish content type: the embedded markup must survive verbatim.
        assertEquals(json, htmlToText(json, "json"))

        val text = "1 < 2 and 3 > 2\nno markup here"
        assertEquals(text, htmlToText(text))

        val svg = """<svg xmlns="http://www.w3.org/2000/svg"><rect/></svg>"""
        assertEquals(svg, htmlToText(svg, "svg+xml"))
    }

    @Test
    fun `xhtml content type counts as html`() {
        assertTrue(looksLikeHtml("any body at all", "xhtml"))
        assertTrue(looksLikeHtml("any body at all", "html"))
        assertFalse(looksLikeHtml("just words", "plain"))
    }

    @Test
    fun `navigation footers and forms are dropped`() {
        val body = "<html><body><nav>menu</nav><article><h1>Story</h1><p>Body text</p></article>" +
            "<footer>footer words</footer><aside>side note</aside><form>subscribe</form></body></html>"
        val text = htmlToText(body, "html")
        assertTrue(text.contains("Story"))
        assertTrue(text.contains("Body text"))
        assertFalse(text.contains("menu"))
        assertFalse(text.contains("footer words"))
        assertFalse(text.contains("side note"))
        assertFalse(text.contains("subscribe"))
    }

    @Test
    fun `article content wins over surrounding page chrome`() {
        val filler = "filler ".repeat(60)
        val story = "Keep this story text. ".repeat(30)
        val body = "<html><body><div>$filler</div><article><h1>Only story</h1><p>$story</p></article></body></html>"
        val text = htmlToText(body, "html")
        assertTrue(text.contains("Only story"))
        assertTrue(text.contains("Keep this story text."))
        assertFalse(text.contains("filler"))
    }

    @Test
    fun `numeric and named entities decode after tag stripping`() {
        assertEquals("It's 5 < 6", htmlToText("<p>It&#39;s 5 &lt; 6</p>", "html"))
    }

    @Test
    fun `page image scan prefers open graph and skips icons and svg`() {
        val html = """
            <title>Red panda</title>
            <meta property="og:image" content="/hero.jpg">
            <meta property="og:image:alt" content="A red panda">
            <img src="/icon.png" width="16" height="16">
            <img data-src="//cdn.test/photo.webp" alt="Sleeping red panda">
            <img src="/logo.svg">
        """.trimIndent()
        val scan = scanPageImages(html, "https://site.test/article")
        assertEquals("Red panda", scan.title)
        assertEquals(listOf("https://site.test/hero.jpg", "https://cdn.test/photo.webp"), scan.images.map { it.url })
        assertEquals(listOf("A red panda", "Sleeping red panda"), scan.images.map { it.alt })
    }

    @Test
    fun `page image scan skips navigation, footer, and icon chrome`() {
        val html = """
            <header><img src="/brand.png" class="site-logo"></header>
            <nav><img src="/nav-photo.jpg" width="800" height="600"></nav>
            <main>
              <img src="/photo.jpg" alt="Photo" width="800" height="600">
              <img src="/public/images/v6/ico/ico_singlePlayer.png" class="category_icon">
              <img src="/public/images/mobile/app_icon.webp">
              <img src="/decor.png" aria-hidden="true">
            </main>
            <footer><img src="/footer-photo.jpg" width="800" height="600"></footer>
        """.trimIndent()
        val scan = scanPageImages(html, "https://site.test/page")
        assertEquals(listOf("https://site.test/photo.jpg"), scan.images.map { it.url })
    }
}
