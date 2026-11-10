package com.ylstack1.ylharness.local

import com.ylstack1.ylharness.core.ChatMessage
import com.ylstack1.ylharness.core.Role
import com.ylstack1.ylharness.llm.LlmProvider
import com.ylstack1.ylharness.llm.ProviderConfig
import com.ylstack1.ylharness.llm.RequestOptions
import com.ylstack1.ylharness.llm.StreamEvent
import com.ylstack1.ylharness.llm.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

class LocalModelProvider(private val manager: LocalModelManager,
    private val agentContextEnabled: suspend () -> Boolean = { false }) : LlmProvider {
    override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
        messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions): Flow<StreamEvent> = flow {
        try {
            val id = config.id.removePrefix(LocalModelCatalog.PROVIDER_PREFIX)
            check(config.model == config.id) { "Choose this provider's installed model. Other model IDs cannot run locally." }
            val mode = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { manager.limits(id).thinkingMode }
            val requested = options.resolveThinking(config)
            val thinking = requested.copy(level = if (mode.enabled(requested.level != com.ylstack1.ylharness.agent.ThinkingLevel.OFF)) com.ylstack1.ylharness.agent.ThinkingLevel.HIGH else com.ylstack1.ylharness.agent.ThinkingLevel.OFF)
            emit(StreamEvent.ThinkingConfigured(thinking.detail))
            val enabled = agentContextEnabled()
            val chatMessages = if (enabled) messages else LocalChatCodec.plainChatMessages(messages)
            val chatTools = if (enabled) tools else emptyList()
            val request = LocalChatCodec.request(systemPrompt, chatMessages, chatTools,
                thinking.level != com.ylstack1.ylharness.agent.ThinkingLevel.OFF, includeAgentContext = enabled, thinkingMode = mode)
            val images = chatMessages.flatMap { it.imageData }.map {
                android.util.Base64.decode(it.base64, android.util.Base64.DEFAULT)
            }.toTypedArray()
            val reasoning = LocalReasoningStream()
            val result = manager.generateChat(id, request.toByteArray(Charsets.UTF_8), images, options.maxOutputTokens, options.cacheKey) { bytes ->
                for (event in reasoning.events(bytes.toString(Charsets.UTF_8))) emit(event)
            }
            for (event in reasoning.finish()) emit(event)
            for (event in LocalChatCodec.finish(result, chatTools)) emit(event)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(StreamEvent.Failure(e.message ?: "Local inference failed"))
        } catch (_: UnsatisfiedLinkError) {
            emit(StreamEvent.Failure("Local inference is unavailable for this device architecture."))
        }
    }
}

internal class Utf8TokenDecoder {
    private val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pending = ByteArray(0)
    fun append(bytes: ByteArray): String = decode(bytes, false)
    fun finish(): String = decode(ByteArray(0), true)
    private fun decode(bytes: ByteArray, end: Boolean): String {
        val input = ByteBuffer.wrap(pending + bytes)
        val output = CharBuffer.allocate(input.remaining() + 2)
        decoder.decode(input, output, end)
        pending = ByteArray(input.remaining()).also { input.get(it) }
        if (end) decoder.flush(output)
        output.flip()
        return output.toString()
    }
}
