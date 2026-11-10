package com.androidharness.app.ui.settings

import com.androidharness.app.llm.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderBrandsTest {
    private val brands = ProviderBrands.filterNotNull()

    @Test fun a2agentSitsAtTheVeryTopAndIsTheOnlyPinnedBrand() {
        assertEquals("A2Agent", brands.first().label)
        assertEquals(listOf("A2Agent"), brands.filter { it.pinned }.map { it.label })
        assertEquals("OpenRouter", brands.drop(1).first().label)
    }

    @Test fun a2agentUsesTheGatewayRootThatServesChatCompletionsAndModelsApi() {
        val a2agent = brands.single { it.label == "A2Agent" }
        assertEquals(ProviderType.OPENAI_COMPAT, a2agent.type)
        // The request URL is baseUrl + /chat/completions, so the version prefix
        // belongs in the stored base URL.
        assertEquals("https://api.a2agent.me/v1", a2agent.baseUrl)
        assertTrue(a2agent.needsKey)
        assertNotNull("A new brand needs its badge", a2agent.badge)
    }

    @Test fun openAiCompatibleBrandsStoreACompleteV1BaseUrl() {
        brands.filter { it.type == ProviderType.OPENAI_COMPAT }.forEach {
            assertTrue("${it.label} must end at /v1", it.baseUrl.trimEnd('/').endsWith("/v1"))
        }
    }

    @Test fun curatedBrandsStayListableDespiteTheFetchedDirectory() {
        // These are compiled in, so a fetched models.dev refresh can never
        // remove or reorder them, and every row needs a unique key.
        assertEquals(brands.size, brands.map { it.label }.distinct().size)
        assertTrue(brands.size >= 11)
    }
}
