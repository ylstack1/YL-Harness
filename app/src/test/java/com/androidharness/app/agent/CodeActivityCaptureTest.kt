package com.androidharness.app.agent

import com.androidharness.app.data.CodeFilePolicy
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class CodeActivityCaptureTest {
    @Test fun `shell snapshot counts source creation edits and deletion but excludes docs and build outputs`() = runBlocking {
        val root = Files.createTempDirectory("code-activity").toFile()
        try {
            root.resolve("old.kt").writeText("old\n")
            root.resolve("edit.kt").writeText("before\n")
            val before = requireNotNull(CodeActivityCapture.capture(FileFs(root)))
            root.resolve("old.kt").delete()
            root.resolve("edit.kt").writeText("after\nsecond\n")
            root.resolve("new.py").writeText("print(1)\n")
            root.resolve("README.md").writeText("documentation\n")
            root.resolve("build").mkdirs(); root.resolve("build/generated.kt").writeText("generated\n")
            val after = requireNotNull(CodeActivityCapture.capture(FileFs(root)))
            val changes = CodeActivityCapture.changes(before, after).associateBy { it.path }
            assertEquals(setOf("old.kt", "edit.kt", "new.py"), changes.keys)
            assertEquals(1L, changes.getValue("old.kt").removed)
            assertEquals(2L, changes.getValue("edit.kt").added)
            assertEquals(1L, changes.getValue("new.py").added)
        } finally { root.deleteRecursively() }
    }
    @Test fun `incomplete snapshots do not invent file creation or deletion`() {
        val changes = CodeActivityCapture.changes(CodeActivityCapture.Snapshot(mapOf("a.kt" to "before\n", "missing.kt" to "x"), false),
            CodeActivityCapture.Snapshot(mapOf("a.kt" to "after\n", "new.kt" to "y"), true))
        assertEquals(listOf("a.kt"), changes.map { it.path })
    }
    @Test fun `classification and identifiers do not retain readable paths`() {
        assertTrue(CodeFilePolicy.counts("@workspaces/id/src/App.KT"))
        assertTrue(CodeFilePolicy.counts("config/settings.yaml"))
        assertFalse(CodeFilePolicy.counts("README.md"))
        assertFalse(CodeFilePolicy.counts("node_modules/a.ts"))
        assertFalse(CodeFilePolicy.counts("src/picture.png"))
        val key = CodeFilePolicy.fileKey("chat", "src/secret-name.kt")
        assertEquals(64, key.length); assertFalse(key.contains("secret"))
    }
}
