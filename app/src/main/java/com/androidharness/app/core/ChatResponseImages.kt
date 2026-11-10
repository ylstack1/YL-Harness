package com.androidharness.app.core

/** Opaque image references are resolved only against attachments in the owning chat. */
internal object ChatResponseImages {
    private val reference = Regex("^harness-image:(img_[A-Za-z0-9_-]{1,128})$")
    private val markdown = Regex("!\\[([^\\]]*)]\\((harness-image:img_[A-Za-z0-9_-]{1,128})\\)")
    fun imageId(url: String): String? = reference.matchEntire(url)?.groupValues?.get(1)
    fun url(imageId: String): String = "harness-image:$imageId"
    fun stableId(value: String): String = "img_" + java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    fun id(ref: ImageRef): String = ref.imageId ?: stableId("stored:" + ref.name)
    fun available(messages: List<ChatMessage>): Map<String, ImageRef> = buildMap {
        messages.forEach { message -> message.images.forEach { ref ->
            if (ref.name.isNotBlank() && ref.name !in setOf(".", "..") && '/' !in ref.name && '\\' !in ref.name)
                put(id(ref), ref)
        } }
    }
    fun referenced(text: String, messages: List<ChatMessage>): List<ImageRef> {
        val available = available(messages)
        var fence: Char? = null
        val visible = text.lineSequence().filter { line ->
            val trimmed = line.trimStart()
            val marker = trimmed.takeWhile { it == '`' || it == '~' }
            if (marker.length >= 3) { fence = if (fence == null) marker.first() else if (marker.first() == fence) null else fence; false }
            else fence == null
        }.joinToString("\n").replace(Regex("`[^`]*`"), "")
        return markdown.findAll(visible).mapNotNull { match -> imageId(match.groupValues[2])?.let(available::get) }
            .distinctBy { it.name }.toList()
    }
}
