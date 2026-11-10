package com.androidharness.app.router

import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Uses the same private local credentials and JSON protocol as 9Router's terminal UI. */
internal class NineRouterControl(private val data: File) {
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private fun token(): String {
        fun persisted(file: File, create: () -> String): String {
            file.parentFile!!.mkdirs()
            if (!file.exists() || file.length() == 0L) {
                val atomic = android.util.AtomicFile(file)
                val stream = atomic.startWrite()
                try { stream.write(create().toByteArray()); atomic.finishWrite(stream) }
                catch (e: Exception) { atomic.failWrite(stream); throw e }
            }
            return file.readText().trim().also { check(it.isNotEmpty()) { "9Router control credentials are unavailable." } }
        }
        val machine = persisted(File(data, "machine-id")) { UUID.randomUUID().toString() }
        val secret = persisted(File(data, "auth/cli-secret")) { ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) } }
        return cliToken(machine, secret)
    }
    fun request(method: String, path: String, body: JsonObject? = null): JsonObject {
        require(path.startsWith("/api/") && !path.contains("..") && !path.contains('?'))
        val payload = body?.toString()?.toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("http://127.0.0.1:${NineRouterManager.PORT}$path")
            .header("x-9r-cli-token", token()).method(method, if (method in listOf("POST", "PUT", "PATCH")) payload ?: "{}".toRequestBody("application/json".toMediaType()) else null).build()
        return http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "9Router control request failed (HTTP ${response.code}). Start 9Router and try again." }
            Json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
        }
    }
    fun integrationKey(): String {
        val keys = request("GET", "/api/keys")["keys"]?.jsonArray.orEmpty()
        val existing = keys.map { it.jsonObject }.firstOrNull { it["name"]?.jsonPrimitive?.content == KEY_NAME && it["isActive"]?.jsonPrimitive?.booleanOrNull != false }
        val key = existing?.get("key")?.jsonPrimitive?.contentOrNull
        if (!key.isNullOrBlank()) return key
        return request("POST", "/api/keys", buildJsonObject { put("name", KEY_NAME) })["key"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: error("9Router did not return an API key.")
    }
    companion object {
        const val KEY_NAME = "AndroidHarness"
        internal fun cliToken(machine: String, secret: String): String = MessageDigest.getInstance("SHA-256")
            .digest((machine + "9r-cli-auth" + secret).toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }
}
