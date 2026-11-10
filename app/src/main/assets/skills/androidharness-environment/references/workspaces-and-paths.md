# Workspaces and paths

## Ownership and scope

A Code conversation captures its assigned workspace(s). Choosing a different project elsewhere in the app does not move an existing chat. Use the workspace names, stable IDs and paths in the live context. For a multi-workspace chat, omit `workspace_id` only for its primary workspace. File paths are relative to that selected root. Saved UI references like `@workspaces/<id>/path` identify a workspace; pass the ID and relative path separately to tools.

A workspace can be writable or read only. Each file operation keeps that grant. If any selected workspace is read only, commands, Git and workspace integrations are disabled for the selection to prevent indirect writes. Read-only file tools and global read-only capabilities may remain available. Use only the current schemas. Ask the user to change a workspace grant in Workspaces if a requested task needs it; do not bypass it through shell or HTTP.

## Filesystem kinds

- A local app workspace has a real command directory, normally inside the app's external-files `workspace` directory. Its name does not mean it has privileged access. The app UID is the ordinary development route.
- An imported device folder also has a real command directory when Android can resolve the path. Access depends on storage permissions and the selected execution route. An apparent `/sdcard` path does not imply execute or symlink support.
- An Android document-tree (SAF) workspace is accessed through the user's folder grant. File tools operate through document nodes. If the live context reports no shell cwd, shell, background commands and Git cannot operate on that tree. Do not assume there is a matching real path or silently copy the project elsewhere.
- An SSH workspace uses its remote root, host files, packages, Git credentials and processes. Local Android paths, Shizuku and root grants do not become privileges on that host. Use the selected workspace's file tools and commands; never substitute a local checkout after a remote failure.

Read `AGENTS.md` or `HARNESS.md` in the target workspace before changing that project. A repo map is an orientation aid, not the current contents of every file. Read cited files before editing. Use `read_image` for images and `file_info` for file metadata rather than guessing bytes or newlines from text rendering.

## Where runtime state belongs

The live context supplies actual app and shell prefixes. Do not hard-code the production or debug package name.

| Location | Purpose and access |
| --- | --- |
| Workspace root | User's source files; normal tools use relative paths |
| `.harness/screenshots/` | Browser screenshots saved in the selected workspace when supported |
| `.harness/memory.md` | Core workspace memory, accessed with memory tools |
| `.harness/memory/<topic>.md` | Additional workspace memory topics |
| `.harness/skills/<name>/SKILL.md` | Project skill overrides for the primary workspace |
| App files `skills/` | User skills shared across projects |
| Bundled skill assets | Built into the APK; read with `skill_view`, not guessed filesystem paths |
| App Linux prefix | Installed packages in app-private storage |
| Deployed Linux prefix | Separate copy for the Shizuku/shell UID under the harness's package-specific `/data/local/tmp` directory |
| `HARNESS_SCRATCH` | Exec-capable scratch for the active execution tier |
| App package caches | Managed download/package caches; not source-project output |

Images, conversation records, file-history backups, credentials and saved artifact revisions also have app-managed stores. Use their tools, not guessed database/cache paths. Never treat deleting a workspace registration as permission to erase its source folder, or erase an environment to solve a missing package.

## Skills

The catalog merges bundled skills, user skills and the primary workspace's project skills. A project override wins over a user override; a user override wins over the bundled version. Disabled skills are not loaded. Use `skills_list` and `skill_view` when advertised. `skill_view` loads the main guide or one support file under `references/`, `templates/`, `scripts/` or `assets/`. Loading a script as text does not execute it or place it in a shell directory. `skill_manage` creates/patches user copies; it does not edit the APK's bundled assets or silently modify project files.
