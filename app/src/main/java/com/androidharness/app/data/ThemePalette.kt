package com.androidharness.app.data

@kotlinx.serialization.Serializable
enum class ThemePalette {
    BLUE, OCEAN, INDIGO, LAVENDER, ORCHID, ROSE, CORAL, AMBER, LIME, FOREST, TEAL, SLATE;

    companion object {
        fun fromStored(value: String?): ThemePalette = entries.firstOrNull { it.name == value } ?: BLUE
    }
}
