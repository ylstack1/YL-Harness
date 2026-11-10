package com.androidharness.app.tools

import com.androidharness.app.core.*
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ChatToolsTest {
    @Test fun textReaderOnlyReadsUserMessagesInTheRequestedConversation(): Unit = runBlocking {
        val root = Files.createTempDirectory("chat-text").toFile()
        try {
            val tool = ReadChatTextTool { sid -> if (sid == "a") listOf(
                ChatMessage(Role.USER, "User supplied text", id = "user"),
                ChatMessage(Role.ASSISTANT, "Private assistant text", id = "assistant"),
                ChatMessage(Role.TOOL, "Raw tool output", id = "tool")) else emptyList() }
            val ctx = ToolContext(FileFs(root), sessionId = "a")
            assertTrue(tool.execute(JsonObject(emptyMap()), ctx).output.contains("User supplied text"))
            for (id in listOf("assistant", "tool", "other-chat-id")) {
                try { tool.execute(buildJsonObject { put("message_id", id) }, ctx); fail(id) } catch (_: ToolFailure) { }
            }
            try { tool.execute(JsonObject(emptyMap()), ctx.copy(sessionId = "b")); fail() } catch (_: ToolFailure) { }
            try { tool.execute(buildJsonObject { put("offset", 0) }, ctx); fail() } catch (_: ToolFailure) { }
        } finally { root.deleteRecursively() }
    }

    @Test fun handoffToolOnlyOffersItNeverStartsExecution(): Unit = runBlocking {
        val root = Files.createTempDirectory("chat-handoff").toFile()
        try {
            val result = OfferCodeHandoffTool().execute(buildJsonObject { put("title", "Apply the project changes"); put("reason", "Editing the project needs Code") }, ToolContext(FileFs(root), sessionId = "chat"))
            assertTrue(result.ok)
            assertTrue(root.listFiles().orEmpty().isEmpty())
            assertTrue(result.output.contains("must choose"))
        } finally { root.deleteRecursively() }
    }
}
