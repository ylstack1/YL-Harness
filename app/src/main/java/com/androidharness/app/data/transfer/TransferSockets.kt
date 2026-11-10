package com.androidharness.app.data.transfer

import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory

/** Track control and data sockets so Cancel also interrupts a blocked network read/write. */
internal class TransferSockets : SocketFactory(), java.io.Closeable {
    private val closed = AtomicBoolean()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    override fun createSocket(): Socket {
        if (closed.get()) throw SocketException("Transfer cancelled")
        sockets.removeIf { it.isClosed }
        return Socket().also { socket ->
            sockets += socket
            if (closed.get()) { socket.close(); throw SocketException("Transfer cancelled") }
        }
    }
    private fun connect(remote: SocketAddress, local: SocketAddress? = null) = createSocket().also { socket ->
        try { if (local != null) socket.bind(local); socket.connect(remote, 15_000) }
        catch (e: Exception) { socket.close(); throw e }
    }
    override fun createSocket(host: String, port: Int) = connect(InetSocketAddress(host, port))
    override fun createSocket(host: InetAddress, port: Int) = connect(InetSocketAddress(host, port))
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int) = connect(InetSocketAddress(host, port), InetSocketAddress(localHost, localPort))
    override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int) = connect(InetSocketAddress(host, port), InetSocketAddress(localHost, localPort))
    override fun close() { closed.set(true); sockets.forEach { runCatching { it.close() } }; sockets.clear() }
}
