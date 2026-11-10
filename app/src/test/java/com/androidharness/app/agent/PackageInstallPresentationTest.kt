package com.androidharness.app.agent

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.data.env.PackageInstallPhase
import com.androidharness.app.data.env.PackageInstallStatus
import org.junit.Assert.*
import org.junit.Test

class PackageInstallPresentationTest {
    private val call = ToolCallData("install", "pkg_install", """{"packages":["ffmpeg"],"reason":"Convert the video."}""")
    private val downloading = PackageInstallStatus(listOf("ffmpeg"), PackageInstallPhase.DOWNLOADING,
        listOf("libx264", "ffmpeg"), currentPackage = "libx264", downloadedBytes = 2048, totalBytes = 8192)

    @Test fun `only a successful result can claim installation completed`() {
        assertEquals("Install ffmpeg", toolPresentation(call).title)
        for (phase in PackageInstallPhase.entries) {
            val info = packageInstallPresentation(call, null, true, false, downloading.copy(phase = phase, downloadedBytes = 8192))
            assertEquals("Installing ffmpeg", info.title)
            assertNotNull(info.phase)
        }
        assertEquals("Installed ffmpeg", packageInstallPresentation(call, ChatMessage(Role.TOOL, "Ready"), true, false, downloading).title)
        assertEquals("Install failed: ffmpeg", packageInstallPresentation(call, ChatMessage(Role.TOOL, "Checksum mismatch", isError = true), true, false, downloading).title)
        assertNull(packageInstallPresentation(call, ChatMessage(Role.TOOL, "Ready"), true, false, downloading).phase)
    }

    @Test fun `download shows dependency bytes while overall counts measure unpacked packages`() {
        val info = packageInstallPresentation(call, null, true, false, downloading)
        assertEquals("Downloading libx264", info.phase)
        assertEquals("2.0 KB / 8.0 KB", info.bytes)
        assertEquals(25, info.percentage)
        assertEquals("0 of 2 packages unpacked", info.count)
        assertEquals("Convert the video.", info.reason)
        val last = packageInstallPresentation(call, null, true, false, downloading.copy(
            phase = PackageInstallPhase.UNPACKING, currentPackage = "ffmpeg", completed = listOf("libx264")))
        assertEquals("Unpacking ffmpeg", last.phase)
        assertEquals("1 of 2 packages unpacked", last.count)
        assertEquals(listOf("libx264"), last.recent)
        assertNull(last.percentage)
        assertNull(last.bytes)
    }

    @Test fun `approval interrupted work and unknown sizes never invent completion`() {
        assertEquals("Waiting for approval", packageInstallPresentation(call, null, true, true, downloading).phase)
        val stopped = packageInstallPresentation(call, null, false, false, downloading)
        assertEquals("Not completed", stopped.phase)
        assertNull(stopped.fraction)
        assertNull(stopped.count)
        val unknown = packageInstallPresentation(call, null, true, false, downloading.copy(totalBytes = null))
        assertNull(unknown.percentage)
        assertEquals("2.0 KB", unknown.bytes)
        assertEquals(100, packageInstallPresentation(call, null, true, false, downloading.copy(downloadedBytes = 99999)).percentage)
    }
}
