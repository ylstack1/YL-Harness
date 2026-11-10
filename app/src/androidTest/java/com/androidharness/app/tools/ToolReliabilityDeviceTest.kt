package com.androidharness.app.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class ToolReliabilityDeviceTest {
    @Test fun defaultBrowserUsesAndroidViewIntentAndRejectsNonWebSchemes() = runBlocking {
        val app = (context.applicationContext as com.androidharness.app.HarnessApp).container
        val filter = android.content.IntentFilter(android.content.Intent.ACTION_VIEW).apply {
            addDataScheme("http"); addCategory(android.content.Intent.CATEGORY_BROWSABLE)
        }
        val monitor = instrumentation.addMonitor(filter,
            android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK, null), true)
        try {
            val tool = requireNotNull(app.registry.get("browser_open_external"))
            val ctx = ToolContext(FileFs(context.cacheDir))
            val result = tool.execute(buildJsonObject { put("url", "http://127.0.0.1:8001/") }, ctx)
            assertTrue(result.output, result.ok)
            assertEquals("Android VIEW request must be dispatched exactly once", 1, monitor.hits)
            assertTrue(runCatching { tool.execute(buildJsonObject { put("url", "file:///data/private") }, ctx) }.isFailure)
            assertEquals(1, monitor.hits)
        } finally { instrumentation.removeMonitor(monitor) }
    }
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }

    @Test fun deniedFilesAreReportedAsPermissionErrorsRatherThanMissing() = runBlocking {
        val name = "harness-denied-${System.nanoTime()}"
        val directory = "/data/local/tmp/$name"
        shell("mkdir $directory")
        shell("touch $directory/file")
        shell("chmod 700 $directory")
        val ctx = ToolContext(FileFs(File("/data/local/tmp")))
        try {
            for (tool in listOf(ReadFileTool(), DeleteFileTool(), MoveFileTool(), FileInfoTool())) {
                try {
                    tool.execute(buildJsonObject {
                        put("path", "$name/file"); put("source", "$name/file"); put("destination", "unused")
                    }, ctx)
                    fail("${tool.name} accepted inaccessible file")
                } catch (e: ToolFailure) {
                    assertTrue("${tool.name}: ${e.message}", e.message.orEmpty().contains("permission denied", true))
                    assertFalse(e.message.orEmpty().contains("does not exist"))
                }
            }
        } finally { shell("rm -rf $directory") }
    }

    @Test fun editAndMovePreserveCrlfAndBinaryBytesOnAndroid() = runBlocking {
        for (base in listOfNotNull(context.cacheDir, context.getExternalFilesDir(null))) {
        val root = File(base, "tool-bytes-${System.nanoTime()}").apply { mkdirs() }
        val ctx = ToolContext(FileFs(root))
        try {
            root.resolve("f.txt").writeText("one\r\ntwo\r\nthree\r\nfour\r\n")
            EditFileTool().execute(buildJsonObject {
                put("path", "f.txt"); put("old_string", "two\nthree"); put("new_string", "TWO\nTHREE")
            }, ctx)
            assertArrayEquals("one\r\nTWO\r\nTHREE\r\nfour\r\n".toByteArray(), root.resolve("f.txt").readBytes())
            val bytes = byteArrayOf(0, -1, -2, 42, 13, 10)
            root.resolve("binary").writeBytes(bytes)
            MoveFileTool().execute(buildJsonObject { put("source", "binary"); put("destination", "nested/binary") }, ctx)
            assertArrayEquals(bytes, root.resolve("nested/binary").readBytes())
            assertFalse(root.resolve("binary").exists())
        } finally { root.deleteRecursively() }
        }
    }

    @Test fun registeredOutputReaderRestoresPersistedMiddleAndEnforcesChatOwnership() = runBlocking<Unit> {
        val app = (context.applicationContext as com.androidharness.app.HarnessApp).container
        val first = app.sessions.createSession("Output regression A")
        val second = app.sessions.createSession("Output regression B")
        try {
            val text = "head\n" + "middle-".repeat(2000) + "\ntail"
            app.sessions.addMessage(first, ChatMessage(Role.TOOL, text, toolCallId = "fixture-output", toolName = "shell"), "fixture")
            val tool = requireNotNull(app.registry.get("read_tool_output"))
            val args = buildJsonObject { put("tool_call_id", "fixture-output"); put("offset", 5001); put("limit", 1000) }
            val root = File(context.cacheDir, "output-reader-fixture").apply { mkdirs() }
            val ctx = ToolContext(FileFs(root), sessionId = first)
            assertTrue(tool.execute(args, ctx).output.startsWith(text.substring(5000, 6000)))
            try { tool.execute(args, ctx.copy(sessionId = second)); fail("Cross-chat access accepted") }
            catch (e: ToolFailure) { assertTrue(e.message.orEmpty().contains("in this chat")) }
            root.deleteRecursively()
        } finally {
            app.sessions.session(first)?.let { app.sessions.deleteSession(it) }
            app.sessions.session(second)?.let { app.sessions.deleteSession(it) }
        }
    }
}
