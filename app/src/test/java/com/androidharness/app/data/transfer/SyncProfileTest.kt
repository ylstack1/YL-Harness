package com.androidharness.app.data.transfer

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json

class SyncProfileTest {
    private val ftp = SyncProfile(host = "localhost", port = 2121, username = "user", password = "password", protocol = SyncProtocol.FTP)
    @Test fun ftpAndPinnedSftpProfilesKeepStructuredCredentialsOutOfLogs() {
        ftp.validate()
        val ssh = ftp.copy(protocol = SyncProtocol.SFTP, port = 22, fingerprint = "SHA256:fixture")
        ssh.validate(); assertEquals("password", ssh.ssh().password)
        assertFalse(ftp.toString().contains("password"))
        assertEquals(ssh, Json.decodeFromString<SyncProfile>(Json.encodeToString(SyncProfile.serializer(), ssh)))
    }
    @Test fun malformedHostsCommandsAndUnpinnedSshCannotBeSaved() {
        for (invalid in listOf(ftp.copy(host = "ftp://host"), ftp.copy(host = "bad host"), ftp.copy(port = 0), ftp.copy(username = "user\nPASS injected"), ftp.copy(password = "a\r\nb"), ftp.copy(remoteFolder = "../escape"), ftp.copy(remoteFolder = "/a/../escape"), ftp.copy(protocol = SyncProtocol.SFTP)))
            assertThrows(IllegalArgumentException::class.java) { invalid.validate() }
        ftp.copy(protocol = SyncProtocol.SFTP).validate(pin = false)
    }
}
