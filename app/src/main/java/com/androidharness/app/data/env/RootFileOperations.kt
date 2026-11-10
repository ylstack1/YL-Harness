package com.androidharness.app.data.env

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.CodingErrorAction
import java.nio.file.Files

@Serializable
data class RootFileEntry(val name: String, val path: String, val directory: Boolean, val size: Long)
@Serializable
data class RootDirectory(val path: String, val entries: List<RootFileEntry>, val nextOffset: Int? = null)
@Serializable
data class RootText(val path: String, val text: String, val hash: String)

internal interface RootFileClient {
    suspend fun rootList(path: String, offset: Int): String
    suspend fun rootRead(path: String): String
    suspend fun rootWrite(path: String, expectedHash: String, text: String): String
}

/** Runs in the privileged service. This API is deliberately unavailable to the ADB shell UID. */
internal object RootFileOperations {
    private const val LIMIT = 64 * 1024
    private fun requireRoot() { check(android.os.Process.myUid() == 0) { "Root is required to browse protected files." } }
    private fun target(path: String): File {
        requireRoot()
        require(path.startsWith('/') && !path.contains('\u0000')) { "An absolute device path is required." }
        return File(path).canonicalFile
    }
    private fun hash(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun list(path: String, offset: Int): String {
        val dir = target(path)
        require(dir.isDirectory) { "Not a directory: $path" }
        val all = Files.newDirectoryStream(dir.toPath()).use { stream -> stream.map { it.toFile() }
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }) }
        val start = offset.coerceIn(0, all.size)
        var count = minOf(200, all.size - start)
        while (true) {
            val page = all.drop(start).take(count)
            val encoded = Json.encodeToString(RootDirectory.serializer(), RootDirectory(dir.path,
                page.map { RootFileEntry(it.name, it.path, it.isDirectory, it.length()) },
                (start + page.size).takeIf { it < all.size }))
            if (encoded.length < 300_000 || count <= 1) return encoded
            count /= 2
        }
    }
    fun read(path: String): String {
        val file = target(path)
        require(file.isFile) { "Only regular text files can be opened." }
        require(file.length() <= LIMIT) { "This file is larger than the 64 KB editor limit." }
        val bytes = file.inputStream().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (out.size() <= LIMIT) { val count = input.read(buffer, 0, minOf(buffer.size, LIMIT + 1 - out.size())); if (count < 0) break; out.write(buffer, 0, count) }
            out.toByteArray()
        }
        require(bytes.size <= LIMIT && !bytes.contains(0)) { "This file is binary or too large for the text editor." }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        return Json.encodeToString(RootText.serializer(), RootText(file.path, text, hash(bytes)))
    }
    fun write(path: String, expectedHash: String, text: String): String {
        val file = target(path)
        require(file.isFile) { "Only an existing regular text file can be edited." }
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= LIMIT && !bytes.contains(0)) { "Text must be UTF-8 and at most 64 KB." }
        java.io.RandomAccessFile(file, "rw").use { handle -> handle.channel.lock().use {
            require(handle.length() <= LIMIT) { "The file changed; reopen it before saving." }
            val before = ByteArray(handle.length().toInt()); handle.readFully(before)
            require(hash(before) == expectedHash) { "The file changed outside the editor. Reopen it before saving." }
            // Write the existing inode so its ownership, permissions and labels survive.
            handle.seek(0); handle.write(bytes); handle.setLength(bytes.size.toLong()); handle.fd.sync()
        } }
        return Json.encodeToString(RootText.serializer(), RootText(file.path, text, hash(bytes)))
    }
}
