# Project Handoff — External Agent Integration (Pi)

**Status:** Ready for continuation  
**Repository:** `ylstack1/YL-Harness`  
**Branch:** `main`  
**CI Status:** ✅ Passing  
**Working Tree:** Clean  
**Last Commit:** `a1928d0`

---

## Repository Summary

| Item | Value |
|------|-------|
| GitHub URL | https://github.com/ylstack1/YL-Harness |
| Default Branch | `main` |
| Package Name | `com.ylstack1.ylharness` |
| Build System | Gradle (AGP) |
| CI | GitHub Actions (`Debug build` workflow) |
| License | MIT |

---

## What's Complete

### Phase 1: External Agent Core Infrastructure ✅

| File | Purpose | Path |
|------|---------|------|
| `ExternalAgentConfig` | Data class for agent configuration | `app/src/main/java/com/ylstack1/ylharness/config/HarnessConfig.kt` |
| `PiProcessManager` | Download, start, stop Pi binary lifecycle | `app/src/main/java/com/ylstack1/ylharness/agent/PiProcessManager.kt` |
| `PiAgentAdapter` | JSON-RPC protocol adapter (stdio-based) | `app/src/main/java/com/ylstack1/ylharness/agent/PiAgentAdapter.kt` |
| `PiToolBridge` | Pi tool → AndroidHarness tool mapping | `app/src/main/java/com/ylstack1/ylharness/agent/PiToolBridge.kt` |
| `ExternalAgentManager` | Orchestrator with ToolRegistry integration | `app/src/main/java/com/ylstack1/ylharness/agent/ExternalAgentManager.kt` |
| `ExternalAgentTool` | `external_agent()` tool for agent engine | `app/src/main/java/com/ylstack1/ylharness/tools/ExternalAgentTool.kt` |

### Phase 2: Agent Engine Integration ✅

- `ExternalAgentTool` registered in `ToolRegistry.default()`
- `ExternalAgentManager` wired into `HarnessApp` with circular dependency resolved via `init {}` block
- `HarnessConfig.externalAgents` parsed from `harness.json`
- `externalAgents` section added to `docs/harness.schema.json`

---

## Key Technical Details

### harness.json Configuration

```json
{
  "externalAgents": {
    "pi": {
      "enabled": true,
      "provider": "openai",
      "model": "gpt-4o",
      "toolMapping": {
        "read": "read_file",
        "write": "write_file",
        "bash": "shell"
      }
    }
  }
}
```

### Agent Invocation

```
external_agent({ "agent": "pi", "prompt": "Explain this codebase structure" })
```

### Tool Mapping (Pi → AndroidHarness)

| Pi Tool | AndroidHarness Tool |
|---------|---------------------|
| read / read_file | read_file |
| write / write_file | write_file |
| edit / edit_file | edit_file |
| list / list_dir | list_dir |
| grep / search | grep |
| bash / shell / exec | shell |
| python | shell |
| patch / apply_patch | apply_patch |
| move / move_file | move_file |
| delete / delete_file | delete_file |
| file_info | file_info |

### RPC Protocol (Pi)

- Start command: `pi --mode rpc --no-session <working-dir>`
- stdin: JSON commands `{"id": "...", "type": "prompt", "message": "..."}`
- stdout: JSONL responses with matching `id` + streaming session events
- Tool calls from Pi are intercepted via `addListener` and routed through `PiToolBridge` → `ToolRegistry`

### Circular Dependency Pattern

`ExternalAgentManager.toolRegistry` is set **after** `ToolRegistry.default()` returns, inside an `init {}` block in `HarnessApp.kt`:

```kotlin
init {
    externalAgentManager.toolRegistry = registry
}
```

---

## Files Removed to Fix CI

The following were temporarily removed to get CI passing. They need to be re-added with proper localization:

