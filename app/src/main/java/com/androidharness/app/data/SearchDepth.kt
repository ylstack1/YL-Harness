package com.androidharness.app.data

import kotlinx.serialization.Serializable

/**
 * How much web research a Chat turn may do. The limit counts distinct result
 * URLs across the whole turn, not a single request, so a large budget can be
 * spent over several focused searches. AUTO leaves the amount to the model.
 */
@Serializable
enum class SearchDepth(val maxSources: Int?) {
    OFF(0),
    INSTANT(5),
    DEEP(20),
    AUTO(null),
    DEEP_RESEARCH(50),
    MAX_RESEARCH(100);

    companion object {
        fun fromStored(value: String?): SearchDepth =
            entries.firstOrNull { it.name == value } ?: AUTO
    }
}
