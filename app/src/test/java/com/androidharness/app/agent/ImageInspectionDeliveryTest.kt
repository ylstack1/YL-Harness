package com.androidharness.app.agent

import com.androidharness.app.core.*
import com.androidharness.app.tools.ImageInspectionDelivery
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ImageInspectionDeliveryTest {
    private val bytes = listOf(ImageData("image/png", "image-payload"))
    private val original = ChatMessage(Role.TOOL, """{"ok":true,"image_attached":true,"error":null,"caption":"A logo","image_id":"img_logo","attached_bytes":123,"attached_image":{"attachment_id":"img_crop","width":540,"height":576,"attached_bytes":123}}""",
        toolName = "zoom_chat_image", imageData = bytes, images = listOf(ImageRef("saved.png", "image/png")))

    @Test fun omittedImagesMoveMetadataOutOfTheAttachmentContract() {
        val omitted = ContextHygiene.shrinkToolResults(listOf(original), maxImages = 0).single()
        val json = Json.parseToJsonElement(omitted.text).jsonObject
        assertTrue(omitted.imageData.isEmpty())
        assertFalse(json.getValue("ok").jsonPrimitive.boolean)
        assertFalse(json.getValue("image_attached").jsonPrimitive.boolean)
        assertFalse(json.getValue("delivered").jsonPrimitive.boolean)
        assertEquals(JsonNull, json["attached_image"])
        assertEquals(0, json.getValue("attached_bytes").jsonPrimitive.int)
        assertEquals(540, json.getValue("available_image").jsonObject.getValue("width").jsonPrimitive.int)
        assertEquals("img_crop", json.getValue("available_image").jsonObject.getValue("attachment_id").jsonPrimitive.content)
        assertTrue(original.text.contains("\"image_attached\":true"))
        assertEquals(omitted, ContextHygiene.shrinkToolResults(listOf(omitted), maxImages = 0).single())
    }

    @Test fun rehydratingASavedCopyRestoresDeliveryWithoutAnotherToolCall() {
        val omitted = ContextHygiene.shrinkToolResults(listOf(original), maxImages = 0).single()
        val restored = ImageInspectionDelivery.update(omitted, bytes)
        val json = Json.parseToJsonElement(restored.text).jsonObject
        assertFalse(restored.isError); assertEquals(bytes, restored.imageData)
        assertTrue(json.getValue("ok").jsonPrimitive.boolean)
        assertTrue(json.getValue("delivered").jsonPrimitive.boolean)
        assertTrue(json.getValue("image_attached").jsonPrimitive.boolean)
        assertEquals(JsonNull, json["error"])
        assertEquals(123, json.getValue("attached_bytes").jsonPrimitive.int)
        assertEquals("img_crop", json.getValue("attached_image").jsonObject.getValue("attachment_id").jsonPrimitive.content)
    }

    @Test fun aMissingFileNeverClaimsDeliveryAndActualToolFailuresAreNotPromoted() {
        val missing = ImageInspectionDelivery.update(original, emptyList())
        assertTrue(missing.isError)
        assertEquals(JsonNull, Json.parseToJsonElement(missing.text).jsonObject["attached_image"])
        val failed = original.copy(text = """{"ok":false,"image_attached":false,"error":"decode_failed"}""")
        val result = ImageInspectionDelivery.update(failed, bytes)
        assertTrue(result.isError); assertTrue(result.imageData.isEmpty())
        assertFalse(Json.parseToJsonElement(result.text).jsonObject.getValue("ok").jsonPrimitive.boolean)
    }

    @Test fun queuedUserTextDoesNotEvictTheBatchJustReturnedByTools() {
        val calls = (1..3).map { ToolCallData("tool-$it", "view_chat_image", "{}") }
        val history = listOf(ChatMessage(Role.ASSISTANT, toolCalls = calls)) +
            calls.map { original.copy(toolCallId = it.id) } + ChatMessage(Role.USER, "Also compare their colors")
        val prepared = ContextHygiene.shrinkToolResults(history)
        assertEquals(3, prepared.sumOf { it.imageData.size })
        assertTrue(prepared.filter { it.role == Role.TOOL }.all { Json.parseToJsonElement(it.text).jsonObject.getValue("ok").jsonPrimitive.boolean })
    }

    @Test fun userTextAndToolOutputReadersAreNeverReinterpretedAsImageResults() {
        val user = original.copy(role = Role.USER, toolName = null)
        val reader = original.copy(toolName = "read_tool_output")
        assertEquals(user, ImageInspectionDelivery.update(user, bytes))
        assertEquals(reader, ImageInspectionDelivery.update(reader, bytes))
    }

    @Test fun screenshotOmissionPreservesTheSuccessfulPageRead() {
        val screenshot = original.copy(toolName = "browser_read", text = "Page contents\n[screenshot result; page text read successfully]\n" + original.text)
        val omitted = ContextHygiene.shrinkToolResults(listOf(screenshot), maxImages = 0).single()
        assertFalse(omitted.isError)
        assertTrue(omitted.text.startsWith("Page contents"))
        assertEquals(JsonNull, ImageInspectionDelivery.envelope(omitted.text)?.body?.get("attached_image"))
    }
}
