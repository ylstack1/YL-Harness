package com.androidharness.app.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.data.ImageStore
import com.androidharness.app.tools.BrowserEvalTool
import com.androidharness.app.tools.ToolContext
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BrowserReliabilityDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun dialogsAndBlockedPopupsAreReportedWithTheirContentAndOutcome() = runBlocking {
        val root = File(context.cacheDir, "browser-dialogs-${System.nanoTime()}").apply { mkdirs() }
        val fs = FileFs(root)
        root.resolve("index.html").writeText("<html><body>Dialog fixture</body></html>")
        val controller = BrowserController(context, ImageStore(context)).apply { currentWorkspace = fs }
        try {
            controller.navigate("index.html", fs)
            val result = BrowserEvalTool(controller).execute(buildJsonObject {
                put("code", "alert('Alert text'); return [confirm('Confirm text'), prompt('Prompt text')];")
            }, ToolContext(fs))
            assertTrue(result.output, result.ok)
            assertTrue(result.output, result.output.contains("Alert text"))
            assertTrue(result.output, result.output.contains("Confirm text"))
            assertTrue(result.output, result.output.contains("Prompt text"))
            assertTrue(result.output, result.output.contains("false") && result.output.contains("null"))
            val popup = controller.evalUser("return window.open('https://example.invalid/');")
            val notices = popup.notices + controller.extractState().notices
            assertTrue(notices.toString(), notices.any { it.contains("Popup window blocked") })
        } finally { controller.close(); root.deleteRecursively() }
    }

    @Test fun infiniteJavascriptTimesOutAndTheBrowserCanNavigateAgain() = runBlocking {
        val root = File(context.cacheDir, "browser-timeout-${System.nanoTime()}").apply { mkdirs() }
        val fs = FileFs(root)
        root.resolve("index.html").writeText("<html><body>Recovered browser</body></html>")
        val controller = BrowserController(context, ImageStore(context)).apply { currentWorkspace = fs }
        try {
            controller.navigate("index.html", fs)
            val outcome = withTimeout(20_000) { controller.evalUser("while (true) {}") }
            assertFalse(outcome.ok)
            assertTrue(outcome.error.orEmpty(), outcome.error.orEmpty().contains("timed out"))
            val recovered = withTimeout(20_000) { controller.navigate("index.html", fs) }
            assertTrue(recovered.toString(), recovered.textSummary.contains("Recovered browser"))
        } finally { controller.close(); root.deleteRecursively() }
    }

    @Test fun backFromBlankAfterBeforeUnloadReturnsToThePreviousPage() = runBlocking {
        val root = File(context.cacheDir, "browser-history-${System.nanoTime()}").apply { mkdirs() }
        val fs = FileFs(root)
        root.resolve("index.html").writeText("<html><body>Original page<button id='leave' onclick=\"window.onbeforeunload=function(e){e.preventDefault();e.returnValue='Leave page?';};location.href='about:blank';\">Leave</button></body></html>")
        val controller = BrowserController(context, ImageStore(context)).apply { currentWorkspace = fs }
        try {
            controller.navigate("index.html", fs)
            controller.click(selector = "#leave")
            val before = controller.debugHistoryDump()
            val start = android.os.SystemClock.elapsedRealtime()
            val previous = withTimeout(30_000) { controller.back() }
            val elapsed = android.os.SystemClock.elapsedRealtime() - start
            val detail = "${elapsed}ms; history before=$before; after=${controller.debugHistoryDump()}; $previous"
            assertTrue(detail, previous.textSummary.contains("Original page"))
            assertTrue(detail, elapsed < 15_000)
        } finally { controller.close(); root.deleteRecursively() }
    }
}
