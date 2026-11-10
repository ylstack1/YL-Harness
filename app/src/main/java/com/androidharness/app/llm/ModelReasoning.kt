package com.androidharness.app.llm

/** A family-name hint is not a capability reported by this provider. */
enum class ModelReasoning {
    REPORTED, CUSTOM, INFERRED, UNKNOWN, UNSUPPORTED;

    companion object {
        fun of(entry: ModelEntry, providerType: ProviderType? = null): ModelReasoning = when {
            entry.custom && entry.reasoning == true -> CUSTOM
            // Older cached Gemini catalogs stored name heuristics as a boolean.
            providerType == ProviderType.GEMINI && !entry.custom && !entry.reasoningReported ->
                if (reasoningCapable(entry.id)) INFERRED else UNKNOWN
            entry.reasoning == true -> REPORTED
            entry.reasoning == false -> UNSUPPORTED
            reasoningCapable(entry.id) -> INFERRED
            else -> UNKNOWN
        }
    }
}
