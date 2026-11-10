package com.ylstack1.ylharness.agent

import com.ylstack1.ylharness.core.ChatMessage
import com.ylstack1.ylharness.core.ImageData
import com.ylstack1.ylharness.core.Role
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChatImageContextTest {
    @Test fun `recent assistant pictures reach the follow up without mutating saved messages`() = runBlocking {
        val history = listOf(ChatMessage(Role.USER, "Show me"),
            ChatMessage(Role.ASSISTANT, "![first](https://example.com/1.png)\n![second](https://example.com/2.png)"),
            ChatMessage(Role.USER, "What color is the second?"))
        val result = ChatImageContext.attach(history) { ImageData("image/jpeg", it) }
        assertEquals(listOf("https://example.com/2.png", "https://example.com/1.png"), result.last().imageData.map { it.base64 })
        assertTrue(result.last().text.contains("pictures shown earlier"))
        assertTrue(history.last().imageData.isEmpty())
        assertEquals("What color is the second?", history.last().text)
    }

    @Test fun `user uploads take priority and failures are identified`() = runBlocking {
        val assistant = ChatMessage(Role.ASSISTANT, "![view](https://example.com/p.png)")
        val upload = ImageData("image/png", "user")
        val withUploads = listOf(assistant, ChatMessage(Role.USER, "Compare", imageData = listOf(upload, upload)))
        assertEquals(withUploads, ChatImageContext.attach(withUploads) { error("should not fetch") })
        val missing = ChatImageContext.attach(listOf(assistant, ChatMessage(Role.USER, "Describe it"))) { null }
        assertTrue(missing.last().text.contains("could not be loaded"))
        assertTrue(missing.last().imageData.isEmpty())
    }
}
