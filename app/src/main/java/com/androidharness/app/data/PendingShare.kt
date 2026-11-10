package com.androidharness.app.data

import android.content.Intent
import android.net.Uri
import android.os.Build
import java.util.UUID

/** Shared content stays a draft and can only be delivered to its chosen regular Chat. */
data class PendingShare(
    val text: String? = null,
    val streams: List<Uri> = emptyList(),
    val id: String = UUID.randomUUID().toString(),
    val targetSessionId: String? = null,
    val mime: String? = null,
) {
    fun isFor(sessionId: String?, conversation: ConversationConfig): Boolean =
        conversation.isChat && targetSessionId != null && targetSessionId == sessionId

    companion object {
        fun fromIntent(intent: Intent): PendingShare? {
            if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return null
            val streams = mutableListOf<Uri>()
            if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                val items = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }
                streams += items.orEmpty()
            } else {
                val stream = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }
                stream?.let(streams::add)
            }
            val clipText = mutableListOf<String>()
            intent.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) {
                    val item = clip.getItemAt(index)
                    item.uri?.let(streams::add)
                    item.text?.toString()?.takeIf(String::isNotBlank)?.let(clipText::add)
                }
            }
            // Senders must grant a content URI; never read arbitrary local file paths.
            require(streams.all { it.scheme == "content" }) { "Unsupported attachment URI" }
            val body = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf(String::isNotBlank)
                ?: clipText.distinct().joinToString("\n\n").takeIf(String::isNotBlank)
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf(String::isNotBlank)
            val text = listOfNotNull(subject?.takeUnless { it == body }, body).joinToString("\n\n").takeIf(String::isNotBlank)
            return if (text == null && streams.isEmpty()) null else PendingShare(text, streams.distinct(), mime = intent.type)
        }
    }
}
