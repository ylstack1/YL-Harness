package com.androidharness.app.ui.files

import com.androidharness.app.workspace.ChatWorkspaceBinding
import com.androidharness.app.workspace.ChatWorkspaceFs
import com.androidharness.app.workspace.FileFs
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceSearchTest {
    @get:Rule val temp = TemporaryFolder()
    private fun root() = temp.newFolder()
    private fun file(root: File, name: String, text: String) = File(root, name).apply { parentFile.mkdirs(); writeText(text) }

    @Test fun indexSkipsGeneratedTreesAndEscapingSymlinks() = runBlocking {
        val root = root()
        file(root, "src/main.kt", "hello")
        file(root, "node_modules/x.js", "hello")
        file(root, ".git/config", "hello")
        val outside = file(root(), "secret.txt", "private")
        java.nio.file.Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath())
        val paths = WorkspaceSearch.index(FileFs(root)).paths
        assertEquals(listOf("src/main.kt"), paths)
    }
    @Test fun quickOpenRanksExactNamesAndFuzzyPaths() {
        val paths = listOf("src/main.kt", "test/main.kt", "src/ModelPicker.kt", "app.js")
        assertEquals("src/main.kt", WorkspaceSearch.quickOpen(paths, "main.kt").first())
        assertEquals(listOf("src/ModelPicker.kt"), WorkspaceSearch.quickOpen(paths, "mdp"))
        assertTrue(WorkspaceSearch.quickOpen(paths, "missing").isEmpty())
    }
    @Test fun quickOpenSeparatesPositiveLineSuffixFromFileQuery() {
        val target=WorkspaceSearch.quickOpenQuery(" src/main.kt:12 ")
        assertEquals("src/main.kt",target.name);assertEquals(12,target.line)
        assertEquals("b.txt",WorkspaceSearch.quickOpen(listOf("a.txt","b.txt"),WorkspaceSearch.quickOpenQuery("b.txt:2").name).single())
        assertNull(WorkspaceSearch.quickOpenQuery("b.txt:0").line)
        assertNull(WorkspaceSearch.quickOpenQuery("b.txt:invalid").line)
    }
    @Test fun searchesContentsAndReturnsCorrectLinesAndColumns() = runBlocking {
        val root = root(); file(root, "a.kt", "hello\n  HELLO world\n"); file(root, "b.txt", "hello")
        val fs = FileFs(root)
        val result = WorkspaceSearch.search(fs, WorkspaceSearch.index(fs), ProjectSearchOptions("hello"))
        assertEquals(3, result.count)
        assertEquals(listOf(1, 2), result.files.first().hits.map { it.line })
        assertEquals(3, result.files.first().hits.last().column)
        assertEquals(2, WorkspaceSearch.search(fs, WorkspaceSearch.index(fs), ProjectSearchOptions("hello", matchCase = true)).count)
    }
    @Test fun globFiltersSupportRootAndNestedFiles() = runBlocking {
        val root = root();file(root,"a.kt","needle");file(root,"src/b.kt","needle");file(root,"src/generated/c.kt","needle");file(root,"a.js","needle")
        val fs=FileFs(root)
        val result=WorkspaceSearch.search(fs,WorkspaceSearch.index(fs),ProjectSearchOptions("needle",include="**/*.kt",exclude="**/generated/**"))
        assertEquals(listOf("a.kt","src/b.kt"),result.files.map{it.path})
    }
    @Test fun literalReplacementPreservesDollarSignsAndBackslashes() = runBlocking {
        val root=root();file(root,"a.txt","hello HELLO")
        val fs=FileFs(root);val options=ProjectSearchOptions("hello")
        val found=WorkspaceSearch.search(fs,WorkspaceSearch.index(fs),options)
        val plan=WorkspaceSearch.replacements(found,options,"\$1\\folder")
        assertEquals("\$1\\folder \$1\\folder",plan.single().text)
    }
    @Test fun regexCaptureGroupsAreExpanded() = runBlocking {
        val root=root();file(root,"a.txt","item12 item34")
        val fs=FileFs(root);val options=ProjectSearchOptions("item(\\d+)",regex=true)
        val plan=WorkspaceSearch.replacements(WorkspaceSearch.search(fs,WorkspaceSearch.index(fs),options),options,"entry\$1")
        assertEquals("entry12 entry34",plan.single().text)
    }
    @Test fun replacementKeepsBomCrLfAndTracksOnlyChosenFiles() = runBlocking {
        val root=root();val a=File(root,"a.txt");val b=file(root,"b.txt","hello\n")
        val original=byteArrayOf(0xef.toByte(),0xbb.toByte(),0xbf.toByte())+"hello\r\n".toByteArray();a.writeBytes(original)
        val fs=FileFs(root);val options=ProjectSearchOptions("hello")
        val plan=WorkspaceSearch.replacements(WorkspaceSearch.search(fs,WorkspaceSearch.index(fs),options),options,"world")
        val receipt=WorkspaceSearch.apply(fs,listOf(plan.first()),{false})
        assertEquals(listOf("a.txt"),receipt.saved)
        assertTrue(receipt.failed.isEmpty())
        assertArrayEquals(byteArrayOf(0xef.toByte(),0xbb.toByte(),0xbf.toByte())+"world\r\n".toByteArray(),a.readBytes())
        assertEquals("hello\n",b.readText())
    }
    @Test fun stalePreviewPreflightsAllFilesBeforeAnyWrite() = runBlocking {
        val root=root();val a=file(root,"a.txt","hello");val b=file(root,"b.txt","hello")
        val fs=FileFs(root);val options=ProjectSearchOptions("hello")
        val plan=WorkspaceSearch.replacements(WorkspaceSearch.search(fs,WorkspaceSearch.index(fs),options),options,"world")
        b.writeText("external edit")
        val failure=runCatching{WorkspaceSearch.apply(fs,plan,{false})}.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("changed after the preview"))
        assertEquals("hello",a.readText());assertEquals("external edit",b.readText())
    }
    @Test fun unsavedDraftBlocksWholeBatchBeforeAnyWrite() = runBlocking {
        val root=root();val a=file(root,"a.txt","hello");file(root,"b.txt","hello")
        val fs=FileFs(root);val options=ProjectSearchOptions("hello")
        val plan=WorkspaceSearch.replacements(WorkspaceSearch.search(fs,WorkspaceSearch.index(fs),options),options,"world")
        val failure=runCatching{WorkspaceSearch.apply(fs,plan,{it=="b.txt"})}.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("unsaved edits"));assertEquals("hello",a.readText())
    }
    @Test fun readOnlyGrantBlocksReplacement() = runBlocking {
        val root=root();val a=file(root,"a.txt","hello")
        val fs=ChatWorkspaceFs("p",listOf(ChatWorkspaceBinding("p","project",false,FileFs(root))))
        val options=ProjectSearchOptions("hello")
        val plan=WorkspaceSearch.replacements(WorkspaceSearch.search(fs,WorkspaceSearch.index(fs),options),options,"world")
        assertTrue(runCatching{WorkspaceSearch.apply(fs,plan,{false})}.exceptionOrNull()?.message.orEmpty().contains("read-only"))
        assertEquals("hello",a.readText())
    }
    @Test fun limitsResultsAndDisallowsIncompleteReplacement() = runBlocking {
        val root=root();file(root,"a.txt","hello ".repeat(1100));val fs=FileFs(root);val options=ProjectSearchOptions("hello")
        val found=WorkspaceSearch.search(fs,WorkspaceSearch.index(fs),options)
        assertTrue(found.limited);assertEquals(1000,found.count)
        assertTrue(runCatching{WorkspaceSearch.replacements(found,options,"world")}.isFailure)
    }
    @Test fun binaryAndOversizedFilesAreSkipped() = runBlocking {
        val root=root();File(root,"bin").writeBytes(byteArrayOf(0,1,2));file(root,"huge.txt","x".repeat(512001));file(root,"text.txt","needle")
        val fs=FileFs(root);val found=WorkspaceSearch.search(fs,WorkspaceSearch.index(fs),ProjectSearchOptions("needle"))
        assertEquals(1,found.count);assertEquals(2,found.skipped)
    }
    @Test fun invalidRegexFailsWithoutTouchingFiles() = runBlocking {
        val root=root();val file=file(root,"a.txt","hello");val fs=FileFs(root)
        assertTrue(runCatching{WorkspaceSearch.search(fs,WorkspaceSearch.index(fs),ProjectSearchOptions("[",regex=true))}.isFailure)
        assertEquals("hello",file.readText())
    }
}
