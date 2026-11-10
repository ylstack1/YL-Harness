package com.androidharness.app.artifacts

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Small native composer attachment; internal references never occupy the user's input. */
@Serializable
data class ArtifactAttachment(val id: String, val revision: String, val title: String) {
    fun encode() = json.encodeToString(serializer(), this)
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private const val START = "[[harness-concept]]\n"
        private const val END = "\n[[/harness-concept]]\n\n"
        fun from(artifact: ChatArtifact) = ArtifactAttachment(artifact.id, artifact.revision, artifact.title)
        fun decode(value: String?): ArtifactAttachment? = value?.takeIf { it.length <= 2048 }?.let {
            runCatching { json.decodeFromString(serializer(), it).also { ref ->
                ChatArtifact(ref.id, ref.revision, ref.title, "<p></p>").validated()
            } }.getOrNull()
        }
        fun fromPrompt(text: String): ArtifactAttachment? {
            if (!text.startsWith(START)) return null
            val end = text.indexOf(END, START.length).takeIf { it in START.length..2048 } ?: return null
            return decode(text.substring(START.length, end))
        }
        fun visibleText(text: String): String = if (fromPrompt(text) == null) text else text.substring(text.indexOf(END) + END.length)
        fun attach(text: String, ref: ArtifactAttachment?): String =
            if (ref == null) text else START + ref.encode() + END + visibleText(text)
    }
}
