package com.androidharness.app.tools

import com.androidharness.app.artifacts.ArtifactSandbox
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VisualGuideToolTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `basics always lead and requested modules follow`() = runBlocking {
        val result = VisualGuideTool().execute(buildJsonObject { putJsonArray("modules") { add(JsonPrimitive("diagram")); add(JsonPrimitive("Chart")) } },
            ToolContext(FileFs(temp.root)))
        assertTrue(result.ok)
        val text = result.output
        assertTrue(text.startsWith("## Basics"))
        assertTrue(text.indexOf("## Diagram") in 1 until text.indexOf("## Chart"))
        assertFalse(text.contains("## Mockup"))
    }

    @Test fun `comma separated strings work and unknown modules fail clearly`() = runBlocking {
        val ok = VisualGuideTool().execute(buildJsonObject { put("modules", "interactive, art") }, ToolContext(FileFs(temp.root)))
        assertTrue(ok.output.contains("## Interactive") && ok.output.contains("## Art"))
        val failure = runCatching { VisualGuide.read(listOf("poster")) }.exceptionOrNull()
        assertTrue(failure is ToolFailure && failure.message!!.contains("diagram"))
    }

    @Test fun `every documented kit class exists in the sandbox stylesheet`() {
        val documented = Regex("""\bhx-[a-z-]+""").findAll(VisualGuide.read(VisualGuide.modules.keys.toList())).map { it.value }.toSet()
        val missing = documented.filterNot { ArtifactSandbox.DESIGN_KIT.contains(".$it") }
        assertTrue("Documented but undefined: $missing", missing.isEmpty())
    }

    @Test fun `guide is read-only and needs no workspace schema`() {
        val tool = VisualGuideTool()
        assertTrue(tool.isReadOnly)
        assertTrue(WorkspaceToolRouting.available(tool, ToolContext(FileFs(temp.root))))
        assertEquals(tool.parametersSchema, WorkspaceToolRouting.schema(tool, FileFs(temp.root)))
    }
}
