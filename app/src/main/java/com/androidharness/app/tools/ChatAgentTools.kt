package com.androidharness.app.tools

import com.androidharness.app.data.AgentKnowledgeSearch
import com.androidharness.app.data.ChatAgentProfile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull

class SearchAgentKnowledgeTool(private val profile: ChatAgentProfile) : Tool {
    override val name = "search_agent_knowledge"
    override val description = "Search this agent's saved knowledge for passages. Returns filenames and passage numbers to cite. No other agent, chat or workspace files are accessible."
    override val parametersSchema = Schema.obj(mapOf(
        "query" to Schema.string("Words or a question to find in the knowledge files."),
        "file_id" to Schema.string("Optional exact source ID from the agent knowledge list."),
        "passage" to Schema.integer("With file_id, read directly from this 1-based passage number instead of searching. Returns up to 4 passages."),
    ), required = listOf("query"))
    override val isReadOnly = true
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (query.length !in 1..500) throw ToolFailure("Use a search query of 1–500 characters.")
        val id = args["file_id"]?.jsonPrimitive?.contentOrNull
        if (id != null && profile.knowledge.none { it.id == id }) throw ToolFailure("That source is not available to this agent.")
        val passage = args["passage"]?.jsonPrimitive?.intOrNull
        val hits = if (passage == null) AgentKnowledgeSearch.search(profile.knowledge, query, id) else {
            val file = profile.knowledge.firstOrNull { it.id == id } ?: throw ToolFailure("Direct reading requires file_id.")
            val all = AgentKnowledgeSearch.passages(file)
            if (passage !in 1..all.size) throw ToolFailure("Use a passage number from 1 to ${all.size}.")
            all.drop(passage - 1).take(4)
        }
        return ToolResult(true, if (hits.isEmpty()) "No matching passages. Try different keywords; do not invent source contents."
            else hits.joinToString("\n\n") { "[${it.file.name}, passage ${it.passage} of ${AgentKnowledgeSearch.passages(it.file).size}; source_id=${it.file.id}]\n${it.text}" })
    }
}

class ChatAgentSkillTool(private val profile: ChatAgentProfile) : Tool {
    override val name = "skill_view"
    override val description = "Read one of this agent's selected instruction-only skills. Scripts, supporting files and unselected skills are unavailable."
    override val parametersSchema = Schema.obj(mapOf("name" to Schema.string("Exact selected skill name.")), required = listOf("name"))
    override val isReadOnly = true
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val skill = profile.skills.firstOrNull { it.name == args["name"]?.jsonPrimitive?.contentOrNull }
            ?: throw ToolFailure("Choose one of this agent's selected skills: ${profile.skills.joinToString { it.name }}")
        return ToolResult(true, "Skill: ${skill.name}\nInstruction-only Chat skill; use only tools exposed in this conversation.\n${skill.content}")
    }
}

fun chatAgentTools(profile: ChatAgentProfile?): List<Tool> = profile?.let {
    buildList {
        if (it.knowledge.isNotEmpty()) add(SearchAgentKnowledgeTool(it))
        if (it.skills.isNotEmpty()) add(ChatAgentSkillTool(it))
    }
}.orEmpty()
