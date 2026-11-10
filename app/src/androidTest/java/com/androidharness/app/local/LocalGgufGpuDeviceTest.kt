package com.androidharness.app.local

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import com.androidharness.app.MainActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalGgufGpuDeviceTest {
    @Test(timeout = 240_000) fun realGgufRunsOnGpuThenSwitchesBackToCpu() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { foreground ->
        foreground.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val id = InstrumentationRegistry.getArguments().getString("localModelId")
        assumeTrue("Pass installed GGUF localModelId", id != null)
        val context = instrumentation.targetContext
        val c = (context.applicationContext as HarnessApp).container
        val model = requireNotNull(c.localModels.find(id!!))
        assertNotEquals(LocalModelFormat.SAFETENSORS, model.format)
        val gpu = withContext(Dispatchers.IO) { LocalNative.gpuDevice() }
        assertTrue("No Vulkan device", gpu.isNotBlank())
        val path = File(context.noBackupFilesDir, "local-models/$id.gguf").path.toByteArray()
        val request = """{"messages":[{"role":"user","content":"What is 2+2? Answer briefly."}],"tools":[],"enable_thinking":false}""".toByteArray()
        suspend fun generate(useGpu: Boolean, cancel: Boolean = false): JsonObject = withContext(Dispatchers.IO) {
            val handle = LocalNative.create()
            var text = ""
            val started = SystemClock.elapsedRealtime()
            try {
                val result = LocalNative.generateChat(handle, path, byteArrayOf(), request, emptyArray(),
                    512, 384, 32, 4, 4, useGpu, byteArrayOf(), object : LocalNative.ChatCallback {
                        override fun onEvent(bytes: ByteArray): Boolean {
                            val event = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                            text += event["text"]?.jsonPrimitive?.content.orEmpty()
                            if (cancel && text.isNotBlank()) LocalNative.cancel(handle)
                            return true
                        }
                        override fun onWarning(bytes: ByteArray): Boolean = false
                    })
                assertTrue("No visible output", text.isNotBlank())
                Json.parseToJsonElement(result.toString(Charsets.UTF_8)).jsonObject.also {
                    assertEquals(if (useGpu) "GPU" else "CPU", it.getValue("backend").jsonPrimitive.content)
                    assertTrue(it.getValue("output").jsonPrimitive.int > 0)
                    println("GGUF_${if (useGpu) "GPU" else "CPU"}_OK device=$gpu ms=${SystemClock.elapsedRealtime()-started} result=$it reply=$text")
                }
            } finally { LocalNative.destroy(handle) }
        }
        generate(false)
        generate(true)
        try { generate(true, true); fail("Expected cancellation") }
        catch (e: IllegalStateException) { assertTrue(e.message.orEmpty().contains("stopped", true)) }
        generate(false)
        val limitsFile = File(context.noBackupFilesDir, "local-models/$id.json")
        val previousLimits = limitsFile.takeIf { it.exists() }?.readBytes()
        val experiments = c.settings.settings.first().localModelExperimental
        try {
            c.settings.setLocalModelExperimental(true)
            for (backend in listOf(LocalBackend.GPU, LocalBackend.CPU, LocalBackend.AUTO)) {
                val limits = LocalModelLimits(512, 384, 32, 4, backend)
                c.localModels.acknowledgeResources(model, limits, false)
                c.localModels.saveLimits(id, limits)
                val reply = c.localModels.generateChat(id, request, emptyArray(), 16) {}
                val expected = if (backend == LocalBackend.CPU) "CPU" else "GPU"
                assertEquals(expected, reply.getValue("backend").jsonPrimitive.content)
                assertTrue(c.localModels.status.value[id].orEmpty().contains(expected))
                println("GGUF_SAVED_BACKEND_OK preference=$backend actual=$expected")
            }
        } finally {
            if (previousLimits == null) limitsFile.delete() else limitsFile.writeBytes(previousLimits)
            c.settings.setLocalModelExperimental(experiments)
        }
        println("GGUF_GPU_SWITCH_CANCEL_OK")
        }
    }
}
