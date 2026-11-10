# Recovery and diagnosis

## Read the actual evidence

Use the first meaningful error from the real tool call. A generic model explanation, package marker, successful download or connected service is not proof that a build/execution succeeded. Preserve the difference between a compiler error, unavailable command, permission denial, transport disconnect, timeout and provider failure.

- `env_status`: Shizuku runner/identity, selected execution route, Linux/package state, storage, scratch, Java/D8 file hints and related environment details. Execution checks remain separate.
- `doctor`: supported GitHub authentication, materialized configuration and Git helper checks; its available checks are defined by the current schema.
- `read_logcat`: Android exceptions/logs, filtered by package/tag/level/buffer as supported.
- `read_tool_output`: page an earlier tool result from this conversation by ID/offset/limit, including content omitted from the model window. It cannot read another chat's raw output.

Use the smallest relevant check. After fixing a real prerequisite, rerun the operation that demonstrates the user's requested outcome. Do not repeat an unchanged deterministic failure or cycle through speculative command variants. If an external operation may already have occurred before a connection loss, inspect its state before retrying.

## Workspace recovery

Checkpoint/undo state and durable file history are managed separately from Git. They do not authorize resetting unrelated changes. Use `file_history` to inspect recorded versions for the current chat/workspace, then `file_restore` for the intended version. Missing-file recovery and replacement of an existing file have different preconditions. To replace an existing file, inspect it and supply its exact current SHA-256 as the tool requires; the current content is backed up before replacement.

Only captured files/versions can be restored. An absent history entry is not proof that the file never existed. Do not replace an entire workspace or delete recovery data to work around a failed restore. Preserve retryable recovery state and inspect the returned error.

## Runs and background jobs

A foreground command timeout and a tracked background server are different lifecycles. A timeout may preserve partial stdout/stderr; it does not prove all side effects were rolled back. A background launch can fail ambiguously before an ID is confirmed; never silently create a duplicate. `bg_list` distinguishes current chat jobs, other workspace/all jobs and earlier app-session records. Processes owned by different UIDs/transports may need their original permitted route for inspection or termination.

The app persists conversation/run recovery, queued steering, tool results and pending approvals/questions where supported. A resumed task stays bound to its own workspace and permissions. A tool result already recovered from the durable execution ledger must not be treated as a request to repeat the mutation. Report incomplete or uncertain outcomes honestly.
