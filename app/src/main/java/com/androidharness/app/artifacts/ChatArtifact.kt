package com.androidharness.app.artifacts

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/** An immutable revision, owned by the message and session that contain it. */
@Serializable
data class ChatArtifact(
    val id: String,
    val revision: String,
    val title: String,
    val html: String,
) {
    fun validated(): ChatArtifact {
        require(id.matches(UUID_PATTERN) && revision.matches(UUID_PATTERN)) { "Invalid concept identifier." }
        require(title.isNotBlank() && title.length <= MAX_TITLE) { "Use a concept title of 1–80 characters." }
        require(html.isNotBlank()) { "The concept HTML is empty." }
        require(html.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Concepts support up to 256 KB of self-contained HTML." }
        return this
    }

    companion object {
        const val MAX_BYTES = 256_000
        const val MAX_TITLE = 80
        private val UUID_PATTERN = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        private val json = Json { ignoreUnknownKeys = true }
        fun create(title: String, html: String, id: String? = null) =
            ChatArtifact(id ?: UUID.randomUUID().toString(), UUID.randomUUID().toString(), title.trim(), html).validated()
        fun encode(artifact: ChatArtifact): String = json.encodeToString(serializer(), artifact.validated())
        fun decode(value: String?): ChatArtifact? = value?.takeIf { it.length <= MAX_BYTES * 6 + 2048 }?.let {
            runCatching { json.decodeFromString(serializer(), it).validated() }.getOrNull()
        }
    }
}
