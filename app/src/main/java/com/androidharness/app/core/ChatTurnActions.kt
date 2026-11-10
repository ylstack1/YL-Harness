package com.androidharness.app.core

/** Pure turn lookups shared by the chat UI and its JVM tests. */
object ChatTurnActions {
    fun answerVersionKey(prefixId: String?, question: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest((prefixId.orEmpty() + "\u0000" + question).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** The question that produced [turnId], used when regenerating its answer. */
    fun userMessageFor(messages: List<ChatMessage>, turnId: String?): ChatMessage? =
        turnId?.let { id -> messages.lastOrNull { it.role == Role.USER && it.turnId == id } }
}
