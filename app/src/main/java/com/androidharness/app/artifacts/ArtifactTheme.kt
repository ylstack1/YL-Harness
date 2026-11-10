package com.androidharness.app.artifacts

/**
 * Colors handed to generated HTML as CSS variables. Built from the app's live Material scheme so visuals follow
 * custom palettes and the Canvas theme; [fallback] keeps the original fixed palette for callers without one.
 */
data class ArtifactTheme(
    val dark: Boolean,
    val background: String,
    val foreground: String,
    val muted: String,
    val surface: String,
    val surface2: String,
    val border: String,
    val accent: String,
    val onAccent: String,
    val accentSoft: String,
    val success: String,
    val warning: String,
    val danger: String,
    val palette: List<String>,
) {
    /** Only #rrggbb values reach the stylesheet, so a color can never close the style block. */
    fun safe(): ArtifactTheme {
        val fallback = fallback(dark)
        fun pick(value: String, default: String) = value.takeIf { HEX.matches(it) } ?: default
        val colors = palette.filter { HEX.matches(it) }.take(8)
        return ArtifactTheme(dark, pick(background, fallback.background), pick(foreground, fallback.foreground), pick(muted, fallback.muted),
            pick(surface, fallback.surface), pick(surface2, fallback.surface2), pick(border, fallback.border), pick(accent, fallback.accent),
            pick(onAccent, fallback.onAccent), pick(accentSoft, fallback.accentSoft), pick(success, fallback.success),
            pick(warning, fallback.warning), pick(danger, fallback.danger), colors.ifEmpty { fallback.palette })
    }

    companion object {
        private val HEX = Regex("#[0-9a-fA-F]{6}")

        fun fallback(dark: Boolean) = if (dark) ArtifactTheme(true, "#141517", "#eceef2", "#adb2bc", "#202226", "#2a2c31", "#33363d",
            "#b3bfff", "#1b2667", "#2b3260", "#7fd49a", "#f0c36b", "#ffb4ab",
            listOf("#b3bfff", "#7fd49a", "#f0c36b", "#f2a5c9", "#7dd3e8", "#ffb4ab"))
        else ArtifactTheme(false, "#fcfcfd", "#22252b", "#626773", "#f1f2f5", "#e8eaef", "#e2e4e9",
            "#435ccd", "#ffffff", "#dfe4fb", "#2e8a4f", "#a86b00", "#ba1a1a",
            listOf("#435ccd", "#2e8a4f", "#c47f00", "#b4467a", "#1f8aa6", "#ba1a1a"))
    }
}
