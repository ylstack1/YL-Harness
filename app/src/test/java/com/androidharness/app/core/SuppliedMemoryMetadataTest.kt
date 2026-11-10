package com.androidharness.app.core

import org.junit.Assert.*
import org.junit.Test

class SuppliedMemoryMetadataTest {
    @Test fun oldMessagesHaveNoInventedMemoryReceipt() {
        assertTrue(GenerationMetadata.decode(null).suppliedMemories.isEmpty())
        assertTrue(GenerationMetadata.decode("{\"reasoningTokens\":12}").suppliedMemories.isEmpty())
    }
    @Test fun suppliedFactsSurvivePersistenceWithTheirOriginalText() {
        val original = GenerationMetadata(suppliedMemories = listOf(SuppliedMemory("fixture", "Use concise replies.")))
        assertEquals(original, GenerationMetadata.decode(GenerationMetadata.encode(original)))
    }

    @Test fun modelProvenanceSurvivesPersistence() {
        val original = GenerationMetadata(model = "provider/model-x")
        assertEquals(original, GenerationMetadata.decode(GenerationMetadata.encode(original)))
        assertNull(GenerationMetadata.decode("{}").model)
    }
}
