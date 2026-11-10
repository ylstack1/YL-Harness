package com.androidharness.app.data.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeGraphCommandsTest {
    @Test
    fun `explore shell quotes model supplied queries`() {
        assertEquals(
            """codegraph --no-color explore 'who calls '\''save'\'' and $(touch /tmp/nope)'""",
            CodeGraphCommands.explore("who calls 'save' and $(touch /tmp/nope)"),
        )
    }

    @Test
    fun `node bounds line controls and quotes paths`() {
        assertEquals(
            """codegraph --no-color node --file 'src/it'\''s.kt' --offset 1 --limit 2000 'Thing'""",
            CodeGraphCommands.node("Thing", "src/it's.kt", 0, 9000),
        )
    }

    @Test
    fun `impact and affected clamp traversal depth`() {
        assertEquals(
            "codegraph --no-color impact --depth 10 'render'",
            CodeGraphCommands.impact("render", 99),
        )
        assertEquals(
            "codegraph --no-color affected --depth 1 'a.kt' 'space name.kt'",
            CodeGraphCommands.affected(listOf("a.kt", "space name.kt"), 0),
        )
    }

    @Test
    fun `launcher resolves its prefix from its own location`() {
        val script = CodeGraphCommands.launcher()
        // $0 relative on purpose: the same file is used from the app-private
        // prefix and from the deployed shell-tier copy under /data/local/tmp,
        // and only one of those paths is known when it is written.
        assertTrue(script.contains("""DIR="${'$'}{0%/*}""""))
        assertTrue(script.contains("""PREFIX="${'$'}(cd "${'$'}DIR/.." && pwd)""""))
        assertTrue(script.contains("""NODE="${'$'}PREFIX/codegraph/node/bin/node""""))
        assertTrue(script.contains("""CLI="${'$'}PREFIX/codegraph/bundle/lib/dist/bin/codegraph.js""""))
    }

@Test
    fun `launcher pins the openssl config and disables the linker-hostile watchdog`() {
        val script = CodeGraphCommands.launcher()
        // Node's built-in config path is /data/data/com.termux/..., which is
        // EACCES (not missing) when Termux is installed, and fatal at startup.
        assertTrue(script.contains("OPENSSL_CONF=\"${'$'}PREFIX/etc/tls/openssl.cnf\""))
        // CodeGraph's watchdog re-runs node through process.execPath, which is
        // the linker on this launch path, so its child dies on "-e".
        assertTrue(script.contains("CODEGRAPH_NO_WATCHDOG=1"))
    }

    @Test
    fun `launcher turns codegraph telemetry off`() {
        val script = CodeGraphCommands.launcher()
        // Telemetry is on by default upstream, and the env vars outrank any
        // stored consent, so both are exported before every invocation.
        assertTrue(script.contains("DO_NOT_TRACK=1"))
        assertTrue(script.contains("CODEGRAPH_TELEMETRY=0"))
        assertTrue(script.contains("export DO_NOT_TRACK CODEGRAPH_TELEMETRY"))
    }

    @Test
    fun `the stored telemetry consent says off and keeps the machine id`() {
        val fresh = CodeGraphProvision.telemetryOffConfig(null, "11111111-2222-3333-4444-555555555555")
        assertTrue(CodeGraphProvision.telemetryIsOff(fresh))
        assertTrue(fresh.contains("11111111-2222-3333-4444-555555555555"))
        assertTrue(fresh.contains("\"androidharness\""))

        // An existing id is preserved: a fresh one would read as a new machine.
        val existing = """{"enabled": true, "machine_id": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"}"""
        val rewritten = CodeGraphProvision.telemetryOffConfig(existing, "unused")
        assertTrue(rewritten.contains("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
        assertFalse(rewritten.contains("unused"))

        assertFalse(CodeGraphProvision.telemetryIsOff("""{"enabled": true}"""))
        assertFalse(CodeGraphProvision.telemetryIsOff(null))
    }

    @Test
    fun `launcher starts the runtime through the linker, never through the prefix node`() {
        val script = CodeGraphCommands.launcher()
        // App-private files cannot be exec'd directly on W^X devices, so the
        // dynamic linker starts the runtime exactly like the shell shims do.
        assertTrue(script.contains("exec \"${'$'}LINKER\" \"${'$'}NODE\""))
        assertTrue(script.contains("--liftoff-only"))
        // A "node" shebang would make the harness shim route this script
        // through the prefix's Node 25+, which CodeGraph refuses to start on.
        assertFalse(script.lineSequence().first().contains("node"))
    }

    @Test
    fun `release tag is read from a redirect, a page, the feed or the api`() {
        assertEquals(
            "v1.6.0",
            CodeGraphProvision.tagFromUrl("https://github.com/colbymchenry/codegraph/releases/tag/v1.6.0"),
        )
        assertNull(CodeGraphProvision.tagFromUrl("https://github.com/colbymchenry/codegraph/releases/latest"))
        assertNull(CodeGraphProvision.tagFromUrl(null))
        // The download URL names the release too, which is how an install
        // learns what it just fetched without a metadata request.
        assertEquals(
            "v1.6.0",
            CodeGraphProvision.tagFromUrl(
                "https://github.com/colbymchenry/codegraph/releases/download/v1.6.0/codegraph-linux-arm64.tar.gz",
            ),
        )
        // A page is full of links, so only a version-shaped tag counts.
        assertEquals(
            "v1.6.0",
            CodeGraphProvision.tagFromPage(
                """<a href="/colbymchenry/codegraph/releases/tag/v1.6.0">v1.6.0</a>
                   <a href="/colbymchenry/codegraph/releases/tag/v1.5.0">v1.5.0</a>""",
            ),
        )
        assertNull(CodeGraphProvision.tagFromPage("""<a href="/colbymchenry/codegraph/releases/tag/downloads">x</a>"""))
        assertEquals("v1.6.0", CodeGraphProvision.tagFromApi("""{"tag_name": "v1.6.0", "name": "x"}"""))
        assertNull(CodeGraphProvision.tagFromApi("{}"))
        assertEquals("v1.6.0", CodeGraphProvision.tagFromManifest("""{"name":"codegraph","version":"1.6.0"}"""))
        assertNull(CodeGraphProvision.tagFromManifest("""{"name":"codegraph"}"""))
    }

    @Test
    fun `the newest archive is addressable without naming a release`() {
        assertTrue(
            CodeGraphProvision.latestAssetUrl()
                .endsWith("/releases/latest/download/${CodeGraphProvision.BUNDLE_ASSET}"),
        )
        assertTrue(
            CodeGraphProvision.latestSumsUrl()
                .endsWith("/releases/latest/download/${CodeGraphProvision.SUMS_ASSET}"),
        )
    }

    @Test
    fun `tags normalize to the v prefix used by release urls`() {
        assertEquals("v1.6.0", CodeGraphProvision.normalizeTag("1.6.0"))
        assertEquals("v1.6.0", CodeGraphProvision.normalizeTag("v1.6.0"))
        assertEquals("v1.6.0", CodeGraphProvision.normalizeTag(" v1.6.0 "))
        assertNull(CodeGraphProvision.normalizeTag("  "))
        assertNull(CodeGraphProvision.normalizeTag(null))
    }

    @Test
    fun `a release tag yields its version`() {
        // The bug this guards: the version numbers used to be fenced with \b,
        // and there is no word boundary between the "v" of a tag and the digit
        // after it, so every tag came back unreadable and the update check
        // blamed the network for it.
        assertEquals("1.6.0", CodeGraphProvision.versionIn("v1.6.0"))
        assertEquals("1.6.0", CodeGraphProvision.versionIn("1.6.0"))
        assertEquals("1.6.0", CodeGraphProvision.versionIn(" v1.6.0\n"))
        // What `codegraph version` prints.
        assertEquals("1.6.0", CodeGraphProvision.versionIn("1.6.0\n"))
        assertEquals("1.6.0", CodeGraphProvision.versionIn("codegraph 1.6.0"))
        // Prerelease suffixes survive, since CodeGraph reports them that way.
        assertEquals("1.6.0-rc.1", CodeGraphProvision.versionIn("v1.6.0-rc.1"))
        assertNull(CodeGraphProvision.versionIn("no version here"))
        assertNull(CodeGraphProvision.versionIn(""))
        assertNull(CodeGraphProvision.versionIn(null))
    }

    @Test
    fun `checksum lookup matches the published sums file`() {
        val sums = listOf(
            "${"a".repeat(64)}  codegraph-darwin-arm64.tar.gz",
            "${"B".repeat(64)}  *codegraph-linux-arm64.tar.gz",
        ).joinToString("\n")
        assertEquals("b".repeat(64), CodeGraphProvision.sha256For(sums, CodeGraphProvision.BUNDLE_ASSET))
        assertNull(CodeGraphProvision.sha256For(sums, "codegraph-win32-x64.zip"))
    }

    @Test
    fun `release tag is read from the feed as well as the redirect and the api`() {
        val feed = """
            <feed><entry><title>v1.6.0</title><updated>x</updated></entry>
            <entry><title>v1.5.0</title></entry></feed>
        """.trimIndent()
        assertEquals("v1.6.0", CodeGraphProvision.tagFromAtom(feed))
        // A named release still yields its version.
        assertEquals(
            "v2.1.3",
            CodeGraphProvision.tagFromAtom("<entry><title>Spring clean v2.1.3</title></entry>"),
        )
        assertNull(CodeGraphProvision.tagFromAtom("<feed></feed>"))
        assertNull(CodeGraphProvision.tagFromAtom("<entry><title>no version here</title></entry>"))
    }

    @Test
    fun `only a later release counts as an update`() {
        assertTrue(CodeGraphProvision.isNewer("1.7.0", "1.6.0"))
        assertTrue(CodeGraphProvision.isNewer("1.6.1", "1.6.0"))
        assertTrue(CodeGraphProvision.isNewer("2.0.0", "1.9.9"))
        assertTrue(CodeGraphProvision.isNewer("1.10.0", "1.9.0"))
        // The installed build IS the release the check just found.
        assertFalse(CodeGraphProvision.isNewer("1.6.0", "1.6.0"))
        assertFalse(CodeGraphProvision.isNewer("1.5.9", "1.6.0"))
        assertFalse(CodeGraphProvision.isNewer("1.6.0", "1.6.0-rc.1"))
    }

    @Test
    fun `only the platform independent payload survives unpacking`() {
        // Kept: the CLI and its grammars.
        assertEquals(
            "lib/dist/bin/codegraph.js",
            CodeGraphProvision.portablePath("codegraph-linux-arm64/lib/dist/bin/codegraph.js"),
        )
        assertEquals(
            "lib/node_modules/tree-sitter-wasms/out/tree-sitter-java.wasm",
            CodeGraphProvision.portablePath(
                "codegraph-linux-arm64/lib/node_modules/tree-sitter-wasms/out/tree-sitter-java.wasm",
            ),
        )
        // Dropped: the archive's glibc Node and native kernel, and dev files.
        assertNull(CodeGraphProvision.portablePath("codegraph-linux-arm64/node"))
        assertNull(CodeGraphProvision.portablePath("codegraph-linux-arm64/lib/kernel/codegraph-kernel.node"))
        assertNull(CodeGraphProvision.portablePath("codegraph-linux-arm64/lib/dist/index.js.map"))
        assertNull(CodeGraphProvision.portablePath("codegraph-linux-arm64/lib/dist/index.d.ts"))
        // Refused: entries trying to escape the destination.
        assertNull(CodeGraphProvision.portablePath("codegraph-linux-arm64/lib/../../etc/passwd"))
        assertNull(CodeGraphProvision.portablePath("./../lib/dist/index.js"))
    }
}