package com.androidharness.app.data.env

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class DeviceOwnerAccessTest {
    private class FakeProcess(var alive: Boolean = false, val broken: Boolean = false) : Process() {
        var destroyed = false
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream("owner output".toByteArray())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor() = 0
        override fun exitValue() = 0
        override fun isAlive(): Boolean { if (broken) error("binder lost"); return alive }
        override fun destroy() { destroyed = true; alive = false }
    }
    private class Api : DeviceOwnerApi {
        var running = true
        var permission = true
        var grantResult = 0
        var isInstalled = true
        @Volatile var dispatches = 0
        var process = FakeProcess()
        override fun init() = running
        override fun installed() = isInstalled
        override fun granted() = permission
        override fun ownerPackage() = "com.rosan.dhizuku"
        override fun requestPermission(callback: (Int) -> Unit) { permission = grantResult == 0; callback(grantResult) }
        override fun newProcess(command: String): Process { dispatches++; return process }
    }
    private fun withManager(block: suspend (DhizukuManager, Api) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val api = Api()
        try { block(DhizukuManager(api, scope), api) } finally { scope.cancel() }
    }
    @Test fun installationNeverCountsAsAnAuthorizedConnection() = withManager { manager, api ->
        api.running = false
        manager.checkConnection()
        assertEquals(DhizukuStatus.NOT_RUNNING, manager.state.value.status)
        api.isInstalled = false
        manager.checkConnection()
        assertEquals(DhizukuStatus.NOT_INSTALLED, manager.state.value.status)
        api.running = true
        api.permission = false
        manager.checkConnection()
        assertEquals(DhizukuStatus.NEEDS_PERMISSION, manager.state.value.status)
        assertEquals(0, api.dispatches)
    }
    @Test fun commandsRecheckPermissionBeforeDispatch() = withManager { manager, api ->
        manager.checkConnection()
        assertEquals(DhizukuStatus.GRANTED, manager.state.value.status)
        api.permission = false
        try { manager.run("id", 500); fail("revoked permission must block dispatch") } catch (_: IllegalStateException) { }
        assertEquals(0, api.dispatches)
        manager.checkConnection()
        assertEquals(DhizukuStatus.NEEDS_PERMISSION, manager.state.value.status)
    }
    @Test fun permissionCanBeGrantedAndDeniedWithoutDispatchingCommands() = withManager { manager, api ->
        api.permission = false
        api.grantResult = -1
        manager.checkConnection()
        manager.requestPermission()
        withTimeout(2_000) { while (manager.state.value.status != DhizukuStatus.DENIED) delay(10) }
        assertEquals(0, api.dispatches)
        api.grantResult = 0
        manager.requestPermission()
        withTimeout(2_000) { while (manager.state.value.status != DhizukuStatus.GRANTED) delay(10) }
        assertEquals("com.rosan.dhizuku", manager.state.value.ownerPackage)
        assertEquals(0, api.dispatches)
    }
    @Test fun timeoutDestroysTheOwnerProcessAndRetainsOutput() = withManager { manager, api ->
        api.process = FakeProcess(alive = true)
        val result = manager.run("sleep", 100)
        assertTrue(result.timedOut)
        assertEquals("owner output", result.output)
        assertTrue(api.process.destroyed)
        assertEquals(1, api.dispatches)
    }
    @Test fun connectionLossAfterDispatchIsNeverRetried() = withManager { manager, api ->
        api.process = FakeProcess(broken = true)
        val result = manager.run("change", 500)
        assertEquals(-1, result.exitCode)
        assertTrue(result.stderr.contains("may have run"))
        assertEquals(1, api.dispatches)
        assertTrue(api.process.destroyed)
    }
    @Test fun stopBlocksNewCommandsUntilResumed() = withManager { manager, api ->
        manager.stopCommands()
        try { manager.run("id", 500); fail("paused manager must block dispatch") } catch (_: IllegalStateException) { }
        assertEquals(0, api.dispatches)
        manager.resumeCommands()
        assertEquals(0, manager.run("id", 500).exitCode)
    }
    @Test fun cancellationDestroysTheOwnerProcess() = withManager { manager, api ->
        api.process = FakeProcess(alive = true)
        coroutineScope {
            val command = launch { manager.run("sleep", 10_000) }
            withTimeout(2_000) { while (api.dispatches == 0) delay(10) }
            command.cancelAndJoin()
        }
        assertTrue(api.process.destroyed)
    }
    @Test fun commandGrantsStaySpecificToTheOwnerTransportAndCommand() {
        val policy = com.androidharness.app.tools.ShellPolicy
        val key = policy.grantKey("device_owner_shell", "id -u")
        assertTrue(policy.isGranted("device_owner_shell", "id -u", setOf(key)))
        assertFalse(policy.isGranted("device_owner_shell", "rm file", setOf(key)))
        assertFalse(policy.isGranted("device_owner_shell", "id -u", setOf("device_owner_shell", "shell#id -u")))
    }
    @Test fun receiptsKeepProviderIdentityAndOwnerAccessCannotBecomeAdbFallback() {
        assertEquals("Shevery", ExecutionReceipt(DeviceBackend.SHIZUKU, 2000, transport = "Shevery").label)
        assertEquals("Dhizuku", ExecutionReceipt(DeviceBackend.DHIZUKU).label)
        for (mode in AccessMode.entries) for (fallback in listOf(false, true)) {
            assertNotEquals(DeviceBackend.DHIZUKU, DeviceAccessPolicy.choose(mode, fallback, false, null, false).backend)
        }
    }
}
