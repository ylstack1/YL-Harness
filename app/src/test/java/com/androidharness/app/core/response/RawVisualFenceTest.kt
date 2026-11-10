package com.androidharness.app.core.response

import com.androidharness.app.artifacts.ArtifactSandbox
import com.androidharness.app.artifacts.ArtifactTheme
import com.androidharness.app.artifacts.ChatArtifact
import com.androidharness.app.core.ChatExperience
import org.junit.Assert.*
import org.junit.Test

class RawVisualFenceTest {
    private val html = "<div class=\"hx-card\">He said \"hi\"\nline two</div>\n<script>document.title='x'</script>"

    @Test fun `html fences render raw markup without JSON escaping`() {
        val parts = RichResponse.parse("Intro\n\n```harness-html title=\"Orbit demo\" loading=\"Placing planets\"\n$html\n```\n\nAfter")
        assertEquals(3, parts.size)
        assertEquals(RichResponse.Part.Markdown("Intro"), parts[0])
        val node = (parts[1] as RichResponse.Part.Content).node as RichResponse.Node.Preview
        assertEquals("Orbit demo", node.title)
        assertEquals(html, node.html)
        assertEquals("After", (parts[2] as RichResponse.Part.Markdown).text.trim())
    }

    @Test fun `svg fences wrap exactly one svg and reject anything else`() {
        val svg = "<svg viewBox=\"0 0 10 10\"><rect class=\"hx-node\" width=\"5\" height=\"5\"/></svg>"
        val node = (RichResponse.parse("```harness-svg title=\"Flow\"\n$svg\n```").single() as RichResponse.Part.Content).node as RichResponse.Node.Preview
        assertEquals("Flow", node.title)
        assertEquals("<div class=\"hx-svg\">$svg</div>", node.html)
        assertEquals(RichResponse.Part.Unavailable, RichResponse.parse("```harness-svg\n<div>not svg</div>\n```").single())
        assertEquals(RichResponse.Part.Unavailable, RichResponse.parse("```harness-html\n\n```").single())
    }

    @Test fun `titles default and accept a bare title`() {
        fun title(fence: String) = ((RichResponse.parse("$fence\n<p>x</p>\n```").single() as RichResponse.Part.Content).node as RichResponse.Node.Preview).title
        assertEquals("Interactive visual", title("```harness-html"))
        assertEquals("Loan calculator", title("```harness-html Loan calculator"))
        assertEquals(mapOf("title" to "A", "loading" to "B"), RichResponse.fenceAttributes("harness-html title=\"A\" loading=\"B\""))
    }

    @Test fun `streaming visuals hold a titled placeholder instead of flashing code`() {
        val source = "Here it is\n```harness-html title=\"Orbit\" loading=\"Placing planets\"\n<div>partial"
        val parts = RichResponse.parse(source, streaming = true)
        assertEquals(RichResponse.Part.PendingVisual("Orbit", "Placing planets"), parts.last())
        for (prefix in listOf("```harness", "```harness-h", "```harness-sv")) {
            assertEquals(prefix, RichResponse.Part.Pending, RichResponse.parse("Text\n$prefix", streaming = true).last())
        }
        assertEquals(RichResponse.Part.Unavailable, RichResponse.parse(source).last())
        assertEquals("Here it is\n\nPreparing content…", RichResponse.plainText(source, streaming = true))
    }

    @Test fun `ordinary html code blocks stay code`() {
        val text = "```html\n<div>example</div>\n```"
        assertEquals(listOf(RichResponse.Part.Markdown(text)), RichResponse.parse(text))
        assertEquals("Orbit (interactive preview)", RichResponse.plainText("```harness-html title=\"Orbit\"\n<p>x</p>\n```"))
    }

    @Test fun `every preview gets the design kit, helper and theme tokens under the same CSP`() {
        val artifact = ChatArtifact.create("Kit", "<div class=\"hx-card\">x</div>")
        val theme = ArtifactTheme.fallback(false).copy(accent = "#123456", palette = listOf("#111111", "#222222"))
        val source = ArtifactSandbox.sourceDocument(artifact, false, theme = theme)
        assertTrue(source.contains("--accent:#123456"))
        assertTrue(source.contains("--c1:#111111;--c2:#222222"))
        assertTrue(source.contains(".hx-card{"))
        assertTrue(source.contains("window.harness="))
        assertTrue(source.contains("connect-src 'none'"))
        // Classes only: the kit never restyles plain elements, so older previews keep their look.
        assertFalse(Regex("""(^|[}\n])\s*(div|p|h1|h2|span|table)\s*[{,]""").containsMatchIn(ArtifactSandbox.DESIGN_KIT))
    }

    @Test fun `theme colors cannot break out of the stylesheet`() {
        val hostile = ArtifactTheme.fallback(true).copy(accent = "red}</style><script>alert(1)</script>", palette = listOf("#zzz", "#abcdef"))
        val source = ArtifactSandbox.sourceDocument(ChatArtifact.create("T", "<p>x</p>"), true, theme = hostile)
        assertFalse(source.contains("alert(1)"))
        assertTrue(source.contains("--accent:${ArtifactTheme.fallback(true).accent}"))
        assertTrue(source.contains("--c1:#abcdef"))
    }

    @Test fun `both Chat and Code prompts teach the raw fences and the guide`() {
        for (prompt in listOf(RichResponse.INSTRUCTIONS, ChatExperience.SYSTEM_PROMPT)) {
            assertTrue(prompt.contains("```harness-html"))
            assertTrue(prompt.contains("```harness-svg"))
            assertTrue(prompt.contains("visual_guide"))
            assertTrue(prompt.contains("hx-card"))
        }
        assertTrue("visual_guide" in ChatExperience.tools)
    }
}
