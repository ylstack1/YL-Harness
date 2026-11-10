package com.androidharness.app.agent

/**
 * Persisted reasoning intent. ThinkingSpecs resolves this to a model's native
 * choices for both the UI and request. Ultra remains readable for older settings.
 */
enum class ThinkingLevel(val label: String) {
    OFF("Off"),
    MINIMAL("Minimal"),
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High"),
    XHIGH("X-High"),
    MAX("Max"),
    ULTRA("Ultra"),
    ;

    /** Position on the global ladder, basis for nearest-weaker clamping. */
    val rank: Int get() = ordinal

    /**
     * Budget in tokens for providers that take an explicit thinking budget.
     * Intermediate presets only. ThinkingSpecs supplies the real minimum and
     * maximum from the provider/model and removes duplicate UI choices.
     */
    fun budgetTokens(@Suppress("UNUSED_PARAMETER") maxOutputTokens: Int): Int = when (this) {
        OFF -> 0
        MINIMAL -> 512
        LOW -> 1_024
        MEDIUM -> 4_096
        HIGH -> 16_384
        XHIGH -> 24_576
        MAX, ULTRA -> 32_768
    }

}
