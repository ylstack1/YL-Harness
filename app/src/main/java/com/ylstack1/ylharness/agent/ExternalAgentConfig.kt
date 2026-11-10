package com.ylstack1.ylharness.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Configuration for an external CLI agent (Pi, OpenCode, Claude Code, etc.)
 * parsed from harness.json externalAgents section.
 */
@Serializable
data class ExternalAgentConfig(
    /** Unique agent identifier, e.g. "pi", "opencode", "claude-code" */
    val id: String,
    /** Human-readable display name */
    val name: String,
    /** Enabled flag - agent can only run when true */
    val enabled: Boolean = false,
    /** Auto-start on session begin */
    val autoStart: Boolean = false,
    /** Provider for model selection (e.g. "openai", "anthropic", "local") */
    val provider: String? = null,
    /** Model identifier */
    val model: String? = null,
    /** Working directory for the agent; null = current workspace root */
    val workingDir: String? = null,
    /** Execution tier override: "app_linux", "shizuku", "proot_ubuntu", etc. */
    val tier: String? = null,
    /** Map from Pi tool names to AndroidHarness tool names */
    val toolMapping: Map<String, String> = emptyMap(),
    /** Additional command-line flags to pass to the agent binary */
    val extraArgs: List<String> = emptyList(),
)

/**
 * Top-level external agents section parsed from harness.json.
 */
@Serializable
data class ExternalAgentsConfig(
    val agents: Map<String, ExternalAgentConfig> = emptyMap(),
)

/**
 * Parse external agents configuration from a harness.json JsonObject.
 * Returns empty config if no externalAgents section exists.
 */
fun parseExternalAgentsConfig(json: JsonObject): ExternalAgentsConfig {
    val agentsSection = json["externalAgents"]?.jsonObject ?: return ExternalAgentsConfig()

    val agents = agentsSection.entries.mapNotNull { (key, value) ->
        val obj = value.jsonObject ?: return@mapNotNull null
        ExternalAgentConfig(
            id = key,
            name = obj["name"]?.jsonPrimitive?.content ?: key,
            enabled = obj["enabled"]?.jsonPrimitive?.boolean ?: false,
            autoStart = obj["autoStart"]?.jsonPrimitive?.boolean ?: false,
            provider = obj["provider"]?.jsonPrimitive?.contentOrNull,
            model = obj["model"]?.jsonPrimitive?.contentOrNull,
            workingDir = obj["workingDir"]?.jsonPrimitive?.contentOrNull,
            tier = obj["tier"]?.jsonPrimitive?.contentOrNull,
            toolMapping = obj["toolMapping"]?.jsonObject?.entries?.associate { (k, v) ->
                k to v.jsonPrimitive?.content ?: ""
            } ?: emptyMap(),
            extraArgs = obj["extraArgs"]?.jsonArray?.map { it.jsonPrimitive?.content ?: "" } ?: emptyList(),
        )
    }.associate { it.id to it }

    return ExternalAgentsConfig(agents)
}
