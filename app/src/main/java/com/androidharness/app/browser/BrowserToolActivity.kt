package com.androidharness.app.browser

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

/** Transcript-derived browser activity; no timer or global browser state is needed. */
data class BrowserToolActivity(val target: String?, val site: String, val action: String, val status: String) {
    companion object {
        fun isBrowserTool(name: String) = name.startsWith("browser_")

        /** Watch can reopen the saved workspace origin as well as a relative HTML path. */
        fun workspacePath(target: String, rootPath: String?): String? {
            val uri = runCatching { URI(target) }.getOrNull()
            val local = when {
                uri?.scheme == "https" && uri.host == "harness.workspace" && uri.rawPath?.startsWith("/ws/") == true ->
                    java.net.URLDecoder.decode(uri.rawPath.removePrefix("/ws/"), "UTF-8")
                uri?.scheme != null && uri.scheme != "file" -> return null
                else -> target
            }
            return BrowserController.normalizeWorkspacePath(local, rootPath)
        }

        private fun requestedUrl(call: ToolCallData): String? = runCatching {
            Json.parseToJsonElement(call.argumentsJson).jsonObject["url"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()?.takeIf { it.isNotBlank() }

        private fun resultUrl(result: ChatMessage?): String? = result?.text?.lineSequence()
            ?.firstOrNull { it.startsWith("URL: ") }?.removePrefix("URL: ")?.trim()
            ?.takeIf { it.isNotBlank() && it != "(blank)" && it != "about:blank" }

        /** Preserve each call's own page when a later call navigates elsewhere. */
        fun targets(messages: List<ChatMessage>): Map<String, String> = buildMap {
            var current: String? = null
            for (message in messages) {
                if (message.role == Role.ASSISTANT) message.toolCalls.filter { isBrowserTool(it.name) }.forEach { call ->
                    if (call.name == "browser_navigate") current = requestedUrl(call) ?: current
                    current?.let { put(call.id, it) }
                }
                if (message.role == Role.TOOL && isBrowserTool(message.toolName.orEmpty())) {
                    resultUrl(message)?.let { url ->
                        current = url
                        message.toolCallId?.let { put(it, url) }
                    }
                }
            }
        }

        fun from(call: ToolCallData, result: ChatMessage?, running: Boolean, target: String? = null,
            awaitingApproval: Boolean = false): BrowserToolActivity {
            val url = resultUrl(result) ?: requestedUrl(call) ?: target
            val site = url?.let { runCatching { URI(it).host }.getOrNull() }?.takeIf { it.isNotBlank() }
                ?.let { if (it == "harness.workspace") "Workspace page" else it }
                ?: if (url != null) "Workspace page" else "Browser page"
            val status = when {
                result?.isError == true -> "Failed"
                result != null -> "Completed"
                awaitingApproval -> "Needs approval"
                running && call.name == "browser_wait_for" -> "Waiting"
                running -> "In progress"
                else -> "Not completed"
            }
            val active = running && result == null && !awaitingApproval
            val action = when (call.name) {
                "browser_navigate" -> if (active) "Opening page…" else "Open page"
                "browser_click" -> if (active) "Clicking a page element…" else "Click page element"
                "browser_type" -> if (active) "Typing into a field…" else "Type into field"
                "browser_scroll" -> if (active) "Scrolling page…" else "Scroll page"
                "browser_get_dom" -> if (active) "Reading page content…" else "Read page content"
                "browser_screenshot" -> if (active) "Capturing screenshot…" else "Capture screenshot"
                "browser_wait_for" -> if (active) "Waiting for page…" else "Wait for page"
                "browser_eval" -> if (active) "Checking page…" else "Check page"
                "browser_get_logs" -> if (active) "Reading browser logs…" else "Read browser logs"
                "browser_back" -> if (active) "Going back…" else "Go back"
                "browser_forward" -> if (active) "Going forward…" else "Go forward"
                "browser_refresh" -> if (active) "Reloading page…" else "Reload page"
                else -> if (active) "Inspecting browser…" else "Inspect browser"
            }
            return BrowserToolActivity(url, site, action, status)
        }
    }
}
