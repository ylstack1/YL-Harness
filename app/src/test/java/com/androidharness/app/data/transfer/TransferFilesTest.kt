package com.androidharness.app.data.transfer

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TransferFilesTest {
    private fun fixtures(block: (LocalTransferFiles, LocalTransferFiles) -> Unit) {
        val folder = Files.createTempDirectory("sync-test").toFile()
        try { block(LocalTransferFiles(File(folder, "local").apply { mkdirs() }), LocalTransferFiles(File(folder, "remote").apply { mkdirs() })) }
        finally { folder.deleteRecursively() }
    }
    private fun LocalTransferFiles.put(path: String, text: String) = writeAtomic(path, text.byteInputStream()) {}

    @Test fun pathsAreConfinedAndSymlinksNeverFollowed() = fixtures { local, remote ->
        remote.put("secret", "outside")
        for (path in listOf("../remote/secret", "/etc/passwd", "a/../../remote/secret", "a\\b", "a\nb")) assertThrows(IllegalArgumentException::class.java) { local.file(path) }
        Files.createSymbolicLink(local.root.resolve("linked"), remote.root)
        assertThrows(IllegalArgumentException::class.java) { local.read("linked/secret") }
        assertThrows(IllegalArgumentException::class.java) { local.put("linked/new", "bad") }
        assertFalse(local.list("").any { it.path == "linked" })
        assertEquals("outside", remote.read("secret").bufferedReader().use { it.readText() })
        assertThrows(IllegalArgumentException::class.java) { TransferPaths.ftp("", "../../secret") }
        assertEquals("a/c", TransferPaths.ftp("a/b", "../c"))
    }
    @Test fun previewComparesContentAndSkipsGeneratedFoldersWithoutDeletingAnything() = fixtures { local, remote ->
        local.put("same", "yes"); remote.put("same", "yes")
        local.put("changed", "one"); remote.put("changed", "two")
        local.put("new dir/a + b.txt", "new"); local.put(".git/config", "private git"); local.put("build/skip", "generated")
        local.mkdir("empty"); remote.put("remote-only", "keep")
        val plan = SyncEngine.preview(local, remote, SyncDirection.UPLOAD)
        assertEquals(1, plan.unchanged); assertEquals(1, plan.replacements)
        assertEquals(setOf("changed", "new dir/a + b.txt"), plan.changes.map { it.path }.toSet())
        val result = SyncEngine.apply(local, remote, plan, false)
        assertEquals(SyncResult(1, 1), result)
        assertEquals("two", remote.read("changed").bufferedReader().use { it.readText() })
        assertNotNull(remote.stat("remote-only")); assertEquals(true, remote.stat("empty")?.directory)
        assertNull(remote.stat(".git"))
    }
    @Test fun explicitReplacementsAndDownloadsPreserveDestinationOnlyFiles() = fixtures { local, remote ->
        local.put("file", "old"); local.put("local-only", "keep"); remote.put("file", "new")
        val permissions = java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")
        Files.setPosixFilePermissions(local.root.resolve("file"), permissions)
        val plan = SyncEngine.preview(local, remote, SyncDirection.DOWNLOAD)
        assertEquals(SyncResult(1, 0), SyncEngine.apply(local, remote, plan, true))
        assertEquals("new", local.read("file").bufferedReader().use { it.readText() }); assertNotNull(local.stat("local-only"))
        assertEquals(permissions, Files.getPosixFilePermissions(local.root.resolve("file")))
    }
    @Test fun changedDestinationAndSourceCannotBeOverwrittenAfterPreview() = fixtures { local, remote ->
        local.put("a", "source"); remote.put("a", "target")
        val first = SyncEngine.preview(local, remote, SyncDirection.UPLOAD)
        remote.put("a", "changed")
        assertThrows(IllegalStateException::class.java) { SyncEngine.apply(local, remote, first, true) }
        assertEquals("changed", remote.read("a").bufferedReader().use { it.readText() })
        val second = SyncEngine.preview(local, remote, SyncDirection.UPLOAD)
        local.put("a", "source changed")
        assertThrows(IllegalStateException::class.java) { SyncEngine.apply(local, remote, second, true) }
        assertEquals("changed", remote.read("a").bufferedReader().use { it.readText() })
        assertTrue(remote.root.toFile().listFiles()!!.none { it.name.startsWith(".harness-transfer-") })
    }
    @Test fun destinationChangedWhileStagingIsCheckedBeforePublication() = fixtures { local, remote ->
        local.put("a", "source"); remote.put("a", "original")
        val plan = SyncEngine.preview(local, remote, SyncDirection.UPLOAD)
        val target = object : TransferFiles by remote {
            override fun writeAtomic(path: String, input: java.io.InputStream, beforeCommit: () -> Unit) {
                remote.writeAtomic(path, input) { remote.put(path, "concurrent edit"); beforeCommit() }
            }
        }
        assertThrows(IllegalStateException::class.java) { SyncEngine.apply(local, target, plan, true) }
        assertEquals("concurrent edit", remote.read("a").bufferedReader().use { it.readText() })
    }
    @Test fun filesAndDirectoriesCannotReplaceEachOther() = fixtures { local, remote ->
        local.put("a", "file"); remote.mkdir("a")
        assertThrows(IllegalArgumentException::class.java) { SyncEngine.preview(local, remote, SyncDirection.UPLOAD) }
        assertThrows(IllegalArgumentException::class.java) { SyncEngine.preview(local, remote, SyncDirection.DOWNLOAD) }
    }
    @Test fun anInterruptedStagedWriteLeavesTheOriginalAndRemovesItsTemporaryFile() = fixtures { local, _ ->
        local.put("a", "original")
        Thread.currentThread().interrupt()
        try { assertThrows(java.util.concurrent.CancellationException::class.java) { local.put("a", "partial") } }
        finally { Thread.interrupted() }
        assertEquals("original", local.read("a").bufferedReader().use { it.readText() })
        assertEquals(listOf("a"), local.root.toFile().listFiles()!!.map { it.name })
    }
}
