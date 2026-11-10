package com.androidharness.app.ui.files

import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class EditorDraftStoreTest {
    private val key = EditorDraftStore.key("chat", "project", "/workspace", "code.kt")
    private fun draft(text: String = "unsaved\n") = EditorDraft(text = text, baseline = "saved\n", saved = "saved\n",
        diskVersion = "a".repeat(64), charset = "UTF-16LE", bom = true, eol = "\r\n",
        missingTrailingNewline = false, editing = true)

    private fun withStore(block: suspend (File, EditorDraftStore) -> Unit): Unit = runBlocking {
        val root = kotlin.io.path.createTempDirectory("editor-drafts").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try { block(root, EditorDraftStore(root, scope, debounceMillis = 10_000)) }
        finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun newStoreRecoversTheCompleteDraftAndOriginalEncoding() = withStore { root, store ->
        val value = draft("unsaved Ω\n")
        store.submit(key, value); store.flush(key)
        val restored = requireNotNull(EditorDraftStore(root).read(key))
        assertEquals(value, restored)
        val bytes = EditorFileCodec.encode(restored.decoded(), restored.text)
        assertEquals("\uFEFFunsaved Ω\r\n", bytes.toString(Charsets.UTF_16LE))
        File(root, "$key.json.tmp").writeText("interrupted write")
        assertEquals(value, EditorDraftStore(root).read(key))
    }

    @Test fun latestWriteAndDiscardWinOverQueuedSnapshots() = withStore { root, store ->
        repeat(50) { store.submit(key, draft("draft $it")) }
        assertEquals("draft 49", store.read(key)?.text)
        store.flush(key)
        assertEquals("draft 49", EditorDraftStore(root).read(key)?.text)
        store.submit(key, draft("do not resurrect"))
        store.submit(key, null, immediate = true); store.flush(key)
        assertNull(EditorDraftStore(root).read(key))
        assertEquals(EditorDraftStatus.SAVED, store.status(key).first())
    }

    @Test fun keysIsolateFilesChatsAndWorkspacesWithoutReadablePaths() {
        assertEquals(key, EditorDraftStore.key("chat", "project", "/workspace", "./code.kt"))
        assertNotEquals(key, EditorDraftStore.key("another chat", "project", "/workspace", "code.kt"))
        assertNotEquals(key, EditorDraftStore.key("chat", "project", "/other", "code.kt"))
        assertNotEquals(key, EditorDraftStore.key("chat", "project", "/workspace", "other.kt"))
        assertTrue(key.matches(Regex("[a-f0-9]{64}")))
    }

    @Test fun corruptionIsReportedAndTheRecoveryFileIsPreserved() = withStore { root, store ->
        val file = File(root, "$key.json").apply { writeText("truncated") }
        try { store.read(key); fail("Corrupt recovery must not be silently discarded") }
        catch (_: kotlinx.serialization.SerializationException) { }
        assertEquals("truncated", file.readText())
    }

    @Test fun failedWritesStayVisibleAndRetainTheInMemoryDraft() = withStore { root, _ ->
        val blocked = File(root, "not-a-directory").apply { writeText("keep") }
        val store = EditorDraftStore(blocked)
        store.submit(key, draft()); store.flush(key)
        assertEquals(EditorDraftStatus.FAILED, store.status(key).first())
        assertEquals(draft(), store.read(key))
        assertEquals("keep", blocked.readText())
    }
}
