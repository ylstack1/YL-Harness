package com.androidharness.app.data

import kotlinx.serialization.Serializable

@Serializable
data class CustomThemePalette(
    val id: String,
    val name: String,
    val primary: Long,
    val secondary: Long? = null,
    val tertiary: Long? = null,
)

internal fun CustomThemePalette.valid(): Boolean = id.length in 1..100 && name.isNotBlank() && name.length <= 40 &&
    listOfNotNull(primary, secondary, tertiary).all { it in 0xFF000000..0xFFFFFFFF }

internal val canvasImageIdPattern = Regex("canvas-[a-fA-F0-9-]{36}\\.(jpg|png)")

@Serializable
data class HarnessCanvasSettings(
    val imageId: String? = null,
    val seed: Long? = null,
    val enabled: Boolean = true,
    val chat: Boolean = true,
    val code: Boolean = true,
    val dim: Float = .65f,
    val adaptColors: Boolean = false,
)

internal fun parseThemeHex(value: String): Long? = value.trim().removePrefix("#")
    .takeIf { it.length == 6 && it.all { c -> c in "0123456789abcdefABCDEF" } }
    ?.toLongOrNull(16)?.or(0xFF000000)

internal fun themeHex(color: Long): String = "#%06X".format(color and 0xFFFFFF)
