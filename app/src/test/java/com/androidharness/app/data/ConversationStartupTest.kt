package com.androidharness.app.data

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ConversationStartupTest {
    @Test fun oldSettingsAndUnknownStoredChoicesRememberTheLastSection() {
        assertEquals(StartScreen.LAST_SELECTED, AppSettings().startScreen)
        assertEquals(StartScreen.LAST_SELECTED, StartScreen.fromStored(null))
        assertEquals(StartScreen.LAST_SELECTED, StartScreen.fromStored("future"))
        assertEquals(StartScreen.LAST_SELECTED, Json.decodeFromString<AppSettings>("{}").startScreen)
        assertEquals(ChatStartMode.NEW_CHAT, AppSettings().chatStart)
        assertEquals(ChatStartMode.NEW_CHAT, ChatStartMode.fromStored(null))
        assertEquals(ChatStartMode.NEW_CHAT, ChatStartMode.fromStored("future"))
        assertEquals(ChatStartMode.NEW_CHAT, Json.decodeFromString<AppSettings>("{}").chatStart)
        assertEquals(CodeStartMode.LAST_CODE, AppSettings().codeStart)
        assertEquals(CodeStartMode.LAST_CODE, CodeStartMode.fromStored(null))
        assertEquals(CodeStartMode.LAST_CODE, CodeStartMode.fromStored("future"))
        assertEquals(CodeStartMode.LAST_CODE, Json.decodeFromString<AppSettings>("{}").codeStart)
    }

    @Test fun eachPreferenceSelectsTheExpectedSectionIndependentlyOfResumeChat() {
        for (last in ConversationKind.entries) {
            assertEquals(ConversationKind.CODE, ConversationStartup.resolve(AppSettings(startScreen = StartScreen.CODE, lastConversationKind = last), null).kind)
            assertEquals(ConversationKind.CHAT, ConversationStartup.resolve(AppSettings(startScreen = StartScreen.CHAT, lastConversationKind = last), null).kind)
            assertEquals(last, ConversationStartup.resolve(AppSettings(startScreen = StartScreen.LAST_SELECTED, lastConversationKind = last, codeStart = CodeStartMode.NEW_CODE), null).kind)
        }
    }

    @Test fun chatStartsNewByDefaultAndOnlyLastChatResumes() {
        val settings = AppSettings(startScreen = StartScreen.CHAT, lastActiveSessionId = "previous")
        assertNull(ConversationStartup.resolve(settings, ConversationKind.CHAT).sessionId)
        assertEquals("previous", ConversationStartup.resolve(settings.copy(chatStart = ChatStartMode.LAST_CHAT), ConversationKind.CHAT).sessionId)
        assertNull(ConversationStartup.resolve(settings.copy(chatStart = ChatStartMode.LAST_CHAT), ConversationKind.CODE).sessionId)
        assertNull(ConversationStartup.resolve(settings.copy(chatStart = ChatStartMode.LAST_CHAT), null).sessionId)
        assertNull(ConversationStartup.resolve(settings.copy(chatStart = ChatStartMode.LAST_CHAT,
            archivedSessions = setOf("previous")), ConversationKind.CHAT).sessionId)
    }

    @Test fun codeStartsLastByDefaultAndOnlyLastCodeResumes() {
        val settings = AppSettings(startScreen = StartScreen.CODE, lastActiveSessionId = "previous")
        assertEquals("previous", ConversationStartup.resolve(settings, ConversationKind.CODE).sessionId)
        assertNull(ConversationStartup.resolve(settings.copy(codeStart = CodeStartMode.NEW_CODE), ConversationKind.CODE).sessionId)
        assertEquals("previous", ConversationStartup.resolve(settings.copy(codeStart = CodeStartMode.NEW_CODE,
            startScreen = StartScreen.CHAT, chatStart = ChatStartMode.LAST_CHAT), ConversationKind.CHAT).sessionId)
    }

    @Test fun legacyBackupDerivesCodeStartFromTheResumeToggle() {
        val json = Json { encodeDefaults = true }
        val oldNew = """{"version":1,"settings":{"resumeLastChat":false}}"""
        assertEquals(CodeStartMode.NEW_CODE,
            legacyCodeStart(json.decodeFromString(SettingsBackupFile.serializer(), oldNew), oldNew).settings.codeStart)
        val oldLast = """{"version":1,"settings":{"resumeLastChat":true}}"""
        assertEquals(CodeStartMode.LAST_CODE,
            legacyCodeStart(json.decodeFromString(SettingsBackupFile.serializer(), oldLast), oldLast).settings.codeStart)
        val modern = """{"version":1,"settings":{"resumeLastChat":false,"codeStart":"LAST_CODE"}}"""
        assertEquals(CodeStartMode.LAST_CODE,
            legacyCodeStart(json.decodeFromString(SettingsBackupFile.serializer(), modern), modern).settings.codeStart)
    }

    @Test fun backupsCarryThePreferencesButDoNotCarryDeviceLocalSelection() {
        val exported = SettingsBackupPolicy.exportSettings(AppSettings(startScreen = StartScreen.LAST_SELECTED,
            chatStart = ChatStartMode.LAST_CHAT, codeStart = CodeStartMode.NEW_CODE,
            lastConversationKind = ConversationKind.CHAT, lastActiveSessionId = "device-chat"), emptySet())
        assertEquals(StartScreen.LAST_SELECTED, exported.startScreen)
        assertEquals(ChatStartMode.LAST_CHAT, exported.chatStart)
        assertEquals(CodeStartMode.NEW_CODE, exported.codeStart)
        assertEquals(ConversationKind.CODE, exported.lastConversationKind)
        assertNull(exported.lastActiveSessionId)
        val decoded = Json.decodeFromString<AppSettings>(Json.encodeToString(AppSettings.serializer(), exported))
        assertEquals(StartScreen.LAST_SELECTED, decoded.startScreen)
        assertEquals(ChatStartMode.LAST_CHAT, decoded.chatStart)
        assertEquals(CodeStartMode.NEW_CODE, decoded.codeStart)
    }
}
