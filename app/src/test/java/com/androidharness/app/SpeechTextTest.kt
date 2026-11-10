package com.androidharness.app

import com.androidharness.app.data.audio.SpeechText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {
    @Test
    fun headingsLoseTheirHashes() {
        assertEquals(
            "Checking the build\nDetails follow.",
            SpeechText.forSpeech("## Checking the build\nDetails follow."),
        )
    }

    @Test
    fun tableBecomesHeaderValueSentences() {
        val markdown = """
            | Name | Status |
            |------|--------|
            | Build | Passing |
            | Tests | 12 |
        """.trimIndent()

        val speech = SpeechText.forSpeech(markdown)

        assertTrue(speech, speech.contains("Name: Build. Status: Passing."))
        assertTrue(speech, speech.contains("Name: Tests. Status: 12."))
        assertFalse(speech, speech.contains("|"))
        assertFalse(speech, speech.contains("---"))
    }

    @Test
    fun fencedCodeIsNotSpoken() {
        val markdown = "Before.\n```kotlin\nprintln(\"hidden\")\n```\nAfter."
        val speech = SpeechText.forSpeech(markdown)
        assertEquals("Before.\n\nAfter.", speech)
        assertFalse(speech.contains("println"))
    }

    @Test
    fun inlineMarkersAndLinksAreRemoved() {
        assertEquals(
            "Bold and code and docs.",
            SpeechText.forSpeech("**Bold** and `code` and [docs](https://example.com)."),
        )
    }

    @Test
    fun bareUrlsAreNotRead() {
        assertEquals(
            "Read link for details.",
            SpeechText.forSpeech("Read https://example.com/very/long/path for details."),
        )
    }

    @Test
    fun bulletsAndTaskBoxesLoseTheirMarkers() {
        assertEquals(
            "one\ntwo\nrun tests",
            SpeechText.forSpeech("- one\n- two\n- [ ] run tests"),
        )
    }

    @Test
    fun hashBeforeANumberIsSpokenAsNumber() {
        assertEquals(
            "See issue number 48.",
            SpeechText.forSpeech("See issue #48."),
        )
    }

    @Test
    fun tablePlumbingAloneHasNothingToSpeak() {
        val speech = SpeechText.forSpeech("|---|---|\n***\n===")
        assertFalse(speech, SpeechText.isSpeakable(speech))
    }

    @Test
    fun underscoreEmphasisIsDroppedButSnakeCaseStays() {
        assertEquals(
            "really good, see my_file.",
            SpeechText.forSpeech("_really_ good, see my_file."),
        )
    }
}
