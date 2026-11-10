package com.androidharness.app.tools

import com.androidharness.app.browser.BrowserController
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class BrowserOpenExternalTool(private val controller: BrowserController) : Tool {
    override val name = "browser_open_external"
    override val description = "Ask Android to open an http/https URL in the user's default browser (or its browser chooser). Works without shell, Shizuku or root. Keep Harness visible when opening it."
    override val parametersSchema = Schema.obj(mapOf("url" to Schema.string("Absolute http or https URL, including localhost URLs.")), listOf("url"))
    override val isReadOnly = false
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val url = args["url"]?.jsonPrimitive?.content ?: throw ToolFailure("Missing required argument: url")
        controller.openExternal(url)
        return ToolResult(true, "Requested Android to open $url in the default browser or browser chooser. This confirms the request was dispatched; browser loading was not verified.")
    }
}
