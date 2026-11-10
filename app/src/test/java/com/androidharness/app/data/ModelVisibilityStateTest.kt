package com.androidharness.app.data

import org.junit.Assert.*
import org.junit.Test

class ModelVisibilityStateTest {
    @Test fun existingUsersKeepTheWholeCatalogUntilTheyCustomizeIt() {
        val state = ModelVisibilityState.decode(null)
        assertTrue(state.includes("provider", "existing"))
        assertTrue(state.includes("provider", "newly-discovered"))
        assertEquals(setOf("existing", "newly-discovered"), state.selection("provider", listOf("existing", "newly-discovered")))
    }

    @Test fun customizedCatalogRefreshNeverEnablesNewModelsOrForgetsTemporarilyMissingOnes() {
        val saved = ModelVisibilityState().save("provider", setOf("chosen", "temporarily-missing"))
        val reloaded = ModelVisibilityState.decode(ModelVisibilityState.encode(saved))
        assertTrue(reloaded.includes("provider", "chosen"))
        assertFalse(reloaded.includes("provider", "newly-discovered"))
        assertTrue(reloaded.includes("provider", "temporarily-missing"))
        assertEquals(saved, reloaded)
    }

    @Test fun identicalModelIdsAreScopedToTheirProviderAndEmptySelectionStaysEmpty() {
        val saved = ModelVisibilityState().save("one", emptySet()).save("two", setOf("shared"))
        val reloaded = ModelVisibilityState.decode(ModelVisibilityState.encode(saved))
        assertFalse(reloaded.includes("one", "shared"))
        assertTrue(reloaded.includes("two", "shared"))
        assertTrue(reloaded.includes("uncustomized", "shared"))
        assertTrue(reloaded.selection("one", listOf("shared")).isEmpty())
        assertEquals(mapOf("two" to setOf("shared")), reloaded.without("one").selected)
    }
}
