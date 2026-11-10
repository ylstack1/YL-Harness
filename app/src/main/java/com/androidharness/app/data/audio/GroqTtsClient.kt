package com.androidharness.app.data.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Groq's OpenAI-compatible speech endpoint. The same Groq key used for Whisper
 * works here, so the app never asks for a second credential.
 */
object GroqTtsClient {
    /** Used until the account's model list is fetched. */
    const val DEFAULT_MODEL = "playai-tts"

    /**
     * Groq has no voices endpoint: the speech API says "list of voices can be
     * found" in the docs, so these catalogs are the documented values per
     * model family. Unknown models fall back to a free-form voice id in the UI.
     */
    val ORPHEUS_VOICES = listOf("autumn", "diana", "hannah", "austin", "daniel", "troy")

    /** Voices offered in Settings for PlayAI English. */
    val VOICES = listOf(
        "Fritz-PlayAI",
        "Arista-PlayAI",
        "Atlas-PlayAI",
        "Celeste-PlayAI",
        "Cheyenne-PlayAI",
        "Chip-PlayAI",
        "Deedee-PlayAI",
        "Eleanor-PlayAI",
        "Faye-PlayAI",
        "Jennifer-PlayAI",
        "Mason-PlayAI",
        "Mitch-PlayAI",
        "Nia-PlayAI",
        "Quinn-PlayAI",
        "Ruby-PlayAI",
        "Sadie-PlayAI",
        "Shelby-PlayAI",
        "Thunder-PlayAI",
        "Tiffany-PlayAI",
    )

    /** PlayAI rejects longer input; Orpheus is much stricter (200 chars). */
    const val MAX_INPUT_CHARS = 3_500
    const val ORPHEUS_MAX_INPUT_CHARS = 190

    /** TTS models in GET /openai/v1/models carry one of these in their id. */
    private val SPEECH_MODEL = Regex("tts|orpheus|playai", RegexOption.IGNORE_CASE)

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val urlPattern = Regex("""https?://[^\s"'<>]+""")

    /** Groq errors carry a machine code and, for model terms, a direct link. */
    class GroqApiException(
        override val message: String,
        val code: String? = null,
        val actionUrl: String? = null,
        val status: Int? = null,
    ) : IOException(message)

    internal fun apiError(body: String, status: Int): GroqApiException {
        val error = runCatching {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
        }.getOrNull()
        val message = error?.get("message")?.jsonPrimitive?.content
            ?: "Groq request failed (HTTP $status)"
        val code = runCatching { error?.get("code")?.jsonPrimitive?.content }.getOrNull()
        val url = urlPattern.find(message)?.value?.trimEnd('.', ',', ')', ']')
        return GroqApiException(message, code, url, status)
    }

    /**
     * Returns a cached MP3 for this exact voice/speed/text, generating it once.
     * Cache names hash the input so replaying a reply costs nothing.
     */
    suspend fun synthesizeCached(
        cacheDir: File,
        text: String,
        apiKey: String,
        model: String,
        voice: String,
        speed: Float,
    ): Result<File> = withContext(Dispatchers.IO) {
        val dir = File(cacheDir, "tts").apply { mkdirs() }
        // v2: older entries cached Groq's streaming WAV with 0xFFFFFFFF sizes,
        // which MediaPlayer cannot open.
        val key = sha256("v2|$model|$voice|$speed|$text")
        val file = File(dir, "$key.${responseFormat(model)}")
        if (file.exists() && file.length() > 0) return@withContext Result.success(file)
        synthesize(text, apiKey, model, voice, speed).mapCatching { bytes ->
            val temp = File(dir, "$key.part")
            temp.writeBytes(bytes)
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
            trimCache(dir)
            file
        }
    }

