package com.androidharness.app.ui.chat

import com.androidharness.app.llm.*
import org.junit.Assert.*
import org.junit.Test

class AttachmentReadinessTest {
    @Test fun explicitImageSupportAndUnsupportedCatalogEntriesAreDistinguished() {
        val provider = ProviderConfig("fixture", "Fixture", ProviderType.OPENAI_COMPAT, "https://example.invalid/v1", "fixture-model")
        fun state(imageInput: Boolean?) = ChatUiState(providers = listOf(provider), activeProviderId = provider.id,
            activeModel = provider.model, catalogs = mapOf(provider.id to listOf(ModelEntry(provider.model, imageInput = imageInput))))
        assertEquals(true, state(true).imageInputSupport())
        assertEquals(false, state(false).imageInputSupport())
        assertNull(state(null).imageInputSupport())
    }
}
