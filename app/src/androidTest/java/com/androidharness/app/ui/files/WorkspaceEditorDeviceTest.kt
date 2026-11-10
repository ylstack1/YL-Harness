package com.androidharness.app.ui.files

import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.theme.HarnessTheme
import io.github.rosemoe.sora.widget.CodeEditor
import java.io.File
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceEditorDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun tabsRetainDraftUndoCursorAndRotationAndCloseSafely(): Unit = fixture { sid, root ->
        open(sid).use { scenario ->
            tapDesc("Edit")
            scenario.onActivity { activity -> editor(activity.window.decorView)!!.apply {
                text.insert(0, 5, " draft"); setSelection(1, 3)
            } }
            openAnother("b.txt")
            awaitEditor(scenario) { it.text.toString() == "hello b\nsecond line\n" }
            tapDesc("Tab a.txt, unsaved changes")
            awaitEditor(scenario) { it.text.toString().startsWith("hello draft") && it.cursor.leftLine == 1 && it.cursor.leftColumn == 3 && it.text.canUndo() }
            tapDesc("Undo")
            awaitEditor(scenario) { it.text.toString() == "hello a\nsecond line\n" }
            tapDesc("Redo")
            awaitEditor(scenario) { it.text.toString().startsWith("hello draft") }
            scenario.recreate(); mount(scenario,sid)
            awaitEditor(scenario) { it.text.toString().startsWith("hello draft") && it.text.canUndo() }
            shot("editor-tabs")
            tapDesc("Open files, 2 open");awaitText("Open files");shot("open-files")
            tapDesc("Close tab b.txt")
            awaitEditor(scenario) { it.text.toString().startsWith("hello draft") }
            eventually { nodes().any { it.contentDescription?.toString()=="Open files, 1 open" } }
            openAnother("b.txt");tapDesc("Tab a.txt, unsaved changes")
            tapDesc("Open files, 2 open");awaitText("Open files")
            tapDesc("Close tab a.txt");awaitText("Unsaved changes");tapText("Cancel")
            awaitEditor(scenario) { it.text.toString().startsWith("hello draft") }
            tapDesc("Close current file");awaitText("Unsaved changes");tapText("Discard")
            awaitEditor(scenario) { it.text.toString() == "hello b\nsecond line\n" }
            assertEquals("hello a\nsecond line\n",File(root,"a.txt").readText())
            assertTrue(nodes().any { it.contentDescription?.toString()=="Open files, 1 open" })
        }
    }

    @Test fun projectSearchPreviewsAndReplacesThenReloadsOpenEditor(): Unit = fixture { sid, root ->
        open(sid).use { scenario ->
            tapDesc("More");tapText("Search project");awaitText("Project search")
            setField("Find in project","hello")
            awaitText("2 matches in 2 files")
            tapText("Replace");setField("Replace with","world")
            scrollToText("Review changes");tapText("Review changes");awaitText("Review replacements")
            awaitText("Replace in 2 files");shot("replace-preview")
            tapText("Replace in 2 files");awaitText("Updated 2 files")
            assertEquals("world a\nsecond line\n",File(root,"a.txt").readText())
            assertEquals("world b\nsecond line\n",File(root,"b.txt").readText())
            tapDesc("Close workspace tools")
            awaitEditor(scenario) { it.text.toString() == "world a\nsecond line\n" }
        }
    }

    @Test fun staleReplacementPreviewWritesNothing(): Unit = fixture { sid, root ->
        open(sid).use {
            tapDesc("More");tapText("Search project");setField("Find in project","hello");awaitText("2 matches in 2 files")
            tapText("Replace");setField("Replace with","world");scrollToText("Review changes");tapText("Review changes");awaitText("Replace in 2 files")
            File(root,"b.txt").writeText("external work\n")
            tapText("Replace in 2 files")
            eventually { nodes().any { it.text?.toString()?.contains("changed after the preview") == true } }
            assertEquals("hello a\nsecond line\n",File(root,"a.txt").readText())
            assertEquals("external work\n",File(root,"b.txt").readText())
        }
    }

    @Test fun paletteAndPhysicalKeyboardOpenFilesAndCommands(): Unit = fixture { sid, _ ->
        open(sid).use { scenario ->
            scenario.onActivity { editor(it.window.decorView)!!.requestFocus() }
            chord(KeyEvent.KEYCODE_P,KeyEvent.META_CTRL_ON)
            awaitText("Quick Open");setField("File name or path","b.txt:2");instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
            awaitEditor(scenario) { it.text.toString().startsWith("hello b") && it.cursor.leftLine==1 }
            scenario.onActivity { editor(it.window.decorView)!!.requestFocus() }
            chord(KeyEvent.KEYCODE_P,KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
            awaitText("Command palette");shot("command-palette")
            setField("Search commands","wrap");tapText("Toggle word wrap")
            eventually { nodes().none { it.text?.toString()=="Command palette" } }
            tapDesc("More");tapText("Quick Open");setField("File name or path","a.txt");tapDesc("Open file a.txt")
            awaitEditor(scenario) { it.text.toString().startsWith("hello a") }
        }
    }

    @Test fun filesEntryPointOpensSearchAndPalette(): Unit = fixture { sid, _ ->
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { HarnessTheme { FilesScreen(c,sessionId=sid,onBack={},onOpenFile={}) } } }
            tapDesc("Search project");awaitText("Project search");setField("Find in project","hello");awaitText("2 matches in 2 files");shot("project-search")
            tapDesc("Close workspace tools");tapDesc("More");tapText("Command palette");awaitText("Command palette")
            setField("Search commands","refresh");tapText("Refresh files")
            eventually { nodes().none { it.text?.toString()=="Command palette" } }
        }
    }

    @Test fun chatOverflowOpensPaletteAndQuickOpenInItsWorkspace(): Unit = fixture { sid, _ ->
        var vm:com.androidharness.app.ui.chat.ChatViewModel?=null
        var opened:String?=null
        try {
            instrumentation.runOnMainSync{vm=com.androidharness.app.ui.chat.ChatViewModel(c,sid)}
            withTimeout(10000){vm!!.state.first{!it.isLoadingMessages}}
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { HarnessTheme {
                    com.androidharness.app.ui.ChatWorkspaceScreen(vm!!, onOpenDrawer={},onOpenFile={path,_->opened=path},onNewChat={},onOpenTerminal={},onOpenSubagent={})
                } } }
                tapDesc("More options");tapText("Command palette");awaitText("Command palette")
                setField("Search commands","quick");tapText("Quick Open");awaitText("Quick Open")
                setField("File name or path","b.txt");tapDesc("Open file b.txt")
                eventually{opened?.endsWith("b.txt")==true}
            }
        } finally {instrumentation.runOnMainSync{vm?.viewModelScope?.cancel()}}
    }

    @Test fun embeddedEditorReopensTheFileChosenFromTheBrowser(): Unit = fixture { sid, _ ->
        assumeTrue("Requires wide workspace verification",InstrumentationRegistry.getArguments().getString("desktop")=="true")
        val oldLayout=c.settings.settings.first().workspaceLayout
        var vm:com.androidharness.app.ui.chat.ChatViewModel?=null
        try {
            c.settings.setWorkspaceLayout(com.androidharness.app.data.WorkspaceLayoutMode.DESKTOP)
            instrumentation.runOnMainSync{vm=com.androidharness.app.ui.chat.ChatViewModel(c,sid)}
            withTimeout(10000){vm!!.state.first{!it.isLoadingMessages}}
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { HarnessTheme {
                    com.androidharness.app.ui.ChatWorkspaceScreen(vm!!, onOpenDrawer={},onOpenFile={_,_->fail("Expected embedded editor")},onNewChat={},onOpenTerminal={},onOpenSubagent={})
                } } }
                tapText("Files");tapText("a.txt");awaitEditor(scenario){it.text.toString().startsWith("hello a")}
                openAnother("b.txt");awaitEditor(scenario){it.text.toString().startsWith("hello b")}
                tapText("Files");tapText("a.txt");awaitEditor(scenario){it.text.toString().startsWith("hello a")}
                shot("embedded-tabs")
            }
        } finally {
            c.settings.setWorkspaceLayout(oldLayout)
            instrumentation.runOnMainSync{vm?.viewModelScope?.cancel()}
        }
    }

    @Test fun tabListAndDraftsSurviveProcessTermination(): Unit = runBlocking {
        val phase=InstrumentationRegistry.getArguments().getString("tabsRecoveryPhase")
        assumeTrue("Requires host-controlled force-stop",phase in setOf("seed","verify"))
        val fixture=File(c.appContext.noBackupFilesDir,"tabs-process-test.properties")
        if(phase=="seed") {
            val root=File(c.appContext.noBackupFilesDir,"tabs-process-test").apply{mkdirs()}
            File(root,"a.txt").writeText("hello a\nsecond line\n");File(root,"b.txt").writeText("hello b\nsecond line\n")
            val project=c.workspace.addShellProject(root.path,activate=false);val sid=c.sessions.createProjectSession(project.id)
            open(sid).use { scenario ->
                tapDesc("Edit");scenario.onActivity{editor(it.window.decorView)!!.text.insert(0,5," process draft")}
                openAnother("b.txt");awaitEditor(scenario){it.text.toString().startsWith("hello b")}
                val identity=c.workspace.forSession(sid).displayPath
                val tabsKey=EditorDraftStore.key(sid,null,identity,"<open-tabs>")
                val tabsFile=File(c.appContext.noBackupFilesDir,"editor-tabs/$tabsKey.json")
                eventually { runCatching { kotlinx.serialization.json.Json.decodeFromString<EditorTabsSnapshot>(tabsFile.readText()).let { it.active=="b.txt" && it.paths==listOf("a.txt","b.txt") } }.getOrDefault(false) }
                val draftKey=EditorDraftStore.key(sid,null,identity,"a.txt")
                c.editorDrafts.flush(draftKey)
                assertTrue(c.editorDrafts.read(draftKey)?.text?.startsWith("hello process draft") == true)
                Properties().apply{setProperty("sid",sid);setProperty("root",root.path);setProperty("project",project.id);setProperty("pid",android.os.Process.myPid().toString())}
                    .also{properties->fixture.outputStream().use{properties.store(it,"Ready for host force-stop")}}
                CountDownLatch(1).await(90,TimeUnit.SECONDS);fail("Host did not stop the process")
            }
        } else {
            val props=Properties().apply{fixture.inputStream().use{load(it)}};val sid=props.getProperty("sid");val root=File(props.getProperty("root"))
            try {
                assertNotEquals(props.getProperty("pid").toInt(),android.os.Process.myPid())
                open(sid).use { scenario ->
                    awaitEditor(scenario){it.text.toString().startsWith("hello b")}
                    tapDesc("Tab a.txt, unsaved changes")
                    awaitEditor(scenario){it.text.toString().startsWith("hello process draft")}
                    assertEquals("hello a\nsecond line\n",File(root,"a.txt").readText())
                }
            } finally {
                cleanup(sid,root)
                c.workspace.projects.first().firstOrNull{it.id==props.getProperty("project")}?.let{c.workspace.deleteProject(it)}
                fixture.delete()
            }
        }
    }

    private fun fixture(block: suspend (String,File)->Unit): Unit = runBlocking {
        val settings=c.settings.settings.first()
        val root=File(c.appContext.cacheDir,"workspace-editor-${System.nanoTime()}").apply{mkdirs()}
        File(root,"a.txt").writeText("hello a\nsecond line\n");File(root,"b.txt").writeText("hello b\nsecond line\n")
        val project=c.workspace.addShellProject(root.path,activate=false);val sid=c.sessions.createProjectSession(project.id)
        try { block(sid,root) } finally {
            c.settings.setEditorWordWrap(settings.editorWordWrap)
            cleanup(sid,root);c.workspace.deleteProject(project)
        }
    }
    private suspend fun cleanup(sid:String,root:File) {
        val identity=c.workspace.forSession(sid).displayPath
        for(path in listOf("a.txt","b.txt")) {val key=EditorDraftStore.key(sid,null,identity,path);c.editorDrafts.submit(key,null,true);c.editorDrafts.flush(key)}
        c.sessions.session(sid)?.let{c.sessions.deleteSession(it)};root.deleteRecursively()
    }
    private fun open(sid:String)=ActivityScenario.launch(ChatListTestActivity::class.java).also{mount(it,sid)}
    private fun mount(scenario:ActivityScenario<ChatListTestActivity>,sid:String) {
        scenario.onActivity { activity->activity.setContent{HarnessTheme{WorkspaceEditorScreen(c,"a.txt",sessionId=sid,onBack={})}} }
        awaitEditor(scenario){true}
    }
    private fun openAnother(path:String) { tapDesc("More");tapText("Quick Open");setField("File name or path",path);tapDesc("Open file $path") }
    private fun chord(code:Int,meta:Int) {
        val now=SystemClock.uptimeMillis()
        for(action in listOf(KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP)) assertTrue(instrumentation.uiAutomation.injectInputEvent(KeyEvent(now,now,action,code,0,meta),true))
    }
    private fun editor(view:View):CodeEditor? {if(view is CodeEditor)return view;if(view is ViewGroup)for(i in 0 until view.childCount)editor(view.getChildAt(i))?.let{return it};return null}
    private fun awaitEditor(scenario:ActivityScenario<ChatListTestActivity>,check:(CodeEditor)->Boolean)=eventually{var ok=false;scenario.onActivity{ok=editor(it.window.decorView)?.let(check)?:false};ok}
    private fun nodes():List<AccessibilityNodeInfo> = buildList {
        instrumentation.uiAutomation.clearCache()
        fun visit(node:AccessibilityNodeInfo){if(node.isVisibleToUser)add(node);for(i in 0 until node.childCount)node.getChild(i)?.let(::visit)}
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun setField(label:String,value:String) {
        scrollToText(label)
        tapText(label)
        instrumentation.sendStringSync(value)
        val info=instrumentation.uiAutomation.serviceInfo
        info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        instrumentation.uiAutomation.serviceInfo=info
        if(instrumentation.uiAutomation.windows.any{it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD}) {
            shot("keyboard-${label.lowercase().replace(' ','-')}")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        }
    }

    private fun tapText(text:String)=tap{it.text?.toString()?.lineSequence()?.any{line->line==text}==true}
    private fun tapDesc(desc:String)=tap{it.contentDescription?.toString()==desc}
    private fun tap(match:(AccessibilityNodeInfo)->Boolean) {
        var node:AccessibilityNodeInfo?=null
        var previous:Rect?=null;var stable=0
        eventually {
            node=nodes().firstOrNull(match)
            val bounds=node?.let{n->Rect().also{n.getBoundsInScreen(it)}}
            if(bounds!=null && bounds==previous)stable++ else stable=0
            previous=bounds
            node!=null && stable>=2
        }
        val bounds=Rect().also{node!!.getBoundsInScreen(it)};val now=SystemClock.uptimeMillis()
        for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)){val event=MotionEvent.obtain(now,now,action,bounds.exactCenterX(),bounds.exactCenterY(),0);try{assertTrue(instrumentation.uiAutomation.injectInputEvent(event,true))}finally{event.recycle()}}
    }
    private fun scrollToText(text:String) {repeat(5){if(nodes().any{it.text?.toString()==text})return;nodes().firstOrNull{it.isScrollable}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);Thread.sleep(150)}}
    private fun awaitText(text:String)=eventually{nodes().any{it.text?.toString()==text}}
    private fun shot(name:String) {
        val dir=File(c.appContext.getExternalFilesDir(null),"workspace-editor-checks").apply{mkdirs()}
        val suffix=InstrumentationRegistry.getArguments().getString("shotSuffix","phone")
        instrumentation.uiAutomation.takeScreenshot()?.let{bitmap->File(dir,"$name-$suffix.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
    }
    private fun eventually(check:()->Boolean) {val end=SystemClock.uptimeMillis()+12000;while(SystemClock.uptimeMillis()<end){if(check())return;Thread.sleep(70)};assertTrue("Timed out; visible text: ${nodes().mapNotNull{it.text?.toString()}.joinToString(" | ")}",check())}
}
