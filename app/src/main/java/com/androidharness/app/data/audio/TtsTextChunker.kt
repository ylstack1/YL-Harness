package com.androidharness.app.data.audio

/**
 * Both TTS engines cap how much text one request may carry (Android's
 * TextToSpeech has [android.speech.tts.TextToSpeech.getMaxSpeechInputLength],
 * Groq limits the input field). Split on sentence and line boundaries so a
 * long reply is spoken without cutting a word or a sentence in half.
 */
object TtsTextChunker {
    private val sentenceBreak = Regex("(?<=[.!?。！？])(?=\\s)")

    fun chunk(text: String, maxChars: Int): List<String> {
        val limit = maxChars.coerceAtLeast(120)
        val chunks = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            // A line of table plumbing or list markers has nothing to say, and
            // Groq rejects an input with no letters or digits.
            current.toString().trim()
                .takeIf { it.isNotEmpty() && it.any(Char::isLetterOrDigit) }
                ?.let(chunks::add)
            current.setLength(0)
        }

        fun append(piece: String) {
            val separator = if (current.isEmpty()) 0 else 1
            if (current.length + separator + piece.length <= limit) {
                if (separator == 1) current.append(' ')
                current.append(piece)
            } else {
                flush()
                current.append(piece)
            }
        }

        for (paragraph in text.replace("\r\n", "\n").split(Regex("\\n+"))) {
            val trimmed = paragraph.trim()
            if (trimmed.isEmpty()) continue
            for (sentence in sentenceBreak.split(trimmed)) {
                var rest = sentence.trim()
                while (rest.isNotEmpty()) {
                    val room = limit - current.length - if (current.isEmpty()) 0 else 1
                    when {
                        rest.length <= room -> {
                            append(rest)
                            rest = ""
                        }
                        room < limit / 2 -> flush()
                        else -> {
                            val cut = cutPoint(rest, room)
                            append(rest.substring(0, cut).trim())
                            flush()
                            rest = rest.substring(cut).trim()
                        }
                    }
                }
            }
            // Keep paragraphs as separate utterances; they read better with a pause.
            flush()
        }
        flush()
        return chunks
    }

    /** Last whitespace before [limit], or the hard limit when no boundary exists. */
    private fun cutPoint(text: String, limit: Int): Int {
        if (text.length <= limit) return text.length
        val boundary = text.lastIndexOf(' ', startIndex = limit - 1)
        return if (boundary >= limit / 2) boundary else limit
    }
}
