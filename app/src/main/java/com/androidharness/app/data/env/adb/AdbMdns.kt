package com.androidharness.app.data.env.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import kotlin.coroutines.resume

/**
 * Finds the ports Android's own adbd advertises for wireless debugging.
 * Adapted from Shizuku (Apache 2.0).
 */
@RequiresApi(Build.VERSION_CODES.R)
internal class AdbMdns(private val context: Context, private val serviceType: String) {

    private val nsdManager: NsdManager = context.getSystemService(NsdManager::class.java)
    private var discovery: NsdManager.DiscoveryListener? = null

    suspend fun findPort(timeoutMs: Long = 10_000): Int? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { continuation ->
            var resolved = false
            var serviceName: String? = null

            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "Discovery failed: $errorCode")
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onDiscoveryStopped(serviceType: String) = Unit

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

                override fun onServiceFound(info: NsdServiceInfo) {
                    runCatching {
                        nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit

                            override fun onServiceResolved(info: NsdServiceInfo) {
                                if (resolved) return
                                val host = info.host?.hostAddress ?: return
                                if (!isLocalAddress(host)) return
                                if (!isPortAvailable(info.port)) return
                                resolved = true
                                serviceName = info.serviceName
                                if (continuation.isActive) continuation.resume(info.port)
                            }
                        })
                    }
                }

                override fun onServiceLost(info: NsdServiceInfo) {
                    if (info.serviceName == serviceName && continuation.isActive) continuation.resume(null)
                }
            }
            discovery = listener
            runCatching { nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener) }
                .onFailure { if (continuation.isActive) continuation.resume(null) }

            continuation.invokeOnCancellation { stop() }
        }
    }.also { stop() }

    fun stop() {
        val listener = discovery ?: return
        discovery = null
        runCatching { nsdManager.stopServiceDiscovery(listener) }
    }

    private fun isLocalAddress(address: String): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence().any { nic ->
            nic.inetAddresses.asSequence().any { it.hostAddress == address }
        }
    }.getOrDefault(false)

    /** A port adbd owns cannot also be bound by us. */
    private fun isPortAvailable(port: Int): Boolean = try {
        ServerSocket().use {
            it.bind(InetSocketAddress("127.0.0.1", port), 1)
            false
        }
    } catch (_: IOException) {
        true
    }

    companion object {
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        private const val TAG = "AdbMdns"
    }
}
