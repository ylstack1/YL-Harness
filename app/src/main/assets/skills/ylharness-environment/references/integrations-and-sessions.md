# Integrations, sessions and context

## Conversation sections

Code operates on assigned workspaces with its exposed tools. Chat is a separate conversation-only section: research, text attachments, supported GitHub reads, memory/recall and inline rich replies. Chat has no project filesystem, shell, root, Shizuku, package manager, saved artifacts or task/subagent tools. Device connections never override that boundary. A Continue in Code offer passes context to a new Code conversation; offering it does not run commands or edit files.

Chat's research Off removes web search while retaining supported direct link/page reading and other tools. Personalization off removes saved-memory and cross-chat recall capabilities and personal instructions for that chat. Use the live schemas, not a remembered tool list from another section. A local model with agent context disabled receives a minimal plain conversation; do not assume Code tools are attached just because it is displayed in the Code section.

## Main and delegated work

A main Code run can use `todo_write` for multi-step progress and `ask_user` for a decision genuinely needing the user. Plan mode ends with its plan and the app's execution controls; it does not request permission by calling a mutating tool. Confirmation modes gate actions independently of root/Shizuku grants. Full access lifts local workspace sandbox checks where supported, not Android permissions, remote-host privileges or selected-workspace grants.

The `task` tool, when advertised, creates a nested agent whose final answer returns to the parent. It is not a separately user-managed conversation. The configured subagent mode determines whether it is read-only or may act under the inherited approval policy. Subagents cannot recursively delegate or ask the user; their exact tool subset is in their current request. The environment guide and runtime snapshot apply to them too. Keep independent read-only explorations parallel when useful; keep dependent edits and stateful browser actions ordered.

## MCP and CodeGraph

Configured MCP servers connect when the app prepares a run, and their tools are attached with names like `mcp__<server>__<tool>`. Use the attached schema, including the exact name; do not invent a generic connector capability. A disconnected server's tools are omitted from the next model request. A newly configured/reconnected server may need the next run to attach a new tool instance. MCP tools follow the harness's approval/access restrictions and are not automatically read-only.

Built-in CodeGraph is a separate integration with its own enable/index/runtime conditions. Availability can change after installation or enabling it; use the refreshed schemas. Loading a skill reference about a capability does not connect an MCP server or install a package.

## Memory, credentials and persistence

`remember`/`recall` operate on the app's reviewable universal memory. Workspace `memory_write`/`memory_read`/`memory_search` use `.harness/memory.md` and topic files. Cross-chat search/snippet tools, when advertised, return bounded context from other conversations and do not give arbitrary access to their workspaces or full logs. Treat recalled material as context, with uncertainty and source attribution when appropriate.

Models/providers, API keys, GitHub sign-in, SSH credentials and MCP OAuth are app settings, not project source. The harness prepares supported credentials for execution without requiring them in the prompt. Never print token files, dump full environments, ask the model to memorize secrets, or copy credentials into skill examples. SSH uses that host's credentials rather than local Android token paths. A login appearing configured does not prove a remote action succeeded.

Telegram and automation entry points reuse the app's run/session/workspace and pending-action mechanisms. They do not create additional tools or bypass grants. Follow the user request associated with this run and preserve ownership when inspecting jobs, artifacts, tool results and file history.
