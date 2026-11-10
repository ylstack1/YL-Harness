package com.androidharness.app.core

import com.androidharness.app.agent.ThinkingTiming
import com.androidharness.app.agent.ThinkingTokenCount
import com.androidharness.app.agent.thinkingTokenCount

internal data class ChatThinkingTotals(val milliseconds: Long, val tokens: Long, val estimated: Boolean)

/** Parent reasoning only; the live request is excluded by callers once its committed row is visible. */
internal fun chatThinkingTotals(messages: List<ChatMessage>, streamingThinking: String, timing: ThinkingTiming?,
    reasoningTokens: Int?, now: Long): ChatThinkingTotals {
    val parents = messages.filter { it.role == Role.ASSISTANT && it.toolCallId == null }

    // A provider's reported count replaces the streamed estimate only when it
    // is not smaller, so the header total never drops mid-turn.
    fun count(text: String, reported: Int?): ThinkingTokenCount? {
        val estimated = thinkingTokenCount(text, null)
        val exact = reported?.takeIf { it >= 0 }?.let { ThinkingTokenCount(it, false) }
        return when {
            exact == null -> estimated
            estimated == null -> exact
            estimated.count > exact.count -> estimated
            else -> exact
        }
    }
    val counts = parents.mapNotNull { count(it.thinking, it.generation.reasoningTokens) } +
        listOfNotNull(count(streamingThinking, reasoningTokens))

    // With committed timestamps the turn shows its wall-clock span, thinking
    // plus tool time; without them it falls back to summed reasoning spans.
    val timestamps = messages.mapNotNull { m -> m.createdAt.takeIf { it > 0 } }
    val spanMs = if (timestamps.size >= 2) timestamps.max() - timestamps.min() else null
    return ChatThinkingTotals(
        (spanMs ?: parents.sumOf { it.thinkingMs.coerceAtLeast(0) }) + (timing?.durationMs(now) ?: 0),
        counts.sumOf { it.count.toLong() },
        counts.isEmpty() || counts.any { it.estimated },
    )
}

/** One decimal where needed, promoting rounded values at the next unit boundary. */
internal fun compactThinkingTokenCount(tokens: Long): String {
    val units = listOf("", "k", "m", "b", "t")
    var value = tokens.coerceAtLeast(0).toDouble()
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) { value /= 1000; unit++ }
    var rounded = kotlin.math.floor(value * 10 + 0.5) / 10
    if (rounded >= 1000 && unit < units.lastIndex) { rounded /= 1000; unit++ }
    val number = if (rounded % 1.0 == 0.0) rounded.toLong().toString()
        else String.format(java.util.Locale.ROOT, "%.1f", rounded)
    return number + units[unit]
}
