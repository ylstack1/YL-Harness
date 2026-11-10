package com.androidharness.app.artifacts

import com.androidharness.app.data.*
import com.androidharness.app.tools.*
import com.androidharness.app.workspace.FileFs
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class ArtifactTest {
    @get:Rule val tmp = TemporaryFolder()
    private val html = "<button onclick=\"this.textContent='Done'\">Try it</button>"
    @Test fun `artifact revisions and backup round trip preserve exact source`() {
        val artifact = ChatArtifact.create("Color explorer", html)
        val revision = ChatArtifact.create("Color explorer", html + "<p>Updated</p>", artifact.id)
        assertEquals(artifact.id, revision.id); assertNotEquals(artifact.revision, revision.revision)
        assertEquals(artifact, ChatArtifact.decode(ChatArtifact.encode(artifact)))
        val backup = ChatBackupFile(sessions = listOf(BackupSession("chat", "Concepts", 1, 2,
            messages = listOf(BackupMessage("message", "TOOL", "Created", createdAt = 1, artifactJson = ChatArtifact.encode(artifact))))))
        assertEquals(backup, ChatBackupCodec.decode(ChatBackupCodec.encode(backup)))
        val old = ChatBackupCodec.decode("""{"format":"androidharness-chats","version":1,"sessions":[]}""")
        assertEquals(1, old.version)
    }
    @Test fun `invalid imported artifacts fail closed without crashing chat`() {
        assertNull(ChatArtifact.decode("{}")); assertNull(ChatArtifact.decode("not-json"))
        val valid = ChatArtifact.create("Concept", html)
        assertNull(ChatArtifact.decode(ChatArtifact.encode(valid).replace(valid.id, "../../other-chat")))
        for ((title, body) in listOf("" to html, "x".repeat(81) to html, "Title" to "", "Title" to "x".repeat(ChatArtifact.MAX_BYTES + 1))) {
            assertThrows(IllegalArgumentException::class.java) { ChatArtifact.create(title, body) }
        }
    }
    @Test fun `sandbox document keeps generated markup inside an opaque script only frame`() {
        val attack = "\"><iframe sandbox='allow-same-origin'><script>parent.document.body.textContent='escaped'</script></iframe>"
        val artifact = ChatArtifact.create("Test \"frame\"", attack)
        val frame = ArtifactSandbox.frameDocument(artifact, false)
        assertTrue(frame.contains("sandbox=\"allow-scripts\""))
        assertFalse(frame.contains("sandbox='allow-same-origin'"))
        assertEquals(1, Regex("<iframe").findAll(frame).count())
        assertTrue(frame.contains("&lt;iframe"))
        assertTrue(frame.contains("connect-src 'none'".replace("'", "&#39;")))
        assertNotEquals(ArtifactSandbox.origin(artifact), ArtifactSandbox.origin(ChatArtifact.create("Other", html)))
    }
    @Test fun `composer attachment preserves selected revision without exposing internal ids in visible text`() {
        val artifact = ChatArtifact.create("A concept", html)
        val ref = ArtifactAttachment.from(artifact)
        val payload = ArtifactAttachment.attach("Make the controls smaller", ref)
        assertEquals(ref, ArtifactAttachment.fromPrompt(payload))
        assertEquals("Make the controls smaller", ArtifactAttachment.visibleText(payload))
        assertEquals(ref, ArtifactAttachment.decode(ref.encode()))
        assertEquals("[[harness-concept]]\ninvalid\n[[/harness-concept]]\n\nHello",
            ArtifactAttachment.visibleText("[[harness-concept]]\ninvalid\n[[/harness-concept]]\n\nHello"))
    }
    @Test fun `tools create locally and refuse cross chat revision and source access`() = runBlocking {
        val byChat = mutableMapOf<String, MutableList<ChatArtifact>>()
        val loader: ArtifactLoader = { byChat[it].orEmpty() }
        val create = CreateArtifactTool(loader)
        val context = ToolContext(FileFs(tmp.root), sessionId = "chat-a")
        val result = create.execute(buildJsonObject { put("title", "Counter"); put("html", html) }, context)
        assertTrue(result.ok); assertTrue(tmp.root.listFiles()!!.isEmpty())
        val saved = result.artifact!!; byChat["chat-a"] = mutableListOf(saved)
        val update = create.execute(buildJsonObject { put("title", "Counter"); put("html", html + "<p>New</p>"); put("artifact_id", saved.id) }, context)
        assertEquals(saved.id, update.artifact!!.id); assertNotEquals(saved.revision, update.artifact!!.revision)
        byChat["chat-a"]!!.add(update.artifact!!)
        val read = ReadArtifactTool(loader)
        assertEquals(update.artifact!!.html, ChatArtifact.decode(read.execute(buildJsonObject { put("artifact_id", saved.id) }, context).output)!!.html)
        assertEquals(html, ChatArtifact.decode(read.execute(buildJsonObject { put("artifact_id", saved.id); put("revision", saved.revision) }, context).output)!!.html)
        for (tool in listOf(create, read)) {
            try {
                tool.execute(buildJsonObject { put("artifact_id", saved.id); put("title", "Counter"); put("html", html) }, context.copy(sessionId = "chat-b"))
                fail("Other chat must not access this artifact")
            } catch (_: ToolFailure) {}
        }
        assertFalse(create.isAvailable(context.copy(sessionId = null)))
    }
}
