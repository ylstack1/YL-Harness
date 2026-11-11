package com.ylstack1.ylharness.agent

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * Tests the pure archive-extraction part of [OpenCodeProcessManager]: an npm
 * tarball (`opencode-linux-arm64-<v>.tgz`) ships the binary as
 * `package/opencode`; extraction must find it under any `package/` prefix,
 * write the bytes, and mark the file executable.
 */
class OpenCodeProcessManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun npmTarballWithBinary(binaryBytes: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(GZIPOutputStream(bytes)).use { tar ->
            val readme = TarArchiveEntry("package/README.md")
            readme.size = 5L
            tar.putArchiveEntry(readme)
            tar.write("hello".toByteArray())
            tar.closeArchiveEntry()

            val binary = TarArchiveEntry("package/opencode")
            binary.size = binaryBytes.size.toLong()
            binary.mode = 0b111_101_101 // executable, like npm preserves
            tar.putArchiveEntry(binary)
            tar.write(binaryBytes)
            tar.closeArchiveEntry()
        }
        return bytes.toByteArray()
    }

    @Test
    fun `extracts the opencode binary from an npm tarball and makes it executable`() {
        val payload = "FAKE_OPENCODE_BINARY".toByteArray()
        val tarball = npmTarballWithBinary(payload)
        val target = File(tmp.newFolder(), "opencode")

        OpenCodeProcessManager.extractBinary(ByteArrayInputStream(tarball), target)

        assertTrue(target.exists())
        assertArrayEquals(payload, target.readBytes())
        assertTrue(target.canExecute())
    }
}
