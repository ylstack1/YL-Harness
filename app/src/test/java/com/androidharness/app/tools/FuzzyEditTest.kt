package com.androidharness.app.tools

import com.androidharness.app.tools.FuzzyEdit.Level
import com.androidharness.app.tools.FuzzyEdit.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyEditTest {

    private fun ok(r: Result): Result.Ok = r as Result.Ok

    @Test
    fun `exact match uses level EXACT`() {
        val r = FuzzyEdit.replace("val a = 1\nval b = 2\n", "val a = 1", "val a = 42", false)
        val o = ok(r)
        assertEquals(Level.EXACT, o.level)
        assertEquals(1, o.count)
        assertEquals("val a = 42\nval b = 2\n", o.newText)
    }

    @Test
    fun `trailing whitespace drift matches at LINE_ENDINGS`() {
        val file = "fun foo() {   \n    return 1   \n}\n"
        val old = "fun foo() {\n    return 1\n}"
        val r = FuzzyEdit.replace(file, old, "fun foo() {\n    return 2\n}", false)
        val o = ok(r)
        assertEquals(Level.LINE_ENDINGS, o.level)
        assertTrue(o.newText.contains("return 2"))
        assertTrue(o.newText.endsWith("}\n"))
    }

    @Test
    fun `internal whitespace drift is NOT tolerated`() {
        // Spaces inside the line (not leading/trailing) must fail,
        // conservative by design.
        val file = "fun foo()   {\n    return 1\n}\n"
        val old = "fun foo() {\n    return 1\n}"
        assertTrue(FuzzyEdit.replace(file, old, "x", false) is Result.NotFound)
    }

    @Test
    fun `CRLF file matched with LF old_string`() {
        val file = "int main() {\r\n    return 0;\r\n}\r\n"
        val old = "int main() {\n    return 0;\n}"
        val r = FuzzyEdit.replace(file, old, "int main() {\n    return 1;\n}", false)
        val o = ok(r)
        assertEquals(Level.LINE_ENDINGS, o.level)
        assertEquals("int main() {\r\n    return 1;\r\n}\r\n", o.newText)
    }

    @Test fun `CRLF edits preserve every surrounding byte and never duplicate a line`() {
        assertEquals("one\r\nTWO\r\nTHREE\r\nfour\r\n", ok(FuzzyEdit.replace(
            "one\r\ntwo\r\nthree\r\nfour\r\n", "two\nthree", "TWO\nTHREE", false)).newText)
        assertEquals("aa\r\nBB\r\nCC\r\n", ok(FuzzyEdit.replace(
            "aa\r\nbb\r\ncc\r\n", "bb\ncc", "BB\nCC", false)).newText)
    }

    @Test fun `normalized replacement consumes a requested trailing terminator exactly once`() {
        for (eol in listOf("\r\n", "\r", "\n")) {
            val original = listOf("one", "two ", "three", "four", "").joinToString(eol)
            assertEquals(listOf("one", "TWO", "THREE", "four", "").joinToString(eol),
                ok(FuzzyEdit.replace(original, "two\nthree\n", "TWO\nTHREE\n", false)).newText)
        }
    }

    @Test fun `CRLF replace all and indentation matches keep correct original spans`() {
        assertEquals("A\r\nB\r\nxx\r\nA\r\nB", ok(FuzzyEdit.replace(
            "a\r\nb\r\nxx\r\na\r\nb", "a\nb", "A\nB", true)).newText)
        assertEquals("head\r\n  X\r\nY\r\ntail", ok(FuzzyEdit.replace(
            "head\r\n  a\r\n  b\r\ntail", "a\nb", "X\nY", false)).newText)
    }

    @Test fun `tolerant matching keeps first line indentation just like substring matching`() {
        assertEquals("x\n   KEY v\n   NEWVAL\nz\n", ok(FuzzyEdit.replace(
            "x\n   KEY v\n   VAL\nz\n", "KEY v\n      VAL", "KEY v\n   NEWVAL", false)).newText)
        assertEquals("start\r\n\tif x:\r\n    DO_NEW()\r\nend\r\n", ok(FuzzyEdit.replace(
            "start\r\n\tif x:\r\n\t\tdo()\r\nend\r\n", "if x:\n  do()", "if x:\n    DO_NEW()", false)).newText)
        assertEquals("    FOO()\n", ok(FuzzyEdit.replace("    foo()\n", "foo()", "FOO()", false)).newText)
        assertEquals("\tNEW\n", ok(FuzzyEdit.replace("\tKEY  \n", "    KEY", "    NEW", false)).newText)
        assertEquals("X\n", ok(FuzzyEdit.replace("\tKEY  \n", "\tKEY\n", "X\n", false)).newText)
        assertEquals("next\n", ok(FuzzyEdit.replace("\tKEY  \nnext\n", "KEY\n", "", false)).newText)
    }

    @Test
    fun `indentation drift matches at INDENTATION`() {
        val file = "if (x) {\n        doThing()\n    }\n"
        val old = "if (x) {\n    doThing()\n}"
        val r = FuzzyEdit.replace(file, old, "if (x) {\n    doOther()\n}", false)
        val o = ok(r)
        assertEquals(Level.INDENTATION, o.level)
        assertTrue(o.newText.contains("doOther()"))
    }

    @Test
    fun `ambiguous at stricter level does not fall through`() {
        val file = "x = 1\nx = 1\n"
        val r = FuzzyEdit.replace(file, "x = 1", "x = 2", false)
        assertTrue(r is Result.Ambiguous)
        assertEquals(2, (r as Result.Ambiguous).count)
    }

    @Test
    fun `replace_all applies every occurrence`() {
        val file = "a\nb\na\n"
        val r = FuzzyEdit.replace(file, "a", "z", true)
        val o = ok(r)
        assertEquals(2, o.count)
        assertEquals("z\nb\nz\n", o.newText)
    }

    @Test
    fun `ambiguous only after normalization still fails`() {
        // Both lines equal after trim, ambiguous at INDENTATION.
        val file = "  foo\n\tfoo\n"
        val old = "foo"
        val r = FuzzyEdit.replace(file, old, "bar", false)
        // L0/L1 find nothing ("  foo" != "foo" exact and trailing-trim doesn't
        // help leading spaces), L2 finds two, must report ambiguous, not replace.
        assertTrue(r is Result.Ambiguous)
    }

    @Test
    fun `not found reports re-read hint`() {
        val r = FuzzyEdit.replace("aaa", "zzz", "b", false)
        assertTrue(r is Result.NotFound)
        assertTrue((r as Result.NotFound).detail.contains("Re-read"))
    }

    @Test
    fun `empty old_string is not found`() {
        assertTrue(FuzzyEdit.replace("abc", "", "x", false) is Result.NotFound)
    }

    @Test
    fun `multi-line window does not produce overlapping matches`() {
        val file = "\n\n\n"
        val old = "\n\n" // lines ["", "", ""]
        val r = FuzzyEdit.replace(file, old, "X", true)
        val o = ok(r)
        assertEquals(1, o.count)
    }

    @Test
    fun `lineEquals tolerance levels`() {
        assertTrue(FuzzyEdit.lineEquals("a ", "a", Level.LINE_ENDINGS))
        assertTrue(!FuzzyEdit.lineEquals(" a", "a", Level.LINE_ENDINGS))
        assertTrue(FuzzyEdit.lineEquals(" a", "a", Level.INDENTATION))
        assertTrue(!FuzzyEdit.lineEquals("a", "b", Level.INDENTATION))
    }
}
