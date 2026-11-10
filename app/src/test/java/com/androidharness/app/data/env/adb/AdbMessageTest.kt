package com.androidharness.app.data.env.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AdbMessageTest {
    @Test fun `open message encodes the shell service and validates`() {
        val message = AdbMessage(AdbProtocol.A_OPEN, 1, 0, "shell:id -u")
        assertTrue(message.validate())
        assertEquals("shell:id -u\u0000", message.data?.toString(Charsets.UTF_8))

        val header = ByteBuffer.wrap(message.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(AdbProtocol.A_OPEN, header.int)
        assertEquals(1, header.int)
        assertEquals(0, header.int)
        assertEquals("shell:id -u\u0000".toByteArray().size, header.int)
        assertEquals(AdbMessage.HEADER_LENGTH + message.data!!.size, message.toByteArray().size)
    }

    @Test fun `close message has no payload`() {
        val message = AdbMessage(AdbProtocol.A_CLSE, 1, 7)
        assertTrue(message.validate())
        assertEquals(0, message.dataLength)
        assertEquals(AdbMessage.HEADER_LENGTH, message.toByteArray().size)
    }

    @Test fun `a bad magic is rejected`() {
        val message = AdbMessage(AdbProtocol.A_WRTE, 1, 7, byteArrayOf(1, 2, 3))
        val corrupted = AdbMessage(message.command, message.arg0, message.arg1,
            message.dataLength, message.dataCrc32, message.magic xor 1, message.data)
        assertFalse(corrupted.validate())
    }
}
