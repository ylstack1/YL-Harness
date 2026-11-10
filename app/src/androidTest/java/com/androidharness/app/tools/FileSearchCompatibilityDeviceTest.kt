package com.androidharness.app.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Also run on API 28: desktop JVM tests cannot catch calls missing from old Android runtimes. */
@RunWith(AndroidJUnit4::class)
class FileSearchCompatibilityDeviceTest {
    private fun fixture(block: suspend (ToolContext) -> Unit) = runBlocking {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "file-glob-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            File(root, "Top.kt").writeText("match top\n")
            File(root, "src/Inner.kt").apply { parentFile!!.mkdirs(); writeText("match inner\n") }
            File(root, "skip.txt").writeText("match excluded\n")
            block(ToolContext(FileFs(root)))
        } finally { root.deleteRecursively() }
    }
    @Test fun recursiveAndBasenameGlobsFindRootAndNestedFiles() = fixture { context ->
        for (pattern in listOf("*.kt", "**/*.kt")) {
            val result = SearchFilesTool().execute(buildJsonObject { put("pattern", pattern) }, context)
            assertTrue(result.output, result.ok)
            assertEquals(setOf("Top.kt", "src/Inner.kt"), result.output.lines().toSet())
        }
        val relative = SearchFilesTool().execute(buildJsonObject { put("pattern", "src/*.kt") }, context)
        assertEquals("src/Inner.kt", relative.output)
    }
    @Test fun grepIncludeFiltersBothRecursiveAndSingleFileSearches() = fixture { context ->
        val recursive = GrepTool().execute(buildJsonObject { put("pattern", "match"); put("include", "*.kt") }, context)
        assertTrue(recursive.output, recursive.ok)
        assertTrue(recursive.output, recursive.output.contains("Top.kt:1:") && recursive.output.contains("src/Inner.kt:1:"))
        assertFalse(recursive.output, recursive.output.contains("skip.txt"))
        val single = GrepTool().execute(buildJsonObject { put("pattern", "match"); put("path", "Top.kt"); put("include", "*.kt") }, context)
        assertTrue(single.output, single.ok)
        assertTrue(single.output, single.output.contains("Top.kt:1:"))
        assertThrows(ToolFailure::class.java) { runBlocking {
            GrepTool().execute(buildJsonObject { put("pattern", "match"); put("path", "skip.txt"); put("include", "*.kt") }, context)
        } }
    }
}
