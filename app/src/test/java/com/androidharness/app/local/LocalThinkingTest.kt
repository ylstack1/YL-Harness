package com.androidharness.app.local

import com.androidharness.app.llm.StreamEvent
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LocalThinkingTest {
    private fun frame(text: String) = buildJsonObject { put("text", text) }.toString()
    private fun text(events: List<StreamEvent>) = events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.text }
    private fun thinking(events: List<StreamEvent>) = events.filterIsInstance<StreamEvent.ThinkingDelta>().joinToString("") { it.text }
    @Test fun overridesAndOldSettingsRemainCompatible() {
        val old = Json.decodeFromString<LocalModelLimits>("{}"); assertEquals(LocalThinkingMode.AUTO, old.thinkingMode)
        assertFalse(LocalThinkingMode.AUTO.enabled(false)); assertTrue(LocalThinkingMode.AUTO.enabled(true))
        assertTrue(LocalThinkingMode.THINKING.enabled(false)); assertFalse(LocalThinkingMode.DIRECT.enabled(true))
        val saved = old.copy(thinkingMode = LocalThinkingMode.THINKING)
        assertEquals(saved, Json.decodeFromString<LocalModelLimits>(Json.encodeToString(LocalModelLimits.serializer(), saved)))
        val request = Json.parseToJsonElement(LocalChatCodec.request("", emptyList(), emptyList(), thinking = false, thinkingMode = LocalThinkingMode.THINKING)).jsonObject
        assertTrue(request.getValue("enable_thinking").jsonPrimitive.boolean)
        assertEquals("THINKING", request.getValue("thinking_model").jsonPrimitive.content)
    }
    @Test fun everyPossibleChunkBoundarySeparatesMarkers() {
        val raw = "<think>Consider this carefully.</think>The answer is 42."
        for (split in 0..raw.length) {
            val stream = LocalReasoningStream()
            val events = stream.events(frame(raw.take(split))) + stream.events(frame(raw.drop(split))) + stream.finish()
            assertEquals("split $split", "Consider this carefully.", thinking(events))
            assertEquals("split $split", "The answer is 42.", text(events))
        }
    }
    @Test fun tokenByTokenAndWhitespaceDoNotLeakTags() {
        val stream = LocalReasoningStream()
        val events = " \n<think>Reason</think>Final".flatMap { stream.events(frame(it.toString())) } + stream.finish()
        assertEquals("Reason", thinking(events)); assertEquals("Final", text(events))
    }
    @Test fun templatePrefilledOpeningIsHandled() {
        val stream = LocalReasoningStream()
        val events = stream.events("{\"thinking_prefill\":true}") + stream.events(frame("Reason</thi")) + stream.events(frame("nk>Final")) + stream.finish()
        assertEquals("Reason", thinking(events)); assertEquals("Final", text(events))
    }
    @Test fun nativeReasoningIsNeverDuplicatedAndInlineTagsRemainLiteral() {
        val stream = LocalReasoningStream()
        val events = stream.events(frame("<thi")) + stream.events("{\"thinking\":\"Native reasoning\"}") + stream.events(frame("Use <think> as an example.")) + stream.finish()
        assertEquals("Native reasoning", thinking(events)); assertEquals("Use <think> as an example.", text(events))
    }
    @Test fun truncatedThinkingAndEmptyBlocksAreSafe() {
        val stream = LocalReasoningStream()
        val events = stream.events(frame("<think>Unfinished reasoning")) + stream.finish()
        assertEquals("Unfinished reasoning", thinking(events)); assertEquals("", text(events))
        val empty = LocalReasoningStream().events(frame("<think></think>Answer"))
        assertEquals("", thinking(empty)); assertEquals("Answer", text(empty))
    }
    @Test fun ordinaryTextAndCodeArePreserved() {
        for (raw in listOf("Hello", "```xml\n<think>literal</think>\n```", "A <think>literal</think> tag", "<thi")) {
            val stream = LocalReasoningStream()
            val events = raw.flatMap { stream.events(frame(it.toString())) } + stream.finish()
            assertEquals(raw, text(events)); assertEquals("", thinking(events))
        }
    }
}
