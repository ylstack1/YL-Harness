package com.androidharness.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

class MarkdownStreamingTest {
    @Test fun completeParagraphsMoveToStablePrefixWithoutLosingTheirSeparation() {
        val tracker = MarkdownStreamTracker()
        val start = "First paragraph.\n\nSecond **paragraph**"
        val window = tracker.update(start)
        assertEquals("First paragraph.\n\n", start.substring(0, window.stableEnd))
        val finished = start + ".\n\n"
        assertEquals(finished.length, tracker.update(finished).stableEnd)
        assertNull(tracker.update(finished).openFenceStart)
    }

    @Test fun byteSizedUpdatesNeverSplitBlankLinesInsideCodeOrDisplayMath() {
        val tracker = MarkdownStreamTracker()
        val prefix = "Intro.\n\n"
        val code = "```kotlin\nval x = 1\n\nval y = 2\n```\n\n"
        val math = "$$\na + b\n\nc + d\n$$\n\n"
        val body = prefix + code + math + "A final paragraph"
        for (end in 1..body.length) {
            val window = tracker.update(body.take(end))
            if (end > prefix.length && end < prefix.length + code.length) assertEquals(prefix.length, window.stableEnd)
            if (end > prefix.length + code.length && end < prefix.length + code.length + math.length)
                assertEquals(prefix.length + code.length, window.stableEnd)
        }
        assertEquals(prefix.length + code.length + math.length, tracker.update(body).stableEnd)
    }

    @Test fun longerFenceKeepsLiteralInnerBackticksInsideCode() {
        val tracker = MarkdownStreamTracker()
        val text = "Before\n\n````markdown\n```kotlin\n\nval x = 1\n```\n\n"
        val window = tracker.update(text)
        assertEquals(8, window.stableEnd)
        assertEquals(8, window.openFenceStart)
        assertEquals(text.indexOf("```kotlin"), window.fenceBodyStart)
        assertNull(tracker.update(text + "````\n\n").openFenceStart)
    }

    @Test fun replacementAtSameBoundaryInvalidatesCachedPrefixAndResetsFenceState() {
        val tracker = MarkdownStreamTracker()
        val before = tracker.update("First\n\n```kotlin\nwork")
        val after = tracker.update("Other\n\nNew content")
        assertEquals(before.stableEnd, after.stableEnd)
        assertTrue(after.revision > before.revision)
        assertNull(after.openFenceStart)
        assertEquals(0, tracker.update("").stableEnd)
    }

    @Test fun tableRowsStayInOneLiveBlockUntilTheFollowingBlankLine() {
        val tracker = MarkdownStreamTracker()
        val intro = "Compare the values.\n\n"
        val table = "| Name | Value |\n| --- | ---: |\n| A | 1 |\n| B | 2 |"
        assertEquals(intro.length, tracker.update(intro + table).stableEnd)
        assertEquals(intro.length + table.length + 2, tracker.update(intro + table + "\n\nNotes").stableEnd)
    }
    @Test fun closingFenceIsRecognizedBeforeItsFinalNewlineArrives() {
        val tracker = MarkdownStreamTracker()
        assertNotNull(tracker.update("```kotlin\nval x = 1\n").openFenceStart)
        assertNull(tracker.update("```kotlin\nval x = 1\n```").openFenceStart)
        assertNull(tracker.update("```kotlin\nval x = 1\n```\n\nNext").openFenceStart)
    }

}
