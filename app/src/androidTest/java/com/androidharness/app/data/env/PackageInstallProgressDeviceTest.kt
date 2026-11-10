package com.androidharness.app.data.env

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

@RunWith(AndroidJUnit4::class)
class PackageInstallProgressDeviceTest {
    @Test fun approvingInstallKeepsItsCardStateUntilInstallationFinishes() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val c = (app.applicationContext as com.androidharness.app.HarnessApp).container
        val root = File(app.cacheDir, "install-card-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getDataDir() = root
            override fun getExternalFilesDir(type: String?) = File(root, "external").apply { mkdirs() }
        }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val env = LinuxEnvironmentManager(context, packageIndexLoader = {
            entered.complete(Unit)
            release.await()
            error("Controlled install failure")
        })
        val manager = com.androidharness.app.agent.RunManager(context, c.engine, c.sessions, c.checkpoints,
            c.workspace, env, c.settings, c.todoStore, c.providers)
        val request = com.androidharness.app.agent.EnvironmentRequest(
            com.androidharness.app.core.ToolCallData("install-call", "shell", "{}"), "python3 --version", listOf("python"))
        @Suppress("UNCHECKED_CAST")
        val state = manager.live("install-progress-fixture") as kotlinx.coroutines.flow.MutableStateFlow<com.androidharness.app.agent.RunManager.LiveRunState>
        state.value = state.value.copy(turnId = "fixture-turn", pendingEnvironment = request)
        try {
            assertTrue(manager.respondToRemoteEnvironment("install-progress-fixture", "fixture-turn", "install-call", true))
            withTimeout(5000) { entered.await() }
            assertNull(state.value.pendingEnvironment)
            assertSame(request, state.value.installingEnvironment)
            assertEquals("Installing Linux environment", manager.actionText(state.value))
            assertFalse(request.response.isCompleted)
            assertFalse(manager.respondToRemoteEnvironment("install-progress-fixture", "fixture-turn", "install-call", true))
            release.complete(Unit)
            assertFalse(withTimeout(5000) { request.response.await() })
            withTimeout(5000) { while (state.value.installingEnvironment != null) delay(10) }
            assertTrue(state.value.error.orEmpty().contains("Controlled install failure"))
        } finally { release.complete(Unit); root.deleteRecursively() }
    }

    @Test fun actualInstallerPublishesByteProgressAndKeepsWorkingAfterChecksumFailure() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(app.cacheDir, "package-progress-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getDataDir() = root
            override fun getExternalFilesDir(type: String?) = File(root, "external").apply { mkdirs() }
        }
        val payload = ByteArray(256 * 1024).also { java.util.Random(42).nextBytes(it) }
        val tarBytes = ByteArrayOutputStream().also { output ->
            TarArchiveOutputStream(GZIPOutputStream(output)).use { tar ->
                val entry = TarArchiveEntry("data/data/com.termux/files/usr/lib/ui-progress-fixture.bin").apply {
                    size = payload.size.toLong(); mode = 0x1A4
                }
                tar.putArchiveEntry(entry); tar.write(payload); tar.closeArchiveEntry()
            }
        }.toByteArray()
        val deb = ByteArrayOutputStream().also { output ->
            ArArchiveOutputStream(output).use { ar ->
                ar.putArchiveEntry(ArArchiveEntry("data.tar.gz", tarBytes.size.toLong()))
                ar.write(tarBytes); ar.closeArchiveEntry()
            }
        }.toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(deb).joinToString("") { "%02x".format(it) }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture").body(
                object : ResponseBody() {
                    override fun contentType() = "application/octet-stream".toMediaType()
                    override fun contentLength() = deb.size.toLong()
                    private val stream = object : ForwardingSource(Buffer().write(deb)) {
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            Thread.sleep(20)
                            return super.read(sink, minOf(byteCount, 8192))
                        }
                    }.buffer()
                    override fun source(): BufferedSource = stream
                }
            ).build()
        }.build()
        val good = PkgMeta("ui-progress-test", "1", emptyList(), "pool/ui-progress-test.deb", deb.size.toLong(), hash)
        val bad = good.copy(name = "bad-progress-test", filename = "pool/bad-progress-test.deb", sha256 = "0".repeat(64))
        val env = LinuxEnvironmentManager(context,
            packageIndexLoader = { mapOf(good.name to good, bad.name to bad) }, packageHttpClient = client)
        val states = mutableListOf<EnvState>()
        val observer = launch(Dispatchers.Unconfined) { env.state.collect { states += it } }
        try {
            val progress = mutableListOf<PackageInstallStatus>()
            assertTrue(env.install(listOf(good.name), onProgress = { progress += it }).isSuccess)
            assertEquals(PackageInstallPhase.WAITING, progress.first().phase)
            assertTrue(progress.all { it.requested == listOf(good.name) })
            assertTrue(progress.any { it.phase == PackageInstallPhase.DOWNLOADING && it.downloadedBytes in 1 until deb.size.toLong() })
            assertTrue(progress.any { it.phase == PackageInstallPhase.VERIFYING && it.currentPackage == good.name })
            assertTrue(progress.any { it.phase == PackageInstallPhase.UNPACKING && it.completed.isEmpty() })
            assertEquals(listOf(good.name), progress.last().completed)
            assertEquals(PackageInstallPhase.CONFIGURING, progress.last().phase)
            assertTrue(env.isReady)
            val downloads = states.filterIsInstance<EnvState.Downloading>()
            assertTrue("A real stream must publish intermediate byte counts", downloads.any { it.downloadedBytes in 1 until deb.size.toLong() })
            assertTrue(downloads.all { it.pkg == good.name && it.totalBytes == deb.size.toLong() })
            assertEquals(deb.size.toLong(), downloads.last().downloadedBytes)
            assertEquals(downloads.map { it.downloadedBytes }.sorted(), downloads.map { it.downloadedBytes })
            assertTrue(states.any { it is EnvState.Installing && it.pkg == good.name })
            assertTrue(states.last() is EnvState.Ready)
            assertArrayEquals(payload, File(env.prefix, "lib/ui-progress-fixture.bin").readBytes())
            val installed = env.installedPackages()
            val count = progress.size
            val failedProgress = mutableListOf<PackageInstallStatus>()
            val failure = env.install(listOf(bad.name), onProgress = { failedProgress += it })
            assertEquals("A later install cannot publish into the first call", count, progress.size)
            assertTrue(failedProgress.all { it.requested == listOf(bad.name) && it.completed.isEmpty() })
            assertFalse(failedProgress.any { it.phase == PackageInstallPhase.UNPACKING })
            assertTrue(failure.isFailure)
            assertTrue(failure.exceptionOrNull()?.message.orEmpty().contains("Checksum mismatch"))
            assertEquals(installed, env.installedPackages())
            assertTrue("A failed add-on must preserve the healthy environment", env.isReady)
        } finally { observer.cancelAndJoin(); root.deleteRecursively() }
    }
}
