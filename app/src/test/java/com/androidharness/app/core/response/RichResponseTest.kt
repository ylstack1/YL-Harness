package com.androidharness.app.core.response

import com.androidharness.app.artifacts.ArtifactSandbox
import com.androidharness.app.artifacts.ChatArtifact
import org.junit.Assert.*
import org.junit.Test

class RichResponseTest {
    private fun fence(json: String) = "```harness-ui\n$json\n```"
    private val card = """{"type":"card","title":"Recommended approach","text":"Use the supplied data","children":[],"actions":[{"label":"Explain","prompt":"Explain the approach"}]}"""

    @Test fun `mixed response preserves prose and component order and readable clipboard text`() {
        val source = "Here is the result.\n\n${fence(card)}\n\nAnd the next step."
        val parts = RichResponse.parse(source)
        assertEquals(3, parts.size)
        assertEquals("Here is the result.", (parts[0] as RichResponse.Part.Markdown).text)
        assertTrue((parts[1] as RichResponse.Part.Content).node is RichResponse.Node.Card)
        assertEquals("And the next step.", (parts[2] as RichResponse.Part.Markdown).text.trim())
        val copy = RichResponse.plainText(source)
        assertTrue(copy.contains("Recommended approach\nUse the supplied data"))
        assertFalse(copy.contains("harness-ui")); assertFalse(copy.contains("\"actions\""))
    }
    @Test fun `every streamed prefix withholds incomplete structured content instead of executing or exposing it`() {
        val source = fence("""{"type":"preview","title":"Counter","html":"<button onclick='this.textContent=1'>Try</button>"}""")
        for (end in "```harness".length until source.length) {
            val parsed = RichResponse.parse(source.take(end), streaming = true)
            assertEquals("Prefix $end", RichResponse.Part.Pending, parsed.last())
            assertTrue(parsed.none { it is RichResponse.Part.Content })
        }
        assertTrue(RichResponse.parse(source, true).single() is RichResponse.Part.Content)
        assertEquals(RichResponse.Part.Unavailable, RichResponse.parse(source.dropLast(3)).single())
    }
    @Test fun `literal examples inside longer code fences stay ordinary code`() {
        val source = "````markdown\n${fence(card)}\n````"
        assertTrue(RichResponse.parse(source).single() is RichResponse.Part.Markdown)
        assertEquals(source, RichResponse.plainText(source))
        assertTrue(RichResponse.parse("```kotlin\nval sample = \"harness-ui\"\n```").single() is RichResponse.Part.Markdown)
    }
    @Test fun `malformed unsupported oversized and excessive nesting fail closed with trailing prose retained`() {
        for (body in listOf("{", "[]", """{"type":"shell","command":"whoami"}""", """{"type":"link","title":"Private","url":"file:///data/data/app"}""", "x".repeat(RichResponse.MAX_JSON_CHARS + 1))) {
            assertNull(RichResponse.decode(body))
            val parts = RichResponse.parse(fence(body) + "\n\nThe explanation still works.")
            assertEquals(RichResponse.Part.Unavailable, parts.first())
            assertTrue((parts.last() as RichResponse.Part.Markdown).text.contains("explanation still works"))
        }
        var body = """{"type":"text","text":"end"}"""
        repeat(8) { body = """{"type":"column","children":[$body]}""" }
        assertNull(RichResponse.decode(body))
    }
    @Test fun `chart supports negative and zero values and rejects invalid numeric ranges`() {
        val valid = """{"type":"chart","title":"Net change","points":[{"label":"A","value":-3},{"label":"B","value":0},{"label":"C","value":4.5}],"unit":"items"}"""
        val chart = RichResponse.decode(valid) as RichResponse.Node.Chart
        assertEquals(listOf(-3.0, 0.0, 4.5), chart.points.map { it.value })
        assertTrue(RichResponse.describe(chart).contains("A: -3 items"))
        assertNull(RichResponse.decode(valid.replace("-3", "1e300")))
        assertNull(RichResponse.decode(valid.replace("-3", "null")))
    }
    @Test fun `preview metadata uses opaque script only frame and optional bounded layout channel`() {
        val artifact = ChatArtifact.create("Calculator", "<input oninput=\"parent.postMessage({type:'harness:state',summary:this.value},'*')\">")
        val frame = ArtifactSandbox.frameDocument(artifact, false, responseLayout = true)
        assertTrue(frame.contains("sandbox=\"allow-scripts\""))
        assertFalse(frame.contains("allow-same-origin"))
        assertTrue(frame.contains("e.source!==frame.contentWindow"))
        assertTrue(frame.contains("Math.min(12000")); assertTrue(frame.contains("slice(0,1500)"))
        assertFalse(ArtifactSandbox.frameDocument(artifact, false).contains("document.title="))
        assertTrue(ArtifactSandbox.sourceDocument(artifact, false).contains("connect-src 'none'"))
    }
    @Test fun `actions carry readable selection context and never claim execution`() {
        val message = RichResponse.followUp("Explain my choice", "Approaches", "Selected: Local\nWorks offline")
        assertEquals("Explain my choice", message.prompt)
        assertTrue(message.context.contains("Selected: Local"))
        assertEquals("Approaches", message.title)
        assertEquals(80, RichResponse.decode("""{"type":"preview","title":"${"t".repeat(80)}","html":"<p>ok</p>"}""").let { (it as RichResponse.Node.Preview).title.length })
    }
    @Test fun `response context stays out of visible prompts and survives editing and serialization`() {
        val ref = ResponseAttachment("Approaches", "Selected: Local\nWorks offline")
        val payload = ResponseAttachment.attach("Explain my choice", ref)
        assertEquals(ref, ResponseAttachment.fromPrompt(payload))
        assertEquals("Explain my choice", UserMessageContent.visibleText(payload))
        val edit = UserMessageContent.edit("Compare the tradeoffs", payload)
        assertEquals(ref, ResponseAttachment.fromPrompt(edit))
        assertEquals("Compare the tradeoffs", UserMessageContent.visibleText(edit))
        assertEquals(ref, ResponseAttachment.decode(ref.encode()))
        val invalid = "[[harness-response]]\ninvalid\n[[/harness-response]]\n\nUser text"
        assertEquals(invalid, UserMessageContent.visibleText(invalid))
        assertNull(ResponseAttachment.decode("{\"title\":\"a\",\"context\":\"${"x".repeat(1501)}\"}"))
    }
    @Test fun `editing an artifact prompt still preserves its exact selected revision`() {
        val artifact = ChatArtifact.create("A concept", "<p>Old workflow</p>")
        val ref = com.androidharness.app.artifacts.ArtifactAttachment.from(artifact)
        val source = com.androidharness.app.artifacts.ArtifactAttachment.attach("Revise it", ref)
        val edited = UserMessageContent.edit("Make it compact", source)
        assertEquals(ref, com.androidharness.app.artifacts.ArtifactAttachment.fromPrompt(edited))
        assertEquals("Make it compact", UserMessageContent.visibleText(edited))
    }
    @Test fun `legacy link probing only sees prose outside response components`() {
        val source = "See https://example.com/docs\n\n" + fence("""{"type":"preview","title":"Offline preview","html":"<img src='http://127.0.0.1/private'>"}""") +
            "\n\n" + fence("""{"type":"link","title":"Native link","url":"https://example.org/"}""")
        val prose = RichResponse.markdownText(source)
        assertTrue(prose.contains("https://example.com/docs"))
        assertFalse(prose.contains("127.0.0.1")); assertFalse(prose.contains("example.org"))
    }
}
