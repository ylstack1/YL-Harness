package com.androidharness.app.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalContextReuseDeviceTest {
    @Test fun cachedTextMatchesFreshAndIsIsolatedBySession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = File(context.filesDir, "local-cache-test.gguf")
        assumeTrue("Place test fixture in app files", model.isFile)
        fun run(text: String, session: String): Pair<String, JsonObject> {
            val handle = LocalNative.create()
            val output = StringBuilder()
            val request = buildJsonObject {
                putJsonArray("messages") { add(buildJsonObject { put("role", "user"); put("content", text) }) }
                putJsonArray("tools") {}
                put("enable_thinking", false)
            }.toString().toByteArray()
            try {
                val result = LocalNative.generateChat(handle, model.path.toByteArray(), byteArrayOf(), request, emptyArray(),
                    512, 384, 16, 2, 3, false, session.toByteArray(), object : LocalNative.ChatCallback {
                        override fun onEvent(bytes: ByteArray): Boolean {
                            val item = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                            output.append(item["text"]?.jsonPrimitive?.content.orEmpty())
                            return true
                        }
                        override fun onWarning(bytes: ByteArray) = true
                    })
                return output.toString() to Json.parseToJsonElement(result.toString(Charsets.UTF_8)).jsonObject
            } finally { LocalNative.destroy(handle) }
        }
        try {
            LocalNative.releaseCachedModel()
            run("Hello there", "chat-a")
            val reused = run("Hello there again", "chat-a")
            assertTrue(reused.second.getValue("reused_prompt_tokens").jsonPrimitive.int > 0)
            val other = run("Hello there again", "chat-b")
            assertEquals(0, other.second.getValue("reused_prompt_tokens").jsonPrimitive.int)
            assertEquals(other.first, reused.first)
            val changed = run("Different content", "chat-b")
            LocalNative.releaseCachedModel()
            val fresh = run("Different content", "chat-b")
            assertEquals(fresh.first, changed.first)
        } finally { LocalNative.releaseCachedModel() }
    }
}
