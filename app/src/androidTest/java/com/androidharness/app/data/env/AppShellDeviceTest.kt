package com.androidharness.app.data.env

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Run on Android 9 and Android 10+ with the installed Linux toolchain. */
@RunWith(AndroidJUnit4::class)
class AppShellDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun environment(): LinuxEnvironmentManager = LinuxEnvironmentManager(context).also {
        if (!it.isReady && InstrumentationRegistry.getArguments().getString("installLinux") == "true") {
            runBlocking { it.install(it.corePackages) }
        }
        assertTrue("Install the core Linux toolchain before running this suite", it.isReady)
        assertNotNull(it.bashExecutable())
    }

    @Test fun appTierExecutesCommandsAndPreservesOutputAndExitCodes() = runBlocking {
        val env = environment()
        val manager = ShizukuManager(context)
        val cwd = File(context.cacheDir, "issue43-shell").apply { mkdirs() }
        try {
            val router = ShellTierRouter(context, manager, env)
            assertEquals(ExecutionTier.APP_LINUX, router.resolveTier(cwd))
            suspend fun check(command: String, code: Int, output: String? = null, error: String? = null) {
                val result = router.run(command, cwd, 10_000, 10_000)
                assertFalse("$command timed out", result.timedOut)
                assertEquals("$command: ${result.rawOutput} ${result.rawStderr}", code, result.exitCode)
                output?.let { assertEquals(command, it, result.rawOutput.trim()) }
                error?.let { assertTrue(result.rawStderr, result.rawStderr.contains(it)) }
                assertFalse(result.rawOutput, result.rawOutput.contains("helper program for dynamic executables"))
            }
            check("echo SHELL_DIAGNOSTIC_OK", 0, "SHELL_DIAGNOSTIC_OK")
            val pwd = router.run("pwd", cwd, 10_000, 10_000)
            assertEquals(pwd.rawStderr, 0, pwd.exitCode)
            assertEquals(cwd.canonicalFile, File(pwd.rawOutput.trim()).canonicalFile)
            val git = router.run("git --version", cwd, 10_000, 10_000)
            assertEquals(git.rawOutput + git.rawStderr, 0, git.exitCode)
            assertTrue(git.rawOutput, git.rawOutput.startsWith("git version "))
            check("false", 1, "")
            check("nonexistent_command", 127, "", "nonexistent_command")
            check("printf '%s' 'spaces \"quotes\" \$literal'; printf 'stderr-only' >&2; exit 23",
                23, "spaces \"quotes\" \$literal", "stderr-only")
            // A real repository operation exercises Git's child processes too.
            check("git init -q && git -c user.name=Harness -c user.email=test@example.invalid commit --allow-empty -qm check && git log -1 --format=%s",
                0, "check")
        } finally {
            manager.close()
            cwd.deleteRecursively()
        }
    }

    @Test fun buildersAndExistingShimsUseTheSupportedLaunchMode() {
        val env = environment()
        val shim = File(env.prefix, "etc/harness-shims.sh")
        val previous = shim.takeIf { it.exists() }?.readBytes()
        try {
            shim.parentFile!!.mkdirs()
            // Simulate a file written by a pre-fix installation.
            shim.writeText("${ShellShims.HEADER}\necho STALE_SHIMS\n")
            if (Build.VERSION.SDK_INT >= 29) env.ensureShims(force = true) else env.ensureShims()
            assertFalse(shim.readText().contains("STALE_SHIMS"))
            val builder = env.shellProcessBuilder("printf 'builder-ok'; exit 17")
            val usesLinker = builder.command().first().contains("linker")
            assertEquals(Build.VERSION.SDK_INT >= 29, usesLinker)
            // Keep interpreter-path translation on older Android as well.
            assertTrue(env.processEnv().containsKey("LD_PRELOAD"))
            assertEquals(Build.VERSION.SDK_INT >= 29, shim.readText().contains("command /system/bin/linker"))
            val process = builder.start()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertEquals("builder-ok", process.inputStream.bufferedReader().readText())
            assertEquals(17, process.exitValue())

            // The terminal uses a stdin shell through the same builder.
            val terminal = env.shellProcessBuilder().redirectErrorStream(true).start()
            terminal.outputStream.bufferedWriter().use { it.write("echo terminal-ok\nexit 19\n") }
            assertTrue(terminal.waitFor(10, TimeUnit.SECONDS))
            assertTrue(terminal.inputStream.bufferedReader().readText().contains("terminal-ok"))
            assertEquals(19, terminal.exitValue())
        } finally {
            if (previous != null) shim.writeBytes(previous) else shim.delete()
        }
    }
}
