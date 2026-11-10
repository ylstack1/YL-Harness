package com.androidharness.app.browser

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import org.junit.Assert.*
import org.junit.Test

class BrowserToolActivityTest {
    private fun result(id: String, text: String, error: Boolean = false) =
        ChatMessage(role = Role.TOOL, toolCallId = id, toolName = "browser_navigate", text = text, isError = error)

    @Test fun `status uses real tool state and never treats missing results as success`() {
        val call = ToolCallData("nav", "browser_navigate", """{"url":"https://example.test/install"}""")
        assertEquals("Not completed", BrowserToolActivity.from(call, null, false).status)
        assertEquals("Needs approval", BrowserToolActivity.from(call, null, true, awaitingApproval = true).status)
        assertEquals("Open page", BrowserToolActivity.from(call, null, true, awaitingApproval = true).action)
        assertEquals("In progress", BrowserToolActivity.from(call, null, true).status)
        assertEquals("Completed", BrowserToolActivity.from(call, result(call.id, "URL: https://example.test/install"), true).status)
        assertEquals("Failed", BrowserToolActivity.from(call, result(call.id, "Navigation failed", true), true).status)
        assertEquals("Waiting", BrowserToolActivity.from(call.copy(name = "browser_wait_for"), null, true).status)
    }

    @Test fun `saved targets retain redirects and subsequent navigation independently`() {
        val nav = ToolCallData("nav", "browser_navigate", """{"url":"https://first.test/start"}""")
        val read = ToolCallData("read", "browser_get_dom", "{}")
        val next = nav.copy(id = "next", argumentsJson = """{"url":"https://second.test/next"}""")
        val history = listOf(
            ChatMessage(role = Role.ASSISTANT, toolCalls = listOf(nav)),
            result("nav", "URL: https://first.test/redirected\nTitle: Redirected"),
            ChatMessage(role = Role.ASSISTANT, toolCalls = listOf(read)),
            ChatMessage(role = Role.ASSISTANT, toolCalls = listOf(next)),
        )
        val targets = BrowserToolActivity.targets(history)
        assertEquals("https://first.test/redirected", targets["nav"])
        assertEquals("https://first.test/redirected", targets["read"])
        assertEquals("https://second.test/next", targets["next"])
    }

    @Test fun `typed contents and URL credentials stay out of the card copy`() {
        val call = ToolCallData("type", "browser_type", """{"text":"private-value","selector":"#password"}""")
        val activity = BrowserToolActivity.from(call, null, true, "https://account:secret@example.test/path?token=hidden")
        assertEquals("example.test", activity.site)
        assertEquals("Typing into a field…", activity.action)
        assertFalse(activity.site.contains("secret"))
        assertFalse(activity.action.contains("private-value"))
    }

    @Test fun `malformed arguments and local pages have readable fallbacks`() {
        val call = ToolCallData("local", "browser_navigate", "broken")
        assertEquals("Browser page", BrowserToolActivity.from(call, null, true).site)
        assertEquals("Workspace page", BrowserToolActivity.from(call, null, true, "index.html").site)
        assertEquals("Workspace page", BrowserToolActivity.from(call, result("local", "URL: https://harness.workspace/ws/index.html"), false).site)
        assertTrue(BrowserToolActivity.isBrowserTool("browser_screenshot"))
        assertFalse(BrowserToolActivity.isBrowserTool("read_file"))
    }

    @Test fun `watch resolves workspace URLs without misclassifying external HTML pages`() {
        assertEquals("docs/setup page.html", BrowserToolActivity.workspacePath("https://harness.workspace/ws/docs/setup+page.html?view=1#step", null))
        assertEquals("index.html", BrowserToolActivity.workspacePath("index.html?view=1", null))
        assertNull(BrowserToolActivity.workspacePath("https://example.test/index.html", null))
        assertNull(BrowserToolActivity.workspacePath("https://harness.workspace/ws/../private.html", null))
        assertNull(BrowserToolActivity.workspacePath("https://harness.workspace.evil/ws/index.html", null))
    }
}
