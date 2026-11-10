package com.androidharness.app.llm

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ImageData
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ChatImageZoomCompatTest {
    @Test fun rehydratedBytesAndTheSerializedDeliveryContractAlwaysAgree() {
        val provider = OpenAiCompatProvider(okhttp3.OkHttpClient(), ProviderFactory.json)
        val original = ChatMessage(Role.TOOL, """{"ok":false,"image_attached":false,"error":"not_in_current_context","image_id":"img_logo","available_image":{"attachment_id":"img_crop","width":540,"height":576,"attached_bytes":123}}""",
            toolCallId = "crop-call", toolName = "zoom_chat_image", isError = true, imageData = listOf(ImageData("image/png", "crop-bytes")))
        val body = provider.buildRequestBody(ProviderConfig("fixture", "Fixture", ProviderType.OPENAI_COMPAT, "https://example.invalid", "gpt-4o"), "system",
            listOf(ChatMessage(Role.ASSISTANT, toolCalls = listOf(ToolCallData("crop-call", "zoom_chat_image", "{}"))), original), emptyList(), RequestOptions())
        val messages = body.getValue("messages").jsonArray.map { it.jsonObject }
        val tool = kotlinx.serialization.json.Json.parseToJsonElement(messages[2].getValue("content").jsonPrimitive.content).jsonObject
        assertEquals("true", tool.getValue("ok").jsonPrimitive.content)
        assertEquals("true", tool.getValue("image_attached").jsonPrimitive.content)
        assertEquals("img_crop", tool.getValue("attached_image").jsonObject.getValue("attachment_id").jsonPrimitive.content)
        val content = messages.last().getValue("content").jsonArray
        val label = content.first().jsonObject.getValue("text").jsonPrimitive.content
        assertTrue(label.contains("tool_call_id=crop-call")); assertTrue(label.contains("img_crop")); assertTrue(label.contains("540"))
        assertTrue(content[1].jsonObject.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content.endsWith("crop-bytes"))
    }

    @Test fun everyToolImageReachesStrictEndpointsAfterAllResultsInCallOrder() {
        val provider = OpenAiCompatProvider(okhttp3.OkHttpClient(), ProviderFactory.json)
        val names = listOf("view_chat_image", "browser_read", "zoom_chat_image")
        val history = listOf(ChatMessage(Role.ASSISTANT, toolCalls = names.mapIndexed { i, name -> ToolCallData("$i", name, "{}") })) +
            names.mapIndexed { i, name -> ChatMessage(Role.TOOL, "image_id=img_$i", toolCallId = "$i", toolName = name,
                imageData = listOf(ImageData("image/png", "bytes-$i"))) }
        val messages = provider.buildRequestBody(ProviderConfig("fixture", "Fixture", ProviderType.OPENAI_COMPAT,
            "https://example.invalid", "gpt-4o"), "system", history, emptyList(), RequestOptions())
            .getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "assistant", "tool", "tool", "tool", "user"), messages.map { it.getValue("role").jsonPrimitive.content })
        assertTrue(messages.filter { it.getValue("role").jsonPrimitive.content == "tool" }.all { it.getValue("content") is kotlinx.serialization.json.JsonPrimitive })
        assertEquals(listOf("bytes-0", "bytes-1", "bytes-2"), messages.last().getValue("content").jsonArray.drop(1)
            .map { it.jsonObject.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content.substringAfter("base64,") })
    }
    @Test fun closeUpFollowsAllToolResultsAsAnImageUserContentPart() {
        val provider = OpenAiCompatProvider(okhttp3.OkHttpClient(), ProviderFactory.json)
        val config = ProviderConfig("fixture", "Fixture", ProviderType.OPENAI_COMPAT, "https://example.invalid", "gpt-4o")
        val body = provider.buildRequestBody(config, "system", listOf(
            ChatMessage(Role.ASSISTANT, toolCalls = listOf(ToolCallData("one", "zoom_chat_image", "{}"), ToolCallData("two", "read_chat_text", "{}"))),
            ChatMessage(Role.TOOL, text = "close-up", toolCallId = "one", toolName = "zoom_chat_image", imageData = listOf(ImageData("image/png", "detail"))),
            ChatMessage(Role.TOOL, text = "text", toolCallId = "two", toolName = "read_chat_text"),
        ), emptyList(), RequestOptions())
        val messages = body.getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "assistant", "tool", "tool", "user"), messages.map { it.getValue("role").jsonPrimitive.content })
        assertEquals("close-up", messages[2].getValue("content").jsonPrimitive.content)
        val parts = messages.last().getValue("content").jsonArray.map { it.jsonObject }
        assertEquals("image_url", parts[1].getValue("type").jsonPrimitive.content)
        assertEquals("data:image/png;base64,detail", parts[1].getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content)
    }
}
