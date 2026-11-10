package com.androidharness.app

import com.androidharness.app.data.audio.GroqTtsClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroqTtsErrorTest {
    @Test
    fun termsErrorCarriesItsCodeAndConsoleLink() {
        val body = """
            {"error":{"message":"The model canopylabs/orpheus-v1-english requires terms acceptance. Please have the org admin accept the terms at https://console.groq.com/model-terms?model=orpheus","type":"invalid_request_error","code":"model_terms_required"}}
        """.trimIndent()
        val error = GroqTtsClient.apiError(body, 400)
        assertEquals("model_terms_required", error.code)
        assertEquals("https://console.groq.com/model-terms?model=orpheus", error.actionUrl)
        assertTrue(error.message.contains("requires terms acceptance"))
    }

    @Test
    fun urlTrailingPunctuationIsDropped() {
        val body = """{"error":{"message":"Accept the terms at https://console.groq.com/model-terms.","code":"model_terms_required"}}"""
        assertEquals("https://console.groq.com/model-terms", GroqTtsClient.apiError(body, 400).actionUrl)
    }

    @Test
    fun unparseableBodiesStillProduceAReadableError() {
        val error = GroqTtsClient.apiError("<html>oops</html>", 502)
        assertNull(error.code)
        assertNull(error.actionUrl)
        assertTrue(error.message.contains("502"))
    }
}
