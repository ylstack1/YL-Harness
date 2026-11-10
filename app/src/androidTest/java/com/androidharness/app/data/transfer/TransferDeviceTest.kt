package com.androidharness.app.data.transfer

import android.app.NotificationManager
import android.content.SharedPreferences
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.data.KeyStoreManager
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.workspace.FileFs
import com.androidharness.app.workspace.SshConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.apache.commons.net.ftp.FTPClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TransferDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val c get() = (context.applicationContext as HarnessApp).container
    @Test fun structuredSshAndSyncCredentialsPersistEncryptedAndLegacySshDataMigrates() {
        val id = UUID.randomUUID().toString()
        val vault = KeyStoreManager(context)
        val prefs = KeyStoreManager::class.java.getDeclaredField("prefs").apply { isAccessible = true }.get(vault) as SharedPreferences
        val ssh = SshConnection(id, "127.0.0.1", 22, "fixture", "ssh fixture password", "-----BEGIN KEY-----\nfixture key data\n-----END KEY-----", "passphrase", "SHA256:fixture", false)
        val profile = SyncProfile(id, "Fixture", SyncProtocol.FTP, "127.0.0.1", 2121, "fixture", "sync fixture password")
        try {
            assertTrue(prefs.edit().putString("ssh_workspace_$id", Json.encodeToString(SshConnection.serializer(), ssh)).commit())
            assertEquals(ssh, c.sshConnections.load(id))
            c.sshConnections.save(ssh)
            assertEquals(ssh, c.sshConnections.load(id))
            assertTrue(vault.providerKeyPool("ssh_workspace_$id").keys.isEmpty())
            assertFalse(prefs.contains("ssh_workspace_$id"))
            c.sync.profiles.save(profile)
            assertEquals(profile, SyncProfiles(KeyStoreManager(context)).all().single { it.id == id })
            val raw = File(context.applicationInfo.dataDir, "shared_prefs/api_keys.xml").readText()
            assertFalse(raw.contains("ssh fixture password")); assertFalse(raw.contains("fixture key data")); assertFalse(raw.contains("sync fixture password"))
        } finally { c.sshConnections.forget(id); c.sync.profiles.remove(id) }
    }
    @Test fun ftpServerContinuesAfterItsScreenClosesAndItsNotificationStopsAllConnections() = runBlocking<Unit> {
        val directory = File(context.cacheDir, "ftp-service-${UUID.randomUUID()}").apply { mkdirs() }
        val port = ServerSocket(0).use { it.localPort }
        val scenario = ActivityScenario.launch(ChatListTestActivity::class.java)
        try {
            File(directory, "read.txt").writeText("service fixture")
            c.ftpShare.start(FileFs(directory), "FTP service fixture", port, "fixture", "fixture-password", false, true)
            val running = withTimeout(10_000) { c.ftpShare.state.first { it.running || it.error != null } }
            assertTrue(running.error, running.running)
            assertEquals(listOf("ftp://127.0.0.1:$port"), running.addresses)
            val ftp = FTPClient().apply { connect("127.0.0.1", port); assertTrue(login("fixture", "fixture-password")); enterLocalPassiveMode() }
            try {
                val output = ByteArrayOutputStream(); assertTrue(ftp.retrieveFile("read.txt", output)); assertEquals("service fixture", output.toString("UTF-8"))
                assertFalse(ftp.storeFile("read.txt", "blocked".byteInputStream()))
                scenario.close()
                assertTrue(c.ftpShare.state.value.running)
                val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.single {
                    it.notification.channelId == "ftp-sharing" &&
                        it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY == 0
                }.notification
                notification.actions.single().actionIntent.send()
                withTimeout(10_000) { c.ftpShare.state.first { !it.running && !it.starting } }
                assertNull(c.ftpShare.passwordForActiveShare())
                assertEquals("service fixture", File(directory, "read.txt").readText())
            } finally { runCatching { ftp.disconnect() } }
        } finally { scenario.close(); c.ftpShare.stop(); directory.deleteRecursively() }
    }
    @Test fun syncManagerUsesRealFtpPreviewsAndOnlyPublishesApprovedReplacements() = runBlocking<Unit> {
        val local = File(context.cacheDir, "sync-local-${UUID.randomUUID()}").apply { mkdirs() }
        val remoteDir = File(context.cacheDir, "sync-remote-${UUID.randomUUID()}").apply { mkdirs() }
        val server = FtpShareServer(LocalTransferFiles(remoteDir), "fixture", "fixture-password", false)
        val manager = SyncManager(c.keys, c.sshConnections) { _, action -> action() }
        try {
            File(local, "a").writeText("new"); File(remoteDir, "a").writeText("old"); File(local, "b").writeText("created")
            server.start(0)
            val profile = SyncProfile(protocol = SyncProtocol.FTP, host = "127.0.0.1", port = server.port, username = "fixture", password = "fixture-password")
            manager.preview(FileFs(local), profile, SyncDirection.UPLOAD)
            val preview = withTimeout(15_000) { manager.state.first { it.plan != null || !it.working } }
            assertNotNull(preview.message, preview.plan); assertEquals(1, preview.plan!!.replacements)
            delay(50); manager.apply(false)
            withTimeout(15_000) { manager.state.first { !it.working && it.plan == null } }
            assertEquals("old", File(remoteDir, "a").readText()); assertEquals("created", File(remoteDir, "b").readText())
            delay(50); manager.preview(FileFs(local), profile, SyncDirection.UPLOAD)
            withTimeout(15_000) { manager.state.first { it.plan != null } }; delay(50); manager.apply(true)
            val result = withTimeout(15_000) { manager.state.first { !it.working && it.plan == null } }
            assertTrue(result.message, result.message!!.startsWith("Copied")); assertEquals("new", File(remoteDir, "a").readText())
        } finally { manager.cancel(); server.close(); local.deleteRecursively(); remoteDir.deleteRecursively() }
    }
    @Test fun cancellingFtpAndSftpDuringConnectionClosesTheSocketPromptly() = runBlocking<Unit> {
        val local = File(context.cacheDir, "cancel-sync-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            for (protocol in SyncProtocol.entries) {
                val server = ServerSocket(0)
                val accepted = java.util.concurrent.CountDownLatch(1)
                val socket = java.util.concurrent.atomic.AtomicReference<java.net.Socket>()
                val worker = kotlin.concurrent.thread(isDaemon = true) {
                    try { server.accept().use { connection ->
                        socket.set(connection); connection.soTimeout = 5000; accepted.countDown(); connection.getInputStream().readBytes()
                    } } catch (_: Exception) { }
                }
                val manager = SyncManager(c.keys, c.sshConnections) { _, action -> action() }
                try {
                    manager.preview(FileFs(local), SyncProfile(protocol = protocol, host = "127.0.0.1", port = server.localPort,
                        username = "fixture", password = "fixture-password", fingerprint = "SHA256:fixture"), SyncDirection.UPLOAD)
                    assertTrue(accepted.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    val start = android.os.SystemClock.elapsedRealtime(); manager.cancel()
                    val stopped = withTimeout(3000) { manager.state.first { !it.working } }
                    assertTrue(stopped.message, stopped.message!!.startsWith("Sync cancelled"))
                    assertTrue(android.os.SystemClock.elapsedRealtime() - start < 3000)
                } finally { manager.cancel(); socket.get()?.close(); server.close(); worker.join(1000) }
            }
        } finally { local.deleteRecursively() }
    }

    @Test fun pinnedSftpSyncUploadsDownloadsAndRejectsAChangedHostKey() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val expected = requireNotNull(args.getString("sftpFingerprint")) { "Start the local SFTP fixture and pass sftpFingerprint." }
        val port = args.getString("sftpPort", "8023").toInt()
        val profile = SyncProfile(host = "127.0.0.1", port = port, username = "fixture", password = "fixture-password", fingerprint = expected)
        assertEquals(expected, withContext(Dispatchers.IO) { c.sshConnections.probe(profile.ssh()) })
        val directory = File(context.cacheDir, "sftp-local-${UUID.randomUUID()}").apply { mkdirs() }
        val remoteFolder = "/case-${UUID.randomUUID()}"
        try {
            withContext(Dispatchers.IO) {
                c.sshConnections.preview(profile.ssh()) { channel -> channel.mkdir(remoteFolder); channel.put("remote".byteInputStream(), "$remoteFolder/remote-only") }
                File(directory, "a + b.txt").writeText("first ✓")
                File(directory, "binary.bin").writeBytes(byteArrayOf(0, 13, 10, -1))
                val local = LocalTransferFiles(directory)
                SftpTransferFiles(profile.copy(remoteFolder = remoteFolder), c.sshConnections).use { remote ->
                    remote.connect()
                    assertEquals(SyncResult(2, 0), SyncEngine.apply(local, remote, SyncEngine.preview(local, remote, SyncDirection.UPLOAD), true))
                    File(directory, "a + b.txt").writeText("second ✓")
                    assertEquals(SyncResult(1, 0), SyncEngine.apply(local, remote, SyncEngine.preview(local, remote, SyncDirection.UPLOAD), true))
                    assertEquals("second ✓", remote.read("a + b.txt").bufferedReader().use { it.readText() })
                    val plan = SyncEngine.preview(local, remote, SyncDirection.DOWNLOAD)
                    assertEquals(SyncResult(1, 0), SyncEngine.apply(local, remote, plan, true))
                    assertEquals("remote", File(directory, "remote-only").readText())
                }
                SftpTransferFiles(profile.copy(fingerprint = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"), c.sshConnections).use { wrong ->
                    assertThrows(Exception::class.java) { wrong.connect() }
                }
            }
        } finally {
            withContext(Dispatchers.IO) { runCatching { c.sshConnections.preview(profile.ssh()) { channel ->
                channel.ls(remoteFolder).filterIsInstance<com.jcraft.jsch.ChannelSftp.LsEntry>().filter { it.filename !in listOf(".", "..") }.forEach { channel.rm("$remoteFolder/${it.filename}") }; channel.rmdir(remoteFolder)
            } } }
            directory.deleteRecursively()
        }
    }
    @Test fun sftpPrivateKeyAuthenticationUsesThePinnedConnection() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val key = File(requireNotNull(args.getString("sftpKeyPath"))).readText()
        val profile = SyncProfile(host = "127.0.0.1", port = args.getString("sftpPort", "8023").toInt(), username = "fixture", privateKey = key,
            passphrase = "fixture-key-passphrase", fingerprint = requireNotNull(args.getString("sftpFingerprint")))
        withContext(Dispatchers.IO) { SftpTransferFiles(profile, c.sshConnections).use { it.connect(); assertTrue(it.stat("")!!.directory) } }
    }
}
