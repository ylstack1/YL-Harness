package com.androidharness.app.llm

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ProviderApiKey(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "",
    val value: String,
    val enabled: Boolean = true,
) {
    override fun toString() = "ProviderApiKey(id=$id, enabled=$enabled)"
}

@Serializable
data class ProviderKeyPool(val keys: List<ProviderApiKey> = emptyList(), val rotate: Boolean = true) {
    fun error(): String? = when {
        keys.size > MAX_KEYS -> "Use at most $MAX_KEYS API keys."
        keys.any { it.id.isBlank() || it.id.length > 100 || it.label.length > 80 } -> "Use a short name for each key."
        keys.map { it.id }.distinct().size != keys.size -> "Each key must have a unique ID."
        keys.any { it.value.isBlank() || it.value.length > 8192 || it.value.any { c -> c !in '!'..'~' } } -> "Enter a valid API key without spaces or line breaks."
        keys.map { it.value }.distinct().size != keys.size -> "Each API key must be different."
        else -> null
    }

    /** Older single-key editors change the default enabled key without deleting additional keys. */
    fun replaceDefault(value: String): ProviderKeyPool {
        val index = keys.indexOfFirst { it.enabled }
        val updated = keys.toMutableList()
        if (value.isBlank()) {
            if (index >= 0) updated.removeAt(index)
        } else {
            val same = keys.indexOfFirst { it.value == value }
            when {
                same >= 0 -> {
                    updated[same] = updated[same].copy(enabled = true)
                    if (index >= 0 && index != same) updated.removeAt(index)
                }
                index >= 0 -> updated[index] = updated[index].copy(value = value)
                else -> updated.add(ProviderApiKey(value = value))
            }
        }
        return copy(keys = updated)
    }

    override fun toString() = "ProviderKeyPool(count=${keys.size}, rotate=$rotate)"
    companion object { const val MAX_KEYS = 20 }
}

@Serializable enum class ProviderProxyMode { INHERIT, DIRECT, CUSTOM }
@Serializable enum class ProviderProxyType { HTTP, SOCKS5 }

/** Includes credentials: kept in encrypted storage, never in ProviderConfig or chat JSON. */
@Serializable
data class ProviderProxy(
    val mode: ProviderProxyMode = ProviderProxyMode.INHERIT,
    val type: ProviderProxyType = ProviderProxyType.HTTP,
    val host: String = "",
    val port: Int = 8080,
    val username: String = "",
    val password: String = "",
) {
    fun error(): String? = when {
        mode != ProviderProxyMode.CUSTOM -> null
        host.isBlank() || host.length > 253 || host.any { it.isWhitespace() || it in "/\\@?#" || it.isISOControl() } || "://" in host -> "Enter a proxy host without a URL, path or credentials."
        port !in 1..65535 -> "Enter a port from 1 to 65535."
        username.length > 1024 || password.length > 4096 || (username + password).any { it.isISOControl() } || ':' in username -> "Enter valid proxy credentials."
        type == ProviderProxyType.SOCKS5 && (username.isNotEmpty() || password.isNotEmpty()) -> "Proxy authentication is available for HTTP proxies."
        else -> null
    }
    fun withoutCredentials() = copy(username = "", password = "")
    override fun toString() = "ProviderProxy(mode=$mode, type=$type)"
    companion object { val DIRECT = ProviderProxy(mode = ProviderProxyMode.DIRECT) }
}
