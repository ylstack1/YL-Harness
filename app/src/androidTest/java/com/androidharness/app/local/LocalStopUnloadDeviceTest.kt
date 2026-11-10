package com.androidharness.app.local

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.MainActivity
import com.androidharness.app.ui.settings.LocalModelCard
import com.androidharness.app.ui.settings.LocalBenchmarkScreen
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalStopUnloadDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun nodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        listOf(node) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::nodes).orEmpty() }
    private fun find(text: String): AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow
        ?.let { nodes(it).firstOrNull { node -> node.text?.toString() == text } }
    private fun waitFor(text: String, present: Boolean = true) {
        val end = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < end) {
            if ((find(text) != null) == present) return
            SystemClock.sleep(100)
        }
        error("UI condition not met: $text present=$present")
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        val target = File(instrumentation.targetContext.getExternalFilesDir(null), "performance-redesign/$name.png")
        target.parentFile!!.mkdirs()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            target.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun click(text: String) {
        var found = find(text)
        for (attempt in 0 until 8) {
            if (found != null) break
            instrumentation.uiAutomation.rootInActiveWindow?.let { root ->
                nodes(root).firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(100)
            found = find(text)
        }
        var node = requireNotNull(found) { "Missing clickable UI: $text" }
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    @Test(timeout = 90_000) fun nativeGpuBenchmarkReportsMeasuredSpeeds() {
        val context = instrumentation.targetContext
        val fixture = File(context.filesDir, "local-cache-test.gguf")
        val library = File(context.filesDir, "local-benchmark-gpu.so")
        assumeTrue("Pass the temporary fixture and GPU backend", fixture.isFile && library.isFile)
        ActivityScenario.launch(MainActivity::class.java).use { foreground ->
            foreground.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            assertTrue("GPU backend could not load", LocalNative.loadBackend(library.absolutePath))
            assertTrue("No compatible GPU", LocalNative.gpuDevice().isNotBlank())
            val request = LocalNative.create()
            var sawLiveGeneration = false
            try {
                val data = LocalNative.benchmark(request, fixture.absolutePath.toByteArray(), true, 2, 2,
                    object : LocalNative.BenchmarkCallback {
                        override fun onProgress(phase: String, tokens: Int, seconds: Double): Boolean {
                            if (phase == "generation" && tokens > 0 && seconds > 0) sawLiveGeneration = true
                            return true
                        }
                    })
                val result = kotlinx.serialization.json.Json.parseToJsonElement(data.toString(Charsets.UTF_8)).jsonObject
                assertTrue(result.getValue("generation_tps").jsonPrimitive.content.toDouble() > 0)
                assertTrue(result.getValue("prompt_tps").jsonPrimitive.content.toDouble() > 0)
                assertTrue("Missing live GPU progress", sawLiveGeneration)
            } finally { LocalNative.releaseCachedModel(); LocalNative.destroy(request) }
        }
    }

    @Test(timeout = 180_000) fun selectedModelActuallyUnloadsAndShowsThatItIsNotLoaded() = runBlocking {
        val context = instrumentation.targetContext
        val fixture = File(context.filesDir, "local-cache-test.gguf")
        assumeTrue("Place a GGUF test fixture in app files", fixture.isFile)
        val root = File(context.noBackupFilesDir, "local-models").apply { mkdirs() }
        val id = "custom-0123456789abcdef0123456789abcdef"
        val spec = LocalModelSpec(id, "Unload test model", "", "", "fixture.gguf", fixture.length(), "", 1, 1024,
            "Device test", custom = true, maxContext = 512, localFile = true)
        val registry = CustomModelRegistry(root)
        check(registry.models.none { it.id == id })
        val target = File(root, "$id.gguf")
        check(!target.exists())
        registry.add(spec)
        fixture.copyTo(target)
        val manager = LocalModelManager(context)
        try {
            withTimeout(10_000) { manager.installed.first { id in it } }
            manager.acknowledgeResources(spec, manager.limits(id), false)
            val request = """{"messages":[{"role":"user","content":"Hello"}],"tools":[],"enable_thinking":false}""".toByteArray()
            manager.generateChat(id, request, emptyArray(), 8, "unload-test") {}
            assertEquals(id, manager.loaded.value)
            assertTrue(LocalNative.isModelCached(target.absolutePath.toByteArray()))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { MaterialTheme {
                    val loaded by manager.loaded.collectAsState()
                    val running by manager.running.collectAsState()
                    val scope = rememberCoroutineScope()
                    LocalModelCard(spec, 512, manager.device(), selected = true, installed = true,
                        working = loaded == id || running == id, loaded = loaded == id, enabled = true,
                        onPrimary = { scope.launch { manager.load(id) } }, onDetails = {}, onStop = { scope.launch { manager.stopAndUnload(id) } })
                } } }
                waitFor("Loaded in memory")
                waitFor("Stop and unload")
                click("Stop and unload")
                withTimeout(15_000) { manager.loaded.first { it == null } }
                assertFalse("Native model remains cached after stop", LocalNative.isModelCached(target.absolutePath.toByteArray()))
                waitFor("Not loaded")
                waitFor("Stop and unload", present = false)
                waitFor("Selected")
                waitFor("Load model")
                click("Load model")
                withTimeout(15_000) { manager.loaded.first { it == id } }
                assertTrue("Load button did not load the native model", LocalNative.isModelCached(target.absolutePath.toByteArray()))
                waitFor("Loaded in memory")
                waitFor("Stop and unload")
                waitFor("Load model", present = false)
            }
            manager.generateChat(id, request, emptyArray(), 8, "unload-test") {}
            assertEquals("Using the model again must reload it", id, manager.loaded.value)
            coroutineScope {
                val generation = async(Dispatchers.Default) {
                    runCatching { manager.generateChat(id, request, emptyArray(), 128, "unload-test") {} }
                }
                withTimeout(10_000) { manager.running.first { it == id } }
                withTimeout(15_000) { manager.stopAndUnload(id) }
                generation.await()
                assertNull("Generation is still running", manager.running.value)
                assertNull("Model is still marked loaded", manager.loaded.value)
                assertFalse("Native model remains cached", LocalNative.isModelCached(target.absolutePath.toByteArray()))
            }
            ActivityScenario.launch(MainActivity::class.java).use { foreground ->
            foreground.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            val observed = mutableListOf<LocalBenchmarkProgress>()
            android.util.Log.i("HarnessBenchTest", "starting CPU benchmark")
            manager.benchmark(id, LocalBenchmarkMode.CPU) { observed.add(it); if (it.finished || it.livePhase == "loading") android.util.Log.i("HarnessBenchTest", "CPU ${it.completed}/${it.total} ${it.phase} finished=${it.finished}") }
            val result = observed.last()
            assertTrue("The first three runs need visible answers", result.answers.take(3).size == 3 && result.answers.take(3).all { it.text.isNotBlank() && it.outputTokens > 0 })
            assertTrue("Warm-up must be recorded", result.answers.first().phase == LocalBenchmarkPhase.WARMUP)
            assertTrue(result.finished)
            assertFalse(result.stopped)
            assertTrue(result.errors.toString(), result.errors.isEmpty())
            assertEquals(1, result.recommendations.size)
            assertTrue(result.samples.any { it.phase == LocalBenchmarkPhase.GENERATION && it.generationTps > 0 })
            assertTrue(result.samples.any { it.phase == LocalBenchmarkPhase.PROMPT && it.promptTps > 0 })
            assertTrue("Missing live generation updates", observed.any { it.livePhase == "generation" && it.liveTokens > 0 && it.liveTps > 0 })
            assertFalse(LocalNative.isModelCached(target.absolutePath.toByteArray()))
            coroutineScope {
                val started = CompletableDeferred<Unit>()
                val updates = mutableListOf<LocalBenchmarkProgress>()
                val pending = async(Dispatchers.Default) {
                    manager.benchmark(id, LocalBenchmarkMode.CPU) {
                        updates.add(it)
                        if (it.livePhase == "generation" && it.liveTokens > 0) started.complete(Unit)
                    }
                }
                withTimeout(10_000) { started.await() }
                manager.stop(id)
                withTimeout(10_000) { pending.await() }
                assertTrue("Stopping a benchmark must be reported", updates.last().stopped)
                assertFalse("Stopped benchmark must release the model", LocalNative.isModelCached(target.absolutePath.toByteArray()))
            }
            val settings = com.androidharness.app.data.SettingsRepository(context)
            android.util.Log.i("HarnessBenchTest", "starting both benchmark")
            val experimental = settings.settings.first().localModelExperimental
            try {
                settings.setLocalModelExperimental(false)
                val both = mutableListOf<LocalBenchmarkProgress>()
                manager.benchmark(id, LocalBenchmarkMode.BOTH) { both.add(it); if (it.finished || it.livePhase == "loading") android.util.Log.i("HarnessBenchTest", "both ${it.completed}/${it.total} ${it.phase} finished=${it.finished}") }
                assertEquals(listOf(LocalBackend.CPU), both.last().recommendations.map { it.backend })
                assertTrue("Unavailable GPU must be reported", both.last().errors.any { it.startsWith("GPU:") })
            } finally { settings.setLocalModelExperimental(experimental) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                android.util.Log.i("HarnessBenchTest", "starting benchmark UI")
                scenario.onActivity { activity -> activity.setContent { MaterialTheme {
                    LocalBenchmarkScreen(spec, manager, onDismiss = {}, onApplied = {})
                } } }
                waitFor("Start test")
                capture("setup")
                click("Start test")
                waitFor("Before you start")
                click("Begin test")
                SystemClock.sleep(200)
                capture("live")
                waitFor("Apply best settings")
                waitFor("Results")
                capture("results")
                click("See all measurements")
                waitFor("Hide measurements")
                click("Hide measurements")
                click("Apply best settings")
                waitFor("Settings applied")
                capture("applied")
                withTimeout(5_000) { while (manager.limits(id).backend != LocalBackend.CPU) delay(50) }
            }
            }
        } finally {
            manager.releaseCachedModel()
            target.delete()
            File(root, "$id.json").delete()
            CustomModelRegistry(root).remove(id)
        }
    }
}
