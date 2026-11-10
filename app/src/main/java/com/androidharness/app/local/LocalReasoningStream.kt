package com.androidharness.app.local

import com.androidharness.app.llm.StreamEvent
import kotlinx.serialization.json.*

/** Fallback for models whose templates leave leading think markers in content. */
internal class LocalReasoningStream {
    private enum class Phase { START, THINKING, ANSWER }
    private var phase = Phase.START
    private val pending = StringBuilder()
    fun events(data: String): List<StreamEvent> = buildList {
        val item = Json.parseToJsonElement(data).jsonObject
        if (item["thinking_prefill"]?.jsonPrimitive?.booleanOrNull == true && phase == Phase.START) phase = Phase.THINKING
        item["thinking"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }?.let {
            // Native template parsing takes priority; its subsequent content is
            // already the final answer and must not be reclassified.
            pending.clear()
            phase = Phase.ANSWER
            add(StreamEvent.ThinkingDelta(it))
        }
        item["text"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }?.let {
            pending.append(it)
            drain(this)
        }
    }
    private fun drain(output: MutableList<StreamEvent>) {
        if (phase == Phase.START) {
            val text = pending.toString().trimStart()
            if (text.length < "<think>".length && "<think>".startsWith(text)) return
            if (text.startsWith("<think>")) {
                pending.clear(); pending.append(text.removePrefix("<think>")); phase = Phase.THINKING
            } else {
                phase = Phase.ANSWER
            }
        }
        if (phase == Phase.THINKING) {
            val close = "</think>"
            val index = pending.indexOf(close)
            if (index >= 0) {
                if (index > 0) output.add(StreamEvent.ThinkingDelta(pending.substring(0, index)))
                pending.delete(0, index + close.length)
                phase = Phase.ANSWER
            } else {
                val text = pending.toString()
                val held = (1 until close.length).lastOrNull { text.endsWith(close.take(it)) } ?: 0
                val ready = text.length - held
                if (ready > 0) { output.add(StreamEvent.ThinkingDelta(text.take(ready))); pending.delete(0, ready) }
            }
        }
        if (phase == Phase.ANSWER && pending.isNotEmpty()) {
            output.add(StreamEvent.TextDelta(pending.toString())); pending.clear()
        }
    }
    fun finish(): List<StreamEvent> {
        if (pending.isEmpty()) return emptyList()
        val value = pending.toString(); pending.clear()
        return listOf(if (phase == Phase.THINKING) StreamEvent.ThinkingDelta(value) else StreamEvent.TextDelta(value))
    }
}
