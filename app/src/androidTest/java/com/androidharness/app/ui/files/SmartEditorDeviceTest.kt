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
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmartEditorDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container
    private val mainText = "import { greet } from './lib';\ngreet('hello');\n// greet stays in this comment\n"
    private val libText = "export function greet(name:string){return name.toUpperCase()}\n"

    @Test fun bundledWorkerWorksOfflineWithSemanticNavigationCompletionOutlineAndFormatting(): Unit = runBlocking {
        val engine = SmartEditorEngine(c.appContext)
        try {
            val files = mapOf("main.ts" to mainText, "lib.ts" to libText)
            val definition = engine.request("definition", "main.ts", files, mainText.indexOf("greet('" )).jsonArray
            assertEquals("lib.ts", definition.first().jsonObject.getValue("path").jsonPrimitive.content)
            val references = engine.request("references", "lib.ts", files, libText.indexOf("greet")).jsonArray
            assertEquals(3, references.size)
            val completionText = "const title: string = 'hello';\ntitle.toUp"
            val completions = engine.request("complete", "main.ts", files + ("main.ts" to completionText), completionText.length).jsonArray
            assertTrue(completions.any { it.jsonObject.getValue("label").jsonPrimitive.content == "toUpperCase" })
            val outline = engine.request("outline", "lib.ts", files).jsonArray
            assertTrue(outline.any { it.jsonObject.getValue("name").jsonPrimitive.content == "greet" })
            val formatted = engine.request("format", "lib.ts", files).jsonPrimitive.content
            assertTrue(formatted.contains("name: string"))
            assertTrue(formatted.contains("  return name.toUpperCase();"))
        } finally { instrumentation.runOnMainSync { engine.close() } }
    }

    @Test fun outlineFormattingUndoAndNativeAutocompleteWork(): Unit = fixture { sid, root ->
        File(root, "main.ts").writeText("// function fake() {}\nclass Account {\n balance=0;\n deposit(amount:number){this.balance+=amount}\n}\nconst title:string='hi';\ntitle.toUp")
        open(sid).use { scenario ->
            tapDesc("Code outline"); awaitText("Code outline"); awaitText("Account")
            assertFalse(nodes().any { it.text?.toString() == "fake" })
            shot("outline")
            tapText("deposit")
            awaitEditor(scenario) { it.cursor.leftLine == 3 }
            val original = File(root,"main.ts").readText()
            menu("Format Document")
            awaitEditor(scenario) { it.text.toString().contains("amount: number") && it.text.canUndo() }
            assertEquals(original,File(root,"main.ts").readText())
            shot("formatted-draft")
            tapDesc("Undo"); awaitEditor(scenario) { it.text.toString() == original }
            scenario.onActivity { activity -> editor(activity.window.decorView)!!.apply {
                isEditable = true
                requestFocus()
                text.replace(text.length - 1, text.length, "")
                setSelection(text.lineCount - 1, text.getColumnCount(text.lineCount - 1))
            } }
            awaitEditor(scenario) { !it.getComponent(EditorAutoCompletion::class.java).checkNoCompletion() }
            instrumentation.sendStringSync("p")
            awaitText("toUpperCase");shot("autocomplete");tapText("toUpperCase")
            awaitEditor(scenario) { it.text.toString().endsWith("title.toUpperCase") }
        }
    }

    @Test fun definitionsOpenTabsAndRenameCreatesReviewedDrafts(): Unit = fixture { sid, root ->
        open(sid).use { scenario ->
            cursor(scenario, mainText.indexOf("greet('"))
            menu("Go to Definition");awaitText("1 location");shot("definition")
            tapText("lib.ts · 1:17")
            awaitEditor(scenario) { it.text.toString() == libText && it.cursor.leftLine == 0 && it.cursor.leftColumn == 16 }
            menu("Find References");awaitText("3 locations");shot("references");tapDesc("Close code tools")
            menu("Rename Symbol");setField("New symbol name", "welcome")
            tapText("Review rename");awaitText("Rename in 2 files");shot("rename-preview")
            tapText("Rename in 2 files");awaitText("Back to editor");tapText("Back to editor")
            awaitEditor(scenario) { it.text.toString().contains("function welcome(") && it.text.canUndo() }
            assertEquals(libText, File(root,"lib.ts").readText())
            assertEquals(mainText, File(root,"main.ts").readText())
            tapDesc("Tab main.ts, unsaved changes")
            awaitEditor(scenario) { it.text.toString().contains("welcome('hello')") && it.text.toString().contains("// greet stays") }
            shot("renamed-tabs")
            tapDesc("Save");eventually { File(root,"main.ts").readText().contains("welcome('hello')") }
            assertEquals(libText,File(root,"lib.ts").readText())
        }
    }

    @Test fun renameRejectsAFileChangedAfterPreview(): Unit = fixture { sid, root ->
        open(sid).use { scenario ->
            cursor(scenario, mainText.indexOf("greet('"))
            menu("Rename Symbol");setField("New symbol name", "welcome");tapText("Review rename")
            awaitText("Rename in 1 file")
            File(root,"main.ts").writeText(mainText + "// external update\n")
            tapText("Rename in 1 file")
            eventually { nodes().any { it.text?.toString()?.contains("changed after the preview") == true } }
            assertEquals(mainText + "// external update\n", File(root,"main.ts").readText())
            tapDesc("Close code tools")
            awaitEditor(scenario) { it.text.toString() == mainText }
        }
    }

    @Test fun smartToolsWorkInEmbeddedWorkspace(): Unit = fixture { sid, _ ->
        assumeTrue("Requires wide workspace verification", InstrumentationRegistry.getArguments().getString("desktop") == "true")
        val layout = c.settings.settings.first().workspaceLayout
        var vm: com.androidharness.app.ui.chat.ChatViewModel? = null
        try {
            c.settings.setWorkspaceLayout(com.androidharness.app.data.WorkspaceLayoutMode.DESKTOP)
            instrumentation.runOnMainSync { vm = com.androidharness.app.ui.chat.ChatViewModel(c, sid) }
            withTimeout(10000) { vm!!.state.first { !it.isLoadingMessages } }
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { HarnessTheme {
                    com.androidharness.app.ui.ChatWorkspaceScreen(vm!!, onOpenDrawer = {}, onOpenFile = { _, _ -> fail("Expected embedded editor") },
                        onNewChat = {}, onOpenTerminal = {}, onOpenSubagent = {})
                } } }
                tapText("Files"); tapText("main.ts"); awaitEditor(scenario) { it.text.toString() == mainText }
                cursor(scenario, mainText.indexOf("greet('")); menu("Go to Definition"); awaitText("1 location")
                tapText("lib.ts · 1:17"); awaitEditor(scenario) { it.text.toString() == libText }
                tapDesc("Code outline"); awaitText("greet");shot("embedded-outline");tapText("greet")
                menu("Format Document");awaitEditor(scenario) { it.text.toString().contains("name: string") }
                shot("embedded-formatted-draft")
            }
        } finally {
            c.settings.setWorkspaceLayout(layout)
            instrumentation.runOnMainSync { vm?.viewModelScope?.cancel() }
        }
    }

    private fun fixture(block:suspend (String,File)->Unit): Unit = runBlocking {
        val root = File(c.appContext.cacheDir,"smart-editor-${System.nanoTime()}").apply { mkdirs() }
        File(root,"main.ts").writeText(mainText);File(root,"lib.ts").writeText(libText)
        val project = c.workspace.addShellProject(root.path,activate=false)
        val sid = c.sessions.createProjectSession(project.id)
        try { block(sid,root) } finally {
            val identity = c.workspace.forSession(sid).displayPath
            for(path in listOf("main.ts","lib.ts")) { val key = EditorDraftStore.key(sid,null,identity,path); c.editorDrafts.submit(key,null,true); c.editorDrafts.flush(key) }
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) };c.workspace.deleteProject(project);root.deleteRecursively()
        }
    }
    private fun open(sid:String) = ActivityScenario.launch(ChatListTestActivity::class.java).also { scenario ->
        scenario.onActivity { activity -> activity.setContent { HarnessTheme { WorkspaceEditorScreen(c,"main.ts",sessionId=sid,onBack={}) } } }
        awaitEditor(scenario) { true }
    }
    private fun cursor(scenario: ActivityScenario<ChatListTestActivity>, offset:Int) { scenario.onActivity { activity -> editor(activity.window.decorView)!!.apply { val pos=text.indexer.getCharPosition(offset); setSelection(pos.line,pos.column) } } }
    private fun menu(label:String) { tapDesc("More");tapText(label) }
    private fun editor(view:View):CodeEditor? { if(view is CodeEditor)return view; if(view is ViewGroup)for(i in 0 until view.childCount)editor(view.getChildAt(i))?.let{return it};return null }
    private fun awaitEditor(scenario:ActivityScenario<ChatListTestActivity>, check:(CodeEditor)->Boolean) = eventually { var ok=false; scenario.onActivity { activity -> ok=editor(activity.window.decorView)?.let(check)==true };ok }
    private fun nodes():List<AccessibilityNodeInfo> {
        val info = instrumentation.uiAutomation.serviceInfo
        if (info.flags and android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS == 0) {
            info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            instrumentation.uiAutomation.serviceInfo = info
        }
        val result=mutableListOf<AccessibilityNodeInfo>()
        fun visit(node:AccessibilityNodeInfo?) { if(node==null)return; result+=node;for(i in 0 until node.childCount)visit(node.getChild(i)) }
        instrumentation.uiAutomation.windows.forEach { visit(it.root) };visit(instrumentation.uiAutomation.rootInActiveWindow)
        return result
    }
    private fun setField(label:String,value:String) {
        tapText(label);instrumentation.sendStringSync(value)
        if(InstrumentationRegistry.getArguments().getString("captureKeyboard") == "true") {
            Thread.sleep(250)
            assertTrue("Expected the on-screen keyboard", instrumentation.uiAutomation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD })
            shot("keyboard-${label.lowercase().replace(' ','-')}")
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    }
    private fun tapText(text:String)=tap{it.text?.toString()?.lineSequence()?.any{line->line==text}==true}
    private fun tapDesc(desc:String)=tap{it.contentDescription?.toString()==desc}
    private fun tap(match:(AccessibilityNodeInfo)->Boolean) {
        var node:AccessibilityNodeInfo?=null;var previous:Rect?=null;var stable=0
        eventually { node=nodes().firstOrNull(match);val bounds=node?.let{n->Rect().also{n.getBoundsInScreen(it)}};if(bounds!=null&&bounds==previous)stable++ else stable=0;previous=bounds;node!=null&&stable>=2 }
        val bounds=Rect().also{node!!.getBoundsInScreen(it)};val now=SystemClock.uptimeMillis()
        for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) { val event=MotionEvent.obtain(now,now,action,bounds.exactCenterX(),bounds.exactCenterY(),0);try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event,true)) } finally { event.recycle() } }
    }
    private fun awaitText(text:String)=eventually { nodes().any{it.text?.toString()==text} }
    private fun eventually(check:()->Boolean) { val end=SystemClock.uptimeMillis()+25000;while(SystemClock.uptimeMillis()<end){if(check())return;Thread.sleep(80)};assertTrue("Timed out; visible text: ${nodes().mapNotNull{it.text?.toString()}.joinToString(" | ")}",check()) }
    private fun shot(name:String) {
        val dir=File(c.appContext.getExternalFilesDir(null),"smart-editor-checks").apply{mkdirs()}
        val suffix=InstrumentationRegistry.getArguments().getString("shotSuffix","phone")
        instrumentation.uiAutomation.takeScreenshot()?.let{bitmap->File(dir,"$name-$suffix.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
    }
}
