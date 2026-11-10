package com.androidharness.app.ui.files

import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EditorTabsStoreTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun closingTabsChoosesAdjacentFileAndDoesNotMixScopes() = runBlocking {
        val store=EditorTabsStore(temp.newFolder(),this)
        val a=store.workspace("a","project-a");val b=store.workspace("b","project-b")
        a.open("a.kt");a.open("b.kt");a.open("c.kt");b.open("a.kt")
        a.close("b.kt");assertEquals("c.kt",a.active)
        a.close("c.kt");assertEquals("a.kt",a.active)
        a.close("a.kt");assertNull(a.active);assertEquals(listOf("a.kt"),b.paths.toList())
    }
    @Test fun freshStoreRestoresActiveTabOrderAndPositions() = runBlocking {
        val root=temp.newFolder();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val store=EditorTabsStore(root,scope);val a=store.workspace("tabs","project")
            a.open("a.kt");a.open("b.kt");a.open("a.kt");a.requestId="navigation-request"
            a.buffer("a.kt").apply{selectionStart=12;selectionEnd=15;offsetX=7;offsetY=80}
            store.persist(a)
            withTimeout(3000){while(!File(root,"tabs.json").exists())delay(10)}
            val restored=EditorTabsStore(root,scope).workspace("tabs","project")
            assertEquals(listOf("a.kt","b.kt"),restored.paths.toList());assertEquals("a.kt",restored.active)
            assertEquals("navigation-request",restored.requestId)
            assertEquals(12,restored.buffer("a.kt").selectionStart);assertEquals(15,restored.buffer("a.kt").selectionEnd)
            assertEquals(80,restored.buffer("a.kt").offsetY)
        } finally {scope.cancel()}
    }
    @Test fun replacementInvalidationRetainsDirtyBuffersAndOtherWorkspaces() = runBlocking {
        val store=EditorTabsStore(temp.newFolder(),this)
        val a=store.workspace("a","one");val b=store.workspace("b","two")
        val clean=a.buffer("clean.kt");val dirty=a.buffer("dirty.kt").apply{dirty.value=true}
        val other=b.buffer("clean.kt")
        store.invalidateSaved("one",setOf("clean.kt","dirty.kt"))
        assertNotSame(clean,a.buffer("clean.kt"));assertSame(dirty,a.buffer("dirty.kt"));assertSame(other,b.buffer("clean.kt"))
    }
}
