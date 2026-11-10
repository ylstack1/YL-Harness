package com.androidharness.app.ui.files

import org.junit.Assert.*
import org.junit.Test

class SmartEditorEditsTest {
    @Test fun renameAppliesOffsetsInReverseOrderWithoutTouchingOtherText() {
        val source = "greet(); // greet\nconst greeting = greet;"
        val edits = listOf(SmartTextEdit("a.ts", 0, 5, "welcome"), SmartTextEdit("a.ts", source.lastIndexOf("greet"), source.length - 1, "welcome"))
        assertEquals("welcome(); // greet\nconst greeting = welcome;", applySmartEdits(source, edits))
    }

    @Test fun invalidOverlappingOrOutOfRangeEditsFailBeforeMutation() {
        for(edits in listOf(listOf(SmartTextEdit("a.ts", -1, 2, "x")), listOf(SmartTextEdit("a.ts", 0, 8, "x")),
            listOf(SmartTextEdit("a.ts", 0, 3, "x"), SmartTextEdit("a.ts", 2, 4, "y")))) {
            assertThrows(IllegalArgumentException::class.java) { applySmartEdits("hello", edits) }
        }
    }

    @Test fun formattingChangesOnlyTheDifferentRange() {
        val before = "const name=\"hello\";\n"
        val after = "const name = \"hello\";\n"
        val edit = minimalSmartEdit(before, after)
        assertEquals(after, StringBuilder(before).replace(edit.first, edit.second, edit.third).toString())
        assertEquals(10, edit.first)
        assertTrue(edit.second < before.length)
    }

    @Test fun capabilitiesNeverAdvertiseUnimplementedLanguageActions() {
        assertTrue(SmartEditorSupport.rename("main.tsx"))
        assertTrue(SmartEditorSupport.navigation("styles.scss"))
        assertFalse(SmartEditorSupport.rename("styles.scss"))
        assertTrue(SmartEditorSupport.outline("guide.md"))
        assertFalse(SmartEditorSupport.completion("guide.md"))
        assertTrue(SmartEditorSupport.format("settings.yaml"))
        assertFalse(SmartEditorSupport.format("Main.kt"))
        assertFalse(SmartEditorSupport.navigation("Main.kt"))
    }
}
