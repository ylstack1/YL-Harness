package com.androidharness.app.ui.files

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Isolated bundled parser worker. No workspace scripts, network, file URLs or external navigation. */
@SuppressLint("SetJavaScriptEnabled")
internal class SmartEditorEngine(private val context: Context) : AutoCloseable {
    private val ready = CompletableDeferred<Unit>()
    private val ids = AtomicLong()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val gate = Mutex()
    private var view: WebView? = null
    private var closed = false
    private var failure: String? = null
    private var pending: Pair<Long, CompletableDeferred<JsonElement>>? = null
    private var previous = emptyMap<String, String>()
    private val json = Json { ignoreUnknownKeys = true }

    private fun fail(message: String) {
        failure = message
        val error = IllegalStateException(message)
        ready.completeExceptionally(error)
        pending?.second?.completeExceptionally(error)
    }

    private fun start() {
        check(!closed) { "The editor language service is closed. Reopen the editor to retry." }
        failure?.let { error(it) }
        if (view != null) return
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
        view = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            settings.domStorageEnabled = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            addJavascriptInterface(object {
                @JavascriptInterface fun reply(value: String) {
                    if (value.length > 12_000_000) return
                    val result = runCatching { json.parseToJsonElement(value).jsonObject }.getOrNull() ?: return
                    main.post {
                        if (closed) return@post
                        when {
                            result["ready"]?.jsonPrimitive?.booleanOrNull == true -> ready.complete(Unit)
                            result["fatal"] != null -> fail(result.getValue("fatal").jsonPrimitive.content)
                            result["id"]?.jsonPrimitive?.longOrNull == pending?.first -> {
                                val waiter = pending?.second ?: return@post
                                result["error"]?.let { waiter.completeExceptionally(IllegalStateException(it.jsonPrimitive.content)) }
                                    ?: waiter.complete(result["result"] ?: JsonNull)
                            }
                        }
                    }
                }
            }, "EditorBridge")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val url = request.url
                    if (url.scheme == "https" && url.host == "appassets.androidplatform.net" && url.path?.startsWith("/assets/editor-tools/") == true) {
                        loader.shouldInterceptRequest(url)?.let { return it }
                    }
                    return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                    if (request.isForMainFrame) fail("The local language service could not start. Reopen the editor to retry.")
                }
                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                    fail("The local language service stopped. Reopen the editor to retry.")
                    return true
                }
            }
            loadUrl("https://appassets.androidplatform.net/assets/editor-tools/index.html")
        }
    }

    suspend fun request(op: String, path: String, files: Map<String, String>, offset: Int = 0, newName: String? = null): JsonElement = gate.withLock {
        withContext(Dispatchers.Main.immediate) {
            start()
            awaitService(ready)
            val id = ids.incrementAndGet()
            val reply = CompletableDeferred<JsonElement>()
            pending = id to reply
            val payload = buildJsonObject {
                put("id", id); put("op", op); put("path", path); put("offset", offset)
                newName?.let { put("newName", it) }
                put("reset", previous.isEmpty())
                putJsonArray("removed") { (previous.keys - files.keys).forEach { add(it) } }
                putJsonObject("files") { files.forEach { (name, text) -> if (previous[name] != text) put(name, text) } }
            }
            try {
                // JSON is an argument, never interpolated source text; U+2028/U+2029 also stay escaped.
                val encoded = payload.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
                view!!.evaluateJavascript("window.editorRequest($encoded)", null)
                previous = files.toMap()
                awaitService(reply)
            } finally { if (pending?.first == id) pending = null }
        }
    }

    private suspend fun <T> awaitService(reply: CompletableDeferred<T>): T = try {
        withTimeout(20_000) { reply.await() }
    } catch (e: TimeoutCancellationException) {
        // Preserve caller cancellation; service timeouts should surface as an actionable UI error.
        currentCoroutineContext().ensureActive()
        close()
        throw IllegalStateException("The local language service timed out. Reopen the editor to retry.", e)
    }

    override fun close() {
        closed = true
        fail("The editor was closed.")
        view?.apply { removeJavascriptInterface("EditorBridge"); stopLoading(); destroy() }
        view = null
        previous = emptyMap()
    }
}
