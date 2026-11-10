package com.androidharness.app.tools

import com.androidharness.app.core.ImageRef
import com.androidharness.app.ui.chat.RemoteImageFetch
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ChatImageResultTest {
    @Test fun cropIdentifiesTheSelectableSourceAndSeparatelyDescribesTheAttachment() {
        val source = ChatImageSource.Remote("https://example.com/full.png", "Diagram")
        val image = ImageRef("crop.png", "image/png", imageId = "crop_attachment", width = 300, height = 200, bytes = 400)
        val result = imageInspectionSuccess(image, source, 2, sourceWidth = 1200, sourceHeight = 800, sourceBytes = 5000,
            crop = listOf(0, 0, 250, 250))
        val json = Json.parseToJsonElement(result.output).jsonObject
        assertTrue(result.ok); assertTrue(json.getValue("image_attached").jsonPrimitive.boolean)
        assertEquals(source.id, json.getValue("image_id").jsonPrimitive.content)
        assertEquals(source.id, json.getValue("source_image_id").jsonPrimitive.content)
        assertEquals(1200, json.getValue("width").jsonPrimitive.int)
        assertEquals(300, json.getValue("attached_image").jsonObject.getValue("width").jsonPrimitive.int)
        assertEquals("crop_attachment", json.getValue("attached_image").jsonObject.getValue("attachment_id").jsonPrimitive.content)
        assertEquals(5000L, json.getValue("source_bytes").jsonPrimitive.long)
        assertEquals(400L, json.getValue("attached_bytes").jsonPrimitive.long)
        assertFalse(json.containsKey("bytes")); assertFalse(json.containsKey("http_status"))
        assertEquals(1.0, json.getValue("attached_image").jsonObject.getValue("scale_x").jsonPrimitive.double, 0.001)
        assertEquals(300, json.getValue("source_region").jsonObject.getValue("right").jsonPrimitive.int)
        assertEquals("remote_web", json.getValue("origin").jsonPrimitive.content)
        assertEquals("assistant_turn", json.getValue("image_number_scope").jsonPrimitive.content)
        assertEquals(image, result.image)
    }

    @Test fun resizedFullFrameDescribesSourceAndAttachmentSeparately() {
        val source = ChatImageSource.Remote("https://example.com/page.png", "Screenshot")
        val result = imageInspectionSuccess(ImageRef("small.png", "image/png", width = 882, height = 1568, bytes = 200), source,
            sourceWidth = 1080, sourceHeight = 1920, sourceBytes = 900, httpStatus = 200, degraded = true)
        val json = Json.parseToJsonElement(result.output).jsonObject
        val attached = json.getValue("attached_image").jsonObject
        assertEquals(200, json.getValue("http_status").jsonPrimitive.int)
        assertEquals(882.0 / 1080, attached.getValue("scale_x").jsonPrimitive.double, 0.0001)
        assertEquals(1568.0 / 1920, attached.getValue("scale_y").jsonPrimitive.double, 0.0001)
        assertTrue(json.getValue("degraded").jsonPrimitive.boolean)
    }

    @Test fun failedLoadsAreExplicitAndNeverClaimAnAttachment() {
        val result = imageInspectionFailure("fetch_failed", "Not available", fetched = RemoteImageFetch(httpStatus = 404, mimeType = "text/html"))
        val json = Json.parseToJsonElement(result.output).jsonObject
        assertFalse(result.ok); assertNull(result.image)
        assertFalse(json.getValue("image_attached").jsonPrimitive.boolean)
        assertEquals("fetch_failed", json.getValue("error").jsonPrimitive.content)
        assertEquals(404, json.getValue("http_status").jsonPrimitive.int)
    }
}
