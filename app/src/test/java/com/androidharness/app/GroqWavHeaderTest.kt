package com.androidharness.app

import com.androidharness.app.data.audio.GroqTtsClient
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Groq streams WAV with 0xFFFFFFFF sizes; MediaPlayer needs the real ones. */
class GroqWavHeaderTest {
    private fun streamingWav(payload: Int = 100): ByteArray {
        val out = ByteBuffer.allocate(44 + payload).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(-1).put("WAVE".toByteArray())
        out.put("fmt ".toByteArray()).putInt(16)
        out.putShort(1).putShort(1).putInt(24_000).putInt(48_000).putShort(2).putShort(16)
        out.put("data".toByteArray()).putInt(-1)
        repeat(payload) { out.put(it.toByte()) }
        return out.array()
    }

    @Test
    fun placeholderSizesAreReplacedWithRealOnes() {
        val fixed = GroqTtsClient.fixStreamingWav(streamingWav())
        val buffer = ByteBuffer.wrap(fixed).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(fixed.size - 8, buffer.getInt(4))
        assertEquals(100, buffer.getInt(40))
    }

    @Test
    fun alreadyValidWavIsUnchanged() {
        val valid = streamingWav()
        ByteBuffer.wrap(valid).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(4, valid.size - 8)
            putInt(40, 100)
        }
        assertArrayEquals(valid, GroqTtsClient.fixStreamingWav(valid))
    }

    @Test
    fun nonWavBytesPassThrough() {
        val mp3 = ByteArray(64) { 0x11 }
        assertArrayEquals(mp3, GroqTtsClient.fixStreamingWav(mp3))
    }
}
