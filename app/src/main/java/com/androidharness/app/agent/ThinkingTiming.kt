package com.androidharness.app.agent

import com.androidharness.app.llm.StreamEvent

/** Per-response reasoning spans, measured with a monotonic clock. Answer/tool time is excluded. */
data class ThinkingTiming(val startedAt: Long? = null, val elapsedMs: Long = 0) {
    fun start(now: Long): ThinkingTiming = if (startedAt == null) copy(startedAt = now) else this
    fun stop(now: Long): ThinkingTiming = if (startedAt == null) this
        else ThinkingTiming(elapsedMs = durationMs(now))
    fun durationMs(now: Long): Long = elapsedMs + (startedAt?.let { (now - it).coerceAtLeast(0) } ?: 0)

    fun observe(event: StreamEvent, now: Long): ThinkingTiming = when (event) {
        is StreamEvent.ThinkingDelta -> if (event.text.isNotEmpty()) start(now) else this
        is StreamEvent.TextDelta -> if (event.text.isNotEmpty()) stop(now) else this
        is StreamEvent.ToolCallDelta -> if (event.arguments.isNotEmpty()) stop(now) else this
        is StreamEvent.ToolCallReady, is StreamEvent.ToolCallBatch, is StreamEvent.Done -> stop(now)
        is StreamEvent.Batch -> event.events.fold(this) { timing, nested -> timing.observe(nested, now) }
        else -> this
    }

    companion object { fun now(): Long = System.nanoTime() / 1_000_000 }
}

data class ThinkingTokenCount(val count: Int, val estimated: Boolean)

/** Visible text is only an estimate, never the provider's hidden/billed reasoning usage. */
fun thinkingTokenCount(text: String, reported: Int?): ThinkingTokenCount? = when {
    reported != null && reported >= 0 -> ThinkingTokenCount(reported, false)
    text.isNotBlank() -> ThinkingTokenCount((text.length / 4 + if (text.length % 4 == 0) 0 else 1).coerceAtLeast(1), true)
    else -> null
}
