package com.androidharness.app.data.transfer

import java.io.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException

internal const val MAX_TRANSFER_FILE = 512L * 1024 * 1024
internal const val MAX_TRANSFER_TOTAL = 1024L * 1024 * 1024
internal const val MAX_TRANSFER_ENTRIES = 10_000
internal data class TransferEntry(val path: String, val directory: Boolean, val size: Long = 0, val modified: Long = 0)

internal object TransferPaths {
    fun relative(path: String): String {
        require(path.length <= 4096 && !path.startsWith('/') && '\\' !in path && path.none { it.isISOControl() }) { "Invalid transfer path." }
        require(path.isEmpty() || path.split('/').all { it.isNotEmpty() && it != "." && it != ".." && it.length <= 255 }) { "Path is outside the shared folder." }
        return path
    }
    fun ftp(cwd: String, argument: String): String {
        require(argument.none { it.isISOControl() } && '\\' !in argument) { "Invalid path." }
        val parts = if (argument.startsWith('/')) mutableListOf() else cwd.split('/').filter { it.isNotEmpty() }.toMutableList()
        argument.split('/').forEach { part -> when (part) {
            "", "." -> Unit
            ".." -> { require(parts.isNotEmpty()) { "Path is outside the shared folder." }; parts.removeAt(parts.lastIndex) }
            else -> parts += part
        } }
        return relative(parts.joinToString("/"))
    }
    fun skip(path: String) = path.split('/').any { it in setOf(".git", ".gradle", "node_modules", "build", ".idea") || it.startsWith(".harness-transfer-") || it.startsWith(".harness-sync-") }
}

internal interface TransferFiles : Closeable {
    fun stat(path: String): TransferEntry?
    fun list(path: String): List<TransferEntry>
    fun read(path: String): InputStream
    fun mkdir(path: String)
    /** Check both source and destination immediately before publishing the staged file. */
    fun writeAtomic(path: String, input: InputStream, beforeCommit: () -> Unit)
    override fun close() {}
}

/** Only ordinary files below the selected root; symlinks and special files are never served. */
internal class LocalTransferFiles(folder: File) : TransferFiles {
    val root = folder.canonicalFile.toPath()
    init { require(Files.isDirectory(root)) { "Choose an available local folder." } }
    fun file(path: String): File {
        TransferPaths.relative(path)
        var current = root
        if (path.isNotEmpty()) path.split('/').forEach { part ->
            current = current.resolve(part)
            require(!Files.isSymbolicLink(current)) { "Symbolic links are not shared." }
        }
        require(current.normalize().startsWith(root) && current.toFile().canonicalFile.toPath().startsWith(root)) { "Path is outside the shared folder." }
        return current.toFile()
    }
    override fun stat(path: String): TransferEntry? {
        val f = file(path)
        if (!Files.exists(f.toPath(), NOFOLLOW_LINKS)) return null
        require(f.isFile || f.isDirectory) { "Special files are not supported." }
        return TransferEntry(path, f.isDirectory, if (f.isFile) f.length() else 0, f.lastModified())
    }
    override fun list(path: String): List<TransferEntry> {
        val f = file(path); require(f.isDirectory) { "Folder unavailable." }
        return requireNotNull(f.listFiles()) { "Cannot list this folder." }.also { require(it.size <= MAX_TRANSFER_ENTRIES) { "Folder has too many entries." } }
            .filterNot { Files.isSymbolicLink(it.toPath()) || it.name.any(Char::isISOControl) || '\\' in it.name || it.name.startsWith(".harness-transfer-") }
            .filter { it.isDirectory || it.isFile }
            .mapNotNull { stat(if (path.isEmpty()) it.name else "$path/${it.name}") }.sortedBy { it.path }
    }
    override fun read(path: String): InputStream {
        require(stat(path)?.directory == false) { "File unavailable." }
        return FileInputStream(file(path))
    }
    override fun mkdir(path: String) { val f = file(path); require(f.isDirectory || f.mkdirs()) { "Could not create folder." } }
    override fun writeAtomic(path: String, input: InputStream, beforeCommit: () -> Unit) {
        require(path.isNotEmpty()) { "Choose a file." }
        mkdir(path.substringBeforeLast('/', ""))
        val target = file(path); require(!target.exists() || target.isFile) { "Cannot replace a folder or special file." }
        val temp = Files.createTempFile(target.parentFile.toPath(), ".harness-transfer-", "").toFile()
        try {
            FileOutputStream(temp).use { output -> copyTransfer(input, output); output.fd.sync() }
            beforeCommit(); file(path)
            if (target.exists()) runCatching { Files.getPosixFilePermissions(target.toPath()) }.getOrNull()?.let {
                Files.setPosixFilePermissions(temp.toPath(), it)
            }
            try { Files.move(temp.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp.toPath(), target.toPath(), REPLACE_EXISTING) }
        } finally { temp.delete() }
    }
}

