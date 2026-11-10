package com.androidharness.app.tools

import com.androidharness.app.workspace.FsNode
import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ToolRegistryAvailabilityTest {
    private val workspace = object : WorkspaceFs {
        override val displayPath = "/workspace"
        override val shellRoot: File? = File("/workspace")
        override val isSaf = false
        override fun resolve(path: String): FsNode = throw UnsupportedOperationException()
        override fun walk(path: String): Sequence<FsNode> = emptySequence()
    }

    @Test
    fun `context hides unavailable tools from schemas`() {
        val visible = fakeTool("visible", available = true)
        val hidden = fakeTool("hidden", available = false)
        val registry = ToolRegistry(listOf(visible, hidden))

        assertEquals(listOf("visible"), registry.schemas(context = ToolContext(workspace)).map { it.name })
        assertEquals(listOf("hidden", "visible"), registry.schemas().map { it.name })
    }

    private fun fakeTool(toolName: String, available: Boolean) = object : Tool {
        override val name = toolName
        override val description = toolName
        override val parametersSchema = JsonObject(emptyMap())
        override val isReadOnly = true
        override fun isAvailable(ctx: ToolContext) = available
        override suspend fun execute(args: JsonObject, ctx: ToolContext) = ToolResult(true, "")
    }
}