    suspend fun synthesize(
        text: String,
        apiKey: String,
        model: String,
        voice: String,
        speed: Float,
    ): Result<ByteArray> {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isBlank()) {
            return Result.failure(IllegalStateException("Groq API key is missing"))
        }
        if (text.isBlank()) {
            return Result.failure(IllegalArgumentException("Nothing to speak"))
        }
        // Groq rejects input without a letter or digit; table plumbing and
        // marker-only lines are filtered earlier, this is the last guard.
        if (text.none(Char::isLetterOrDigit)) {
            return Result.failure(IllegalArgumentException("Nothing to speak"))
        }
        val payload = buildJsonObject {
            put("model", model.ifBlank { DEFAULT_MODEL })
            put("input", text)
            put("voice", voice)
            put("response_format", responseFormat(model))
            put("speed", speed.coerceIn(0.5f, 2f))
        }.toString()
        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/speech")
            .header("Authorization", "Bearer $trimmedKey")
            .post(payload.toRequestBody(jsonMedia))
            .build()
        return try {
            execute(request).use { response ->
                if (!response.isSuccessful) {
                    Result.failure(apiError(response.body?.string().orEmpty(), response.code))
                } else {
                    val bytes = response.body?.bytes()
                    if (bytes == null || bytes.isEmpty()) {
                        Result.failure(IOException("Groq returned no audio"))
                    } else {
                        Result.success(if (responseFormat(model) == "wav") fixStreamingWav(bytes) else bytes)
                    }
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Lists the account's speech models so new ones (Orpheus today, whatever
     * replaces it later) appear without an app update. Voice ids still come
     * from [voicesFor] because Groq does not expose a voices endpoint.
     */
    suspend fun fetchSpeechModels(apiKey: String): Result<List<String>> {
        val trimmed = apiKey.trim()
        if (trimmed.isBlank()) return Result.failure(IllegalStateException("Groq API key is missing"))
        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/models")
            .header("Authorization", "Bearer $trimmed")
            .get()
            .build()
        return try {
            execute(request).use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Result.failure(apiError(body, response.code))
                } else {
                    val ids = runCatching {
                        json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
                            ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
                            .orEmpty()
                    }.getOrDefault(emptyList())
                    Result.success(ids.filter(::isSpeechModel).sorted())
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun isSpeechModel(id: String): Boolean = SPEECH_MODEL.containsMatchIn(id)

    /** Documented voices for a model family; empty means the UI asks for a custom id. */
    fun voicesFor(model: String): List<String> = when {
        model.contains("orpheus", true) -> ORPHEUS_VOICES
        model.contains("playai", true) && !model.contains("arabic", true) -> VOICES
        else -> emptyList()
    }

    fun defaultVoiceFor(model: String): String? = when {
        model.contains("orpheus", true) -> ORPHEUS_VOICES.first()
        model.contains("playai", true) && !model.contains("arabic", true) -> VOICES.first()
        else -> null
    }

    fun maxInputChars(model: String): Int =
        if (model.contains("orpheus", true)) ORPHEUS_MAX_INPUT_CHARS else MAX_INPUT_CHARS

    /** Orpheus only returns wav; PlayAI defaults to mp3. */
    fun responseFormat(model: String): String =
        if (model.contains("orpheus", true)) "wav" else "mp3"

    fun modelLabel(model: String): String = when {
        model.contains("orpheus", true) && model.contains("arabic", true) -> "Orpheus Arabic"
        model.contains("orpheus", true) -> "Orpheus English"
        model == "playai-tts" -> "PlayAI TTS"
        model == "playai-tts-arabic" -> "PlayAI Arabic"
        else -> model
    }

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = httpClient.newCall(request)
        continuation.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response)
                else response.close()
            }
        })
    }

    /**
     * Orpheus streams WAV and leaves the RIFF/data sizes at 0xFFFFFFFF. Android's
     * parser trusts those fields and refuses the file, so stamp the real sizes.
     * Other formats and already-valid headers pass through untouched.
     */
    internal fun fixStreamingWav(bytes: ByteArray): ByteArray {
        if (bytes.size < 44) return bytes
        fun tag(offset: Int) = String(bytes, offset, 4, Charsets.US_ASCII)
        if (tag(0) != "RIFF" || tag(8) != "WAVE") return bytes
        val out = bytes.copyOf()
        fun readU32(offset: Int): Long =
            (out[offset].toLong() and 0xFF) or
                ((out[offset + 1].toLong() and 0xFF) shl 8) or
                ((out[offset + 2].toLong() and 0xFF) shl 16) or
                ((out[offset + 3].toLong() and 0xFF) shl 24)
        fun writeU32(offset: Int, value: Long) {
            for (i in 0 until 4) out[offset + i] = ((value shr (8 * i)) and 0xFF).toByte()
        }
        var offset = 12
        var dataOffset = -1
        while (offset + 8 <= out.size) {
            val id = String(out, offset, 4, Charsets.US_ASCII)
            val size = readU32(offset + 4)
            if (id == "data") {
                dataOffset = offset
                break
            }
            if (size == 0xFFFFFFFFL) break
            val next = offset + 8 + size
            if (next <= offset || next > out.size) break
            offset = next.toInt()
        }
        if (dataOffset < 0) return out
        val riffSize = out.size - 8L
        val dataSize = out.size - dataOffset - 8L
        if (readU32(4) != riffSize) writeU32(4, riffSize)
        if (readU32(dataOffset + 4) != dataSize) writeU32(dataOffset + 4, dataSize)
        return out
    }

    private fun trimCache(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        if (files.size <= 200) return
        files.sortedBy { it.lastModified() }
            .take(files.size - 200)
            .forEach { runCatching { it.delete() } }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
