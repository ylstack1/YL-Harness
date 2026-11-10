package com.androidharness.app.llm

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ModelCapabilitiesTest {
    private fun directory(reasoning: Boolean? = true, tools: Boolean? = true, images: Boolean? = true) =
        ModelsDev.Entry(reasoning, null, false, null, false, tools = tools, imageInput = images)

    @Test fun providerReportsOverrideDirectoryIncludingExplicitUnsupported() {
        val value = ModelCapabilities.of(ModelEntry("model", reasoning = false, tools = false, imageInput = true),
            ProviderType.OPENAI_COMPAT, directory())
        assertEquals(ModelCapabilities.Capability(false, ModelCapabilities.Source.PROVIDER), value.thinking)
        assertEquals(ModelCapabilities.Capability(false, ModelCapabilities.Source.PROVIDER), value.tools)
        assertEquals(ModelCapabilities.Capability(true, ModelCapabilities.Source.PROVIDER), value.images)
    }

    @Test fun missingReportsUseDirectoryWithDistinctProvenanceAndKeepUnknownUnknown() {
        val listed = ModelCapabilities.of(ModelEntry("model"), ProviderType.OPENAI_COMPAT, directory())
        assertEquals(ModelCapabilities.Source.DIRECTORY, listed.tools.source)
        assertEquals(ModelCapabilities.Source.DIRECTORY, listed.images.source)
        val unknown = ModelCapabilities.of(ModelEntry("unrecognised"), ProviderType.OPENAI_COMPAT, null)
        assertNull(unknown.thinking.supported)
        assertNull(unknown.tools.supported)
        assertNull(unknown.images.supported)
        val hint = ModelCapabilities.of(ModelEntry("deepseek-r1"), ProviderType.OPENAI_COMPAT, null)
        assertEquals(ModelCapabilities.Source.FAMILY, hint.thinking.source)
        assertNull(hint.images.supported)
    }

    @Test fun catalogRetainsInputCapabilitiesAndMalformedMetadataCannotInventSupport() {
        val entries = ModelCatalog.parseCatalog(ProviderType.OPENAI_COMPAT, """{"data":[
            {"id":"yes","supported_parameters":["tools"],"architecture":{"input_modalities":["text","image"]}},
            {"id":"no","supported_parameters":["temperature"],"architecture":{"input_modalities":["text"],"output_modalities":["image"]}},
            {"id":"unknown","supported_parameters":[null,{}],"architecture":{"input_modalities":[{}]}}
        ]}""").associateBy { it.id }
        assertEquals(true, entries.getValue("yes").tools)
        assertEquals(true, entries.getValue("yes").imageInput)
        assertEquals(false, entries.getValue("no").tools)
        assertEquals(false, entries.getValue("no").imageInput)
        assertNull(entries.getValue("unknown").tools)
        assertNull(entries.getValue("unknown").imageInput)
    }

    @Test fun directoryParsesBooleanToolsAndInputImagesAndDoesNotCrossProviderBoundaries() {
        val parsed = ModelsDev.parse("""{"openai":{"models":{
            "demo":{"tool_call":true,"modalities":{"input":["text","image"]}},
            "malformed":{"tool_call":"true","modalities":{"input":[null]}},
            "plain":{"tool_call":false,"modalities":{"input":["text"],"output":["image"]}}
        }}}""").entries
        assertEquals(true, parsed.getValue("openai").getValue("demo").tools)
        assertEquals(true, parsed.getValue("openai").getValue("demo").imageInput)
        assertNull(parsed.getValue("openai").getValue("malformed").tools)
        assertNull(parsed.getValue("openai").getValue("malformed").imageInput)
        assertEquals(false, parsed.getValue("openai").getValue("plain").imageInput)
        ModelsDev.replaceForTesting(parsed)
        try {
            val provider = ProviderConfig("custom", "Custom", ProviderType.OPENAI_COMPAT, "https://example.test", "demo")
            assertNull(ModelCapabilities.of(ModelEntry("demo"), provider).tools.supported)
            assertEquals(ModelCapabilities.Source.DIRECTORY,
                ModelCapabilities.of(ModelEntry("demo"), provider.copy(baseUrl = "https://api.openai.com/v1")).images.source)
        } finally { ModelsDev.replaceForTesting(emptyMap()) }
    }

    @Test fun existingSavedCatalogsRemainReadableAndNewCapabilitiesRoundTrip() {
        val json = Json { ignoreUnknownKeys = true }
        assertNull(json.decodeFromString<ModelEntry>("""{"id":"old"}""").tools)
        val entry = ModelEntry("new", tools = true, imageInput = false)
        assertEquals(entry, json.decodeFromString<ModelEntry>(json.encodeToString(ModelEntry.serializer(), entry)))
    }
}
