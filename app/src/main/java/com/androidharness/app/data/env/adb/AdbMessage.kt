package com.androidharness.app.data.env.adb

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One ADB transport packet, adapted from Shizuku (Apache 2.0). */
internal class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val dataLength: Int,
    val dataCrc32: Int,
    val magic: Int,
    val data: ByteArray?,
) {
    constructor(command: Int, arg0: Int, arg1: Int) : this(command, arg0, arg1, null as ByteArray?)

    constructor(command: Int, arg0: Int, arg1: Int, data: String) : this(
        command, arg0, arg1, "${data}\u0000".toByteArray(),
    )

    constructor(command: Int, arg0: Int, arg1: Int, data: ByteArray?) : this(
        command, arg0, arg1, data?.size ?: 0, crc32(data), (command.toLong() xor 0xFFFFFFFFL).toInt(), data,
    )

    fun validate(): Boolean {
        if (command != magic xor -0x1) return false
        if (dataLength != 0 && crc32(data) != dataCrc32) return false
        return true
    }

    fun validateOrThrow() {
        if (!validate()) throw IllegalArgumentException("Bad ADB message")
    }

    fun toByteArray(): ByteArray {
        val length = HEADER_LENGTH + (data?.size ?: 0)
        return ByteBuffer.allocate(length).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            putInt(command)
            putInt(arg0)
            putInt(arg1)
            putInt(dataLength)
            putInt(dataCrc32)
            putInt(magic)
            if (data != null) put(data)
        }.array()
    }

    companion object {
        const val HEADER_LENGTH = 24

        private fun crc32(data: ByteArray?): Int {
            if (data == null) return 0
            var result = 0
            for (byte in data) result += if (byte >= 0) byte.toInt() else byte + 256
            return result
        }
    }
}
