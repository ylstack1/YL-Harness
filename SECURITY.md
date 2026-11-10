# Security Policy

AndroidHarness can read and change files, execute programs, connect to external
services, and use privileged device access when enabled. Use it only with
projects, tools, models, and services you trust. This policy describes the
security boundaries and how to report problems; it is not a security guarantee.

## Supported Versions

Report against the latest stable release when possible. Include the exact app
version and, for source or nightly builds, the commit ID. Security fixes target
the current release; there is no published long-term support or guaranteed
backport schedule for older releases.

Nightly, debug, and locally modified builds are development builds. Do not assume
they have the same signing identity or hardening as a production release.

## Reporting a Vulnerability

Use **Report a vulnerability** from the repository's
[security advisories page](https://github.com/Sanuu7/AndroidHarness/security/advisories)
when GitHub private vulnerability reporting is available. Adding this policy
file does not itself enable private reporting.

If that option is unavailable, open an
[issue requesting a private security contact](https://github.com/Sanuu7/AndroidHarness/issues/new?title=Private%20security%20reporting%20contact).
Include no vulnerability details, credentials, sensitive logs, or working
exploit in that public issue. GitHub documents this fallback in its
[private reporting guidance](https://docs.github.com/en/code-security/how-tos/report-and-fix-vulnerabilities/report-privately).

A private report should include:

- App version or commit, Android version, and affected feature.
- Relevant access and permission settings, including whether privileged access,
  remote control, or automatic execution was enabled.
- Minimal reproduction steps using disposable files and accounts, expected and
  actual behavior, and the resulting security impact.
- Sanitized logs or a small proof of concept where needed. Remove API keys,
  passwords, private keys, tokens, pairing codes, and unrelated personal data.

Unexpected credential exposure, permission or approval bypasses, workspace
escapes, unsafe update acceptance, and unauthorized remote actions are in scope.
Documented limitations do not make a bypass of a user-facing restriction harmless.

Please coordinate disclosure while a report is assessed. The project does not
currently publish a guaranteed response time, remediation deadline, or bug bounty.
Do not test against other people's devices, accounts, or services.

## Execution and Workspace Boundaries

**Workspace selection, tool permissions, and shell policy are best-effort
application checks, not a complete filesystem or process sandbox.** The selected
execution backend and the operating system determine the actual accessible data.

- Ordinary local programs run as the app's Android UID. They can access data
  available to that UID, including app-private runtime files. A project folder
  does not create a separate OS user or isolate an interpreter or installed tool.
- Path checks must not be relied on to contain arbitrary code or existing
  symbolic links. Treat untrusted project files and scripts as executable risks.
- Remembered shell approvals are command-prefix grants, not approvals of an exact
  complete command. Inspect compound commands and use **Confirm all** when
  reviewing unfamiliar work.
- Full access deliberately skips the normal shell denylist and broadens file
  access. Root and Shizuku can further extend OS-level access. Enable these only
  for a task that needs them, and revoke or pause them when finished.
- Read-only workspace selection restricts the app's supported tool routes; it
  does not turn separately launched programs, remote servers, or the device into
  a read-only environment.

Repository instructions, model replies, web pages, command output, and MCP tool
results can contain malicious instructions. Review proposed actions rather than
assuming AI-generated commands or third-party instructions are safe.

Relevant code: [workspace access](app/src/main/java/com/androidharness/app/workspace/WorkspaceFs.kt),
[tool routing](app/src/main/java/com/androidharness/app/tools/WorkspaceToolRouting.kt),
[shell policy](app/src/main/java/com/androidharness/app/tools/ShellPolicy.kt),
[permission checks](app/src/main/java/com/androidharness/app/agent/AgentEngine.kt), and
[device access](app/src/main/java/com/androidharness/app/data/env/DeviceAccessPolicy.kt).

## Credentials, Local Data, and Backups

| Data | Current handling and limits |
| --- | --- |
| Provider keys, saved sign-in state, SSH credentials, and sync credentials | Stored through Android Keystore-backed encrypted preferences. This does not protect them from a compromised OS or all code running with the app's own authority. |
| Runtime credentials and MCP configuration | Tooling may materialize credentials in app-private files. MCP command arguments, environment values, and static headers are stored in its configuration file; the configuration HMAC is an integrity check, not encryption or a sandbox. |
| Chats, memory, checkpoints, workspace files, and tool logs | Stored locally, without a blanket application-level encryption guarantee. Shared-storage and remote workspaces have their own access rules. |
| Settings exports | Password-encrypted with AES-256-GCM and PBKDF2-HMAC-SHA256. Credentials are included only when selected. Use a long, unique password; an exported file can be attacked offline. |
| Chat exports, shared files, and other exports | Do not assume settings-backup encryption applies to them. Anyone receiving the exported or shared data may be able to read it. |
| Android backup and device transfer | Rules exclude selected credential, configuration, Telegram, and toolchain locations. Other app data may still be backed up according to Android and device settings. |

App locking and screenshot protections reduce casual exposure; they do not
replace device security, file encryption, or careful handling of exports. Avoid
putting secrets in prompts, project instructions, command arguments, or logs.

Relevant code: [credential storage](app/src/main/java/com/androidharness/app/data/KeyStoreManager.kt),
[backup encryption](app/src/main/java/com/androidharness/app/data/BackupEncryption.kt),
[settings backups](app/src/main/java/com/androidharness/app/data/SettingsBackup.kt),
[backup rules](app/src/main/res/xml/backup_rules.xml), and
[device-transfer rules](app/src/main/res/xml/data_extraction_rules.xml).

## Network Services and Remote Access

- **AI providers and research tools:** prompts, selected context, images, files,
  or command output may be sent to configured services. Custom HTTP endpoints
  are supported; prefer HTTPS and verify the destination before supplying data
  or credentials. Local models do not make unrelated network tools offline.
- **MCP:** connecting a server can execute its local command or contact a remote
  service. Workspace configuration has a content-based approval check, and MCP
  tool calls enter the normal permission flow. OAuth uses state and PKCE, but
  these mechanisms do not establish that a configured server or endpoint is
  trustworthy. Review commands, environment variables, headers, and sign-in hosts.
- **SSH/SFTP:** connections use a saved host-key fingerprint and strict host-key
  checking. Verify the initial fingerprint through a trusted channel; do not
  accept an unexpected replacement just to clear a connection error.
- **FTP:** both FTP sharing and FTP sync use unencrypted FTP. Login credentials
  and files can be observed in transit. LAN sharing exposes a listener beyond
  loopback when selected. Use trusted networks, narrow folders, read-only sharing
  where appropriate, and stop sharing after use. Prefer SFTP for sensitive data.
- **Telegram:** enabling the gateway authorizes a remote account to submit work
  and interact with approvals. The gateway checks its paired owner and uses
  expiring, single-use callback actions. Protect the bot token and paired account,
  review the selected execution permissions, and disable the gateway when unused.
- **Root, Shizuku, and wireless debugging:** these are powerful developer access
  paths. An enabled connection is not a reason to trust untrusted code. Revoke
  grants and debugging pairings in the appropriate app or Android settings.

Relevant code: [MCP](app/src/main/java/com/androidharness/app/tools/mcp/),
[SSH](app/src/main/java/com/androidharness/app/workspace/SshConnections.kt),
[file transfers](app/src/main/java/com/androidharness/app/data/transfer/), and
[Telegram](app/src/main/java/com/androidharness/app/telegram/).

## Browser, Previews, and Local Models

The agent browser can execute website JavaScript, retain browsing state, and
interact with pages. Workspace previews execute project content. Do not treat
browser state, synthetic preview origins, or a selected workspace as an isolated
browser profile for mutually untrusted projects or accounts.

Interactive artifacts use a separate WebView with network and file/content
access blocked and no native JavaScript bridge. The bundled editor service has
its own restricted asset loader and bridge. These controls do not establish that
every WebView or third-party rendering dependency is free of vulnerabilities.

Local model files are parsed by native code. Download or import them only from
sources you trust. Catalog checksums detect mismatched bytes where an expected
checksum exists; they do not independently prove a model publisher is trustworthy.
CPU and experimental GPU execution are not additional security sandboxes.

Relevant code: [browser](app/src/main/java/com/androidharness/app/browser/),
[artifact renderer](app/src/main/java/com/androidharness/app/artifacts/ArtifactWebView.kt),
[editor service](app/src/main/java/com/androidharness/app/ui/files/SmartEditorEngine.kt), and
[local inference](app/src/main/java/com/androidharness/app/local/).

## Updates and Supply Chain

The in-app updater restricts accepted release-asset URLs, checks download size
and an advertised checksum when present, and validates package identity,
non-decreasing version, and signing compatibility with the installed app before
installation. A checksum alone does not authenticate a publisher. Start from an
installation and signing identity you trust.

Build dependencies, native libraries, downloaded developer packages, and their
upstream services are part of the trust boundary. Package or version pinning and
checksums do not replace dependency advisory review. Keep Android and WebView
updated, and use the least CI and account permissions needed.

Relevant code: [update validation](app/src/main/java/com/androidharness/app/data/update/),
[toolchain downloads](app/src/main/java/com/androidharness/app/data/env/LinuxEnvironment.kt),
[native build](app/src/main/cpp/CMakeLists.txt), and
[CI workflow](.github/workflows/nightly.yml).

## Review Scope and Verification

Updated on **October 28, 2026**, starting from the existing GitHub policy. The
review included a repository-wide text/source survey and focused inspection of
Android entry points, execution and workspace controls, credentials and backups,
network services, OAuth, WebViews, model handling, updates, and build configuration.
Generated outputs, private local credentials, signing material, and user
attachments were excluded. Bundled or upstream dependencies were not exhaustively
audited or checked against a complete vulnerability database.

Four isolated JVM probes of the current filesystem and shell-policy sources
reproduced the workspace and remembered-approval limitations described above.
Those checks do not validate the complete app or its privileged/native backends.

The working tree included in-progress wireless-debugging and native-pairing
changes. The full working-tree unit-test attempt stopped at Kotlin compilation
errors in those changes; no full-suite pass is claimed for this review. Native
pairing, privileged execution, device behavior, and cross-workspace browser
isolation were not validated on hardware as part of this documentation update.

This review does not certify the app as vulnerability-free, and documenting a
limitation does not fix it. See [PRIVACY.md](PRIVACY.md) for the data-handling policy.
