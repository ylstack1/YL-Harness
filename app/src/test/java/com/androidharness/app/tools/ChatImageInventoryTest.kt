package com.androidharness.app.tools

import com.androidharness.app.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ChatImageInventoryTest {
    @Test fun stableIdsSurviveOrdinalChangesAndTheListEchoesIdentity() {
        val photo = ChatMessage(Role.ASSISTANT, "![First caption](https://example.com/one.png) ![Second caption](https://example.com/two.png)")
        val initial = ChatImageInventory.from(listOf(photo))
        assertEquals(listOf("https://example.com/two.png", "https://example.com/one.png"), initial.map { (it as ChatImageSource.Remote).url })
        val newer = ChatMessage(Role.USER, "Inspect my diagram", images = listOf(ImageRef("user.png", "image/png", width = 1200, height = 800)))
        val changed = ChatImageInventory.from(listOf(photo, newer))
        assertEquals(initial.map { it.id }, changed.drop(1).map { it.id })
        val chosen = ChatImageInventory.select(buildJsonObject { put("image_id", initial[1].id) }, changed)
        assertEquals(initial[1], chosen)
        assertNull(ChatImageInventory.select(buildJsonObject { put("image_id", initial[1].id); put("image_number", 1) }, changed))
        assertNull(ChatImageInventory.select(buildJsonObject { put("image_id", "invented") }, changed))
        val prompt = ChatImageInventory.prompt(listOf(photo, newer))
        assertTrue(prompt.contains("user_upload")); assertTrue(prompt.contains("First caption"))
        assertTrue(prompt.contains("\"width\":1200")); assertTrue(prompt.contains("\"height\":800"))
        assertTrue(prompt.contains(initial[1].id)); assertTrue(prompt.contains("https://example.com/one.png"))
    }

    @Test fun numbersStayFixedForTheWholeAssistantTurnWhileNewImagesAreAppended() {
        val first = ChatMessage(Role.USER, images = listOf(ImageRef("logo.png", "image/png")))
        val turn = ChatImageTurnInventory(listOf(first))
        val originalId = turn.sources().single().id
        val second = ChatMessage(Role.ASSISTANT, "![banner](https://example.com/banner.png)")
        turn.update(listOf(first, second))
        val bannerId = turn.sources()[1].id
        val third = ChatMessage(Role.ASSISTANT, "![social](https://example.com/social.png)")
        turn.update(listOf(first, second, third))
        turn.update(listOf(first, second, third))
        assertEquals(listOf(originalId, bannerId, ChatImageInventory.remoteId("https://example.com/social.png")), turn.sources().map { it.id })
        assertEquals(originalId, ChatImageInventory.select(buildJsonObject { put("image_number", 1) }, turn.sources())?.id)
        val saved = ImageRef("banner.png", "image/png", imageId = bannerId, sourceUrl = "https://example.com/banner.png", origin = "web_image")
        turn.update(listOf(first, second, third, ChatMessage(Role.TOOL, toolName = "view_chat_image", images = listOf(saved))))
        assertEquals(bannerId, turn.sources()[1].id)
        assertTrue(turn.sources()[1] is ChatImageSource.Stored)
        assertEquals("remote_web", turn.sources()[1].origin)
        assertEquals("tool", turn.sources()[1].displayRole)
        assertTrue(ChatImageInventory.promptSources(turn.sources()).contains("stays fixed throughout this assistant turn"))
    }

    @Test fun legacyToolImagesRecoverCleanCaptionAndOriginFromTheEnvelope() {
        val body = """{"ok":false,"image_attached":false,"error":"not_in_current_context","caption":"A2 agent logo","origin":"tool_image","image_id":"img_legacy","available_image":{"width":256,"height":256}}"""
        val result = ChatMessage(Role.TOOL, body, toolName = "view_chat_image", isError = true,
            images = listOf(ImageRef("legacy.png", "image/png")))
        val source = ChatImageInventory.from(listOf(result)).single()
        assertEquals("A2 agent logo", source.label)
        assertEquals("tool_image", source.origin)
        assertEquals("tool", source.displayRole)
        assertEquals("img_legacy", source.id)
        assertFalse(ChatImageInventory.prompt(listOf(result)).contains("assistant image: {"))
    }

    @Test fun queryCleanupDeduplicatesButPreservesImageTransformsAndSignedUrls() {
        val history = listOf(ChatMessage(Role.ASSISTANT, "![a](https://example.com/opengraph-image?c3e5ae0697f46c0e)\n![b](https://example.com/opengraph-image)"))
        assertEquals(1, ChatImageInventory.from(history).size)
        assertEquals("https://example.com/p.png?w=640&h=320", httpImageUrl("https://example.com/p.png?w=640&amp;h=320#preview"))
        assertEquals("https://example.com/p.png?signature=abc&expires=123", httpImageUrl("https://example.com/p.png?signature=abc&amp;expires=123"))
        assertEquals("https://example.com/protected.png?0123456789abcdef", httpImageUrl("https://example.com/protected.png?0123456789abcdef"))
        assertNotEquals(ChatImageInventory.remoteId("https://example.com/p.png?w=640"), ChatImageInventory.remoteId("https://example.com/p.png?w=1280"))
        assertNull(httpImageUrl("https://name:secret@example.com/p.png"))
    }

    @Test fun inspectedFullFramesAreIdentifiableButCropsDoNotRenumberTheirSources() {
        val remote = "https://example.com/source.png"
        val id = ChatImageInventory.remoteId(remote)
        val messages = listOf(ChatMessage(Role.ASSISTANT, "![source]($remote)"),
            ChatMessage(Role.TOOL, toolName = "view_chat_image", images = listOf(ImageRef("saved.png", "image/png", imageId = id, sourceUrl = remote))),
            ChatMessage(Role.TOOL, toolName = "zoom_chat_image", images = listOf(ImageRef("crop.png", "image/png"))))
        assertEquals(listOf(id), ChatImageInventory.from(messages).map { it.id })
    }
}
