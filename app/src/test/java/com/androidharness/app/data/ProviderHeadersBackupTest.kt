package com.androidharness.app.data

import com.androidharness.app.llm.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ProviderHeadersBackupTest {
    private val config = ProviderConfig("test", "Test", ProviderType.OPENAI_COMPAT, "https://example.com/v1", "model")

    @Test fun encryptedBackupPayloadPreservesValuesAndDisabledRows() {
        val headers = listOf(ProviderHeader("X-Token", "fixture-token"), ProviderHeader("X-Client", "mobile", false))
        val file = SettingsBackupFile(settings = AppSettings(), providers = listOf(BackupProvider(config, headers = headers)))
        val json = Json { encodeDefaults = true }
        val decoded = json.decodeFromString(SettingsBackupFile.serializer(), json.encodeToString(SettingsBackupFile.serializer(), file))
        SettingsBackupValidation.validate(decoded)
        assertEquals(headers, decoded.providers.single().headers)
        assertTrue(decoded.providers.single().config.headers.isEmpty())
    }

    @Test fun invalidBackupHeadersAreRejectedBeforeRestore() {
        for (headers in listOf(listOf(ProviderHeader("X-Key", "bad\r\nInjected: true")),
            listOf(ProviderHeader("Authorization", "unsafe")),
            listOf(ProviderHeader("X-Key", "a"), ProviderHeader("x-key", "b")))) {
            val failure = assertThrows(SettingsBackupValidationException::class.java) {
                SettingsBackupValidation.validate(SettingsBackupFile(settings = AppSettings(), providers = listOf(BackupProvider(config, headers = headers))))
            }
            assertEquals(SettingsBackupProblem.PROVIDER_HEADERS, failure.problem)
        }
    }
}
