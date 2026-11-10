package com.androidharness.app.router

import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

internal object NineRouterRelease {
    private val valid = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[a-zA-Z0-9.-]+)?")
    fun validate(version: String): String = version.also { require(it.length <= 80 && valid.matches(it)) { "Invalid 9Router release version." } }
    fun newer(candidate: String, current: String): Boolean {
        validate(candidate); validate(current)
        val a = candidate.substringBefore('-').split('.').map { it.toLong() }
        val b = current.substringBefore('-').split('.').map { it.toLong() }
        for (i in 0..2) if (a[i] != b[i]) return a[i] > b[i]
        if ('-' !in candidate) return '-' in current
        if ('-' !in current) return false
        val ap = candidate.substringAfter('-').split('.'); val bp = current.substringAfter('-').split('.')
        for (i in 0 until minOf(ap.size, bp.size)) {
            if (ap[i] == bp[i]) continue
            val an = ap[i].toLongOrNull(); val bn = bp[i].toLongOrNull()
            return when { an != null && bn != null -> an > bn; an != null -> false; bn != null -> true; else -> ap[i] > bp[i] }
        }
        return ap.size > bp.size
    }
    fun latest(): String {
        val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
        return http.newCall(Request.Builder().url("https://registry.npmjs.org/9router/latest").build()).execute().use {
            check(it.isSuccessful) { "Could not check 9Router updates (HTTP ${it.code})." }
            validate(Json.parseToJsonElement(it.body!!.string()).jsonObject.getValue("version").jsonPrimitive.content)
        }
    }
}
