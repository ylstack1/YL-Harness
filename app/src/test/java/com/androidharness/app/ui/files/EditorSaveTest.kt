package com.androidharness.app.ui.files

import com.androidharness.app.workspace.FileFs
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class EditorSaveTest {
    @Test fun `conflict keeps both versions and a reviewed overwrite is checked again`() {
        val root = Files.createTempDirectory("editor-save").toFile()
        try {
            val file = root.resolve("test.kt").apply { writeText("original\n") }
            val loaded = EditorDiskSnapshot(file.readBytes())
            file.writeText("agent edit\n")
            val conflict = saveEditorFile(FileFs(root), file.name, loaded.version, "my draft\n".toByteArray()) as EditorSaveResult.Conflict
            assertEquals("agent edit\n", file.readText())
            assertEquals("agent edit\n", conflict.current.text)
            file.writeText("another edit\n")
            assertTrue(saveEditorFile(FileFs(root), file.name, conflict.current.version, "my draft\n".toByteArray()) is EditorSaveResult.Conflict)
            assertEquals("another edit\n", file.readText())
            val latest = EditorDiskSnapshot(file.readBytes())
            assertTrue(saveEditorFile(FileFs(root), file.name, latest.version, "my draft\n".toByteArray()) is EditorSaveResult.Saved)
            assertEquals("my draft\n", file.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun `deleted files require review before recreation and unchanged bytes preserve encoding`() {
        val root = Files.createTempDirectory("editor-save-delete").toFile()
        try {
            val file = root.resolve("test.kt").apply { writeBytes(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + "original\r\n".toByteArray()) }
            val loaded = EditorDiskSnapshot(file.readBytes())
            val bytes = EditorFileCodec.encode(requireNotNull(EditorFileCodec.decode(file.readBytes())), "draft\n")
            file.delete()
            assertTrue(saveEditorFile(FileFs(root), file.name, loaded.version, bytes) is EditorSaveResult.Conflict)
            assertFalse(file.exists())
            assertTrue(saveEditorFile(FileFs(root), file.name, "missing", bytes) is EditorSaveResult.Saved)
            assertArrayEquals(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + "draft\r\n".toByteArray(), file.readBytes())
        } finally { root.deleteRecursively() }
    }
}
