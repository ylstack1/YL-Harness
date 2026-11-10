package com.androidharness.app.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.agent.*
import com.androidharness.app.core.*
import com.androidharness.app.data.*
import com.androidharness.app.llm.*
import com.androidharness.app.tools.*
import com.androidharness.app.workspace.FileFs
import com.androidharness.app.workspace.ChatWorkspaceFs
import com.androidharness.app.workspace.ChatWorkspaceBinding
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import androidx.lifecycle.ViewModelStore
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ChatImageZoomDeviceTest {
    private val c get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HarnessApp).container

    private fun picture(): File {
        val file = File(c.appContext.cacheDir, "zoom-source-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(3200, 1600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawRect(2380f, 780f, 2420f, 820f, Paint().apply { color = Color.BLACK })
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        return file
    }

    private val area = buildJsonObject { put("left", 625); put("top", 250); put("right", 875); put("bottom", 750) }

    @Test fun composerKeepsOriginalsOnlyForChatEvenWhileOpeningAnExistingChat() = runBlocking {
        val original = picture()
        val shared = File(c.appContext.cacheDir, "shared/zoom-${System.nanoTime()}.png").apply {
            parentFile?.mkdirs(); writeBytes(original.readBytes())
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(c.appContext, c.appContext.packageName + ".update", shared)
        val sid = c.sessions.createSession("Image composer", conversation = ConversationConfig(ConversationKind.CHAT))
        val store = ViewModelStore()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var chat: ChatViewModel
        lateinit var code: ChatViewModel
        try {
            instrumentation.runOnMainSync {
                chat = ChatViewModel(c, sid); store.put("chat", chat)
                chat.attachImage(uri)
                code = ChatViewModel(c, null, ConversationKind.CODE); store.put("code", code)
                code.attachImage(uri)
            }
            val chatState = withTimeout(10_000) { chat.state.first { it.attachments.isNotEmpty() || it.attachmentImports.any { item -> item.status != AttachmentImportStatus.READING } } }
            val codeState = withTimeout(10_000) { code.state.first { it.attachments.isNotEmpty() || it.attachmentImports.any { item -> item.status != AttachmentImportStatus.READING } } }
            assertEquals(chatState.attachmentImports.toString(), 1, chatState.attachments.size)
            assertEquals(codeState.attachmentImports.toString(), 1, codeState.attachments.size)
            val chatName = chatState.attachments.single()
            val codeName = codeState.attachments.single()
            assertTrue(c.images.originalFile(chatName).isFile)
            assertFalse(c.images.originalFile(codeName).exists())
            instrumentation.runOnMainSync { chat.removeAttachment(0); code.removeAttachment(0) }
            assertFalse(c.images.originalFile(chatName).exists())
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            shared.delete(); original.delete()
        }
        Unit
    }

    @Test fun originalDetailIsCroppedAndFallbackIsHonest() = runBlocking {
        val original = picture()
        val stored = requireNotNull(c.images.import(Uri.fromFile(original), keepOriginal = true))
        val legacy = requireNotNull(c.images.import(Uri.fromFile(original)))
        val root = File(c.appContext.cacheDir, "zoom-workspace-${System.nanoTime()}").apply { mkdirs() }
        val ctx = ToolContext(FileFs(root), sessionId = "only-this-chat")
        val image = ImageRef(stored.file.name, stored.mime)
        val old = ImageRef(legacy.file.name, legacy.mime)
        try {
            assertTrue(c.images.originalFile(image.name).isFile)
            val preview = BitmapFactory.decodeFile(stored.file.path)
            assertTrue(preview.width < 3200)
            preview.recycle()
            val tool = ChatImageZoomTool(c.images) { listOf(ChatMessage(Role.USER, images = listOf(image))) }
            val cropped = tool.execute(area, ctx)
            assertTrue(cropped.output, cropped.ok)
            assertTrue(cropped.output.contains("original image"))
            val result = requireNotNull(cropped.image)
            try {
                val bitmap = BitmapFactory.decodeFile(File(c.images.imagesDir, result.name).path)
                assertEquals(800, bitmap.width)
                assertEquals(800, bitmap.height)
                assertEquals(Color.BLACK, bitmap.getPixel(400, 400))
                assertEquals(Color.WHITE, bitmap.getPixel(100, 100))
                bitmap.recycle()
            } finally { c.images.delete(result.name) }
            val legacyTool = ChatImageZoomTool(c.images) { listOf(ChatMessage(Role.USER, images = listOf(old))) }
            val fallback = legacyTool.execute(area, ctx)
            assertTrue(fallback.output, fallback.ok)
            assertTrue(fallback.output.contains("original is unavailable"))
            fallback.image?.let { c.images.delete(it.name) }
            assertFalse(tool.execute(buildJsonObject { put("left", 800); put("top", 0); put("right", 400); put("bottom", 1000) }, ctx).ok)
            assertFalse(tool.execute(buildJsonObject { put("image_number", 2); put("left", 0); put("top", 0); put("right", 1000); put("bottom", 1000) }, ctx).ok)
            assertFalse(tool.execute(area, ToolContext(FileFs(root))).ok)
            val assistant = ChatImageZoomTool(c.images) { listOf(ChatMessage(Role.ASSISTANT, images = listOf(image))) }.execute(area, ctx)
            assertTrue(assistant.output, assistant.ok)
            assistant.image?.let { c.images.delete(it.name) }
        } finally {
            c.images.delete(image.name); c.images.delete(old.name)
            assertFalse(c.images.originalFile(image.name).exists())
            original.delete(); root.deleteRecursively()
        }
        Unit
    }

    @Test fun smallGifCanBeInspectedWhenRegionDecodingIsUnsupported() = runBlocking {
        val source = File(c.appContext.cacheDir, "zoom-${System.nanoTime()}.gif")
        source.writeBytes(android.util.Base64.decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7", android.util.Base64.DEFAULT))
        val image = requireNotNull(c.images.import(Uri.fromFile(source), keepOriginal = true))
        val root = File(c.appContext.cacheDir, "zoom-gif-${System.nanoTime()}").apply { mkdirs() }
        try {
            val tool = ChatImageZoomTool(c.images) { listOf(ChatMessage(Role.USER, images = listOf(ImageRef(image.file.name, image.mime)))) }
            val result = tool.execute(buildJsonObject { put("left", 0); put("top", 0); put("right", 1000); put("bottom", 1000) }, ToolContext(FileFs(root), sessionId = "chat"))
            assertTrue(result.output, result.ok)
            result.image?.let { c.images.delete(it.name) }
        } finally { c.images.delete(image.file.name); source.delete(); root.deleteRecursively() }
        Unit
    }

    @Test fun assistantImageUsesTheFullCachedRemotePicture() = runBlocking {
        val source = picture()
        val url = "https://example.com/chat-zoom-fixture.png"
        val key = java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val cache = File(c.appContext.cacheDir, "remote-images/$key.img").apply { parentFile?.mkdirs(); writeBytes(source.readBytes()) }
        val root = File(c.appContext.cacheDir, "zoom-remote-${System.nanoTime()}").apply { mkdirs() }
        try {
            val tool = ChatImageZoomTool(c.images) { listOf(ChatMessage(Role.ASSISTANT, "![shown image]($url)")) }
            val result = tool.execute(area, ToolContext(FileFs(root), sessionId = "this-chat"))
            assertTrue(result.output, result.ok)
            assertTrue(result.output.contains("original image"))
            val ref = requireNotNull(result.image)
            try {
                val crop = BitmapFactory.decodeFile(File(c.images.imagesDir, ref.name).path)
                assertEquals(800, crop.width)
                assertEquals(Color.BLACK, crop.getPixel(400, 400))
                crop.recycle()
            } finally { c.images.delete(ref.name) }
        } finally { cache.delete(); source.delete(); root.deleteRecursively() }
        Unit
    }

    @Test fun deletingChatsRemovesOriginalOnlyAfterLastReference() = runBlocking {
        val source = picture()
        val stored = requireNotNull(c.images.import(Uri.fromFile(source), keepOriginal = true))
        val image = ImageRef(stored.file.name, stored.mime)
        val first = c.sessions.createSession("Zoom original first")
        val second = c.sessions.createSession("Zoom original second")
        try {
            c.sessions.addMessage(first, ChatMessage(Role.USER, "First", images = listOf(image)))
            c.sessions.addMessage(second, ChatMessage(Role.USER, "Second", images = listOf(image)))
            c.sessions.session(first)?.let { c.sessions.deleteSession(it) }
            assertTrue(c.images.originalFile(image.name).isFile)
            c.sessions.session(second)?.let { c.sessions.deleteSession(it) }
            assertFalse(c.images.originalFile(image.name).exists())
        } finally {
            c.sessions.session(first)?.let { c.sessions.deleteSession(it) }
            c.sessions.session(second)?.let { c.sessions.deleteSession(it) }
            c.images.delete(image.name); source.delete()
        }
        Unit
    }

    @Test fun providerRejectingImagesDisablesZoomForTheRetry() = runBlocking {
        val original = picture()
        val stored = requireNotNull(c.images.import(Uri.fromFile(original), keepOriginal = true))
        val root = File(c.appContext.cacheDir, "zoom-rejected-${System.nanoTime()}").apply { mkdirs() }
        val sid = c.sessions.createSession("Zoom rejected fixture")
        val calls = mutableListOf<Set<String>>()
        val prompts = mutableListOf<String>()
        val provider = object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
                messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                calls += tools.map { it.name }.toSet(); prompts += systemPrompt
                if (calls.size == 1) emit(StreamEvent.Failure("image input not supported", code = 400))
                else {
                    assertTrue(messages.all { it.imageData.isEmpty() })
                    emit(StreamEvent.TextDelta("I cannot inspect the image.")); emit(StreamEvent.Done("stop"))
                }
            }
        }
        val engine = AgentEngine({ provider }, ToolRegistry(emptyList()), c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills)
        val config = ProviderConfig("fixture", "Fixture", ProviderType.OPENAI_COMPAT, "https://example.invalid", "gpt-4o")
        try {
            val events = engine.run(sid, "zoom-rejected-turn", config, "", listOf(ChatMessage(Role.USER, "Look", images = listOf(ImageRef(stored.file.name, stored.mime)))),
                { PermissionMode.FULL_AUTO }, mutableSetOf(), FileFs(root), RequestOptions(), 100_000, AgentMode.ACT,
                maxIterations = 4, repoMapEnabled = false, conversation = ConversationConfig(ConversationKind.CHAT), searchDepth = SearchDepth.OFF).toList()
            assertFalse(events.toString(), events.any { it is AgentEvent.Error })
            assertEquals(2, calls.size)
            assertTrue(calls.first().contains("zoom_chat_image"))
            assertFalse(calls.last().contains("zoom_chat_image"))
            assertTrue(prompts.last().contains("Image input was rejected"))
        } finally {
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            c.images.delete(stored.file.name); original.delete(); root.deleteRecursively()
        }
        Unit
    }

    @Test fun modelReceivesTheCloseUpOnlyInChatAndOnlyWithVision() = runBlocking {
        val original = picture()
        val stored = requireNotNull(c.images.import(Uri.fromFile(original), keepOriginal = true))
        val root = File(c.appContext.cacheDir, "zoom-engine-${System.nanoTime()}").apply { mkdirs() }
        val sid = c.sessions.createSession("Zoom fixture")
        val requests = mutableListOf<List<ChatMessage>>()
        val prompts = mutableListOf<String>()
        val names = mutableListOf<Set<String>>()
        val emitted = mutableListOf<AgentEvent>()
        val provider = object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
                messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                requests += messages.toList(); prompts += systemPrompt; names += tools.map { it.name }.toSet()
                if (requests.size == 1) {
                    emit(StreamEvent.ToolCallReady(ToolCallData("zoom-1", "zoom_chat_image", area.toString())))
                    emit(StreamEvent.Done("tool_calls"))
                } else { emit(StreamEvent.TextDelta("I inspected the detail.")); emit(StreamEvent.Done("stop")) }
            }
        }
        val engine = AgentEngine({ provider }, ToolRegistry(emptyList()), c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills)
        val config = ProviderConfig("fixture", "Fixture", ProviderType.OPENAI_COMPAT, "https://example.invalid", "gpt-4o")
        try {
            engine.run(sid, "zoom-turn", config, "", listOf(ChatMessage(Role.USER, "Read the small detail", images = listOf(ImageRef(stored.file.name, stored.mime)))),
                { PermissionMode.FULL_AUTO }, mutableSetOf(), ChatWorkspaceFs("fixture", listOf(ChatWorkspaceBinding("fixture", "Read only", false, FileFs(root)))), RequestOptions(), 100_000, AgentMode.ACT,
                maxIterations = 5, repoMapEnabled = false, conversation = ConversationConfig(ConversationKind.CHAT), searchDepth = SearchDepth.OFF)
                .collect { emitted += it }
            assertFalse(emitted.toString(), emitted.any { it is AgentEvent.Error || it is AgentEvent.ApprovalNeeded })
            assertEquals(2, requests.size)
            assertTrue(names.first().contains("zoom_chat_image"))
            assertTrue(prompts.first().contains("\"image_number\":1"))
            assertTrue(prompts.first().contains("\"origin\":\"user_upload\""))
            val toolResult = requests.last().single { it.role == Role.TOOL && it.toolName == "zoom_chat_image" }
            assertFalse(toolResult.isError)
            assertEquals(1, toolResult.imageData.size)
            val bytes = android.util.Base64.decode(toolResult.imageData.single().base64, android.util.Base64.DEFAULT)
            val crop = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertEquals(800, crop.width)
            assertEquals(Color.BLACK, crop.getPixel(400, 400))
            crop.recycle()
            assertFalse(emitted.any { it is AgentEvent.UserMessageInjected })
            emitted.filterIsInstance<AgentEvent.ToolFinished>().forEach { it.result.image?.let { ref -> c.images.delete(ref.name) } }

            suspend fun namesFor(kind: ConversationKind, model: String): Set<String> {
                var tools = emptySet<String>()
                val fixture = object : LlmProvider {
                    override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
                        messages: List<ChatMessage>, schemas: List<ToolSchema>, options: RequestOptions) = flow {
                        tools = schemas.map { it.name }.toSet(); emit(StreamEvent.TextDelta("Done")); emit(StreamEvent.Done("stop"))
                    }
                }
                val e = AgentEngine({ fixture }, ToolRegistry(emptyList()), c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills)
                e.run(sid, "other-turn", config.copy(model = model), "", listOf(ChatMessage(Role.USER, "Hi")),
                    { PermissionMode.FULL_AUTO }, mutableSetOf(), FileFs(root), RequestOptions(), 100_000, AgentMode.ACT,
                    maxIterations = 2, repoMapEnabled = false, conversation = ConversationConfig(kind), searchDepth = SearchDepth.OFF).toList()
                return tools
            }
            assertFalse(namesFor(ConversationKind.CODE, "gpt-4o").contains("zoom_chat_image"))
            assertFalse(namesFor(ConversationKind.CHAT, "deepseek-chat").contains("zoom_chat_image"))
        } finally {
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            c.images.delete(stored.file.name); original.delete(); root.deleteRecursively()
        }
    }
}
