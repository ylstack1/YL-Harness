package com.ylstack1.ylharness.ui.chat

import com.ylstack1.ylharness.data.ModelShortcut
import com.ylstack1.ylharness.llm.ProviderConfig
import com.ylstack1.ylharness.llm.ProviderType
import com.ylstack1.ylharness.ui.chat.components.retryModelChoices
import org.junit.Assert.assertEquals
import org.junit.Test

class RetryModelChoicesTest {
    private fun provider(id: String) = ProviderConfig(id, id, ProviderType.OPENAI_COMPAT, "https://example.invalid/v1", "default")
    private val a = ModelShortcut("one", "alpha")
    private val b = ModelShortcut("one", "beta")
    private val c = ModelShortcut("two", "gamma")

    @Test fun favouritesComeFirstAndDuplicatesCollapse() {
        val result = retryModelChoices(listOf(b), listOf(a, b, c), listOf(provider("one"), provider("two")), null) { _, _ -> true }
        assertEquals(listOf(b, a, c), result)
    }

    @Test fun currentHiddenAndUnconfiguredModelsAreLeftOut() {
        val result = retryModelChoices(emptyList(), listOf(a, b, c), listOf(provider("one")), a) { _, model -> model != "beta" }
        assertEquals(emptyList<ModelShortcut>(), result)
        val withTwo = retryModelChoices(emptyList(), listOf(a, b, c), listOf(provider("one"), provider("two")), a) { _, model -> model != "beta" }
        assertEquals(listOf(c), withTwo)
    }
}
