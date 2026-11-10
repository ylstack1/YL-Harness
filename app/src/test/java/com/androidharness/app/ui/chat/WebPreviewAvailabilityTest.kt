package com.androidharness.app.ui.chat

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.workspace.FileFs
import com.androidharness.app.workspace.WorkspaceFs
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class WebPreviewAvailabilityTest {
    @Test fun onlySupportedWebLinksEnableTheShortcut() {
        listOf("See [Site](https://example.com/page)", "http://localhost:5173/app", "localhost:3000/", "127.0.0.1:8080/index.html")
            .forEach { assertTrue(it, hasPreviewLink(it)) }
        listOf("ordinary chat", "javascript:alert(1)", "file:///etc/hosts", "http://", "http://localhost:99999")
            .forEach { assertFalse(it, hasPreviewLink(it)) }
    }

    @Test fun savedAndStreamingLinksAppearImmediatelyWithoutRescanningFilesPerToken(): Unit = runBlocking {
        val root = kotlin.io.path.createTempDirectory("preview-links").toFile()
        val walks = AtomicInteger()
        val fs = object : WorkspaceFs by FileFs(root) {
            override fun walk(path: String) = FileFs(root).walk(path).also { walks.incrementAndGet() }
        }
        val input = MutableStateFlow(WebPreviewInput(emptyList(), null, emptySet()))
        val available = MutableStateFlow(false)
        val job = launch { webPreviewAvailability(flowOf(fs), input).collect { available.value = it } }
        try {
            withTimeout(3000) { while (walks.get() == 0) delay(10) }
            val before = walks.get()
            input.value = input.value.copy(streamingText = "Open http://localhost:5173")
            withTimeout(1000) { available.first { it } }
            repeat(20) { input.value = input.value.copy(streamingText = "Open http://localhost:5173 " + "x".repeat(it)); delay(5) }
            delay(300)
            assertEquals(before, walks.get())
            input.value = input.value.copy(streamingText = null)
            withTimeout(1000) { available.first { !it } }
            input.value = input.value.copy(messages = listOf(ChatMessage(role = Role.TOOL, text = "Visit https://example.com")))
            withTimeout(1000) { available.first { it } }
        } finally { job.cancelAndJoin(); root.deleteRecursively() }
    }

    @Test fun actualHtmlFilesAppearAndDisappearAfterEditsAndResumeRefreshes(): Unit = runBlocking {
        val root = kotlin.io.path.createTempDirectory("preview-files").toFile()
        val fs = MutableStateFlow<WorkspaceFs?>(FileFs(root))
        val input = MutableStateFlow(WebPreviewInput(emptyList(), null, emptySet()))
        val refresh = MutableStateFlow(0L)
        val available = MutableStateFlow(false)
        val job = launch { webPreviewAvailability(fs, input, refresh).collect { available.value = it } }
        try {
            File(root, "notes.txt").writeText("no site")
            delay(400); assertFalse(available.value)
            val html = File(root, "index.HTML").apply { writeText("<h1>Site</h1>") }
            input.value = input.value.copy(editedPaths = setOf(html.name))
            withTimeout(3000) { available.first { it } }
            html.delete(); refresh.value++
            withTimeout(3000) { available.first { !it } }
            File(root, "next.htm").writeText("<p>Next</p>")
            refresh.value++
            withTimeout(3000) { available.first { it } }
            fs.value = null
            withTimeout(3000) { available.first { !it } }
        } finally { job.cancelAndJoin(); root.deleteRecursively() }
    }
}
