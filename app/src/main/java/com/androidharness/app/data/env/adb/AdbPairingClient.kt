package com.androidharness.app.data.env.adb

import android.net.ssl.SSLSockets
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import org.conscrypt.Conscrypt
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

private const val TAG = "AdbPairClient"

/**
 * Pairs with Android's own adbd over the wireless debugging pairing port.
 * Adapted from Shizuku (Apache 2.0).
 */
@RequiresApi(Build.VERSION_CODES.R)
internal class AdbPairingClient(
    private val host: String,
    private val port: Int,
    private val pairCode: String,
    private val key: AdbKey,
) : Closeable {

    private enum class State { Ready, ExchangingMessages, ExchangingPeerInfo, Stopped }

    private lateinit var socket: Socket
    private lateinit var inputStream: DataInputStream
    private lateinit var outputStream: DataOutputStream
    private lateinit var pairingContext: PairingContext
    private var state = State.Ready

    fun start(): Boolean {
        setupTlsConnection()
        state = State.ExchangingMessages
        if (!exchangeMessages()) {
            state = State.Stopped
            return false
        }
        state = State.ExchangingPeerInfo
        if (!exchangePeerInfo()) {
            state = State.Stopped
            return false
        }
        state = State.Stopped
        return true
    }

    private fun setupTlsConnection() {
        // Resolve the target before apply: inside the block `port` would bind to
        // Socket.getPort() (0 on a new socket) instead of this class's port.
        val address = InetSocketAddress(host, port)
        socket = Socket().apply {
            connect(address, CONNECT_TIMEOUT_MS)
            soTimeout = READ_TIMEOUT_MS
            tcpNoDelay = true
        }
        val tlsContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            key.sslContext
        } else {
            key.pairingSslContext(Conscrypt.newProvider())
        }
        val secure = tlsContext.socketFactory.createSocket(socket, host, port, true) as SSLSocket
        secure.soTimeout = READ_TIMEOUT_MS
        secure.startHandshake()
        Log.d(TAG, "Pairing TLS handshake succeeded")
        inputStream = DataInputStream(secure.inputStream)
        outputStream = DataOutputStream(secure.outputStream)

        val codeBytes = pairCode.toByteArray()
        val keyMaterial = exportKeyingMaterial(secure, EXPORTED_KEY_LABEL, EXPORTED_KEY_SIZE)
        val password = ByteArray(codeBytes.size + keyMaterial.size)
        codeBytes.copyInto(password)
        keyMaterial.copyInto(password, codeBytes.size)
        pairingContext = checkNotNull(PairingContext.create(password)) { "Unable to create the pairing context." }
    }

    private fun exchangeMessages(): Boolean {
        val message = pairingContext.msg
        Log.d(TAG, "Sending SPAKE2 message (${message.size} bytes)")
        writePacket(pairingContext.msg, PacketType.SPAKE2_MESSAGE)
        val header = readHeader() ?: return false
        if (header.type != PacketType.SPAKE2_MESSAGE) return false
        val theirMessage = ByteArray(header.payload)
        inputStream.readFully(theirMessage)
        Log.d(TAG, "Received SPAKE2 response (${theirMessage.size} bytes)")
        return pairingContext.initCipher(theirMessage)
    }

    private fun exchangePeerInfo(): Boolean {
        val peerInfo = ByteBuffer.allocate(MAX_PEER_INFO_SIZE).order(ByteOrder.BIG_ENDIAN)
        peerInfo.put(PEER_INFO_RSA_PUBLIC_KEY)
        peerInfo.put(ByteArray(MAX_PEER_INFO_SIZE - 1).also { key.adbPublicKey.copyInto(it, 0, 0, minOf(key.adbPublicKey.size, MAX_PEER_INFO_SIZE - 1)) })

        val encrypted = pairingContext.encrypt(peerInfo.array()) ?: return false
        Log.d(TAG, "Sending encrypted peer info (${encrypted.size} bytes)")
        writePacket(encrypted, PacketType.PEER_INFO)

        val header = readHeader() ?: return false
        if (header.type != PacketType.PEER_INFO) return false
        val theirMessage = ByteArray(header.payload)
        inputStream.readFully(theirMessage)
        val decrypted = pairingContext.decrypt(theirMessage) ?: throw AdbInvalidPairingCodeException()
        Log.d(TAG, "Pairing exchange complete")
        return decrypted.size == MAX_PEER_INFO_SIZE
    }

    private fun writePacket(payload: ByteArray, type: PacketType) {        val header = ByteBuffer.allocate(PACKET_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        header.put(CURRENT_VERSION)
        header.put(type.value)
        header.putInt(payload.size)
        outputStream.write(header.array())
        outputStream.write(payload)
    }

    private fun readHeader(): PacketHeader? {
        val bytes = ByteArray(PACKET_HEADER_SIZE)
        inputStream.readFully(bytes)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val version = buffer.get()
        val type = buffer.get()
        val payload = buffer.int
        if (version < MIN_VERSION || version > MAX_VERSION) return null
        if (type != PacketType.SPAKE2_MESSAGE.value && type != PacketType.PEER_INFO.value) return null
        if (payload <= 0 || payload > MAX_PAYLOAD_SIZE) return null
        return PacketHeader(PacketType.from(type) ?: return null, payload)
    }

    override fun close() {
        runCatching { inputStream.close() }
        runCatching { outputStream.close() }
        runCatching { socket.close() }
        if (state != State.Ready) runCatching { pairingContext.destroy() }
    }

    private data class PacketHeader(val type: PacketType, val payload: Int)

    private enum class PacketType(val value: Byte) {
        SPAKE2_MESSAGE(0),
        PEER_INFO(1);

        companion object {
            fun from(value: Byte): PacketType? = entries.firstOrNull { it.value == value }
        }
    }

    companion object {
        private const val CURRENT_VERSION: Byte = 1
        private const val MIN_VERSION: Byte = 1
        private const val MAX_VERSION: Byte = 1
        private const val MAX_PEER_INFO_SIZE = 8192
        private const val MAX_PAYLOAD_SIZE = MAX_PEER_INFO_SIZE * 2
        private const val PACKET_HEADER_SIZE = 6
        private const val EXPORTED_KEY_LABEL = "adb-label\u0000"
        private const val EXPORTED_KEY_SIZE = 64
        private const val PEER_INFO_RSA_PUBLIC_KEY: Byte = 0
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 10_000

        private val loaded = runCatching { System.loadLibrary("harness_adb") }.isSuccess

        /** False on builds without the native pairing library (for example 32-bit skipped builds). */
        fun available(): Boolean = loaded
    }
}

/**
 * Android 14 provides a platform API. Older releases use the bundled Conscrypt
 * provider for the pairing socket, avoiding restricted platform APIs.
 */
private fun exportKeyingMaterial(socket: SSLSocket, label: String, length: Int): ByteArray {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        return publicKeyingMaterial(socket, label, length)
    }
    return checkNotNull(Conscrypt.exportKeyingMaterial(socket, label, null, length)) {
        "The pairing TLS session did not expose keying material."
    }
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private fun publicKeyingMaterial(socket: SSLSocket, label: String, length: Int): ByteArray =
    checkNotNull(SSLSockets.exportKeyingMaterial(socket, label, null, length)) {
        "The pairing TLS session did not expose keying material."
    }

