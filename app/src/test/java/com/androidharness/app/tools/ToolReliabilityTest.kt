package com.androidharness.app.tools

import com.androidharness.app.agent.TodoItem
import com.androidharness.app.agent.TodoStore
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

class ToolReliabilityTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun ctx() = ToolContext(FileFs(tmp.root), sessionId = "chat-a")
    private fun args(vararg pairs: Pair<String, Any>) = buildJsonObject {
        pairs.forEach { (key, value) -> put(key, when (value) {
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is JsonElement -> value
            else -> JsonPrimitive(value.toString())
        }) }
    }

    @Test fun `explicit filesystem root cwd is honored only with full access`() {
        for (path in listOf("/", "//")) {
            assertEquals(java.io.File("/"), resolveShellWorkingDirectory(path, tmp.root, true))
            try { resolveShellWorkingDirectory(path, tmp.root, false); fail("Root must not bypass sandbox") }
            catch (e: ToolFailure) { assertTrue(e.message.orEmpty().contains("outside the workspace")) }
        }
        assertEquals(tmp.root, resolveShellWorkingDirectory("", tmp.root, false))
        val sibling = java.io.File(tmp.root.parentFile, tmp.root.name + "-sibling").apply { mkdirs() }
        try {
            try { resolveShellWorkingDirectory(sibling.path, tmp.root, false); fail("Sibling prefix must not bypass sandbox") }
            catch (e: ToolFailure) { assertTrue(e.message.orEmpty().contains("outside the workspace")) }
        } finally { sibling.delete() }
    }
    private suspend fun failure(tool: Tool, input: JsonObject): String {
        try { tool.execute(input, ctx()); fail("Expected tool failure") } catch (e: ToolFailure) { return e.message.orEmpty() }
        error("unreachable")
    }

    @Test fun `both edit tools preserve CRLF bytes through the full write path`() = runBlocking {
        val file = tmp.root.resolve("f.txt")
        file.writeText("one\r\ntwo\r\nthree\r\nfour\r\n")
        EditFileTool().execute(args("path" to "f.txt", "old_string" to "two\nthree", "new_string" to "TWO\nTHREE"), ctx())
        assertArrayEquals("one\r\nTWO\r\nTHREE\r\nfour\r\n".toByteArray(), file.readBytes())
        val edits = buildJsonArray { add(buildJsonObject { put("old_string", "TWO\nTHREE"); put("new_string", "2\n3") }) }
        MultiEditTool().execute(args("path" to "f.txt", "edits" to edits), ctx())
        assertArrayEquals("one\r\n2\r\n3\r\nfour\r\n".toByteArray(), file.readBytes())
    }

    @Test fun `text edit tools reject malformed UTF8 anywhere without changing original bytes`() = runBlocking {
        val file = tmp.root.resolve("encoding.txt")
        val invalid = listOf(
            "HEAD".toByteArray() + byteArrayOf(-1, -2) + "TAIL\n".toByteArray(),
            "caf".toByteArray() + byteArrayOf(0xe9.toByte()) + " au TAIL\n".toByteArray(),
            "TAIL\n".repeat(300).toByteArray() + byteArrayOf(0xc3.toByte()),
            "TAIL\n".toByteArray() + byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte())
        )
        for (bytes in invalid) {
            file.writeBytes(bytes)
            val edit = args("path" to file.name, "old_string" to "TAIL", "new_string" to "END", "replace_all" to true)
            val multi = args("path" to file.name, "edits" to buildJsonArray { add(edit) })
            for ((tool, input) in listOf(EditFileTool() to edit, MultiEditTool() to multi,
                ApplyPatchTool() to args("patch" to "--- a/encoding.txt\n+++ b/encoding.txt\n@@ -1 +1 @@\n-TAIL\n+END\n"))) {
                assertTrue(failure(tool, input).contains("not valid UTF-8"))
                assertArrayEquals(tool.name, bytes, file.readBytes())
            }
        }
    }

    @Test fun `valid unicode BOM and literal replacement characters survive both edits`() = runBlocking {
        val file = tmp.root.resolve("unicode.txt").apply { writeText("\uFEFFcafé � 😀\r\nTAIL\r\n") }
        EditFileTool().execute(args("path" to file.name, "old_string" to "TAIL", "new_string" to "END"), ctx())
        val edits = buildJsonArray { add(buildJsonObject { put("old_string", "END"); put("new_string", "TAIL") }) }
        MultiEditTool().execute(args("path" to file.name, "edits" to edits), ctx())
        assertArrayEquals("\uFEFFcafé � 😀\r\nTAIL\r\n".toByteArray(), file.readBytes())
    }

    @Test fun `multi edit preserves tolerant indentation through disk write`() = runBlocking {
        tmp.root.resolve("indent.txt").writeText("x\n   KEY v\n   VAL\nz\n")
        val edits = buildJsonArray { add(buildJsonObject { put("old_string", "KEY v\n      VAL"); put("new_string", "KEY v\n   NEWVAL") }) }
        MultiEditTool().execute(args("path" to "indent.txt", "edits" to edits), ctx())
        assertArrayEquals("x\n   KEY v\n   NEWVAL\nz\n".toByteArray(), tmp.root.resolve("indent.txt").readBytes())
    }

    @Test fun `file move fallback copies arbitrary bytes without text decoding`() = runBlocking {
        val bytes = byteArrayOf(0, -1, -2, 13, 10, 127, -128, 42)
        tmp.root.resolve("source.bin").writeBytes(bytes)
        MoveFileTool().execute(args("source" to "source.bin", "destination" to "nested/dest.bin"), ctx())
        assertFalse(tmp.root.resolve("source.bin").exists())
        assertArrayEquals(bytes, tmp.root.resolve("nested/dest.bin").readBytes())
    }

    @Test fun `failed streamed writes preserve original data and remove the temporary file`() {
        val original = tmp.root.resolve("original").apply { writeText("unchanged") }
        val broken = object : InputStream() {
            var reads = 0
            override fun read(): Int { if (reads++ < 4) return 65; throw IOException("copy interrupted") }
        }
        try { FileFs(tmp.root).resolve("original").writeFrom(broken); fail("Expected failed write") }
        catch (e: ToolFailure) { assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("copy interrupted")) }
        assertEquals("unchanged", original.readText())
        assertEquals(listOf("original"), tmp.root.list()!!.toList())
    }

    @Test fun `atomic replacement preserves executable permissions`() {
        val file = tmp.root.resolve("run.sh").apply { writeText("old") }
        val perms = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
        Files.setPosixFilePermissions(file.toPath(), perms)
        FileFs(tmp.root).resolve("run.sh").writeText("new")
        assertEquals("new", file.readText())
        assertEquals(perms, Files.getPosixFilePermissions(file.toPath()))
    }

    @Test fun `file search sorts and pages past the old cap without dropping files`() = runBlocking {
        for (i in 305 downTo 1) tmp.root.resolve("f%03d.txt".format(i)).writeText("x")
        tmp.root.resolve("nested").mkdir()
        tmp.root.resolve("nested/deep.txt").writeText("x")
        val tool = SearchFilesTool()
        val first = tool.execute(args("pattern" to "*.txt", "recursive" to false), ctx()).output
        val second = tool.execute(args("pattern" to "*.txt", "recursive" to false, "offset" to 301), ctx()).output
        assertTrue(first.startsWith("f001.txt\nf002.txt"))
        assertTrue(first.contains("next offset=301"))
        val all = (first.lines() + second.lines()).filter { !it.startsWith("[") }
        assertEquals((1..305).map { "f%03d.txt".format(it) }, all)
        assertFalse(all.contains("nested/deep.txt"))
    }

    @Test fun `grep reports skipped binaries and offers stable next pages`() = runBlocking {
        tmp.root.resolve("a.bin").writeBytes(byteArrayOf(0) + "FINDME".toByteArray())
        tmp.root.resolve("z.txt").writeText("FINDME 1\nFINDME 2\nFINDME 3\n")
        val tool = GrepTool()
        val first = tool.execute(args("pattern" to "FINDME", "limit" to 2), ctx()).output
        assertTrue(first, first.contains("a.bin (binary)"))
        assertTrue(first, first.contains("next offset=3"))
        val second = tool.execute(args("pattern" to "FINDME", "offset" to 3, "limit" to 2), ctx()).output
        assertTrue(second, second.contains("z.txt:3: FINDME 3"))
        assertFalse(second.contains("z.txt:2:"))
    }

    @Test fun `invalid todo statuses fail without replacing the original list`() = runBlocking {
        val store = TodoStore()
        val original = listOf(TodoItem("Keep me"))
        store.setAll(original)
        for (status in listOf("bogus", "12", "bad!")) {
            val message = failure(TodoWriteTool(store), args("todos" to """[{"content":"New","status":"$status"}]"""))
            assertTrue(message, message.contains("Invalid todo status"))
            assertEquals(original, store.todos.value)
        }
        TodoWriteTool(store).execute(args("todos" to """[{"content":"New","status":"done"}]"""), ctx())
        assertEquals(TodoItem.Status.COMPLETED, store.todos.value.single().status)
    }

    @Test fun `empty file paths are rejected and special files are not ordinary files`() = runBlocking {
        for (tool in listOf(FileInfoTool(), WriteFileTool())) {
            val message = failure(tool, args("path" to "", "content" to "oops"))
            assertTrue(message, message.contains("path must not be empty"))
        }
        val workspace = com.androidharness.app.workspace.UnboundedFileFs(tmp.root)
        val result = FileInfoTool().execute(args("path" to "/dev/null"), ToolContext(workspace, sandboxOff = true))
        assertTrue(result.output, result.output.contains("type: special"))
    }

    @Test fun `historical output can be paged but only inside the owning chat`() = runBlocking {
        val output = "head\n" + "middle-".repeat(1500) + "\ntail"
        val tool = ReadToolOutputTool { session, id -> output.takeIf { session == "chat-a" && id == "old" } }
        val page = tool.execute(args("tool_call_id" to "old", "offset" to 4001, "limit" to 1000), ctx())
        assertTrue(page.output.startsWith(output.substring(4000, 5000)))
        assertTrue(page.output.contains("next offset=5001"))
        try {
            tool.execute(args("tool_call_id" to "old"), ctx().copy(sessionId = "chat-b"))
            fail("Cross-chat output must not be accessible")
        } catch (e: ToolFailure) { assertTrue(e.message.orEmpty().contains("in this chat")) }
    }
}
