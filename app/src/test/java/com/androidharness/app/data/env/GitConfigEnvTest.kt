package com.androidharness.app.data.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The bundled git is Termux-built, so it reads its SYSTEM config from
 * /data/data/com.termux/files/usr/etc/gitconfig. When the Termux app is
 * installed that file exists, is mode 600 in another app's private data, and
 * the shizuku shell uid gets EACCES on it, which aborts git before any
 * subcommand runs:
 *   fatal: unable to access '…/etc/gitconfig': Permission denied
 * (on-device QA, 2026-09-17). The env decision below is what keeps git out of
 * that file; these tests pin every branch of it.
 */
class GitConfigEnvTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a readable termux system config is named explicitly`() {
        val cfg = tmp.newFile("termux-gitconfig")
        val env = gitSystemConfigEnvOf(termuxSystemConfig = cfg, systemConfig = tmp.newFile("etc-gitconfig"))
        assertEquals("0", env["GIT_CONFIG_NOSYSTEM"])
        assertEquals(cfg.absolutePath, env["GIT_CONFIG_SYSTEM"])
    }

    @Test
    fun `an unreadable termux system config turns the system scope off`() {
        val cfg = tmp.newFile("termux-gitconfig")
        cfg.writeText("[safe]\n\tdirectory = *\n")
        assertTrue(cfg.setReadable(false, false))
        // Running as root would defeat the permission bits; the branch is only
        // meaningful for an unprivileged uid.
        assumeTrue(!cfg.canRead())

        val env = gitSystemConfigEnvOf(termuxSystemConfig = cfg, systemConfig = tmp.newFile("etc-gitconfig"))
        assertEquals("1", env["GIT_CONFIG_NOSYSTEM"])
        assertFalse(env.containsKey("GIT_CONFIG_SYSTEM"))
    }

    @Test
    fun `a missing termux config falls back to the standard path`() {
        val system = tmp.newFile("etc-gitconfig")
        val env = gitSystemConfigEnvOf(
            termuxSystemConfig = tmp.root.resolve("absent-gitconfig"),
            systemConfig = system,
        )
        assertEquals("0", env["GIT_CONFIG_NOSYSTEM"])
        assertEquals(system.absolutePath, env["GIT_CONFIG_SYSTEM"])
    }

    @Test
    fun `no system config anywhere turns the system scope off`() {
        val env = gitSystemConfigEnvOf(
            termuxSystemConfig = tmp.root.resolve("absent-termux"),
            systemConfig = tmp.root.resolve("absent-etc"),
        )
        assertEquals("1", env["GIT_CONFIG_NOSYSTEM"])
        assertFalse(env.containsKey("GIT_CONFIG_SYSTEM"))
    }

    @Test
    fun `an unreadable termux config never resolves to the standard path`() {
        // Even when /etc/gitconfig is readable, the handler must not let a
        // Termux-built git stay on its compiled-in prefix: the dead path is
        // reported as "no system config" instead of silently skipped.
        val cfg = tmp.newFile("termux-gitconfig")
        assertTrue(cfg.setReadable(false, false))
        assumeTrue(!cfg.canRead())

        val env = gitSystemConfigEnvOf(
            termuxSystemConfig = cfg,
            systemConfig = tmp.newFile("etc-gitconfig"),
        )
        assertEquals("1", env["GIT_CONFIG_NOSYSTEM"])
    }

    /**
     * The same re-rooting, non-fatal version: an unreadable system gitattributes
     * file does not stop git, it just prints
     *   warning: unable to access '…/etc/gitattributes': Permission denied
     * on every single invocation (3-5 stderr lines per git tool call, on-device
     * QA 2026-09-17). git has no GIT_ATTR_SYSTEM to name a readable file with,
     * so the scope is switched off by GIT_ATTR_NOSYSTEM instead.
     */
    @Test
    fun `an unreadable system gitattributes turns the attributes scope off`() {
        val attrs = tmp.newFile("termux-gitattributes")
        attrs.writeText("*.png binary\n")
        assertTrue(attrs.setReadable(false, false))
        assumeTrue(!attrs.canRead())

        val env = gitSystemAttributesEnvOf(
            termuxSystemAttributes = attrs,
            systemAttributes = tmp.newFile("etc-gitattributes"),
        )
        assertEquals("1", env["GIT_ATTR_NOSYSTEM"])
    }

    @Test
    fun `a readable system gitattributes is left in place`() {
        val attrs = tmp.newFile("termux-gitattributes")
        attrs.writeText("*.png binary\n")

        val env = gitSystemAttributesEnvOf(
            termuxSystemAttributes = attrs,
            systemAttributes = tmp.newFile("etc-gitattributes"),
        )
        // No GIT_ATTR_NOSYSTEM: git reads the file it was built to read.
        assertFalse(env.containsKey("GIT_ATTR_NOSYSTEM"))
    }

    @Test
    fun `a missing system gitattributes turns the attributes scope off`() {
        // Nothing to read either way, and no way for git to know that: the
        // scope is closed so a Termux-built git cannot walk into the old
        // prefix and warn about it.
        val env = gitSystemAttributesEnvOf(
            termuxSystemAttributes = tmp.root.resolve("absent-termux-attributes"),
            systemAttributes = tmp.root.resolve("absent-etc-attributes"),
        )
        assertEquals("1", env["GIT_ATTR_NOSYSTEM"])
    }
}