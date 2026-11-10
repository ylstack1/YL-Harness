package com.androidharness.app.data.update

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.data.env.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okio.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class UpdateInstallDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val c get() = (context.applicationContext as HarnessApp).container
    private fun fixture(block: suspend (File, Context) -> Unit) = runBlocking<Unit> {
        val directory = File(context.cacheDir, "update-test-${UUID.randomUUID()}").apply { mkdirs() }
        val prefs = "update-test-${UUID.randomUUID()}"
        val wrapped = object : ContextWrapper(context) {
            override fun getExternalFilesDir(type: String?) = directory
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(prefs, mode)
        }
        try { block(directory, wrapped) } finally { directory.deleteRecursively(); context.deleteSharedPreferences(prefs) }
    }
    private fun client(file: File, slow: Boolean = false): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val source = object : ForwardingSource(file.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long { if (slow) Thread.sleep(20); return super.read(sink, byteCount) }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = file.length()
            override fun source() = source
        }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
    }.build()
    private fun release(file: File, sha: String? = null) = UpdateManager.LatestRelease("fixture", "fixture", "", "https://github.com/Sanuu7/AndroidHarness/releases",
        "https://github.com/Sanuu7/AndroidHarness/releases/download/fixture/app.apk", "../../untrusted.apk", file.length(), sha)

    @Test fun verifiedMatchingApkUsesTheSystemHandoffAndNeverTheAssetFilename() = fixture { directory, wrapped ->
        val source = File(context.applicationInfo.sourceDir)
        val manager = UpdateManager(wrapped, c.deviceAccess, client(source))
        manager.setInstallMethod(UpdateInstallMethod.SYSTEM)
        var callbacks = 0
        manager.startUpdate(release(source), { apk -> callbacks++; manager.validateApk(apk) }, { callbacks++ })
        assertEquals(1, callbacks)
        assertTrue(File(directory, "update/pending-update.apk").isFile)
        assertFalse(File(directory.parentFile, "untrusted.apk").exists())
        assertEquals(UpdateInstallMethod.SYSTEM, UpdateManager(wrapped, c.deviceAccess).installMethod.value)
    }
    @Test fun invalidChecksumsAndForeignPackagesNeverReachAnInstaller() = fixture { directory, wrapped ->
        val payload = File(directory, "wrong.apk").apply { writeText("not an APK") }
        val manager = UpdateManager(wrapped, c.deviceAccess, client(payload))
        var callbacks = 0
        manager.startUpdate(release(payload, "0".repeat(64)), { callbacks++ }, { callbacks++ })
        assertEquals(0, callbacks)
        assertTrue((manager.step.value as UpdateManager.Step.Error).message.contains("checksum"))
        assertFalse(File(directory, "update/pending-update.apk.part").exists())
        val side = InstrumentationRegistry.getArguments().getString("updateFixtureApk")
        if (side != null) assertThrows(IllegalArgumentException::class.java) { manager.validateApk(File(side)) }
    }
    @Test fun cancellingDownloadClosesTheTransferAndRemovesItsPartialApk() = fixture { directory, wrapped ->
        val payload = File(directory, "large.bin").apply { java.io.RandomAccessFile(this, "rw").use { it.setLength(20L * 1024 * 1024) } }
        val manager = UpdateManager(wrapped, c.deviceAccess, client(payload, slow = true))
        var callbacks = 0
        coroutineScope {
            val job = launch { manager.startUpdate(release(payload), { callbacks++ }, { callbacks++ }) }
            withTimeout(10_000) { manager.step.first { it is UpdateManager.Step.Downloading && it.mb > 0f } }
            manager.dismiss()
            withTimeout(5000) { job.join() }
        }
        assertEquals(UpdateManager.Step.Idle, manager.step.value); assertEquals(0, callbacks)
        assertFalse(File(directory, "update/pending-update.apk.part").exists())
    }
    @Test fun explicitShizukuInstallsATestPackageWithoutRequestingDirectRootOrUsingTheAppUid() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("shizukuReady") == "true")
        val apk = File(requireNotNull(InstrumentationRegistry.getArguments().getString("updateFixtureApk")))
        val manager = ShizukuManager(context, userServiceTag = "updater-${UUID.randomUUID()}")
        val access = DeviceAccessManager(context, manager, c.linuxEnv)
        val saved = access.store.preferences.first()
        val scenario = androidx.test.core.app.ActivityScenario.launch(com.androidharness.app.ui.chat.ChatListTestActivity::class.java)
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { manager.requestPermission() }
            try { withTimeout(60_000) { manager.serviceState.first { it.isReady } } }
            catch (e: TimeoutCancellationException) { throw AssertionError(manager.connectionReport(), e) }
            access.store.setMode(AccessMode.AUTO); access.disableRootUse()
            var dispatched: DeviceBackend? = null
            val result = requireNotNull(access.installUpdate(apk, DeviceBackend.SHIZUKU) { dispatched = it })
            assertEquals(DeviceBackend.SHIZUKU, dispatched); assertEquals(DeviceBackend.SHIZUKU, result.first)
            assertTrue(result.second.stderr + result.second.output, UpdateInstallPolicy.succeeded(result.second.exitCode, result.second.timedOut, result.second.output))
            assertNull(access.root.service)
            val rejected = File(context.getExternalFilesDir(null), "invalid-update-fixture.apk").apply { writeText("not an APK") }
            try {
                var calls = 0
                val failed = requireNotNull(access.installUpdate(rejected, DeviceBackend.SHIZUKU) { calls++ })
                assertEquals(1, calls); assertFalse(UpdateInstallPolicy.succeeded(failed.second.exitCode, failed.second.timedOut, failed.second.output))
            } finally { rejected.delete() }
            access.store.setMode(AccessMode.NORMAL)
            assertNull(access.installUpdate(apk, DeviceBackend.SHIZUKU) { fail("Normal mode must not dispatch") })
        } finally {
            access.store.setMode(saved.mode); access.store.setFallback(saved.autoFallback); access.store.setRootUse(saved.rootUse); access.store.setTemporaryRoot(saved.temporaryRoot)
            access.disableRootUse(); manager.close(); scenario.close()
        }
    }
}
