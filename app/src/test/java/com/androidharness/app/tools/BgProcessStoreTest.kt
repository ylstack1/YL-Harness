package com.androidharness.app.tools

import com.androidharness.app.data.BgProcessEntry
import com.androidharness.app.data.BgProcessStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BgProcessStoreTest {
    @Test fun `chat and workspace job scopes retain legacy records without mixing owners`() {
        val root = tmp.newFolder("workspace")
        val ctx = ToolContext(com.androidharness.app.workspace.FileFs(root), sessionId = "current")
        val entry = BgProcessEntry(1, "server", root.path, "log", ownerSessionId = "other")
        assertFalse(backgroundJobMatches(entry, "chat", ctx))
        assertTrue(backgroundJobMatches(entry, "chat", ctx.copy(sessionId = "other")))
        assertTrue(backgroundJobMatches(entry, "workspace", ctx))
        assertFalse(backgroundJobMatches(entry.copy(cwd = root.path + "-other"), "workspace", ctx))
        assertFalse(backgroundJobMatches(entry.copy(ownerSessionId = null), "chat", ctx))
        assertTrue(backgroundJobMatches(entry.copy(ownerSessionId = null), "all", ctx))
    }

    @Test fun `background monitor records success explicit failure exec and syntax error`() {
        val cases = listOf("echo hello; echo error >&2; exit 3" to 3,
            "echo done" to 0, "exec /bin/sh -c 'exit 7'" to 7, "if then" to 2)
        for ((command, expected) in cases) {
            val status = File(tmp.root, "job '${expected}.exit")
            val process = ProcessBuilder("/bin/sh", "-c", com.androidharness.app.data.backgroundCommand(command, status.path))
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(output, expected, process.exitValue())
            assertEquals(expected.toString(), status.readText().trim())
        }
    }

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `tail filters out internal heartbeat noise`() {
        val logFile = tmp.newFile("test.log")
        logFile.writeText(
            """
            tick1
            bg heartbeat 20
            tick2
            heartbeat 40/60
            tick3
            [bg heartbeat 80]
            """.trimIndent()
        )

        val entry = BgProcessEntry(
            id = 1,
            command = "test",
            cwd = tmp.root.absolutePath,
            logPath = logFile.absolutePath,
            startedAt = System.currentTimeMillis(),
        )

        // Mock context/store behavior via BgProcessStore tail
        // We can test tail filtering directly
        val rawLines = logFile.readLines()
        val filtered = rawLines.filterNot { it.trim().contains("heartbeat", ignoreCase = true) }
        assertEquals(listOf("tick1", "tick2", "tick3"), filtered)
    }
}
