package com.androidharness.app.ui.chat.rich

import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.agent.*
import com.androidharness.app.core.*
import com.androidharness.app.core.response.RichResponse
import com.androidharness.app.data.*
import com.androidharness.app.llm.*
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.chat.ChatScreen
import com.androidharness.app.ui.chat.ChatViewModel
import com.androidharness.app.ui.chat.RemoteContentLoader
import com.androidharness.app.ui.chat.components.AssistantText
import com.androidharness.app.ui.theme.HarnessTheme
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RichReplyFeaturesDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container
    private val types = listOf("table", "tabs", "gallery", "chart", "calculator", "evidence", "diff", "writing")
    private val table = """{"type":"table","title":"Device scores","columns":["Option","Score"],"rows":[["Alpha","10"],["Beta","2"],["Gamma","30"]],"caption":"Illustrative data"}"""
    private val calc = """{"type":"calculator","title":"Estimate effort","inputs":[{"id":"hours","label":"Hours","value":4,"min":0,"max":24,"step":0.5}],"outputs":[{"label":"Total","expression":"hours * 10","unit":"credits"}],"caption":"Illustrative estimate"}"""
    private fun ui(json: String) = "```harness-ui\n$json\n```"
    private fun tabbed(): String = ui("""{"type":"tabs","title":"Explore results","tabs":[{"title":"Compare","children":[$table]},{"title":"Calculate","children":[$calc]}]}""")

    @Test fun tableTabsCalculatorChartAndSavedStateWorkWithCurrentValues() {
        val mode = mutableStateOf(0)
        val scale = mutableStateOf(1f)
        val desktop = mutableStateOf(false)
        var attached = ""
        var follow = RichResponse.FollowUp("", "", "")
        val chart = """{"type":"chart","title":"Benchmark","kind":"line","unit":"ms","series":[{"name":"Before","points":[{"label":"Day 1","value":18},{"label":"Day 2","value":20},{"label":"Day 3","value":22}]},{"name":"After","points":[{"label":"Day 1","value":12},{"label":"Day 2","value":14},{"label":"Day 3","value":16}]}],"caption":"Fixture measurements"}"""
        fun show(host: ChatListTestActivity) { host.setContent { HarnessTheme(dynamicColor = false) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value), LocalResponseContext provides { _, state -> attached = state }) {
                Column(Modifier.width(if (desktop.value) 1000.dp else 320.dp).fillMaxHeight().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    key(mode.value) { AssistantText(if (mode.value == 0) tabbed() else ui(chart), onFollowUp = { follow = it }) }
                }
            }
        } } }
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity(::show)
            await("Explore results")
            frames()
            await("Device scores")
            assertEquals("No automatic context on initial display", "", attached)
            tap("Score ↕")
            assertTrue(attached.contains("Sort: Score ascending"))
            val beta = await("Beta"); var row: AccessibilityNodeInfo? = beta
            while (row != null && !row.isCheckable) row = row.parent
            assertTrue(requireNotNull(row).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitFor { attached.contains("Beta | 2") }
            tap("Discuss selected rows")
            assertTrue(follow.context.contains("Beta | 2")); assertFalse(follow.context.contains("Alpha | 10"))
            edit("Filter rows", "Gamma")
            waitFor { find("Alpha") == null && attached.contains("Filter: Gamma") }
            await("Gamma")
            tap("Copy"); await("Copied")
            instrumentation.runOnMainSync {
                val clip = c.appContext.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
                assertTrue(clip.contains("Gamma")); assertFalse(clip.contains("Alpha"))
            }
            tap("Calculate")
            edit("Hours", "6")
            await("60 credits")
            assertTrue(attached.contains("Hours: 6")); assertTrue(attached.contains("Total: 60 credits"))
            tap("Ask about these values"); assertTrue(follow.context.contains("Hours: 6"))
            tap("Ask about this tab"); assertTrue("Tab follow-up keeps edited child values", follow.context.contains("Total: 60 credits"))
            screenshot("calculator-phone")
            tap("Compare"); await("Gamma")
            tap("Calculate"); await("60 credits")
            scenario.recreate(); scenario.onActivity(::show)
            await("60 credits")
            scenario.onActivity { scale.value = 1.5f; desktop.value = true }
            await("60 credits"); screenshot("calculator-large-desktop")
            scenario.onActivity { mode.value = 1; scale.value = 1f; desktop.value = false }
            await("Benchmark")
            tap("Before")
            tap("Ask about this chart")
            assertFalse(follow.context.contains("Before:")); assertTrue(follow.context.contains("After: 16 ms"))
            tap("Show data"); tap("Select Day 1")
            tap("Ask about this chart")
            assertTrue(follow.context.contains("Selected: Day 1")); assertTrue(follow.context.contains("After: 12 ms"))
            fun rangeHandle(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                node ?: return null
                if (node.rangeInfo != null) return node
                for (i in 0 until node.childCount) rangeHandle(node.getChild(i))?.let { return it }
                return null
            }
            val handle = requireNotNull(rangeHandle(instrumentation.uiAutomation.rootInActiveWindow))
            assertTrue(handle.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply {
                putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 1f)
            }))
            waitFor { attached.contains("Range: Day 2 to Day 3") }
            tap("Show full range")
            waitFor { attached.contains("Range: Day 1 to Day 3") }
            screenshot("chart-multiple-datasets")
        }
    }

    @Test fun galleryEvidenceDiffAndWritingHaveWorkingControlsAndRespectImagesOff() {
        val mode = mutableStateOf(0)
        val imagesOn = mutableStateOf(true)
        val scale = mutableStateOf(1f)
        var attached = ""
        var opened = ""
        var file = ""
        var follow = RichResponse.FollowUp("", "", "")
        val one = "https://example.com/rich-fixture-one.png"
        val two = "https://example.com/rich-fixture-two.png"
        val gallery = """{"type":"gallery","title":"Compare screens","images":[{"url":"$one","caption":"First screen","source":"https://example.com/first"},{"url":"$two","caption":"Second screen","source":"https://example.com/second"}]}"""
        val evidence = """{"type":"evidence","title":"Evidence","items":[{"title":"Source result","text":"A supporting source","url":"https://example.com/reference"},{"title":"Test result","text":"Observed fixture output","path":"src/Main.kt","line":12,"status":"passed"}]}"""
        val diff = """{"type":"diff","title":"Review proposal","before":"old line\nkeep","after":"new line\nkeep","path":"src/Main.kt","line":12,"language":"kotlin"}"""
        val writing = """{"type":"writing","title":"Reusable message","text":"Hello from the exact writing block.\nSecond line."}"""
        @Suppress("UNCHECKED_CAST")
        val cache = RemoteContentLoader::class.java.getDeclaredField("memory").apply { isAccessible = true }.get(RemoteContentLoader) as android.util.LruCache<String, Bitmap>
        val bitmaps = listOf(one, two).mapIndexed { index, url -> Bitmap.createBitmap(200, 120, Bitmap.Config.ARGB_8888).apply {
            eraseColor(if (index == 0) android.graphics.Color.BLUE else android.graphics.Color.GREEN); cache.put(url, this)
        } }
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { host -> host.setContent { HarnessTheme(dynamicColor = false) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value),
                        com.androidharness.app.ui.chat.LocalChatImagesEnabled provides imagesOn.value,
                        LocalResponseContext provides { _, state -> attached = state }, LocalResponseFileOpen provides { path, line -> file = "$path:$line" }) {
                        Column(Modifier.width(320.dp).fillMaxHeight().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(12.dp)) {
                            key(mode.value) { AssistantText(ui(listOf(gallery, evidence, diff, writing)[mode.value]), onOpenUrl = { opened = it }, onFollowUp = { follow = it }) }
                        }
                    }
                } } }
                await("First screen"); tap("Next"); await("Second screen")
                tap("Ask about this image"); assertTrue(follow.context.contains(two))
                waitFor { attached.contains("Second screen") }
                tap("Expand image"); await("Close")
                screenshot("gallery-expanded"); tap("Close")
                tap("Open source"); assertEquals("https://example.com/second", opened)
                scenario.onActivity { imagesOn.value = false }
                await("Images are turned off for this chat.")
                scenario.onActivity { mode.value = 1; imagesOn.value = true }
                await("Evidence")
                tap("Open source"); assertEquals("https://example.com/reference", opened)
                await("Reported: Passed"); tap("Open file"); assertEquals("src/Main.kt:12", file)
                screenshot("evidence-phone")
                scenario.onActivity { mode.value = 2 }
                await("1 added · 1 removed")
                tap("After"); await("new line\nkeep")
                tap("Discuss these changes"); assertTrue(follow.context.contains("-old line")); assertTrue(follow.context.contains("+new line"))
                screenshot("diff-phone")
                scenario.onActivity { mode.value = 3; scale.value = 1.5f }
                await("Reusable message")
                tap("Copy"); await("Copied")
                instrumentation.runOnMainSync {
                    val text = c.appContext.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
                    assertEquals("Hello from the exact writing block.\nSecond line.", text)
                }
                screenshot("writing-large-text")
            }
        } finally { cache.remove(one); cache.remove(two); bitmaps.forEach { it.recycle() } }
    }

    @Test fun bothRealSectionsReceiveTheCatalogAndAttachSelectedRowsWithoutSending(): Unit = runBlocking {
        val saved = c.settings.settings.first()
        val root = File(c.appContext.cacheDir, "rich-catalog-${System.nanoTime()}").apply { mkdirs() }
        val sessions = mutableListOf<String>()
        val store = ViewModelStore()
        var calls = 0
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                for (kind in listOf(ConversationKind.CHAT, ConversationKind.CODE)) {
                    val sid = c.sessions.createSession("Rich $kind fixture", conversation = ConversationConfig(kind)); sessions += sid
                    val provider = object : LlmProvider {
                        override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String, messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                            calls++
                            types.forEach { assertTrue("$kind knows $it", systemPrompt.contains("\"type\":\"$it\"")) }
                            assertTrue("$kind receives answer layout guidance", systemPrompt.contains("Answer readability:"))
                            assertTrue("$kind chooses an appropriate format", systemPrompt.contains("simplest presentation that helps"))
                            assertTrue("$kind keeps non-tabular rich responses", systemPrompt.contains("Prefer cards or choices"))
                            assertTrue("$kind allows useful comparisons", systemPrompt.contains("qualitative attributes as well as measurements"))
                            assertTrue("$kind rejects invented chart scores", systemPrompt.contains("never invent scores"))
                            assertFalse("$kind has no component percentage target", systemPrompt.contains("70–80%"))
                            ui(table).chunked(31).forEach { emit(StreamEvent.TextDelta(it)) }
                            emit(StreamEvent.Done("stop"))
                        }
                    }
                    val user = ChatMessage(Role.USER, "Compare the fixture choices.")
                    c.sessions.addMessage(sid, user, "fixture-turn")
                    val engine = AgentEngine({ provider }, c.registry, c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills)
                    engine.run(sid, "fixture-turn", ProviderConfig("fixture", "Fixture", ProviderType.OPENAI_COMPAT, "http://localhost", "fixture"), "", listOf(user),
                        { PermissionMode.FULL_ACCESS }, mutableSetOf(), FileFs(root), RequestOptions(), 100_000, AgentMode.ACT, repoMapEnabled = false,
                        conversation = ConversationConfig(kind)).collect { event -> if (event is AgentEvent.AssistantCommitted) c.sessions.addMessage(sid, event.message, "fixture-turn") }
                    scenario.onActivity { host ->
                        store.clear(); val vm = ChatViewModel(c, sid); store.put("chat", vm)
                        host.setContent { HarnessTheme { key(sid) { ChatScreen(vm, {}, { _, _ -> }, {}, {}, onOpenSubagent = {}) } } }
                    }
                    await("Rich $kind fixture")
                    frames()
                    await("Device scores")
                    assertNull(find("Response · Device scores"))
                    var row: AccessibilityNodeInfo? = await("Beta")
                    while (row != null && !row.isCheckable) row = row.parent
                    assertTrue(requireNotNull(row).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    await("Response · Device scores")
                    assertEquals("Only fixture generation contacts a provider", sessions.size, calls)
                    screenshot("real-${kind.name.lowercase()}-table-context")
                }
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            sessions.forEach { id -> c.sessions.session(id)?.let { c.sessions.deleteSession(it) } }
            root.deleteRecursively(); c.settings.setLastActiveSessionId(saved.lastActiveSessionId)
        }
    }

    private fun frames() {
        val ready = java.util.concurrent.CountDownLatch(1)
        instrumentation.runOnMainSync {
            android.view.Choreographer.getInstance().postFrameCallback {
                android.view.Choreographer.getInstance().postFrameCallback { ready.countDown() }
            }
        }
        assertTrue("UI frames did not settle", ready.await(5, java.util.concurrent.TimeUnit.SECONDS))
    }
    private fun find(text: String): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.isVisibleToUser && (node.text?.toString() == text || node.contentDescription?.toString() == text)) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        return walk(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun waitFor(check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 12_000
        while (SystemClock.uptimeMillis() < end) { if (check()) return; SystemClock.sleep(100) }
        assertTrue("Condition timed out", check())
    }
    private fun await(text: String): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + 12_000
        fun scrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.isScrollable && node.className?.contains("Horizontal") != true) return node
            for (i in 0 until node.childCount) scrollable(node.getChild(i))?.let { return it }
            return null
        }
        var tries = 0
        while (SystemClock.uptimeMillis() < end) {
            find(text)?.let { return it }
            if (++tries % 4 == 0) scrollable(instrumentation.uiAutomation.rootInActiveWindow)?.performAction(
                if ((tries / 16) % 2 == 0) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            SystemClock.sleep(100)
        }
        screenshot("failure"); throw AssertionError("Missing: $text")
    }
    private fun tap(text: String) {
        await(text).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        waitFor {
            var node: AccessibilityNodeInfo? = find(text)
            while (node != null && !node.isClickable) node = node.parent
            node?.isEnabled == true && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        frames()
    }
    private fun edit(label: String, text: String) {
        fun editable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.isEditable) return node
            for (i in 0 until node.childCount) editable(node.getChild(i))?.let { return it }
            return null
        }
        var node: AccessibilityNodeInfo? = await(label)
        while (node != null && editable(node) == null) node = node.parent
        assertTrue(requireNotNull(editable(node)).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }))
        frames()
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val dir = File(c.appContext.getExternalFilesDir(null), "rich-features-qa").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap -> File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
    }
}
