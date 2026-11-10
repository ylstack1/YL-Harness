package com.androidharness.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

@Serializable
enum class ConversationKind { CODE, CHAT }

@Serializable
enum class ChatTool { WEB_SEARCH, WEB_PAGES, ARTIFACTS }

/** How readily the assistant adds pictures to Chat answers. */
@Serializable
enum class ImageFrequency { NONE, LESS, DEFAULT, MORE, ALWAYS }

/** Independent of PLAN/ACT and of approvals. Old sessions remain Code. */
@Serializable
data class ConversationConfig(
    val kind: ConversationKind = ConversationKind.CODE,
    val toolsEnabled: Boolean = false,
    val selectedTools: Set<ChatTool> = setOf(ChatTool.WEB_SEARCH, ChatTool.WEB_PAGES),
    val sourceChatId: String? = null,
    val draftText: String = "",
    /** Per-chat replacement for global Chat instructions. */
    val instructions: String = "",
    /** Off means no saved memory, no memory tools, and no chat instructions. */
    val personalizationEnabled: Boolean = true,
    /** Per-chat image appetite; the assistant decides the exact count. */
    val imageFrequency: ImageFrequency = ImageFrequency.DEFAULT,
    /** Old nonempty per-chat instructions remain overrides; other chats inherit the app setting. */
    val useGlobalInstructions: Boolean = instructions.isBlank(),
    val agent: ChatAgentProfile? = null,
) {
    val isChat: Boolean get() = kind == ConversationKind.CHAT

    fun effectiveInstructions(global: String): String =
        if (!isChat || !personalizationEnabled) "" else
            (if (useGlobalInstructions) global else instructions).trim().take(MAX_INSTRUCTIONS_CHARS)

    /** Legacy toggle fields remain readable in backups; Chat now has a fixed conversation-only tool set. */
    val allowedTools: Set<String>? get() = if (!isChat) null else agent?.let(ChatAgentPolicy::tools)
        ?: com.androidharness.app.core.ChatExperience.tools

    companion object {
        const val MAX_INSTRUCTIONS_CHARS = 4_000
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun decode(value: String?): ConversationConfig = if (value == null) ConversationConfig() else
            runCatching {
                val fields = json.parseToJsonElement(value).jsonObject
                require("kind" in fields)
                json.decodeFromJsonElement<ConversationConfig>(fields).also { config -> config.agent?.validated() }
            }
                // Unknown or damaged configuration must never unlock coding tools.
                .getOrElse { ConversationConfig(kind = ConversationKind.CHAT) }
        fun encode(value: ConversationConfig): String = json.encodeToString(value)
    }
}
