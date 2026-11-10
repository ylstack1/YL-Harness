package com.androidharness.app.data

import kotlinx.serialization.Serializable

@Serializable
enum class StartScreen {
    CODE, CHAT, LAST_SELECTED;

    fun kind(last: ConversationKind): ConversationKind = when (this) {
        CODE -> ConversationKind.CODE
        CHAT -> ConversationKind.CHAT
        LAST_SELECTED -> last
    }

    companion object {
        fun fromStored(value: String?): StartScreen = entries.firstOrNull { it.name == value } ?: LAST_SELECTED
    }
}

/** Chat has its own start preference; Code has an equivalent one below. */
@Serializable
enum class ChatStartMode {
    NEW_CHAT, LAST_CHAT;

    companion object {
        fun fromStored(value: String?): ChatStartMode = entries.firstOrNull { it.name == value } ?: NEW_CHAT
    }
}

/** Code keeps its own start preference so changing Chat never changes Code. */
@Serializable
enum class CodeStartMode {
    NEW_CODE, LAST_CODE;

    companion object {
        fun fromStored(value: String?): CodeStartMode = entries.firstOrNull { it.name == value } ?: LAST_CODE
    }
}

/** Startup mode and resume-chat preference are independent; never resume a different section. */
internal data class ConversationStartup(val kind: ConversationKind, val sessionId: String?) {
    companion object {
        fun resolve(settings: AppSettings, lastSessionKind: ConversationKind?): ConversationStartup {
            val kind = settings.startScreen.kind(settings.lastConversationKind)
            val resume = when (kind) {
                ConversationKind.CHAT -> settings.chatStart == ChatStartMode.LAST_CHAT
                ConversationKind.CODE -> settings.codeStart == CodeStartMode.LAST_CODE
            }
            val session = settings.lastActiveSessionId?.takeIf {
                resume && lastSessionKind == kind && it.isNotBlank() && it !in settings.archivedSessions
            }
            return ConversationStartup(kind, session)
        }
    }
}
