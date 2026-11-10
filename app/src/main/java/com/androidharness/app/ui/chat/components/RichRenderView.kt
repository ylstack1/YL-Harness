package com.androidharness.app.ui.chat.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Message
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive

internal enum class RichRenderKind { MATH, MERMAID }

private data class RenderRequest(
    val kind: RichRenderKind,
    val source: String,
    val display: Boolean,
    val color: String,
    val dark: Boolean,
)

@Composable
internal fun MathInlineView(tex: String, color: Color, onMeasured: (Int, Int) -> Unit) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    SandboxedRenderView(
        kind = RichRenderKind.MATH,
        source = tex,
        display = false,
        color = color,
        dark = dark,
        modifier = Modifier.fillMaxSize(),
        onMeasured = onMeasured,
    )
}

@Composable
internal fun MathBlock(tex: String) {
    val color = MaterialTheme.colorScheme.onSurface
    var height by remember(tex) { mutableIntStateOf(64) }
    var measured by remember(tex) { mutableStateOf(false) }
    var failed by remember(tex) { mutableStateOf(false) }
    LaunchedEffect(tex) {
        delay(3_000)
        if (!measured) failed = true
    }
    Box(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (failed) {
            Text(tex, style = MaterialTheme.typography.bodyLarge)
        } else {
            SandboxedRenderView(
                kind = RichRenderKind.MATH,
                source = tex,
                display = true,
                color = color,
                dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f,
                modifier = Modifier.fillMaxWidth().height(height.dp.coerceIn(40.dp, 600.dp)),
                onMeasured = { _, h ->
                    if (h in 1..4000) {
                        height = h
                        measured = true
                    }
                },
            )
        }
    }
}

@Composable
internal fun MermaidBlock(code: String) {
    var height by remember(code) { mutableIntStateOf(200) }
    var measured by remember(code) { mutableStateOf(false) }
    var failed by remember(code) { mutableStateOf(false) }
    LaunchedEffect(code) {
        delay(3_000)
        if (!measured) failed = true
    }
    Box(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (failed) {
            Text(code, style = MaterialTheme.typography.bodySmall)
        } else {
            SandboxedRenderView(
                kind = RichRenderKind.MERMAID,
                source = code,
                display = true,
                color = MaterialTheme.colorScheme.onSurface,
                dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f,
                modifier = Modifier.fillMaxWidth().height(height.dp.coerceIn(120.dp, 800.dp)),
                onMeasured = { _, h ->
                    if (h in 1..4000) {
                        height = h
                        measured = true
                    }
                },
            )
        }
    }
}

@Composable
private fun SandboxedRenderView(
    kind: RichRenderKind,
    source: String,
    display: Boolean,
    color: Color,
    dark: Boolean,
    modifier: Modifier,
    onMeasured: (Int, Int) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val request = remember(kind, source, display, color.toArgb(), dark) {
        RenderRequest(kind, source, display, color.toArgb().toCssHex(), dark)
    }
    AndroidView(
        factory = { RichRenderWebView(context, density.fontScale).also { it.request(request) } },
        update = { view ->
            view.onMeasured = onMeasured
            view.updateFontScale(density.fontScale)
            view.request(request)
        },
        modifier = modifier,
        onRelease = { it.release() },
    )
}

/**
 * Sandboxed local renderer for KaTeX and Mermaid. Only /assets/richtext/ is
 * served; network, file access, storage, bridges, popups, and downloads are off.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
private class RichRenderWebView(context: Context, fontScale: Float) : WebView(context) {
    var onMeasured: ((Int, Int) -> Unit)? = null
    private var released = false
    private var loaded = false
    private var pending: RenderRequest? = null
    private var current: RenderRequest? = null
    private val assetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()

    init {
        settings.apply {
            javaScriptEnabled = true
            textZoom = (fontScale * 100).toInt().coerceIn(100, 200)
            domStorageEnabled = false
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            blockNetworkLoads = true
            blockNetworkImage = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            @Suppress("DEPRECATION")
            setSaveFormData(false)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        removeJavascriptInterface("searchBoxJavaBridge_")
        removeJavascriptInterface("accessibility")
        removeJavascriptInterface("accessibilityTraversal")
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                if (url.scheme == "https" && url.host == "appassets.androidplatform.net" &&
                    url.path?.startsWith("/assets/richtext/") == true
                ) {
                    assetLoader.shouldInterceptRequest(url)?.let { return it }
                }
                return blocked()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

            override fun onPageFinished(view: WebView, url: String?) {
                if (!released) {
                    loaded = true
                    pending?.let { render(it) }
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) loaded = false
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                release()
                return true
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (title == null || !title.startsWith("render:")) return
                val parts = title.removePrefix("render:").split(':')
                val width = parts.getOrNull(0)?.toIntOrNull() ?: return
                val height = parts.getOrNull(1)?.toIntOrNull() ?: return
                onMeasured?.invoke(width.coerceIn(1, 4_000), height.coerceIn(1, 20_000))
            }

            override fun onPermissionRequest(request: PermissionRequest?) { request?.deny() }
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                callback?.invoke(origin, false, false)
            }
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?) = false
            override fun onShowFileChooser(view: WebView?, callback: ValueCallback<Array<android.net.Uri>>?, params: FileChooserParams?): Boolean {
                callback?.onReceiveValue(null)
                return true
            }
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                result?.cancel()
                return true
            }
            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                result?.cancel()
                return true
            }
            override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
                result?.cancel()
                return true
            }
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean = true
        }
        setDownloadListener { _, _, _, _, _ -> }
        loadUrl("https://appassets.androidplatform.net/assets/richtext/index.html")
    }

    fun request(next: RenderRequest) {
        if (released || current == next) return
        current = next
        if (loaded) render(next) else pending = next
    }

    fun updateFontScale(fontScale: Float) {
        if (released) return
        val zoom = (fontScale * 100).toInt().coerceIn(100, 200)
        if (settings.textZoom != zoom) {
            settings.textZoom = zoom
            current = null // Re-render and report the new size once at the new font scale.
        }
    }

    fun release() {
        if (released) return
        released = true
        loaded = false
        pending = null
        current = null
        onMeasured = null
        stopLoading()
        settings.javaScriptEnabled = false
        webChromeClient = null
        destroy()
    }

    private fun render(request: RenderRequest) {
        pending = null
        isVerticalScrollBarEnabled = request.kind == RichRenderKind.MERMAID
        val source = JsonPrimitive(request.source).toString()
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")
        val call = when (request.kind) {
            RichRenderKind.MATH ->
                "renderMath($source, ${request.display}, ${JsonPrimitive(request.color)})"
            RichRenderKind.MERMAID ->
                "renderMermaid($source, ${request.dark})"
        }
        runCatching { evaluateJavascript(call, null) }
    }

    private fun blocked(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
}

private fun Int.toCssHex(): String = String.format("#%06X", 0xFFFFFF and this)
