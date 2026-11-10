package com.androidharness.app.core.response

import org.junit.Assert.*
import org.junit.Test

class RichResponseComparisonRegressionTest {
    private fun fence(body: String) = "```harness-ui\n$body\n```"

    @Test fun originalPhoneComparisonAndGrowthBlocksRenderWithoutLosingItems() {
        val source = requireNotNull(javaClass.getResource("/rich-response/comparison-regression.md")).readText()
        val parts = RichResponse.parse(ChatRichResponse.normalize(source))
        assertEquals(4, parts.size)
        assertTrue(parts.all { it is RichResponse.Part.Content })
        val nodes = parts.filterIsInstance<RichResponse.Part.Content>().map { it.node }
        val table = nodes[0] as RichResponse.Node.Table
        assertEquals(listOf("", "Android Harness", "Mobile Harness"), table.columns)
        assertEquals(10, table.rows.size)
        val mobile = (nodes[1] as RichResponse.Node.Card).children.single() as RichResponse.Node.Steps
        val android = (nodes[2] as RichResponse.Node.Card).children.single() as RichResponse.Node.Steps
        assertEquals("", mobile.title)
        assertEquals(6, mobile.items.size)
        assertEquals(10, android.items.size)
        assertFalse(mobile.checkable)
        assertFalse(android.checkable)
        assertTrue((mobile.items + android.items).all { it.done })
        val growth = nodes[3] as RichResponse.Node.Steps
        assertEquals("Actionable Growth Playbook", growth.title)
        assertEquals(4, growth.items.size)
        assertTrue(growth.items.none { it.done })
        assertFalse(growth.checkable)
        val copy = RichResponse.plainText(source)
        for (expected in listOf("Quick Comparison", "Full Ubuntu 20.04 environment", "Works without an API key", "Curated Skills Ecosystem")) {
            assertTrue(expected, copy.contains(expected))
        }
        assertFalse(copy.contains("Rich content unavailable"))
        assertFalse(copy.contains("harness-ui"))
        assertFalse(copy.contains("[x]"))
        assertFalse(copy.contains("[ ]"))
    }

    @Test fun checkboxesRequireExplicitBooleanOptInRatherThanDoneFields() {
        val plain = """{"type":"steps","title":"Key layout upgrades","items":[{"title":"Split screen","text":"Use the available space","done":true},{"title":"Context bridge","done":false}]}"""
        assertFalse((RichResponse.decode(plain) as RichResponse.Node.Steps).checkable)
        val copy = RichResponse.plainText(fence(plain))
        assertTrue(copy.contains("1. Split screen"))
        assertTrue(copy.contains("2. Context bridge"))
        assertFalse(copy.contains("[x]"))
        assertFalse(copy.contains("[ ]"))
        val checklist = plain.replace("\"type\":\"steps\"", "\"type\":\"steps\",\"checkable\":true")
        assertTrue((RichResponse.decode(checklist) as RichResponse.Node.Steps).checkable)
        val checklistCopy = RichResponse.plainText(fence(checklist))
        assertTrue(checklistCopy.contains("[x] Split screen"))
        assertTrue(checklistCopy.contains("[ ] Context bridge"))
        assertFalse((RichResponse.decode(checklist.replace("\"checkable\":true", "\"checkable\":false")) as RichResponse.Node.Steps).checkable)
        for (invalid in listOf("\"true\"", "1", "null", "{}")) {
            assertNull(RichResponse.decode(checklist.replace("\"checkable\":true", "\"checkable\":$invalid")))
        }
    }

    @Test fun optionalChecklistHeadingDoesNotRelaxRequiredItemTitlesOrBounds() {
        val item = """{"title":"Review","text":"Check the result","done":false}"""
        assertNotNull(RichResponse.decode("""{"type":"steps","items":[$item]}"""))
        assertNotNull(RichResponse.decode("""{"type":"steps","title":"  ","items":[$item]}"""))
        assertNull(RichResponse.decode("""{"type":"steps","items":[{"title":""}]}"""))
        assertNull(RichResponse.decode("""{"type":"steps","items":[]}"""))
        assertNull(RichResponse.decode("""{"type":"steps","title":12,"items":[$item]}"""))
        assertNull(RichResponse.decode("""{"type":"steps","items":[${List(13) { item }.joinToString(",")}]}"""))
        val table = """{"type":"table","title":"Compare","columns":["","Other"],"rows":[["A","B"]]}"""
        assertNotNull(RichResponse.decode(table))
        assertNull(RichResponse.decode(table.replace("[\"A\",\"B\"]", "[\"A\"]")))
        assertNull(RichResponse.decode(table.replace("\"Other\"", "false")))
    }

    @Test fun redundantClosersRecoverOnlyAfterAnOtherwiseValidCompleteObject() {
        val body = """{"type":"text","text":"Quoted brackets: } ] { [ and an escaped \"quote\"."}"""
        val expected = RichResponse.decode(body)
        for (suffix in listOf("}", "]}", " \n ] } \n")) {
            assertEquals(expected, RichResponse.decode(body + suffix))
            assertEquals(listOf(RichResponse.Part.Content(requireNotNull(expected))), RichResponse.parse(fence(body + suffix)))
        }
        for (invalid in listOf(body.dropLast(1), body + ",", body + " trailing text", body + "{}", body + "]{", body + "]".repeat(13), "{\"type\":\"text\",\"text\":\"ok\",]}", "{\"type\":\"text\",\"text\":\"ok\",}")) {
            assertNull(invalid, RichResponse.decode(invalid))
        }
        assertNull(RichResponse.decode("""{"type":"link","title":"Private","url":"file:///private"}]}"""))
        assertNull(RichResponse.decode("""{"type":"shell","command":"whoami"}]}"""))
    }

    @Test fun repairedBlockStillWaitsForItsFenceAndPreservesFollowingProse() {
        val source = fence("""{"type":"steps","title":"","items":[{"title":"Review"}]}]}""")
        for (end in "```harness".length until source.length) {
            assertEquals("prefix $end", RichResponse.Part.Pending, RichResponse.parse(source.take(end), true).single())
        }
        val parts = RichResponse.parse("Before\n\n$source\n\nAfter", true)
        assertEquals(3, parts.size)
        assertTrue(parts[1] is RichResponse.Part.Content)
        assertEquals("After", (parts[2] as RichResponse.Part.Markdown).text.trim())
    }
}
