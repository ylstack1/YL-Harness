package com.androidharness.app.core.response

import com.androidharness.app.artifacts.ArtifactAttachment
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Selection/context is attached to the user message, leaving their editable prompt compact. */
@Serializable
data class ResponseAttachment(val title: String, val context: String) {
    fun encode(): String = json.encodeToString(serializer(), this)
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private const val START = "[[harness-response]]\n"
        private const val END = "\n[[/harness-response]]\n\n"
        fun decode(value: String?): ResponseAttachment? = value?.takeIf { it.length <= 12_000 }?.let {
            runCatching { json.decodeFromString(serializer(), it).also { ref ->
                require(ref.title.isNotBlank() && ref.title.length <= 100 && ref.context.length <= 1500)
            } }.getOrNull()
        }
        fun fromPrompt(text: String): ResponseAttachment? {
            if (!text.startsWith(START)) return null
            val end = text.indexOf(END, START.length).takeIf { it in START.length..12_000 } ?: return null
            return decode(text.substring(START.length, end))
        }
        fun visibleText(text: String): String = if (fromPrompt(text) == null) text else text.substring(text.indexOf(END) + END.length)
        fun attach(text: String, ref: ResponseAttachment?): String = if (ref == null) text else {
            require(decode(ref.encode()) != null)
            START + ref.encode() + END + visibleText(text)
        }
    }
}

/** Shared display/edit handling keeps both kinds of chat context out of user-facing message text. */
object UserMessageContent {
    fun visibleText(text: String): String = ArtifactAttachment.visibleText(ResponseAttachment.visibleText(text))
    fun edit(text: String, original: String): String = ResponseAttachment.attach(
        ArtifactAttachment.attach(text, ArtifactAttachment.fromPrompt(ResponseAttachment.visibleText(original))),
        ResponseAttachment.fromPrompt(original),
    )
}