internal fun copyTransfer(input: InputStream, output: OutputStream): Long {
    val buffer = ByteArray(64 * 1024); var count = 0L
    while (true) {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Transfer cancelled")
        val n = input.read(buffer); if (n < 0) break
        count += n; require(count <= MAX_TRANSFER_FILE) { "Files must be no larger than 512 MB." }
        output.write(buffer, 0, n)
    }
    return count
}

internal class LimitedTransferInput(input: InputStream) : FilterInputStream(input) {
    private var count = 0L
    private fun checked(size: Int): Int {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Transfer cancelled")
        if (size > 0) { count += size; require(count <= MAX_TRANSFER_FILE) { "Files must be no larger than 512 MB." } }
        return size
    }
    override fun read(): Int { val next = `in`.read(); checked(if (next < 0) 0 else 1); return next }
    override fun read(b: ByteArray, off: Int, len: Int): Int = checked(`in`.read(b, off, len))
}

internal fun TransferFiles.hash(path: String): String? {
    val entry = stat(path) ?: return null
    require(!entry.directory && entry.size <= MAX_TRANSFER_FILE) { "A file conflicts with a folder, or is larger than 512 MB." }
    val digest = MessageDigest.getInstance("SHA-256")
    read(path).use { input ->
        copyTransfer(input, object : OutputStream() {
            override fun write(b: Int) { digest.update(b.toByte()) }
            override fun write(b: ByteArray, off: Int, len: Int) { digest.update(b, off, len) }
        })
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal enum class SyncDirection { UPLOAD, DOWNLOAD }
internal data class SyncChange(val path: String, val size: Long, val sourceHash: String, val targetHash: String?)
internal data class SyncPlan(val direction: SyncDirection, val changes: List<SyncChange>, val directories: List<String>, val unchanged: Int) {
    val replacements get() = changes.count { it.targetHash != null }
    val bytes get() = changes.sumOf { it.size }
}
internal data class SyncResult(val copied: Int, val skipped: Int)

internal object SyncEngine {
    fun preview(local: TransferFiles, remote: TransferFiles, direction: SyncDirection, progress: (String) -> Unit = {}): SyncPlan {
        val source = if (direction == SyncDirection.UPLOAD) local else remote
        val target = if (direction == SyncDirection.UPLOAD) remote else local
        val changes = mutableListOf<SyncChange>(); val directories = mutableListOf<String>()
        val pending = ArrayDeque<String>().apply { add("") }; var entries = 0; var total = 0L; var unchanged = 0
        while (pending.isNotEmpty()) {
            val directory = pending.removeFirst()
            for (entry in source.list(directory)) {
                if (TransferPaths.skip(entry.path)) continue
                require(++entries <= MAX_TRANSFER_ENTRIES && entry.path.count { it == '/' } <= 32) { "Folder is too large or too deep to sync." }
                progress(entry.path)
                if (entry.directory) {
                    require(target.stat(entry.path)?.directory != false) { "A folder conflicts with a file: ${entry.path}" }
                    directories += entry.path; pending += entry.path
                } else {
                    total += entry.size; require(entry.size >= 0 && total <= MAX_TRANSFER_TOTAL) { "Sync at most 1 GB at a time." }
                    val hash = requireNotNull(source.hash(entry.path)) { "Source file disappeared. Preview again." }
                    val previous = target.hash(entry.path)
                    if (hash == previous) unchanged++ else changes += SyncChange(entry.path, entry.size, hash, previous)
                }
            }
        }
        return SyncPlan(direction, changes, directories, unchanged)
    }
    fun apply(local: TransferFiles, remote: TransferFiles, plan: SyncPlan, replace: Boolean, progress: (String) -> Unit = {}): SyncResult {
        val source = if (plan.direction == SyncDirection.UPLOAD) local else remote
        val target = if (plan.direction == SyncDirection.UPLOAD) remote else local
        var copied = 0; var skipped = 0
        plan.directories.forEach { target.mkdir(it) }
        plan.changes.forEach { change ->
            if (change.targetHash != null && !replace) { skipped++; return@forEach }
            progress(change.path)
            check(target.hash(change.path) == change.targetHash) { "Destination changed: ${change.path}. Preview again." }
            val digest = MessageDigest.getInstance("SHA-256")
            source.read(change.path).use { raw ->
                val input = java.security.DigestInputStream(raw, digest)
                target.writeAtomic(change.path, input) {
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    check(actual == change.sourceHash) { "Source changed: ${change.path}. Preview again." }
                    check(target.hash(change.path) == change.targetHash) { "Destination changed: ${change.path}. Preview again." }
                }
            }
            copied++
        }
        return SyncResult(copied, skipped)
    }
}
