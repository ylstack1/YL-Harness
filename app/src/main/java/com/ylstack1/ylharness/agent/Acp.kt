package com.ylstack1.ylharness.agent

import kotlinx.serialization.json.JsonObject

/**
 * Shared types for the Agent Client Protocol (ACP) v1 client used to drive
 * ACP-capable coding agents (OpenCode via `opencode acp`) as subprocesses.
 * Wire shapes follow agentclientprotocol.com v1 and OpenCode's own
 * packages/opencode/src/acp implementation.
 */

/** Result of one ACP prompt turn. */
data class AcpTurn(
    /** end_turn | max_tokens | max_turn_requests | refusal | cancelled | unknown */
    val stopReason: String,
    /** Concatenated agent_message_chunk text. */
    val text: String,
    /** Concatenated agent_thought_chunk (reasoning) text. */
    val thinking: String,
    /** Tool calls reported during the turn, merged by toolCallId, in first-seen order. */
    val toolCalls: List<AcpToolCall>,
)

/** A tool call reported by the agent during a turn. */
data class AcpToolCall(
    val toolCallId: String,
    val title: String? = null,
    /** read | edit | delete | move | search | execute | think | fetch | switch_mode | other */
    val kind: String? = null,
    /** pending | in_progress | completed | failed */
    val status: String? = null,
)

/** One selectable option of a session/request_permission request. */
data class AcpPermissionOption(
    val optionId: String,
    val name: String,
    /** allow_once | allow_always | reject_once | reject_always */
    val kind: String,
)

/** An agent request for user permission before a sensitive operation. */
data class AcpPermissionRequest(
    val toolCallId: String?,
    val title: String?,
    val kind: String?,
    /** Absolute paths the operation touches (locations[].path). */
    val locations: List<String>,
    /** The tool's raw input object, for diff/command display. */
    val rawInput: JsonObject?,
    val options: List<AcpPermissionOption>,
)

/** The user's decision on a permission request. */
sealed interface AcpPermissionOutcome {
    data class Selected(val optionId: String) : AcpPermissionOutcome

    /** The turn was cancelled before the user answered; agents treat this as reject. */
    data object Cancelled : AcpPermissionOutcome
}

/**
 * Client-side operations an ACP agent may ask of the app: filesystem access
 * within the session workspace, and user permission for sensitive operations.
 *
 * OpenCode's own tools read its filesystem directly, but an approved `edit`
 * operation is applied by pushing the computed new content to the client via
 * fs/write_text_file — a client that does not implement it silently drops
 * edits. fs/read_text_file is implemented for future agents that rely on it.
 */
interface AcpBridge {
    suspend fun fsRead(path: String, line: Int?, limit: Int?): String

    /** Must create the file if it does not exist (ACP spec). */
    suspend fun fsWrite(path: String, content: String)

    suspend fun permission(request: AcpPermissionRequest): AcpPermissionOutcome
}

/** A JSON-RPC error returned by the agent, e.g. -32000 auth_required. */
class AcpAgentException(val code: Int, message: String) : Exception(message)
