package com.androidharness.app

import com.androidharness.app.data.audio.GroqTtsClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Model discovery filters the account's /models response, so these helpers
 * decide what appears in the picker and how each family is spoken.
 */
class GroqTtsModelsTest {
    @Test
    fun onlySpeechModelsAreListed() {
        assertTrue(GroqTtsClient.isSpeechModel("playai-tts"))
        assertTrue(GroqTtsClient.isSpeechModel("playai-tts-arabic"))
        assertTrue(GroqTtsClient.isSpeechModel("canopylabs/orpheus-v1-english"))
        assertTrue(GroqTtsClient.isSpeechModel("canopylabs/orpheus-arabic-saudi"))
        assertFalse(GroqTtsClient.isSpeechModel("llama-3.3-70b-versatile"))
        assertFalse(GroqTtsClient.isSpeechModel("whisper-large-v3"))
    }

    @Test
    fun orpheusUsesItsShortInputLimitAndWav() {
        val model = "canopylabs/orpheus-v1-english"
        assertEquals(GroqTtsClient.ORPHEUS_MAX_INPUT_CHARS, GroqTtsClient.maxInputChars(model))
        assertEquals("wav", GroqTtsClient.responseFormat(model))
        assertTrue("autumn" in GroqTtsClient.voicesFor(model))
        assertEquals("autumn", GroqTtsClient.defaultVoiceFor(model))
    }

    @Test
    fun playaiUsesItsDocumentedVoices() {
        val model = "playai-tts"
        assertEquals(GroqTtsClient.MAX_INPUT_CHARS, GroqTtsClient.maxInputChars(model))
        assertEquals("mp3", GroqTtsClient.responseFormat(model))
        assertTrue("Fritz-PlayAI" in GroqTtsClient.voicesFor(model))
        assertEquals("Fritz-PlayAI", GroqTtsClient.defaultVoiceFor(model))
    }

    @Test
    fun unknownModelsAskForACustomVoice() {
        val model = "some-future-tts"
        assertTrue(GroqTtsClient.voicesFor(model).isEmpty())
        assertEquals(null, GroqTtsClient.defaultVoiceFor(model))
        assertEquals("mp3", GroqTtsClient.responseFormat(model))
        assertEquals(model, GroqTtsClient.modelLabel(model))
    }

    @Test
    fun labelsAreReadableButIdsRemainAvailable() {
        assertEquals("Orpheus English", GroqTtsClient.modelLabel("canopylabs/orpheus-v1-english"))
        assertEquals("PlayAI TTS", GroqTtsClient.modelLabel("playai-tts"))
    }
}
