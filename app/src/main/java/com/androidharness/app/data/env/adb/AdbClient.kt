package com.androidharness.app.data.env.adb

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket

/**
 * A minimal ADB transport client, enough to open shell streams against the
 * device's own adbd. Adapted from Shizuku (Apache 2.0).
 */
@RequiresApi(Build.VERSION_CODES.R)
internal class AdbClient(
    private val host: String,
    private val port: Int,
    private val key: AdbKey,
) : Closeable {

    private lateinit var socket: Socket
    private lateinit var plainInput: DataInputStream
    private lateinit var plainOutput: DataOutputStream
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream
    private val writeLock = Any()
    private val readLock = Any()
    private var secureSocket: SSLSocket? = null

    @Volatile private var closed = false

    val isConnected: Boolean get() = !closed

    fun connect() {
        Log.d(TAG, "Connecting to adbd on port $port")
        // Resolve the target before apply: inside the block `port` would bind to
        // Socket.getPort() (0 on a new socket) instead of this class's port.
        val address = InetSocketAddress(host, port)
        socket = Socket().apply {
            connect(address, CONNECT_TIMEOUT_MS)
            soTimeout = READ_TIMEOUT_MS
            tcpNoDelay = true
        }
        plainInput = DataInputStream(socket.inputStream)
        plainOutput = DataOutputStream(socket.outputStream)
        input = plainInput
        output = plainOutput

        writeMessage(AdbMessage(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, AdbProtocol.A_MAXDATA, "host::"))
        var message = readMessage()

        if (message.command == AdbProtocol.A_STLS) {
            Log.d(TAG, "ADB requested a TLS upgrade")
            writeMessage(AdbMessage(AdbProtocol.A_STLS, AdbProtocol.A_STLS_VERSION, 0))
            val secure = key.sslContext.socketFactory.createSocket(socket, host, port, true) as SSLSocket
            secure.soTimeout = READ_TIMEOUT_MS
            secure.startHandshake()
            secureSocket = secure
            input = DataInputStream(secure.inputStream)
            output = DataOutputStream(secure.outputStream)
            message = readMessage()
        }

        if (message.command == AdbProtocol.A_AUTH) {
            Log.d(TAG, "ADB requested authentication")
            check(message.arg0 == AdbProtocol.ADB_AUTH_TOKEN) { "ADB asked for an unsupported authentication type." }
            val token = message.data ?: throw AdbException("ADB sent an empty authentication token.")
            writeMessage(AdbMessage(AdbProtocol.A_AUTH, AdbProtocol.ADB_AUTH_SIGNATURE, 0, key.sign(token)))
            message = readMessage()
            if (message.command != AdbProtocol.A_CNXN) {
                writeMessage(AdbMessage(AdbProtocol.A_AUTH, AdbProtocol.ADB_AUTH_RSAPUBLICKEY, 0, key.adbPublicKey))
                message = readMessage()
            }
        }

        if (message.command != AdbProtocol.A_CNXN) {
            throw AdbException("ADB did not accept the connection. Pair again or enable wireless debugging.")
        }
        Log.d(TAG, "Connected to adbd on port $port")
    }

    /** Opens a shell stream. Writes go to the shell's stdin, reads come back merged. */
    fun openShell(command: String): AdbShell {
        val localId = 1
        writeMessage(AdbMessage(AdbProtocol.A_OPEN, localId, 0, "shell:$command"))
        val response = readMessage()
        if (response.command == AdbProtocol.A_CLSE) {
            writeMessage(AdbMessage(AdbProtocol.A_CLSE, localId, response.arg0))
            throw AdbException("ADB refused to open a shell. Is wireless debugging still on?")
        }
        check(response.command == AdbProtocol.A_OKAY) { "ADB did not accept the shell stream." }
        // The handshake is bounded; the long-lived shell must not time out while idle.
        clearReadTimeout()
        Log.d(TAG, "Shell opened on port $port")
        return AdbShell(this, localId, response.arg0)
    }

    private fun clearReadTimeout() {
        runCatching { secureSocket?.soTimeout = 0 }
        runCatching { socket.soTimeout = 0 }
    }

    fun shellCommand(command: String): String {
        val shell = openShell(command)
        return try {
            shell.inputStream.readBytes().toString(Charsets.UTF_8)
        } finally {
            shell.close()
        }
    }

    internal fun writeShellData(localId: Int, remoteId: Int, data: ByteArray) {
        writeMessage(AdbMessage(AdbProtocol.A_WRTE, localId, remoteId, data))
    }

    internal fun acknowledge(localId: Int, remoteId: Int) {
        writeMessage(AdbMessage(AdbProtocol.A_OKAY, localId, remoteId))
    }

    internal fun closeShell(localId: Int, remoteId: Int) {
        runCatching { writeMessage(AdbMessage(AdbProtocol.A_CLSE, localId, remoteId)) }
    }

    internal fun readMessage(): AdbMessage = synchronized(readLock) {
        val buffer = ByteBuffer.allocate(AdbMessage.HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
        input.readFully(buffer.array(), 0, AdbMessage.HEADER_LENGTH)
        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val dataLength = buffer.int
        val checksum = buffer.int
        val magic = buffer.int
        val data = if (dataLength > 0) ByteArray(dataLength).also { input.readFully(it, 0, dataLength) } else null
        AdbMessage(command, arg0, arg1, dataLength, checksum, magic, data).also { it.validateOrThrow() }
    }

    private fun writeMessage(message: AdbMessage) = synchronized(writeLock) {
        check(!closed) { "ADB connection is closed." }
        try {
            output.write(message.toByteArray())
            output.flush()
        } catch (e: IOException) {
            closed = true
            throw e
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { plainInput.close() }
        runCatching { plainOutput.close() }
        runCatching { socket.close() }
    }

    private companion object {
        const val TAG = "AdbClient"
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 10_000
    }
}

/** One shell stream over an [AdbClient]. Adapted from Shizuku (Apache 2.0). */
@RequiresApi(Build.VERSION_CODES.R)
internal class AdbShell(
    private val client: AdbClient,
    private val localId: Int,
    private val remoteId: Int,
) : Closeable {

    private val buffer = PipedInputStream(BUFFER_SIZE)
    private val sink = PipedOutputStream(buffer)
    private val permits = Semaphore(1)
    private val finished = CountDownLatch(1)

    @Volatile private var closed = false

    val inputStream: InputStream get() = buffer
    val isAlive: Boolean get() = !closed

    val outputStream: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var offset = off
            val end = off + len
            while (offset < end) {
                val size = minOf(AdbProtocol.A_MAXDATA, end - offset)
                if (!permits.tryAcquire(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    throw IOException("ADB shell stopped accepting input.")
                }
                client.writeShellData(localId, remoteId, b.copyOfRange(offset, offset + size))
                offset += size
            }
        }

        override fun flush() = Unit
        override fun close() = this@AdbShell.close()
    }

    init {
        Thread({ readLoop() }, "AdbShellReader").apply { isDaemon = true; start() }
    }

    private fun readLoop() {
        try {
            while (!closed) {
                val message = client.readMessage()
                when (message.command) {
                    AdbProtocol.A_OKAY -> permits.release()
                    AdbProtocol.A_WRTE -> {
                        val data = message.data
                        if (data != null && data.isNotEmpty()) sink.write(data)
                        client.acknowledge(localId, remoteId)
                    }
                    AdbProtocol.A_CLSE -> {
                        client.closeShell(localId, remoteId)
                        break
                    }
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            if (!closed) Log.d(TAG, "Shell stream ended: ${e.message}")
        } finally {
            closed = true
            permits.release()
            runCatching { sink.close() }
            finished.countDown()
        }
    }

    fun awaitClosed(seconds: Long, unit: TimeUnit): Boolean = finished.await(seconds, unit)

    override fun close() {
        if (closed) return
        closed = true
        client.closeShell(localId, remoteId)
        permits.release()
        runCatching { sink.close() }
        finished.countDown()
    }

    private companion object {
        const val TAG = "AdbShell"
        const val BUFFER_SIZE = 64 * 1024
        const val WRITE_TIMEOUT_MS = 10_000L
    }
}
