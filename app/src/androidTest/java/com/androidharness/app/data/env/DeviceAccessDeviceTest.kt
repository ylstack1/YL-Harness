package com.androidharness.app.data.env

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceAccessDeviceTest {
    @Test fun explicitShizukuShellFromAppWorkspaceKeepsOneTransportAndDoesNotRequestDirectRoot() = runBlocking {
        val manager = connected()
        val access = DeviceAccessManager(context, manager, env)
        val saved = access.store.preferences.first()
        val directory = File(context.getExternalFilesDir(null), "shizuku-shell-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            access.store.setMode(AccessMode.AUTO)
            access.disableRootUse()
            val router = ShellTierRouter(context, manager, env, access)
            val uid = raw(manager, "id -u").output.trim().toInt()
            val result = access.traceTool("fixture", "fixture-chat", requireShizuku = true) {
                com.androidharness.app.tools.ShellTool(env, router).execute(
                    kotlinx.serialization.json.buildJsonObject {
                        put("command", kotlinx.serialization.json.JsonPrimitive("id -u; command -v cmd; cmd package path android"))
                        put("access", kotlinx.serialization.json.JsonPrimitive("shizuku"))
                    }, com.androidharness.app.tools.ToolContext(com.androidharness.app.workspace.FileFs(directory), sessionId = "fixture-chat"))
            }
            assertTrue(result.output, result.ok)
            assertTrue(result.output, result.output.contains("package:"))
            assertTrue(result.output, result.output.contains("/system/bin/cmd"))
            assertEquals(DeviceBackend.SHIZUKU, result.execution.single().backend)
            assertEquals(uid, result.execution.single().uid)
            assertEquals("Shizuku", result.execution.single().label)
            assertNull(access.root.service)
        } finally { restore(access, saved); directory.deleteRecursively(); manager.close() }
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val env get() = (context.applicationContext as HarnessApp).container.linuxEnv
    private suspend fun connected(compat: Boolean = false): ShizukuManager {
        assumeTrue(InstrumentationRegistry.getArguments().getString("shizukuReady") == "true")
        return ShizukuManager(context,
            userServiceClassName = if (compat) "${context.packageName}.MissingRootTestService" else HarnessUserService::class.java.name,
            userServiceTimeoutMs = if (compat) 1_000 else 10_000, userServiceTag = "access-${UUID.randomUUID()}").also {
            try { withTimeout(30_000) { it.serviceState.first { state -> state.isReady } } }
            catch (e: TimeoutCancellationException) {
                val report = it.connectionReport()
                it.close()
                throw AssertionError(report, e)
            }
            assertEquals(it.connectionReport(), if (compat) UserServiceState.COMPAT_READY else UserServiceState.BOUND_READY, it.serviceState.value)
        }
    }
    private suspend fun restore(access: DeviceAccessManager, prefs: AccessPreferences) {
        access.store.setMode(prefs.mode); access.store.setFallback(prefs.autoFallback); access.store.setRootUse(prefs.rootUse)
        access.store.setTemporaryRoot(prefs.temporaryRoot)
        access.store.preferences.first().rootProjects.forEach { if (it !in prefs.rootProjects) access.store.allowProject(it, false) }
        prefs.rootProjects.forEach { access.store.allowProject(it, true) }
        access.disableRootUse(); access.resume()
    }
    private suspend fun raw(manager: ShizukuManager, script: String, timeout: Int = 5_000): PrivilegedResult =
        requireNotNull(manager.runPrivileged(arrayOf("/system/bin/sh", "-c", script), null, "/", timeout))
    private fun result(code: Int = 0) = ShellRunResult(code, false, "test", "", ExecutionTier.PRIVILEGED, null)

    @Test fun fallbackOccursOnlyBeforeDispatchAndSavedModeSurvivesStoreRecreation() = runBlocking {
        val manager = connected()
        val access = DeviceAccessManager(context, manager, env)
        val saved = access.store.preferences.first()
        try {
            access.store.setMode(AccessMode.AUTO); access.store.setFallback(false)
            assertEquals(AccessMode.AUTO, DeviceAccessStore(context).preferences.first().mode)
            assertNull(access.root.service) // Auto never asks for su.
            var normal = 0
            access.withRequest(null, null, forceDevice = true) {
                val fallback = access.execute(normal = { normal++; result().copy(tier = ExecutionTier.TOYBOX) },
                    shizukuRun = { null }, rootRun = { error("Root must not be requested") })
                assertEquals(0, fallback.exitCode)
                assertEquals(1, normal)
            }
            access.withRequest(null, null, forceDevice = true) {
                val failed = access.execute(normal = { normal++; result() }, shizukuRun = { result(-23) }, rootRun = { error("No root grant") })
                assertEquals(-23, failed.exitCode)
                assertEquals("A dispatched failure must never be replayed", 1, normal)
            }
        } finally { restore(access, saved); manager.close() }
    }

    @Test fun stopCancelsForegroundAndDetachedJobsAndBlocksQueuedExecutionForBothTransports() = runBlocking {
        for (compat in listOf(false, true)) {
            val manager = connected(compat)
            val marker = "/data/local/tmp/harness-access-${UUID.randomUUID()}"
            try {
                coroutineScope {
                    val command = async(Dispatchers.IO) { raw(manager, "sleep 60 & echo \$! > '$marker'; wait", 60_000) }
                    withTimeout(8_000) { while (raw(manager, "test -s '$marker'").exitCode != 0) delay(100) }
                    val pid = raw(manager, "cat '$marker'").output.trim().toInt()
                    val detached = requireNotNull(manager.spawnDetached(arrayOf("/system/bin/sh", "-c", "sleep 60"), null, "/", "$marker.log"))
                    assertTrue(detached > 0)
                    val stop = manager.stopActiveCommands()
                    assertTrue(stop, stop.contains("stopped"))
                    assertEquals(-130, withTimeout(8_000) { command.await() }.exitCode)
                    withTimeout(8_000) { while (manager.isProcessAlive(pid) || manager.isProcessAlive(detached)) delay(100) }
                    assertEquals(-130, raw(manager, "echo queued > '$marker.queued'").exitCode)
                    assertEquals("2000", raw(manager, "id -u").output.trim())
                    manager.resumeCommands()
                    assertEquals(0, raw(manager, "test ! -e '$marker.queued'").exitCode)
                }
            } finally { manager.resumeCommands(); raw(manager, "rm -f '$marker' '$marker.log' '$marker.queued'"); manager.close() }
        }
    }

    @Test fun changedUidIsRejectedBeforeExecOrSpawnForBothTransports() = runBlocking {
        for (compat in listOf(false, true)) {
            val manager = connected(compat)
            val marker = "/data/local/tmp/harness-identity-${UUID.randomUUID()}"
            try {
                val uid = raw(manager, "id -u").output.trim().toInt()
                withContext(VerifiedDeviceAccess(DeviceBackend.SHIZUKU, if (uid == 0) 2000 else 0)) {
                    assertNull(manager.runPrivileged(arrayOf("/system/bin/sh", "-c", "touch '$marker'"), null, "/", 5_000))
                    assertNull(manager.spawnDetached(arrayOf("/system/bin/sh", "-c", "touch '$marker'"), null, "/", "$marker.log"))
                }
                assertEquals(0, raw(manager, "test ! -e '$marker' && test ! -e '$marker.log'").exitCode)
            } finally { manager.resumeCommands(); raw(manager, "rm -f '$marker' '$marker.log'"); manager.close() }
        }
    }

    @Test fun rootStartedShizukuStaysIndependentOfDirectRootAuthorization() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("rootShizuku") == "true")
        for (compat in listOf(false, true)) {
            val manager = connected(compat)
            val access = DeviceAccessManager(context, manager, env)
            val saved = access.store.preferences.first()
            val project = "shizuku-only-${UUID.randomUUID()}"
            try {
                assertEquals("0", raw(manager, "id -u").output.trim())
                access.store.setTemporaryRoot(true)
                access.disableRootUse()
                for (mode in listOf(AccessMode.AUTO, AccessMode.SHIZUKU, AccessMode.BOTH)) {
                    access.store.setMode(mode)
                    for (fallback in listOf(false, true)) {
                        access.store.setFallback(fallback)
                        val tool = access.traceTool(project, "chat") {
                            val executed = access.execute(
                                normal = { error("Connected Shizuku must not downgrade to Normal") },
                                rootRun = { error("Shizuku must not use the direct root runner") },
                                shizukuRun = {
                                    val actual = raw(manager, "id -u")
                                    result(actual.exitCode).copy(rawOutput = actual.output)
                                },
                            )
                            com.androidharness.app.tools.ToolResult(executed.exitCode == 0, executed.rawOutput)
                        }
                        assertTrue(tool.ok)
                        assertEquals("0", tool.output.trim())
                        assertEquals(ExecutionReceipt(DeviceBackend.SHIZUKU, 0), tool.execution.single())
                        assertEquals("Shizuku", tool.execution.single().label)
                        assertNull(access.root.service)
                        access.withRequest(project, "chat", requireRoot = true) { assertNull(access.select().backend) }
                    }
                }
                // Ordinary Shizuku work must not spend a next-action root grant.
                access.store.setMode(AccessMode.SHIZUKU)
                access.store.setRootUse(RootUse.NEXT_ACTION)
                access.store.allowProject(project, true)
                access.enableTemporaryUse(RootUse.NEXT_ACTION, "chat")
                access.withRequest(project, "chat") {
                    access.execute(normal = { error("Unexpected Normal execution") },
                        rootRun = { error("Unexpected direct root execution") }, shizukuRun = { result() })
                }
                assertTrue(access.rootUseActive("chat", access.store.preferences.first()))
                access.disableRootUse()
                access.withRequest(project, "chat", requireRoot = true) { assertNull(access.select().backend) }
            } finally { restore(access, saved); manager.close() }
        }
    }

    @Test fun rootBackedShizukuHonorsProjectScopeAndSupportsProtectedFileEdits() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("rootShizuku") == "true")
        for (compat in listOf(false, true)) {
            val manager = connected(compat)
            val access = DeviceAccessManager(context, manager, env)
            val saved = access.store.preferences.first()
            val project = "root-test-${UUID.randomUUID()}"
            val fixture = "/data/local/tmp/$project"
            try {
                assertEquals("0", raw(manager, "id -u").output.trim())
                access.store.setMode(AccessMode.AUTO)
                access.enableTemporaryUse(RootUse.SESSION, null)
                access.withRequest(project, "chat", requireRoot = true) { assertNull(access.select().backend) }
                access.store.allowProject(project, true)
                access.withRequest(project, "chat", requireRoot = true) {
                    val selected = access.select()
                    assertEquals(DeviceBackend.SHIZUKU, selected.backend); assertEquals(0, selected.uid)
                }
                val tool = access.traceTool(project, "chat", requireRoot = true) {
                    val result = ShellTierRouter(context, manager, env, access).run("id -u; printf root-stderr >&2; exit 23", File("/"), 10_000, 4_000)
                    assertEquals(23, result.exitCode)
                    assertEquals("root-stderr", result.rawStderr)
                    com.androidharness.app.tools.ToolResult(true, result.rawOutput)
                }
                assertEquals("0", tool.output.trim())
                assertEquals("Shizuku", tool.execution.single().label)
                val terminal = com.androidharness.app.data.TerminalManager(context, env, manager,
                    (context.applicationContext as HarnessApp).container.runManager, ShellTierRouter(context, manager, env, access))
                try {
                    terminal.send("id -u", com.androidharness.app.workspace.FileFs(context.cacheDir))
                    val terminalResult = withTimeout(15_000) { terminal.state.first { !it.busy && it.lastCommand == "id -u" && it.backendLabel == "Shizuku" } }
                    assertEquals(0, terminalResult.lastExitCode)
                } finally { terminal.stopTerminal() }
                assertEquals(0, raw(manager, "mkdir '$fixture'; printf 'initial' > '$fixture/file.txt'; chmod 600 '$fixture/file.txt'").exitCode)
                assertFalse("App user cannot read the protected fixture", File(fixture, "file.txt").canRead())
                val listing = access.rootFiles(project, "chat") { Json.decodeFromString<RootDirectory>(it.rootList(fixture, 0)) }
                assertEquals("file.txt", listing.entries.single().name)
                val file = access.rootFiles(project, "chat") { Json.decodeFromString<RootText>(it.rootRead("$fixture/file.txt")) }
                assertEquals("initial", file.text)
                val modified = access.rootFiles(project, "chat") { Json.decodeFromString<RootText>(it.rootWrite(file.path, file.hash, "changed ✓")) }
                assertEquals("changed ✓", modified.text)
                assertEquals("600 0", raw(manager, "stat -c '%a %u' '$fixture/file.txt'").output.trim())
                try { access.rootFiles(project, "chat") { it.rootWrite(file.path, file.hash, "stale") }; fail("Stale edit must be rejected") }
                catch (e: Exception) { assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("changed")) }
                assertEquals("changed ✓", raw(manager, "cat '$fixture/file.txt'").output)
                val log = "$fixture/recovered.log"
                val pid = requireNotNull(manager.spawnDetached(arrayOf("/system/bin/sh", "-c", "sleep 60"), null, "/", log))
                val recovered = connected(compat)
                try {
                    assertFalse(recovered.killProcess(pid, "$fixture/wrong.log"))
                    assertTrue(recovered.isProcessAlive(pid))
                    assertTrue(recovered.killProcess(pid, log))
                    withTimeout(6_000) { while (recovered.isProcessAlive(pid)) delay(100) }
                } finally { manager.killProcess(pid); recovered.close() }
                access.disableRootUse()
                access.withRequest(project, "chat", requireRoot = true) { assertNull(access.select().backend) }
            } finally { manager.resumeCommands(); raw(manager, "rm -rf '$fixture'"); restore(access, saved); manager.close() }
        }
    }

    @Test fun directRootRequestReportsUnavailableWithoutAutoPrompting() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("checkRootGrant") == "true")
        val root = RootManager(context)
        try {
            assertNull(root.service)
            val granted = root.requestAccess()
            File(context.getExternalFilesDir(null), "root-grant-verification.txt").writeText(root.state.value.toString())
            if (granted) {
                assertTrue(root.verify())
                assertEquals("0", root.run(arrayOf("/system/bin/sh", "-c", "id -u"), null, "/", 5_000, 1_000)!!.output.trim())
            } else {
                assertEquals(RootStatus.DENIED, root.state.value.status)
                assertNull(root.service)
                assertNull(root.run(arrayOf("/system/bin/sh", "-c", "id -u"), null, "/", 1_000, 1_000))
            }
        } finally { root.disconnect() }
    }
}
