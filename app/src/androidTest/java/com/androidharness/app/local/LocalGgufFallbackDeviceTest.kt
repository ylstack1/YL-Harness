package com.androidharness.app.local

import android.system.Os
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run alone in a fresh process: upstream's backend switch simulates a phone without usable Vulkan. */
@RunWith(AndroidJUnit4::class)
class LocalGgufFallbackDeviceTest {
    @Test(timeout = 90_000) fun missingGpuUsesCpuForAutoAndSavedGpuPreference() = runBlocking {
        val id = InstrumentationRegistry.getArguments().getString("localModelId")
        assumeTrue("Pass installed GGUF localModelId", id != null)
        val oldDisabled = Os.getenv("GGML_DISABLE_VULKAN")
        Os.setenv("GGML_DISABLE_VULKAN", "1", true)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { foreground ->
                foreground.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val c = (context.applicationContext as HarnessApp).container
                val model = requireNotNull(c.localModels.find(id!!))
                val limitsFile = File(context.noBackupFilesDir, "local-models/$id.json")
                val oldLimits = limitsFile.takeIf { it.exists() }?.readBytes()
                val experimental = c.settings.settings.first().localModelExperimental
                try {
                    assertEquals("", LocalNative.gpuDevice())
                    c.settings.setLocalModelExperimental(true)
                    for (backend in listOf(LocalBackend.AUTO, LocalBackend.GPU)) {
                        val limits = LocalModelLimits(512, 384, 32, 4, backend)
                        c.localModels.acknowledgeResources(model, limits, false)
                        c.localModels.saveLimits(id, limits)
                        val request = """{"messages":[{"role":"user","content":"Hi"}],"tools":[],"enable_thinking":false}""".toByteArray()
                        val reply = c.localModels.generateChat(id, request, emptyArray(), 16) {}
                        assertEquals("CPU", reply.getValue("backend").jsonPrimitive.content)
                        println("GGUF_NO_GPU_FALLBACK_OK preference=$backend actual=CPU")
                    }
                } finally {
                    if (oldLimits == null) limitsFile.delete() else limitsFile.writeBytes(oldLimits)
                    c.settings.setLocalModelExperimental(experimental)
                }
            }
        } finally {
            if (oldDisabled == null) Os.unsetenv("GGML_DISABLE_VULKAN") else Os.setenv("GGML_DISABLE_VULKAN", oldDisabled, true)
        }
    }
}
