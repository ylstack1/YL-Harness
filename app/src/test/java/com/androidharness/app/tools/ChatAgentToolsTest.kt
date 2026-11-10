package com.androidharness.app.tools

import com.androidharness.app.data.*
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.data.db.SessionEntity
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ChatAgentToolsTest {
    @Test fun knowledgeAndSkillsOnlyReadTheOwningSnapshot(): Unit = runBlocking {
        val root = Files.createTempDirectory("agent-tools").toFile()
        try {
            val profile = ChatAgentProfile(name = "Tutor", knowledge = listOf(AgentKnowledge("own", "notes.txt", "Jupiter is a gas giant.")),
                skills = listOf(AgentSkill("explain", "Use small examples.")))
            val ctx = ToolContext(FileFs(root), sessionId = "a")
            val search = SearchAgentKnowledgeTool(profile)
            val result = search.execute(buildJsonObject { put("query", "Jupiter") }, ctx)
            assertTrue(result.output.contains("notes.txt, passage 1"))
            assertTrue(result.output.contains("gas giant"))
            assertTrue(runCatching { search.execute(buildJsonObject { put("query", "Jupiter"); put("file_id", "foreign") }, ctx) }.isFailure)
            val skill = ChatAgentSkillTool(profile)
            assertTrue(skill.execute(buildJsonObject { put("name", "explain") }, ctx).output.contains("Use small examples"))
            assertTrue(runCatching { skill.execute(buildJsonObject { put("name", "shell") }, ctx) }.isFailure)
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun historyGuardsAlsoRejectDirectSessionReads(): Unit = runBlocking {
        val root = Files.createTempDirectory("agent-recall").toFile()
        try {
            val ctx = ToolContext(FileFs(root), sessionId = "own")
            var loaded = false
            val read = ReadChatSnippetTool({ loaded = true; listOf(ChatMessage(Role.USER, "secret")) }, { SessionEntity(it, "Secret", 1, 1) })
                .apply { canAccess = { _, _ -> false } }
            assertTrue(runCatching { read.execute(buildJsonObject { put("session_id", "foreign") }, ctx) }.isFailure)
            assertFalse(loaded)
            val recent = RecentChatsTool(flowOf(listOf(SessionEntity("foreign", "Secret", 1, 1), SessionEntity("friend", "Own agent chat", 2, 2))))
                .apply { canAccess = { _, id -> id == "friend" } }
            val out = recent.execute(JsonObject(emptyMap()), ctx).output
            assertTrue(out.contains("Own agent chat"))
            assertFalse(out.contains("Secret"))
        } finally { root.deleteRecursively() }
    }
}
