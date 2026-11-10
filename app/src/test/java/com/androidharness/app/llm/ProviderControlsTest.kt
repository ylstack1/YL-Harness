package com.androidharness.app.llm

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ProviderControlsTest {
    @Test fun oldSingleKeyEditsPreserveAdditionalKeysAndDisabledChoices() {
        val first = ProviderApiKey("a", "First", "alpha-key")
        val second = ProviderApiKey("b", "Spare", "beta-key")
        val pool = ProviderKeyPool(listOf(first, second), rotate = false)
        assertEquals(listOf("new-key", "beta-key"), pool.replaceDefault("new-key").keys.map { it.value })
        assertEquals(listOf(second), pool.replaceDefault("").keys)
        assertEquals(listOf(second), pool.copy(keys = listOf(first, second.copy(enabled = false))).replaceDefault("beta-key").keys)
        val off = pool.copy(keys = listOf(first.copy(enabled = false)))
        assertEquals(off, off.replaceDefault(""))
        assertEquals(listOf(false, true), off.replaceDefault("new-key").keys.map { it.enabled })
    }

    @Test fun keyPoolValidationPreventsDuplicateOrMalformedCredentialsWithoutEchoingThem() {
        val secret = "sensitive-test-key"
        val key = ProviderApiKey("a", secret, secret)
        assertNull(ProviderKeyPool(listOf(key)).error())
        for (keys in listOf(listOf(key, key.copy(id = "b")), listOf(key, key.copy(value = "other")),
            listOf(key.copy(value = "bad\nkey")), listOf(key.copy(value = "")), List(21) { key.copy(id = "$it", value = "key-$it") })) {
            val error = requireNotNull(ProviderKeyPool(keys).error())
            assertFalse(error.contains(secret))
        }
        assertFalse(key.toString().contains(secret))
        assertFalse(ProviderKeyPool(listOf(key)).toString().contains(secret))
    }

    @Test fun proxyValidationDistinguishesSystemDirectHttpAndUnauthenticatedSocks() {
        assertNull(ProviderProxy().error())
        assertNull(ProviderProxy.DIRECT.error())
        val http = ProviderProxy(ProviderProxyMode.CUSTOM, host = "proxy.example", port = 3128, username = "user", password = "secret")
        assertNull(http.error())
        assertFalse(http.toString().contains("user")); assertFalse(http.toString().contains("secret"))
        for (invalid in listOf(http.copy(host = "https://proxy.example"), http.copy(host = "user@proxy"), http.copy(host = "bad host"),
            http.copy(port = 0), http.copy(port = 65536), http.copy(username = "bad:name"), http.copy(type = ProviderProxyType.SOCKS5))) assertNotNull(invalid.error())
        assertNull(http.copy(type = ProviderProxyType.SOCKS5, username = "", password = "").error())
        assertEquals("", http.withoutCredentials().password)
        assertEquals(http.host, http.withoutCredentials().host)
        assertEquals(http, Json.decodeFromString<ProviderProxy>(Json.encodeToString(ProviderProxy.serializer(), http)))
    }
}
