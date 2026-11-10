package com.androidharness.app.ui.chat

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.WebResourceExtractor
import com.androidharness.app.workspace.WorkspaceFs
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

internal data class WebPreviewInput(
    val messages: List<ChatMessage>,
    val streamingText: String?,
    val editedPaths: Set<String>,
)

internal fun hasPreviewLink(text: String): Boolean = WebResourceExtractor.extractUrls(text).any { value ->
    val normalized = if (value.startsWith("localhost:", true) || value.startsWith("127.0.0.1:", true)) "http://$value" else value
    runCatching { URI(normalized).let {
        it.scheme?.lowercase() in setOf("http", "https") && !it.host.isNullOrBlank() && (it.port == -1 || it.port in 1..65535)
    } }.getOrDefault(false)
}

/** Only scans files when saved chat activity changes, never on each streamed token. */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
internal fun webPreviewAvailability(
    workspace: Flow<WorkspaceFs?>,
    input: Flow<WebPreviewInput>,
    refresh: Flow<Long> = flowOf(0L),
): Flow<Boolean> {
    val savedLinks = input.map { it.messages }.distinctUntilChanged().map { messages -> messages.any { hasPreviewLink(it.text) } }
    val liveLinks = input.map { it.streamingText }.distinctUntilChanged().map { hasPreviewLink(it.orEmpty()) }
    val links = combine(savedLinks, liveLinks) { saved, live -> saved || live }.distinctUntilChanged()
    val fileRefresh = combine(input.map { it.messages to it.editedPaths }.distinctUntilChanged(), refresh) { activity, revision -> activity to revision }
    val html = combine(workspace, fileRefresh) { fs, _ -> fs }
        .debounce(200)
        .mapLatest { WebResourceExtractor.findWorkspaceHtmlFiles(it, limit = 1).isNotEmpty() }
        .onStart { emit(false) }
    return combine(links, html) { chatLink, htmlFile -> chatLink || htmlFile }
        .distinctUntilChanged().flowOn(Dispatchers.IO)
}
