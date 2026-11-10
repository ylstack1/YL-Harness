package com.androidharness.app.ui.files

import com.androidharness.app.workspace.WorkspaceFs
import java.security.MessageDigest

internal data class EditorDiskSnapshot(val bytes: ByteArray?) {
    val version: String = bytes?.let {
        MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { b -> "%02x".format(b) }
    } ?: "missing"
    val text: String? get() = bytes?.let { EditorFileCodec.decode(it)?.text }
}

internal sealed interface EditorSaveResult {
    data class Saved(val snapshot: EditorDiskSnapshot, val previous: EditorDiskSnapshot) : EditorSaveResult
    data class Conflict(val current: EditorDiskSnapshot) : EditorSaveResult
}

/** Caller holds RunManager's workspace gate so an agent cannot start between check and write. */
internal fun saveEditorFile(fs: WorkspaceFs, path: String, expectedVersion: String, bytes: ByteArray): EditorSaveResult {
    val node = fs.resolve(path)
    check(!node.exists || node.isFile) { "This path is now a folder. Your edits have been kept." }
    check(!node.exists || node.length <= 16_000_000L) { "The saved file is now too large to compare. Your edits have been kept." }
    val current = EditorDiskSnapshot(if (node.exists) node.openInputStream()?.use { it.readBytes() }
        ?: error("Could not read the saved file. Your edits have been kept.") else null)
    if (current.version != expectedVersion) return EditorSaveResult.Conflict(current)
    node.writeBytes(bytes)
    return EditorSaveResult.Saved(EditorDiskSnapshot(bytes), current)
}