| File | Reason Removed | Re-add Checklist |
|------|---------------|------------------|
| `ExternalAgentsSection.kt` | Settings UI not localized | Recreate composable + add LearnCatalog tutorial entry |
| `SettingsPage.EXTERNAL_AGENTS` enum entry | Test failure (exhaustive `when`) | Re-add to `SettingsNavigation.kt`, all `when` expressions, icon map, string resources |
| `settings_page_external_agents_title` / `_desc` | Test failure | Add to all `values-*` folders |

---

## How to Continue Development

### 1. Clone and Set Up

```bash
git clone https://github.com/ylstack1/YL-Harness.git
cd YL-Harness
git checkout main
```

### 2. Build and Test Locally

```bash
./gradlew :app:testDebugUnitTest
```

### 3. Add OpenCode Agent Support

Follow the same pattern as Pi:

1. Create `OpenCodeAgentAdapter.kt` (JSON-RPC over stdio, per `opencode.ai/v2/docs/build/plugins/rpc/`)
2. Create `OpenCodeProcessManager.kt` (download/start/stop OpenCode binary)
3. Add `opencode` to `externalAgents` config parsing in `HarnessConfig.kt`
4. Add tool mapping in `PiToolBridge.kt` (or create shared `ToolBridge`)

### 4. Add Claude Code Agent Support

1. Create `ClaudeCodeAdapter.kt` (uses native binary + Agent SDK)
2. Add `claude-code` to `externalAgents` config
3. Add tool mapping

### 5. Re-add Settings UI

1. Recreate `ExternalAgentsSection.kt` composable
2. Add `EXTERNAL_AGENTS` to `SettingsPage` enum in `SettingsNavigation.kt`
3. Add string resources to `app/src/main/res/values/strings.xml` and all `values-*` folders
4. Add icon mapping in `SettingsHome.settingsIcon()`
5. Add case in `SettingsScreen.kt` `when` expression
6. Add LearnCatalog tutorial entry in `src/main/assets/learn/guide.json` (required by `LearnCatalogTest`)

### 6. Run CI

Push to a feature branch; GitHub Actions will run the `Debug build` workflow.

---

## Known Issues / Gotchas

| Issue | Solution |
|-------|----------|
| `Result.Failure` / `getOrDefault()` not available | Use `getOrNull()` or check `isSuccess` / `getOrThrow()` |
| `jsonPrimitive.contentOrNull` unresolved | Use `.content` instead |
| `when` expressions on `SettingsPage` are exhaustive | Adding a new enum value requires updating **all** `when` blocks |
| `LearnCatalogTest` fails for new `SettingsPage` | Add tutorial entry in `src/main/assets/learn/guide.json` |
| `LocalizationResourcesTest` fails for new strings | Add translations in all `values-*` folders |
| Suspending functions in non-suspend listeners | Use `GlobalScope.launch(Dispatchers.IO) { ... }` |
| Circular dependency between `ToolRegistry` and `ExternalAgentManager` | Set `toolRegistry` in `init {}` block after registry creation |

---

## GitHub PRs (All Merged)

| PR | Title | Status |
|----|-------|--------|
| #1 | Add Pi external agent integration (Phase 1) | Merged |
| #2 | Phase 2: Agent Engine integration | Merged |
| #3 | Fix HarnessApp.kt syntax error | Merged |
| #4 | Fix old package imports | Merged |

---

## Next Recommended Steps

1. **Re-add Settings UI** with proper localization (string resources for all languages)
2. **Add OpenCode agent support** — same pattern as Pi, uses RPC
3. **Add Claude Code agent support** — uses native binary + Agent SDK
4. **Add Propt sandbox tier** — optional glibc sandbox for agents requiring it
5. **Add end-to-end device tests** for external agent invocation

---

## Contact

- Repository: https://github.com/ylstack1/YL-Harness
- Issues: https://github.com/ylstack1/YL-Harness/issues
- PRs: https://github.com/ylstack1/YL-Harness/pulls
