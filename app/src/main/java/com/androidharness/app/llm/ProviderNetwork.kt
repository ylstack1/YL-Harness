package com.androidharness.app.llm

import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy

/** Provider traffic only. A failed proxy never silently falls back to a direct connection. */
object ProviderNetwork {
    @Volatile var settings: (String?) -> ProviderProxy = { ProviderProxy() }

    fun selected(providerId: String?): ProviderProxy {
        val override = settings(providerId)
        val selected = if (override.mode == ProviderProxyMode.INHERIT) settings(null) else override
        require(selected.error() == null) { "Saved provider proxy is invalid. Review provider controls." }
        return selected
    }

    fun client(base: OkHttpClient, providerId: String?): OkHttpClient = configure(base, selected(providerId))

    internal fun configure(base: OkHttpClient, proxy: ProviderProxy): OkHttpClient {
        require(proxy.error() == null) { "Provider proxy is invalid." }
        if (proxy.mode == ProviderProxyMode.INHERIT) return base
        if (proxy.mode == ProviderProxyMode.DIRECT) return base.newBuilder()
            .proxy(Proxy.NO_PROXY).proxyAuthenticator(Authenticator.NONE).build()
        val address = InetSocketAddress.createUnresolved(proxy.host, proxy.port)
        val route = Proxy(if (proxy.type == ProviderProxyType.HTTP) Proxy.Type.HTTP else Proxy.Type.SOCKS, address)
        return base.newBuilder().proxy(route).proxyAuthenticator { _, response ->
            if (proxy.username.isEmpty() && proxy.password.isEmpty() || response.request.header("Proxy-Authorization") != null) null
            else response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(proxy.username, proxy.password)).build()
        }.build()
    }
}
