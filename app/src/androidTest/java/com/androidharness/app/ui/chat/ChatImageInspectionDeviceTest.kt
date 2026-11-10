package com.androidharness.app.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.agent.ThinkingTiming
import com.androidharness.app.browser.BrowserController
import com.androidharness.app.core.*
import com.androidharness.app.tools.*
import com.androidharness.app.ui.chat.components.ChatActivity
import com.androidharness.app.ui.theme.HarnessTheme
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket

@RunWith(AndroidJUnit4::class)
class ChatImageInspectionDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    private fun picture(): ByteArray {
        val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply { drawColor(Color.RED); drawRect(48f, 0f, 96f, 64f, Paint().apply { color = Color.BLUE }) }
        return try { java.io.ByteArrayOutputStream().also { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray() }
        finally { bitmap.recycle() }
    }

    @Test fun remoteResultsReportStatusDecodeErrorsCacheAndActualAttachments(): Unit = runBlocking {
        val url = "https://1.1.1.1/inspection-${System.nanoTime()}.png"
        val requests = mutableListOf<String>()
        fun client(code: Int, type: String, bytes: ByteArray, redirect: String? = null) = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request().url.toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(bytes.toResponseBody(type.toMediaType())).apply { if (redirect != null) header("Location", redirect) }.build()
        }.build()
        val fetched = RemoteContentLoader.fetchOriginal(c.appContext, url + "?w=96&amp;h=64", client(200, "IMAGE/PNG", picture()))
        assertNull(fetched.error); assertEquals(200, fetched.httpStatus); assertEquals(96, fetched.width); assertEquals(64, fetched.height)
        assertTrue(requests.single().contains("w=96&h=64"))
        val cached = RemoteContentLoader.fetchOriginal(c.appContext, url + "?w=96&h=64", client(500, "text/plain", byteArrayOf()))
        assertTrue(cached.cached); assertNull(cached.httpStatus); assertEquals(1, requests.size)
        val missing = RemoteContentLoader.fetchOriginal(c.appContext, url + "?missing=1", client(404, "text/plain", byteArrayOf()))
        assertEquals("fetch_failed", missing.error); assertEquals(404, missing.httpStatus)
        val invalid = RemoteContentLoader.fetchOriginal(c.appContext, url + "?invalid=1", client(200, "image/png", "not an image".toByteArray()))
        assertEquals("decode_failed", invalid.error); assertNull(invalid.bytes)
        val redirected = RemoteContentLoader.fetchOriginal(c.appContext, url + "?redirect=1", client(302, "text/plain", byteArrayOf(), "http://127.0.0.1/private.png"))
        assertEquals("blocked", redirected.error)
        val root = File(c.appContext.cacheDir, "inspection-${System.nanoTime()}").apply { mkdirs() }
        val context = ToolContext(FileFs(root), sessionId = "fixture")
        val tool = ViewChatImageTool(c.images)
        val result = tool.execute(buildJsonObject { put("url", url + "?w=96&amp;h=64") }, context)
        try {
            assertTrue(result.output, result.ok)
            val envelope = Json.parseToJsonElement(result.output).jsonObject
            assertTrue(envelope.getValue("image_attached").jsonPrimitive.boolean)
            assertTrue(envelope.getValue("cached").jsonPrimitive.boolean)
            assertEquals(96, envelope.getValue("width").jsonPrimitive.int)
            assertNotNull(c.images.resolve(requireNotNull(result.image)))
            val history = listOf(ChatMessage(Role.TOOL, toolName = "view_chat_image", images = listOf(result.image!!)))
            val id = ChatImageInventory.from(history).single().id
            val full = ViewChatImageTool(c.images) { history }.execute(buildJsonObject { put("image_id", id) }, context)
            assertTrue(full.output, full.ok); assertEquals(id, Json.parseToJsonElement(full.output).jsonObject.getValue("source_image_id").jsonPrimitive.content)
            c.images.deleteOriginal(result.image!!.name)
            val fallback = ViewChatImageTool(c.images) { history }.execute(buildJsonObject { put("image_id", id) }, context)
            val fallbackJson = Json.parseToJsonElement(fallback.output).jsonObject
            assertTrue(fallback.output, fallback.ok)
            assertTrue(fallbackJson.getValue("degraded").jsonPrimitive.boolean)
            assertFalse(fallbackJson.getValue("original_available").jsonPrimitive.boolean)
            assertEquals(96, fallbackJson.getValue("attached_image").jsonObject.getValue("width").jsonPrimitive.int)
            assertEquals("remote_web", fallbackJson.getValue("origin").jsonPrimitive.content)
            assertEquals("saved_copy", fallbackJson.getValue("fetch_source").jsonPrimitive.content)
            assertFalse(fallbackJson.containsKey("http_status"))
            val zoom = ChatImageZoomTool(c.images) { history }.execute(buildJsonObject { put("image_id", id) }, context)
            assertTrue(zoom.output, zoom.ok)
            zoom.image?.let { c.images.delete(it.name) }
            val blocked = tool.execute(buildJsonObject { put("url", "http://127.0.0.1/private.png") }, context)
            assertFalse(blocked.ok); assertNull(blocked.image)
            assertEquals("blocked", Json.parseToJsonElement(blocked.output).jsonObject.getValue("error").jsonPrimitive.content)
            val disabled = tool.execute(buildJsonObject { put("url", url) }, context.copy(imageInputAllowed = false))
            assertFalse(disabled.ok); assertTrue(disabled.output.contains("images_disabled"))
        } finally { result.image?.let { c.images.delete(it.name) }; root.deleteRecursively() }
    }

    @Test fun imagePhasesReturnToThinkingWhenReasoningResumesAndAfterFastTools() {
        val call = ToolCallData("view", "view_chat_image", "{}")
        val rows = mutableStateOf(listOf(ChatMessage(Role.ASSISTANT, toolCalls = listOf(call))))
        val running = mutableStateOf(listOf(call))
        val thought = mutableStateOf("")
        val timing = mutableStateOf<ThinkingTiming?>(null)
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { HarnessTheme {
                Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
                    ChatActivity(rows.value, live = true, runningCalls = running.value, streamingThinking = thought.value,
                        thinkingTiming = timing.value, autoExpand = false)
                }
            } } }
            await("Looking at the image")
            SystemClock.sleep(1_050)
            assertNotNull(find("Looking at the image"))
            await("Thinking") // Even a still-running tool yields the header after two seconds.
            scenario.onActivity { rows.value += ChatMessage(Role.TOOL, "ok", toolCallId = "view", toolName = call.name); running.value = emptyList() }
            await("Looked at the image")
            scenario.onActivity { thought.value = "Compare what I saw"; timing.value = ThinkingTiming(ThinkingTiming.now()) }
            SystemClock.sleep(1_050)
            assertNotNull(find("Looked at the image")) // New reasoning must not shorten the notice.
            await("Thinking"); screenshot("image-inspection-back-to-thinking")
            val zoom = ToolCallData("zoom", "zoom_chat_image", "{}")
            scenario.onActivity {
                thought.value = ""; timing.value = null
                rows.value += listOf(ChatMessage(Role.ASSISTANT, toolCalls = listOf(zoom)), ChatMessage(Role.TOOL, "ok", toolCallId = "zoom", toolName = zoom.name))
            }
            await("Looked closely at the image")
            SystemClock.sleep(1_050)
            assertNotNull(find("Looked closely at the image"))
            await("Thinking")
            assertNull(find("Finished activity")); assertNull(find("Looked closely at the image"))
            val read = ToolCallData("read", "web_fetch", "{}")
            scenario.onActivity { rows.value += ChatMessage(Role.ASSISTANT, toolCalls = listOf(read)); running.value = listOf(read) }
            await("Reading a web page")
            SystemClock.sleep(1_050); assertNotNull(find("Reading a web page")); await("Thinking")
            scenario.onActivity { rows.value += ChatMessage(Role.TOOL, "ok", toolCallId = read.id, toolName = read.name); running.value = emptyList() }
            await("Read web pages")
            SystemClock.sleep(1_050); assertNotNull(find("Read web pages")); await("Thinking")
        }
    }

    @Test fun toolsUseTheInventorySuppliedBeforeNewInlinePicturesAreGenerated(): Unit = runBlocking {
        val original = picture()
        val bitmap = BitmapFactory.decodeByteArray(original, 0, original.size)
        val ref = try { c.images.saveCloseUp(bitmap) } finally { bitmap.recycle() }
        val root = File(c.appContext.cacheDir, "image-numbering-${System.nanoTime()}").apply { mkdirs() }
        val sid = c.sessions.createSession("Image numbering fixture", conversation = com.androidharness.app.data.ConversationConfig(com.androidharness.app.data.ConversationKind.CHAT))
        val history = listOf(ChatMessage(Role.USER, "Inspect my uploaded picture", images = listOf(ref)))
        val expected = ChatImageInventory.from(history).single().id
        var round = 0
        val provider = object : com.androidharness.app.llm.LlmProvider {
            override fun streamChat(config: com.androidharness.app.llm.ProviderConfig, apiKey: String, systemPrompt: String,
                messages: List<ChatMessage>, tools: List<com.androidharness.app.llm.ToolSchema>, options: com.androidharness.app.llm.RequestOptions) = kotlinx.coroutines.flow.flow {
                if (round++ == 0) {
                    assertTrue(systemPrompt.contains(expected))
                    emit(com.androidharness.app.llm.StreamEvent.TextDelta("![new link emitted alongside tools](https://not-a-real-image.invalid/new.png)"))
                    emit(com.androidharness.app.llm.StreamEvent.ToolCallBatch(listOf(ToolCallData("view", "view_chat_image", "{\"image_number\":1}"),
                        ToolCallData("zoom", "zoom_chat_image", "{\"image_number\":1}"),
                        ToolCallData("view-extra", "view_chat_image", "{\"image_number\":1}"))))
                    emit(com.androidharness.app.llm.StreamEvent.Done("tool_calls"))
                } else {
                    val current = messages.filter { it.role == Role.TOOL }.takeLast(if (round == 2) 3 else 1)
                    assertEquals(if (round == 2) 3 else 1, current.size)
                    val inventoryLines = systemPrompt.lines().filter { it.startsWith("{\"image_number\"") }
                    val first = Json.parseToJsonElement(inventoryLines.first()).jsonObject
                    assertEquals(expected, first.getValue("image_id").jsonPrimitive.content)
                    for (result in current) {
                        assertFalse(result.text, result.isError)
                        val json = Json.parseToJsonElement(result.text).jsonObject
                        assertEquals(expected, json.getValue("source_image_id").jsonPrimitive.content)
                        assertEquals(1, json.getValue("image_number").jsonPrimitive.int)
                        assertEquals(1, result.imageData.size)
                        val bytes = android.util.Base64.decode(result.imageData.single().base64, android.util.Base64.DEFAULT)
                        val actual = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        assertEquals(Color.RED, actual.getPixel(10, 10)); actual.recycle()
                    }
                    if (round == 2) {
                        assertTrue(systemPrompt.contains("not-a-real-image.invalid"))
                        emit(com.androidharness.app.llm.StreamEvent.ToolCallBatch(listOf(ToolCallData("view-later", "view_chat_image", "{\"image_number\":1}"))))
                        emit(com.androidharness.app.llm.StreamEvent.Done("tool_calls"))
                    } else {
                        val full = Json.parseToJsonElement(current.single().text).jsonObject.getValue("display_url").jsonPrimitive.content
                        val crop = messages.last { it.toolName == "zoom_chat_image" }.text.let { Json.parseToJsonElement(it).jsonObject.getValue("display_url").jsonPrimitive.content }
                        emit(com.androidharness.app.llm.StreamEvent.TextDelta("The same image kept its number across requests.\n\n![Full frame]($full)\n\n![Close-up]($crop)"))
                        emit(com.androidharness.app.llm.StreamEvent.Done("stop"))
                    }
                }
            }
        }
        try {
            val engine = com.androidharness.app.agent.AgentEngine({ provider }, c.registry, c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills)
            val events = engine.run(sid, "numbering", com.androidharness.app.llm.ProviderConfig("fixture", "Fixture", com.androidharness.app.llm.ProviderType.OPENAI_COMPAT,
                "https://example.invalid", "gpt-4o"), "", history, { com.androidharness.app.agent.PermissionMode.FULL_AUTO }, mutableSetOf(),
                FileFs(root), com.androidharness.app.llm.RequestOptions(), 100_000, com.androidharness.app.agent.AgentMode.ACT,
                conversation = com.androidharness.app.data.ConversationConfig(com.androidharness.app.data.ConversationKind.CHAT))
            val committed = events.toList().filterIsInstance<com.androidharness.app.agent.AgentEvent.AssistantCommitted>().last().message
            assertEquals(2, committed.images.size)
            assertTrue(committed.images.any { it.name == ref.name })
            assertTrue(committed.images.any { it.name != ref.name })
            assertEquals(3, round)
        } finally { c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }; c.images.delete(ref.name); root.deleteRecursively() }
    }

    @Test fun rehydratedLegacyLogoIsUsableOnItsFirstInspection(): Unit = runBlocking {
        val data = picture()
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
        val ref = try { c.images.saveCloseUp(bitmap) } finally { bitmap.recycle() }
        val root = File(c.appContext.cacheDir, "legacy-inspection-${System.nanoTime()}").apply { mkdirs() }
        val sid = c.sessions.createSession("Legacy delivery fixture", conversation = com.androidharness.app.data.ConversationConfig(com.androidharness.app.data.ConversationKind.CHAT))
        val prior = ChatMessage(Role.TOOL, """{"ok":false,"image_attached":false,"error":"not_in_current_context","image_id":"img_legacy_logo","caption":"A clean logo caption","origin":"tool_image","attached_bytes":0,"available_image":{"attachment_id":"img_legacy_logo","width":96,"height":64,"attached_bytes":123}}""",
            toolCallId = "old-view", toolName = "view_chat_image", isError = true, images = listOf(ref))
        val history = listOf(ChatMessage(Role.ASSISTANT, toolCalls = listOf(ToolCallData("old-view", "view_chat_image", "{}"))), prior,
            ChatMessage(Role.USER, "Inspect the logo again"))
        var round = 0
        val provider = object : com.androidharness.app.llm.LlmProvider {
            override fun streamChat(config: com.androidharness.app.llm.ProviderConfig, apiKey: String, systemPrompt: String, messages: List<ChatMessage>,
                tools: List<com.androidharness.app.llm.ToolSchema>, options: com.androidharness.app.llm.RequestOptions) = kotlinx.coroutines.flow.flow {
                assertTrue(systemPrompt.contains("A clean logo caption")); assertFalse(systemPrompt.contains("assistant image: {"))
                assertTrue(systemPrompt.contains("\"origin\":\"tool_image\""))
                if (round++ == 0) {
                    emit(com.androidharness.app.llm.StreamEvent.ToolCallBatch(listOf(
                        ToolCallData("first-view", "view_chat_image", "{\"image_id\":\"img_legacy_logo\"}"),
                        ToolCallData("first-zoom", "zoom_chat_image", "{\"image_id\":\"img_legacy_logo\"}"))))
                    emit(com.androidharness.app.llm.StreamEvent.Done("tool_calls"))
                } else {
                    for (result in messages.filter { it.toolCallId in setOf("first-view", "first-zoom") && it.role == Role.TOOL }) {
                        assertFalse(result.text, result.isError); assertEquals(1, result.imageData.size)
                        val body = Json.parseToJsonElement(result.text).jsonObject
                        assertTrue(body.getValue("ok").jsonPrimitive.boolean); assertTrue(body.getValue("image_attached").jsonPrimitive.boolean)
                        assertTrue(body.getValue("delivered").jsonPrimitive.boolean); assertTrue(body["attached_image"] is JsonObject)
                        assertEquals("A clean logo caption", body.getValue("caption").jsonPrimitive.content)
                    }
                    emit(com.androidharness.app.llm.StreamEvent.TextDelta("Both first inspections delivered the saved logo."))
                    emit(com.androidharness.app.llm.StreamEvent.Done("stop"))
                }
            }
        }
        val created = mutableListOf<String>()
        try {
            val engine = com.androidharness.app.agent.AgentEngine({ provider }, c.registry, c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills)
            engine.run(sid, "legacy", com.androidharness.app.llm.ProviderConfig("fixture", "Fixture", com.androidharness.app.llm.ProviderType.OPENAI_COMPAT,
                "https://example.invalid", "gpt-4o"), "", history, { com.androidharness.app.agent.PermissionMode.FULL_AUTO }, mutableSetOf(), FileFs(root),
                com.androidharness.app.llm.RequestOptions(), 100_000, com.androidharness.app.agent.AgentMode.ACT,
                conversation = com.androidharness.app.data.ConversationConfig(com.androidharness.app.data.ConversationKind.CHAT)).collect { event ->
                    if (event is com.androidharness.app.agent.AgentEvent.ToolFinished) event.result.image?.name?.let(created::add)
                }
            assertEquals(2, round)
        } finally { created.forEach(c.images::delete); c.images.delete(ref.name); c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }; root.deleteRecursively() }
    }

    @Test fun finalAnswerShowsTheSavedImageBelowItsTextAndSurvivesReload(): Unit = runBlocking {
        val data = picture()
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
        val ref = try { c.images.saveCloseUp(bitmap).copy(origin = "user_upload") } finally { bitmap.recycle() }
        val root = File(c.appContext.cacheDir, "final-image-${System.nanoTime()}").apply { mkdirs() }
        val sid = c.sessions.createSession("Final image fixture", conversation = com.androidharness.app.data.ConversationConfig(com.androidharness.app.data.ConversationKind.CHAT))
        val user = ChatMessage(Role.USER, "Show this picture after explaining it", images = listOf(ref))
        val url = ChatResponseImages.url(ChatResponseImages.id(ref))
        val answer = "The picture has a red left half and a blue right half.\n\n![The inspected picture]($url)"
        val store = androidx.lifecycle.ViewModelStore()
        val provider = object : com.androidharness.app.llm.LlmProvider {
            override fun streamChat(config: com.androidharness.app.llm.ProviderConfig, apiKey: String, systemPrompt: String, messages: List<ChatMessage>,
                tools: List<com.androidharness.app.llm.ToolSchema>, options: com.androidharness.app.llm.RequestOptions) = kotlinx.coroutines.flow.flow {
                assertTrue(systemPrompt.contains("Zoom is optional")); assertTrue(systemPrompt.contains(url))
                emit(com.androidharness.app.llm.StreamEvent.TextDelta(answer)); emit(com.androidharness.app.llm.StreamEvent.Done("stop"))
            }
        }
        try {
            c.sessions.addMessage(sid, user, "final-image")
            val engine = com.androidharness.app.agent.AgentEngine({ provider }, c.registry, c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills)
            engine.run(sid, "final-image", com.androidharness.app.llm.ProviderConfig("fixture", "Fixture", com.androidharness.app.llm.ProviderType.OPENAI_COMPAT,
                "https://example.invalid", "gpt-4o"), "", listOf(user), { com.androidharness.app.agent.PermissionMode.FULL_AUTO }, mutableSetOf(), FileFs(root),
                com.androidharness.app.llm.RequestOptions(), 100_000, com.androidharness.app.agent.AgentMode.ACT,
                conversation = com.androidharness.app.data.ConversationConfig(com.androidharness.app.data.ConversationKind.CHAT)).collect { event ->
                    if (event is com.androidharness.app.agent.AgentEvent.AssistantCommitted) c.sessions.addMessage(sid, event.message, "final-image")
                }
            val saved = c.sessions.messages(sid).last { it.role == Role.ASSISTANT }
            assertEquals(answer, saved.text); assertEquals(listOf(ref), saved.images)
            assertTrue(ChatResponseImages.available(listOf(saved)).containsKey(ChatResponseImages.id(ref)))
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { host ->
                    val vm = ChatViewModel(c, sid); store.put("chat", vm)
                    host.setContent { HarnessTheme { ChatScreen(vm, {}, { _, _ -> }, {}, {}, onOpenSubagent = {}) } }
                }
                await("The picture has a red left half and a blue right half.")
                awaitDescription("The inspected picture")
                val pictureBounds = android.graphics.Rect().also { requireNotNull(findDescription("The inspected picture")).getBoundsInScreen(it) }
                val textBounds = android.graphics.Rect().also { requireNotNull(find("The picture has a red left half and a blue right half.")).getBoundsInScreen(it) }
                assertTrue("Image follows the answer text", pictureBounds.top >= textBounds.bottom)
                assertTrue("The final image uses a readable size", pictureBounds.height() > 100 * c.appContext.resources.displayMetrics.density)
                screenshot("saved-image-below-final-answer")
                clickNode(requireNotNull(findDescription("The inspected picture"))); awaitDescription("Close image")
                clickNode(requireNotNull(findDescription("Close image")))
                scenario.onActivity { host -> host.setContent { HarnessTheme {
                    CompositionLocalProvider(LocalChatImagesEnabled provides false) {
                        com.androidharness.app.ui.chat.components.AssistantText(answer, chatImages = ChatResponseImages.available(listOf(saved)))
                    }
                } } }
                SystemClock.sleep(400)
                assertNull(findDescription("The inspected picture"))
                assertNull(find(url)); assertNull(find(answer))
            }
        } finally { instrumentation.runOnMainSync { store.clear() }; c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }; c.images.delete(ref.name); root.deleteRecursively() }
    }

    @Test fun expandedImageDetailsKeepBothCollapseControlsReachable() {
        val bitmap = Bitmap.createBitmap(600, 2400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        val ref = try { c.images.saveCloseUp(bitmap).copy(caption = "Tall inspection preview") } finally { bitmap.recycle() }
        val view = ToolCallData("panel-view", "view_chat_image", "{}")
        val zoom = ToolCallData("panel-zoom", "zoom_chat_image", "{}")
        val rows = listOf(ChatMessage(Role.ASSISTANT, toolCalls = listOf(view, zoom)),
            ChatMessage(Role.TOOL, "ok", toolCallId = view.id, toolName = view.name, images = listOf(ref)),
            ChatMessage(Role.TOOL, "ok", toolCallId = zoom.id, toolName = zoom.name, images = listOf(ref)))
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { HarnessTheme {
                    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                        androidx.compose.ui.unit.Density(androidx.compose.ui.platform.LocalDensity.current.density, 1.5f)) {
                        Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) { ChatActivity(rows) }
                    }
                } } }
                await("Thoughts"); clickText("Thoughts")
                await("Looked at the image"); clickText("Looked at the image")
                awaitDescription("Tall inspection preview")
                screenshot("expanded-image-details-bounded")
                val preview = requireNotNull(findDescription("Tall inspection preview"))
                val bounds = android.graphics.Rect().also { preview.getBoundsInScreen(it) }
                val density = c.appContext.resources.displayMetrics.density
                assertTrue("Bounded preview: $bounds", bounds.height() <= 122 * density)
                assertNotNull(find("Looked at the image")); assertNotNull(find("Thoughts"))
                assertTrue(preview.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                awaitDescription("Close image")
                clickNode(requireNotNull(findDescription("Close image")))
                await("Looked at the image"); clickText("Looked at the image")
                SystemClock.sleep(400); assertNull(findDescription("Tall inspection preview"))
                clickText("Looked closely at the image"); awaitDescription("Tall inspection preview")
                assertNotNull(find("Looked closely at the image")); clickText("Looked closely at the image")
                SystemClock.sleep(400); assertNull(findDescription("Tall inspection preview"))
                clickText("Thoughts"); SystemClock.sleep(400); assertNull(find("Looked at the image"))
            }
        } finally { c.images.delete(ref.name) }
    }

    @Test fun browserReadCapturesCssViewportAndFullPageWithoutWorkspaceWrites(): Unit = runBlocking {
        val server = ServerSocket(0)
        val html = """<!doctype html><meta name="viewport" content="width=device-width, initial-scale=1"><title>CSS mockup fixture</title>
            <style>body{margin:0;background:white;height:1800px} .red{background:#ff0000;width:300px;height:180px;margin:20px}
            .blue{position:absolute;top:1400px;background:#0000ff;width:300px;height:200px}</style>
            <div class="red">Rendered CSS product mockup</div><main>${"A readable article about this CSS interface. ".repeat(15)}</main><div class="blue">End of page</div>"""
        val worker = Thread {
            while (!server.isClosed) try { server.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader()
                val request = input.readLine().orEmpty()
                while (!input.readLine().isNullOrEmpty()) { }
                val longPage = if (request.contains("?long=1")) "<img loading=lazy src=/never style='position:absolute;top:9000px'>" else ""
                val bytes = (html + longPage).toByteArray()
                socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(bytes); flush() }
            } } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        val root = File(c.appContext.cacheDir, "page-capture-${System.nanoTime()}").apply { mkdirs() }
        val browser = BrowserController(c.appContext, c.images)
        val refs = mutableListOf<ImageRef>()
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { host ->
                    host.setContent { HarnessTheme { Text("Chat remains visible") } }
                    browser.bindReadCaptureHost(host.window.decorView as android.view.ViewGroup)
                }
                SystemClock.sleep(500)
                val tool = BrowserReadTool(browser)
                val url = "http://127.0.0.1:${server.localPort}/mockup"
                for ((mode, longPage) in listOf("viewport" to false, "full_page" to false, "full_page" to true)) {
                    val result = withTimeout(30_000) { tool.execute(buildJsonObject { put("url", url + if (longPage) "?long=1" else ""); put("screenshot", mode); put("images", true) }, ToolContext(FileFs(root), sessionId = "chat")) }
                    assertTrue(result.output, result.ok)
                    val ref = requireNotNull(result.image) { result.output }.also(refs::add)
                    assertTrue(result.output.contains("\"image_attached\":true"))
                    assertTrue(result.output, result.output.contains("\"truncated\":$longPage"))
                    val file = c.images.originalFile(ref.name).takeIf { it.isFile } ?: requireNotNull(c.images.savedImageFile(ref.name))
                    val evidence = File(c.appContext.getExternalFilesDir(null), "image-inspection-evidence").apply { mkdirs() }
                    file.copyTo(File(evidence, "css-$mode.png"), overwrite = true)
                    val bitmap = BitmapFactory.decodeFile(file.path)
                    var red = 0; var blue = 0
                    for (y in 0 until bitmap.height step 12) for (x in 0 until bitmap.width step 12) {
                        val color = bitmap.getPixel(x, y)
                        if (Color.red(color) > 220 && Color.blue(color) < 40) red++
                        if (Color.blue(color) > 220 && Color.red(color) < 40) blue++
                    }
                    bitmap.recycle()
                    assertTrue("The screenshot contains the CSS card", red > 100)
                    if (mode == "full_page") assertTrue("Full page includes content below the viewport", blue > 100) else assertEquals(0, blue)
                    assertFalse(File(root, ".harness/screenshots").exists())
                }
                scenario.onActivity { browser.unbindReadCaptureHost(it.window.decorView as android.view.ViewGroup) }
                val hidden = tool.execute(buildJsonObject { put("url", url); put("screenshot", "viewport") }, ToolContext(FileFs(root), sessionId = "chat"))
                assertNull(hidden.image); assertTrue(hidden.output.contains("\"image_attached\":false"))
                assertTrue(hidden.output.contains("capture_failed"))
                val disabled = tool.forImageInput { false }.execute(buildJsonObject { put("url", url); put("screenshot", "viewport") }, ToolContext(FileFs(root), sessionId = "chat"))
                assertNull(disabled.image); assertTrue(disabled.output.contains("images_disabled"))
            }
        } finally { browser.close(); refs.forEach { c.images.delete(it.name) }; root.deleteRecursively(); server.close(); worker.join(1_000) }
    }

    private fun clickText(text: String) {
        clickNode(requireNotNull(find(text)))
    }
    private fun clickNode(found: AccessibilityNodeInfo) {
        var node = found
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue("Clickable control", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun awaitDescription(text: String) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) { if (findDescription(text) != null) return; SystemClock.sleep(40) }
        screenshot("missing-image-description"); error("Missing description $text")
    }
    private fun findDescription(text: String) = findNode { it.contentDescription?.toString() == text }
    private fun find(text: String) = findNode { it.text?.toString() == text }
    private fun findNode(matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && matches(node)) return node
            for (i in 0 until node.childCount) visit(node.getChild(i))?.let { return it }
            return null
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun await(text: String) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) { if (find(text) != null) return; SystemClock.sleep(40) }
        screenshot("missing-image-state"); error("Missing $text")
    }
    private fun screenshot(name: String) {
        val dir = File(c.appContext.getExternalFilesDir(null), "image-inspection-evidence").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
}
