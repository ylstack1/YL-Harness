package com.androidharness.app.data

import com.androidharness.app.tools.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class ConversationConfigTest {
    @Test fun globalInstructionsAreInheritedUnlessReplacedAndAreExcludedFromCodeAndUnpersonalizedChat() {
        val chat = ConversationConfig(ConversationKind.CHAT)
        assertEquals("global", chat.effectiveInstructions("global"))
        val custom = chat.copy(instructions = "local", useGlobalInstructions = false)
        assertEquals("local", custom.effectiveInstructions("global"))
        assertEquals("", custom.copy(instructions = "").effectiveInstructions("global"))
        assertEquals("global", custom.copy(useGlobalInstructions = true).effectiveInstructions("global"))
        assertEquals("", chat.copy(personalizationEnabled = false).effectiveInstructions("global"))
        assertEquals("", custom.copy(personalizationEnabled = false).effectiveInstructions("global"))
        assertEquals("", ConversationConfig().effectiveInstructions("global"))
        assertEquals(custom, ConversationConfig.decode(ConversationConfig.encode(custom)))
        assertEquals(chat, ConversationConfig.decode(ConversationConfig.encode(chat)))
    }

    @Test fun legacyChatInstructionsRemainReplacementsAndEmptyLegacyChatsInheritGlobalInstructions() {
        val legacy = ConversationConfig.decode("""{"kind":"CHAT","instructions":"legacy custom"}""")
        assertFalse(legacy.useGlobalInstructions)
        assertEquals("legacy custom", legacy.effectiveInstructions("global"))
        assertTrue(ConversationConfig.decode("""{"kind":"CHAT"}""").useGlobalInstructions)
        assertEquals(ConversationConfig.MAX_INSTRUCTIONS_CHARS,
            ConversationConfig(ConversationKind.CHAT).effectiveInstructions("x".repeat(5000)).length)
    }
    @Test fun legacySessionsKeepCodeAndMalformedPreferencesDoNotUnlockCodeTools() {
        assertEquals(ConversationKind.CODE, ConversationConfig.decode(null).kind)
        assertNull(ConversationConfig.decode(null).allowedTools)
        assertEquals(com.androidharness.app.core.ChatExperience.tools, ConversationConfig.decode("broken").allowedTools)
        assertEquals(com.androidharness.app.core.ChatExperience.tools, ConversationConfig.decode("{}").allowedTools)
        assertEquals(com.androidharness.app.core.ChatExperience.tools, ConversationConfig.decode("{\"kind\":\"FUTURE\"}").allowedTools)
    }

    @Test fun legacyPreferencesStayReadableWhileChatToolsAreAlwaysAvailable() {
        val saved = ConversationConfig(ConversationKind.CHAT, toolsEnabled = true,
            selectedTools = setOf(ChatTool.WEB_SEARCH, ChatTool.ARTIFACTS), draftText = "An unsent idea")
        assertEquals(saved, ConversationConfig.decode(ConversationConfig.encode(saved)))
        assertEquals(com.androidharness.app.core.ChatExperience.tools, saved.allowedTools)
        assertEquals(saved.allowedTools, saved.copy(toolsEnabled = false).allowedTools)
        assertEquals(saved.allowedTools, saved.copy(selectedTools = emptySet()).allowedTools)
        assertFalse(saved.allowedTools!!.any { it in setOf("shell", "read_file", "write_file", "task", "http_request", "create_artifact", "read_artifact") })
    }

    @Test fun chatInstructionsAndPersonalizationSurvivePersistence() {
        val saved = ConversationConfig(
            kind = ConversationKind.CHAT,
            instructions = "Answer in German and stay brief.",
            personalizationEnabled = false,
        )
        assertEquals(saved, ConversationConfig.decode(ConversationConfig.encode(saved)))
        val legacy = ConversationConfig.decode("{\"kind\":\"CHAT\"}")
        assertEquals("", legacy.instructions)
        assertTrue(legacy.personalizationEnabled)
    }

    @Test fun chatImageFrequencyDefaultsAndSurvivesPersistence() {
        assertEquals(ImageFrequency.DEFAULT, ConversationConfig.decode("{\"kind\":\"CHAT\"}").imageFrequency)
        val saved = ConversationConfig(kind = ConversationKind.CHAT, imageFrequency = ImageFrequency.ALWAYS)
        assertEquals(saved, ConversationConfig.decode(ConversationConfig.encode(saved)))
    }

    @Test fun restrictingRegistryBlocksBothAdvertisedAndExecutableToolsIncludingExtras() {
        fun tool(name: String) = object : Tool {
            override val name = name
            override val description = name
            override val parametersSchema = JsonObject(emptyMap())
            override val isReadOnly = true
            override suspend fun execute(args: JsonObject, ctx: ToolContext) = ToolResult(true, "done")
        }
        val base = ToolRegistry(listOf(tool("web_search"), tool("shell"), tool("task")))
        val merged = base.withExtra(listOf(tool("mcp_write")))
        val chat = merged.restrictedTo(setOf("web_search"))
        assertEquals(listOf("web_search"), chat.schemas().map { it.name })
        listOf("shell", "task", "mcp_write").forEach { assertNull(chat.get(it)) }
        assertTrue(merged.restrictedTo(emptySet()).schemas().isEmpty())
        assertSame(merged, merged.restrictedTo(null))
    }
}
