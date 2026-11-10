package com.androidharness.app.chatgpt

import com.androidharness.app.llm.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ChatGptAccountsTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private fun account(id: String, models: List<ModelEntry> = listOf(ModelEntry("sol"))) = ChatGptRegistration(id,
        issuer = ChatGptProtocol.ISSUER, subject = id, email = "$id@example.test", accessToken = "fixture-$id",
        expiresAt = System.currentTimeMillis() / 1000 + 3600, scopes = setOf(ChatGptProtocol.PLAN_SCOPE), models = models)
    private fun catalog(vararg ids: String) = "{\"models\":[" + ids.joinToString(",") {
        "{\"slug\":\"$it\",\"display_name\":\"$it\",\"visibility\":\"list\"}"
    } + "]}"

    private class Fixture(val manager: ChatGptAccounts, val stored: () -> String, val requests: MutableList<String>)
    private fun fixture(accounts: List<ChatGptRegistration>,
        catalogs: Map<String, String> = emptyMap()): Fixture {
        var stored = json.encodeToString(ChatGptStore("fixture-host", accounts))
        val requests = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals(ChatGptProtocol.RESOURCE + "/models", request.url.toString())
            val id = request.header("Authorization")!!.removePrefix("Bearer fixture-")
            requests += id
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (id == "offline") 503 else 200).message("fixture")
                .body((catalogs[id] ?: catalog("sol", "luna")).toResponseBody("application/json".toMediaType())).build()
        }.build()
        return Fixture(ChatGptAccounts({ stored }, { stored = it }, {}, http = client, refreshOnStart = false), { stored }, requests)
    }
    private fun config(id: String) = ProviderConfig(ChatGptProtocol.providerId(id), "ChatGPT", ProviderType.OPENAI_RESPONSES,
        ChatGptProtocol.RESOURCE, "sol")

    @Test fun `manual selection keeps an offered model or uses the first one`() = runBlocking {
        val f = fixture(listOf(account("a"), account("b"), account("signed-out").copy(accessToken = null)),
            catalogs = mapOf("b" to catalog("terra", "luna")))
        assertEquals("terra", f.manager.selectAccount(config("b").id, "terra").model)
        assertEquals("terra", f.manager.selectAccount(config("b").id, "gone").model)
        assertEquals(listOf("b", "b"), f.requests)
        assertTrue(runCatching { f.manager.selectAccount(config("signed-out").id, "sol") }.isFailure)
    }

    @Test fun `stored accounts survive removed switching preferences`() {
        val original = """{"hostId":"host","autoSwitch":true,"welcomeSeen":true,"accounts":[{"clientId":"legacy","accessToken":"fixture-legacy","scopes":["${ChatGptProtocol.PLAN_SCOPE}"],"models":[{"id":"sol"}],"useForAutoSwitch":false,"fallbackModel":"sol","usageLimitedAt":123}]}"""
        val restored = ChatGptAccounts({ original }, {}, {}, refreshOnStart = false)
        val account = restored.state.value.accounts.single()
        assertEquals(ChatGptProtocol.providerId("legacy"), account.providerId)
        assertTrue(account.connected)
        assertEquals(listOf("sol"), account.models.map { it.id })
    }
}
