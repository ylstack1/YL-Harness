package com.androidharness.app.workspace

import com.androidharness.app.data.CheckpointStore
import com.androidharness.app.data.db.CheckpointEntity
import com.androidharness.app.data.db.HarnessDao
import com.androidharness.app.tools.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.Base64

class ChatWorkspacesTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun fixture(readOnly: Boolean = true): ChatWorkspaceFs {
        val a = tmp.newFolder("a-${System.nanoTime()}")
        val b = tmp.newFolder("b-${System.nanoTime()}")
        a.resolve("same.txt").writeText("alpha")
        b.resolve("same.txt").writeText("beta")
        return ChatWorkspaceFs("a", listOf(ChatWorkspaceBinding("a", "Alpha", true, FileFs(a)),
            ChatWorkspaceBinding("b", "Beta", !readOnly, FileFs(b))))
    }
    private fun args(vararg pairs: Pair<String, String>) = buildJsonObject { pairs.forEach { (key, value) -> put(key, value) } }
    private fun blocked(block: () -> Unit) { assertTrue("Expected access to fail", runCatching(block).isFailure) }

    @Test fun `selection round trips and invalid grants fail closed`() {
        val value = ChatWorkspaceSelection("a", listOf(ChatWorkspaceGrant("a", true), ChatWorkspaceGrant("b")))
        assertEquals(value, ChatWorkspaceSelection.decode(ChatWorkspaceSelection.encode(value)))
        assertTrue(ChatWorkspaceSelection.single("a").grants.single().canEdit)
        blocked { ChatWorkspaceSelection.decode("garbage") }
        blocked { ChatWorkspaceSelection("a", emptyList()).validated() }
        blocked { value.copy(primaryId = "c").validated() }
        blocked { value.copy(grants = value.grants + value.grants.first()).validated() }
    }

    @Test fun `chat backup and tool metadata retain workspace identity while old data stays readable`() {
        val selection = ChatWorkspaceSelection("a", listOf(ChatWorkspaceGrant("a", true), ChatWorkspaceGrant("b")))
        val session = com.androidharness.app.data.BackupSession("chat", "Projects", 1, 2, projectId = "a",
            workspaceSelectionJson = ChatWorkspaceSelection.encode(selection))
        val file = com.androidharness.app.data.ChatBackupFile(sessions = listOf(session))
        val restored = com.androidharness.app.data.ChatBackupCodec.decode(com.androidharness.app.data.ChatBackupCodec.encode(file))
        assertEquals(selection, ChatWorkspaceSelection.decode(restored.sessions.single().workspaceSelectionJson!!))
        val call = com.androidharness.app.core.ToolCallData("id", "read_file", "{}", workspaceId = "b", workspaceName = "Beta")
        val encoded = Json.encodeToString(com.androidharness.app.core.ToolCallData.serializer(), call)
        val decoded = Json.decodeFromString(com.androidharness.app.core.ToolCallData.serializer(), encoded)
        assertEquals(call, decoded)
        assertTrue(com.androidharness.app.agent.toolPresentation(decoded).title.startsWith("Beta ·"))
        assertNull(Json.decodeFromString(com.androidharness.app.core.ToolCallData.serializer(), """{"id":"old","name":"read_file","argumentsJson":"{}"}""").workspaceId)
    }

    @Test fun `same named files stay separate and normal paths use the primary`() {
        val fs = fixture(false)
        assertEquals("alpha", fs.resolve("same.txt").readText())
        assertEquals("beta", fs.resolve(fs.key("b", "same.txt")).readText())
        fs.resolve(fs.key("b", "same.txt")).writeText("beta changed")
        assertEquals("alpha", fs.resolve("same.txt").readText())
        assertEquals("beta changed", fs.resolve(fs.key("b", "same.txt")).readText())
        assertNotEquals(fs.key("a", "same.txt"), fs.key("b", "same.txt"))
        assertEquals(fs.key("a", "same.txt"), fs.resolve("same.txt").relPath)
        assertEquals(listOf("Alpha", "Beta"), fs.resolve("@workspaces").list().map { it.name })
    }

    @Test fun `read only grants survive direct nodes children and tool contexts`() = runBlocking {
        val fs = fixture()
        val root = fs.resolve(fs.key("b", "."))
        val file = root.list().single()
        assertEquals("beta", file.readText())
        blocked { file.writeText("changed") }
        blocked { file.writeBytes(byteArrayOf(1)) }
        blocked { file.delete() }
        blocked { file.renameTo("moved.txt") }
        blocked { root.createFile("new") }
        blocked { root.createDir("new") }
        blocked { root.mkdirs() }
        val read = WorkspaceToolRouting.route(ReadFileTool(), args("workspace_id" to "b", "path" to "same.txt"), fs)
        assertEquals("beta", read.workspace.resolve("same.txt").readText())
        blocked { read.workspace.resolve("same.txt").writeText("bypass") }
        blocked { WorkspaceToolRouting.route(WriteFileTool(), args("workspace_id" to "b"), fs) }
        assertNull(fs.shellRoot)
        assertEquals("beta", fs.resolve(fs.key("b", "same.txt")).readText())
    }

    @Test fun `unknown workspaces absolute paths traversal and symlinks cannot select another root`() {
        val fs = fixture()
        blocked { fs.resolve("@workspaces/unknown/same.txt") }
        blocked { fs.resolve("@workspaces/a/../b/same.txt") }
        blocked { fs.resolve("../same.txt") }
        blocked { fs.resolve("/etc/passwd") }
        blocked { WorkspaceToolRouting.route(ReadFileTool(), args("workspace_id" to "unknown"), fs) }
        val aRoot = fs.binding("a").fs.shellRoot!!
        val bRoot = fs.binding("b").fs.shellRoot!!
        Files.createSymbolicLink(aRoot.resolve("other").toPath(), bRoot.toPath())
        blocked { fs.resolve("other/same.txt").readText() }
        blocked { fs.fileAccess(fs.binding("a")).resolve("other/same.txt").writeText("bypass") }
        assertEquals("beta", bRoot.resolve("same.txt").readText())
    }

    @Test fun `schemas advertise valid routing and process tools cannot bypass read only access`() {
        val fs = fixture()
        val unsafe = object : Tool {
            override val name = "shell"
            override val description = "Fixture"
            override val parametersSchema = Schema.obj(emptyMap())
            override val isReadOnly = false
            override suspend fun execute(args: JsonObject, ctx: ToolContext) = ToolResult(true, "")
        }
        val registry = ToolRegistry(listOf(ReadFileTool(), WriteFileTool(), unsafe))
        val schemas = registry.schemas(context = ToolContext(fs, sandboxOff = true))
        assertFalse(schemas.any { it.name == "shell" })
        val properties = WorkspaceToolRouting.schema(ReadFileTool(), fs)["properties"]!!.jsonObject
        assertEquals(listOf("a", "b"), properties["workspace_id"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
        blocked { WorkspaceToolRouting.route(unsafe, args("workspace_id" to "a"), fs) }
        val writable = ChatWorkspaceFs(fs.primaryId, fs.bindings.map { it.copy(canEdit = true) })
        assertTrue(WorkspaceToolRouting.available(unsafe, ToolContext(writable)))
        assertSame(writable.binding("b").fs, WorkspaceToolRouting.route(unsafe, args("workspace_id" to "b"), writable).workspace)
    }

    @Test fun `checkpoint restore resolves each workspace and preserves failed read only checkpoints`() = runBlocking {
        val fs = fixture(false)
        fun cp(id: String) = CheckpointEntity(id, "chat", "turn", fs.key(id, "same.txt"),
            Base64.getEncoder().encodeToString("$id original".toByteArray()), true, false, 1)
        val rows = mutableListOf(cp("a"), cp("b"))
        val dao = Proxy.newProxyInstance(HarnessDao::class.java.classLoader, arrayOf(HarnessDao::class.java)) { _, method, args ->
            when (method.name) {
                "checkpointsForTurn" -> rows.toList()
                "deleteCheckpoint" -> { rows.remove(args!![0]); Unit }
                else -> error(method.name)
            }
        } as HarnessDao
        val store = CheckpointStore(dao)
        val limited = ChatWorkspaceFs("a", fs.bindings.map { if (it.id == "b") it.copy(canEdit = false) else it })
        assertEquals(1, store.rewind("chat", "turn", limited).failed)
        assertEquals("a original", fs.resolve("same.txt").readText())
        assertEquals("beta", fs.resolve(fs.key("b", "same.txt")).readText())
        assertEquals(listOf(fs.key("b", "same.txt")), rows.map { it.relPath })
        assertEquals(1, store.rewind("chat", "turn", fs).restored)
        assertEquals("b original", fs.resolve(fs.key("b", "same.txt")).readText())
    }

    @Test fun `stable paths do not follow a changed primary and removed roots fail closed`() {
        val fs = fixture(false)
        val path = fs.key("a", "same.txt")
        val switched = ChatWorkspaceFs("b", fs.bindings)
        assertEquals("beta", switched.resolve("same.txt").readText())
        assertEquals("alpha", switched.resolve(path).readText())
        val removed = ChatWorkspaceFs("b", fs.bindings.filter { it.id == "b" })
        blocked { removed.resolve(path) }
        assertTrue(workspacePathsOverlap(listOf("/data/project"), listOf("/data/project/sub")))
        assertFalse(workspacePathsOverlap(listOf("/data/project"), listOf("/data/project-two")))
    }
    @Test fun `document trees omit command tools and stale connection tools fail before execution`() {
        val disk = FileFs(tmp.newFolder("document-fixture"))
        val documents = object : WorkspaceFs by disk {
            override val shellRoot: File? = null
            override val isSaf = true
        }
        var ready = true
        fun tool(toolName: String, readOnly: Boolean = true) = object : Tool {
            override val name = toolName
            override val description = "Fixture"
            override val parametersSchema = Schema.obj(emptyMap())
            override val isReadOnly = readOnly
            override fun isAvailable(ctx: ToolContext) = ready
            override suspend fun execute(args: JsonObject, ctx: ToolContext) = ToolResult(true, "")
        }
        val shell = tool("shell", false)
        val git = tool("git_status")
        val skill = tool("skill_view")
        val extension = tool("mcp__fixture__lookup")
        val registry = ToolRegistry(listOf(shell, git, skill, extension, ReadFileTool()))
        assertEquals(setOf("skill_view", "mcp__fixture__lookup", "read_file"),
            registry.schemas(context = ToolContext(documents)).map { it.name }.toSet())
        blocked { WorkspaceToolRouting.route(shell, args(), documents) }
        blocked { WorkspaceToolRouting.route(git, args(), documents) }
        val selection = ChatWorkspaceFs("docs", listOf(ChatWorkspaceBinding("docs", "Docs", false, documents)))
        assertTrue(WorkspaceToolRouting.available(skill, ToolContext(selection)))
        assertFalse(WorkspaceToolRouting.available(shell, ToolContext(selection)))
        assertSame(selection, WorkspaceToolRouting.route(skill, args(), selection).workspace)
        assertTrue(WorkspaceToolRouting.available(extension, ToolContext(disk)))
        ready = false
        assertFalse(registry.schemas(context = ToolContext(disk)).any { it.name == extension.name })
        blocked { WorkspaceToolRouting.route(extension, args(), disk) }
    }

    @Test fun `availability routing preserves session scoped artifact tools`() = runBlocking {
        val fs = FileFs(tmp.newFolder("artifact-routing"))
        val artifact = CreateArtifactTool { emptyList() }
        val context = ToolContext(fs, sessionId = "current-chat")
        assertTrue(WorkspaceToolRouting.available(artifact, context))
        assertSame(fs, WorkspaceToolRouting.route(artifact, args(), fs, context).workspace)
        val selected = ChatWorkspaceFs("a", listOf(ChatWorkspaceBinding("a", "Alpha", false, fs)))
        assertSame(selected, WorkspaceToolRouting.route(artifact, args(), selected, context.copy(workspace = selected)).workspace)
        blocked { WorkspaceToolRouting.route(artifact, args(), fs) }
    }

}
