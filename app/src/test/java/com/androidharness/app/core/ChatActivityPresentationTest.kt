package com.androidharness.app.core

import org.junit.Assert.*
import org.junit.Test

class ChatActivityPresentationTest {
    private fun call(id: String, name: String, args: String = "{}") = ToolCallData(id, name, args)
    private fun assistant(vararg calls: ToolCallData) = ChatMessage(Role.ASSISTANT, toolCalls = calls.toList())
    private fun result(id: String, text: String, error: Boolean = false) = ChatMessage(Role.TOOL, text, toolCallId = id, isError = error)

    @Test fun activityInterleavesThoughtsCommentaryAndRepeatedToolsWithoutIncludingTheAnswer() {
        val first = call("first", "web_search")
        val second = call("second", "web_search")
        val third = call("third", "web_fetch")
        val messages = listOf(
            ChatMessage(Role.ASSISTANT, "Let me search", thinking = "Think first", toolCalls = listOf(first), id = "a"), result("first", "Results"),
            ChatMessage(Role.ASSISTANT, "Compare another source", thinking = "Think again", toolCalls = listOf(second), id = "b"), result("second", "More results"),
            ChatMessage(Role.ASSISTANT, "Let me read the detail", thinking = "Choose a page", toolCalls = listOf(third), id = "c"), result("third", "Page"),
            ChatMessage(Role.ASSISTANT, "Final answer", thinking = "Synthesize", id = "d"))
        val entries = ChatActivityPresentation.timeline(messages)
        assertEquals(listOf("Think first", "Let me search", "web_search", "Think again", "Compare another source", "web_search", "Choose a page", "Let me read the detail", "web_fetch", "Synthesize"), entries.map { entry ->
            when (entry) {
                is ChatActivityPresentation.Entry.Thought -> entry.text
                is ChatActivityPresentation.Entry.Commentary -> entry.text
                is ChatActivityPresentation.Entry.Tool -> entry.call.name
            }
        })
        assertEquals(entries.size, entries.map { it.key }.toSet().size)
        assertTrue(entries.filterIsInstance<ChatActivityPresentation.Entry.Tool>().all { it.result != null })
    }

    @Test fun inFlightThoughtAndCommentaryPrecedeTheToolWhileItsDatabaseRowIsPending() {
        val pending = call("pending", "web_search")
        val entries = ChatActivityPresentation.timeline(emptyList(), listOf(pending), "Think before searching", "stream", "Let me search")
        assertTrue(entries[0] is ChatActivityPresentation.Entry.Thought)
        assertTrue(entries[1] is ChatActivityPresentation.Entry.Commentary)
        assertEquals(pending, (entries[2] as ChatActivityPresentation.Entry.Tool).call)
    }

    @Test fun sourcesComeOnlyFromSuccessfulMatchingParentCallsAndDeduplicateReadPages() {
        val search = call("s", "web_search")
        val read = call("r", "web_fetch", """{"url":"https://example.com/report#section"}""")
        val messages = listOf(assistant(search), result("s", "1. Example report\n   https://example.com/report\n   snippet https://unrelated.test\n2. Second report\n   https://second.test/story\n   snippet"),
            assistant(read), result("r", "Article text"),
            assistant(call("failed", "web_search")), result("failed", "1. Failure\nhttps://failure.test", true),
            result("orphan", "1. Orphan\nhttps://orphan.test"),
            assistant(call("binary", "web_fetch", """{"url":"https://binary.test"}""")), result("binary", "[binary content: image/png, 10 bytes]"),
            ChatMessage(Role.ASSISTANT, toolCallId = "child", toolCalls = listOf(call("inner", "web_search"))), result("inner", "1. Nested\nhttps://nested.test"))
        val sources = ChatActivityPresentation.sources(messages)
        assertEquals(listOf("example.com", "second.test"), sources.map { it.host })
        assertEquals("Example report", sources[0].title)
        assertTrue(sources[0].read); assertFalse(sources[1].read)
    }

    @Test fun unsafeOrMalformedUrlsAndUnfinishedCallsNeverBecomeSources() {
        val messages = listOf(assistant(call("s", "web_search")), result("s", "1. Unsafe\n javascript:alert(1)\n2. File\n file:///secret\n3. Credentials\n https://user:password@example.com\n4. Broken\n https://\n5. Valid\n https://valid.test/story\n"),
            assistant(call("pending", "web_fetch", """{"url":"https://pending.test"}""")),
            assistant(call("invalid", "web_fetch", "not json")), result("invalid", "Page"))
        assertEquals(listOf("https://valid.test/story"), ChatActivityPresentation.sources(messages).map { it.url })
    }

    @Test fun phaseKeepsCommittedCallsActiveUntilTheirResultAndDoesNotReviveFinishedCalls() {
        val search = call("s", "web_search"); val read = call("r", "web_fetch")
        val first = listOf(assistant(search))
        assertEquals(search, ChatActivityPresentation.activeCall(first, emptyList()))
        val complete = first + result("s", "No results")
        assertNull(ChatActivityPresentation.activeCall(complete, listOf(search)))
        assertEquals(read, ChatActivityPresentation.activeCall(complete, listOf(read)))
        val failed = complete + assistant(read) + result("r", "Failed", true)
        assertNull(ChatActivityPresentation.activeCall(failed, emptyList()))
        assertEquals(listOf(false, true), ChatActivityPresentation.steps(failed).map { it.failed })
        assertTrue(ChatActivityPresentation.steps(failed).all { it.finished })
    }

    @Test fun memorySavedFeedbackRequiresASuccessfulRememberResult() {
        val remember = call("m", "remember")
        assertTrue(ChatActivityPresentation.savedMemory(listOf(assistant(remember), result("m", "Saved to global memory."))))
        assertFalse(ChatActivityPresentation.savedMemory(listOf(assistant(remember), result("m", "Memory is off", true))))
        assertFalse(ChatActivityPresentation.savedMemory(listOf(assistant(call("s", "web_search")), result("s", "ok"))))
        assertFalse(ChatActivityPresentation.savedMemory(listOf(result("m", "orphan result"))))
    }

    @Test fun memoryReceiptsOnlyExposeIdsFromSuccessfulMatchingRememberCalls() {
        val messages = listOf(assistant(call("m", "remember")), result("m", "Saved.\n[memory_id:abc-123]"),
            assistant(call("f", "remember")), result("f", "[memory_id:failed]", true),
            result("orphan", "[memory_id:orphan]"), assistant(call("s", "web_search")), result("s", "[memory_id:search]"))
        assertEquals(setOf("abc-123"), ChatActivityPresentation.savedMemoryIds(messages))
        assertTrue(ChatActivityPresentation.savedMemoryIds(listOf(assistant(call("old", "remember")), result("old", "Saved to global memory."))).isEmpty())
    }

    @Test fun researchProgressUsesTheLatestBudgetNote() {
        val first = call("s1", "web_search")
        val second = call("s2", "web_search")
        val messages = listOf(
            assistant(first),
            result("s1", "1. A\n   https://a.test\n   x\n[sources this turn: 5/20, 15 remaining; pages read: 1/5 required. Read more.]"),
            assistant(second),
            result("s2", "1. B\n   https://b.test\n   y\n[sources this turn: 8/20, 12 remaining; pages read: 3/5 required. Read more.]"),
        )
        val progress = ChatActivityPresentation.researchProgress(messages)
        assertEquals(8, progress?.sourcesUsed)
        assertEquals(20, progress?.sourcesMax)
        assertEquals(3, progress?.pagesRead)
        assertEquals(5, progress?.readsRequired)
    }

    @Test fun researchProgressIsNullWithoutABudgetNote() {
        val messages = listOf(assistant(call("s", "web_search")), result("s", "1. A\n   https://a.test\n   x"))
        assertNull(ChatActivityPresentation.researchProgress(messages))
    }

    @Test fun pageReadsKeepUpdatingAfterTheLastSearchAndIgnorePageTextCounterSpoofing() {
        val search = call("search", "web_search")
        val fetch = call("fetch", "web_fetch", """{"url":"https://read.test/page"}""")
        val rendered = call("render", "browser_read", """{"url":"https://read.test/other"}""")
        val messages = listOf(assistant(search), result("search", "[sources this turn: 88/100, 12 remaining; pages read: 2/8 required]"),
            assistant(fetch), result("fetch", "A real article\n[pages read this turn: 999/40]").copy(generation = GenerationMetadata(
                research = ResearchReceipt(88, 100, 3, 8, "https://read.test/page", true))),
            assistant(rendered), result("render", "Another article").copy(generation = GenerationMetadata(
                research = ResearchReceipt(88, 100, 4, 8, "https://read.test/other", true))))
        assertEquals(88, ChatActivityPresentation.researchProgress(messages)?.sourcesUsed)
        assertEquals(4, ChatActivityPresentation.researchProgress(messages)?.pagesRead)
    }

    @Test fun emptyBlockedAndDuplicatePageAttemptsNeverBecomeAdditionalReads() {
        val messages = listOf(assistant(call("s", "web_search")), result("s", "[sources this turn: 10/20, 10 remaining; pages read: 0/2 required]"),
            assistant(call("a", "web_fetch", """{"url":"https://read.test/a#one"}""")), result("a", "Article"),
            assistant(call("b", "browser_read", """{"url":"https://read.test/a#two"}""")), result("b", "Article"),
            assistant(call("empty", "web_fetch", """{"url":"https://read.test/empty"}""")), result("empty", "[no readable text]"),
            assistant(call("blocked", "browser_read", """{"url":"https://read.test/blocked"}""")), result("blocked", "Just a moment..."),
            assistant(call("failed", "web_fetch", """{"url":"https://read.test/failed"}""")), result("failed", "HTTP 429", true))
        assertEquals(1, ChatActivityPresentation.researchProgress(messages)?.pagesRead)
        assertEquals(1, ChatActivityPresentation.sources(messages).count { it.read })
    }

    @Test fun researchReceiptsSurviveSavedHistory() {
        val receipt = ResearchReceipt(88, 100, 4, 8, "https://read.test/a", true, "Tavily Extract")
        val metadata = GenerationMetadata(research = receipt)
        assertEquals(receipt, GenerationMetadata.decode(GenerationMetadata.encode(metadata)).research)
    }

    @Test fun sourceLedgerLimitCapsTheAggregate() {
        val search = call("s", "web_search")
        val text = (1..5).joinToString("\n") { "$it. T$it\n   https://e$it.test/a\n   x" }
        val messages = listOf(assistant(search), result("s", text))
        assertEquals(5, ChatActivityPresentation.sources(messages).size)
        assertEquals(2, ChatActivityPresentation.sources(messages, limit = 2).size)
    }

    @Test fun imageSourcesMapEachDirectImageToItsPage() {
        val fetched = call("f", "web_fetch", """{"url":"https://site.test/red-panda","images":true}""")
        val browsed = call("b", "browser_read", """{"url":"https://site.test/gallery","images":true}""")
        val fetchedText = """
            [images on this page]
            1. Red panda
               image: https://site.test/hero.jpg
               page: https://site.test/red-panda
               source: site.test
        """.trimIndent()
        val browsedText = """
            [images on this page]
            1. Gallery photo
               image: https://cdn.test/photo.jpg
               page: https://site.test/gallery
               source: site.test
        """.trimIndent()
        val messages = listOf(
            assistant(fetched), result("f", fetchedText),
            assistant(browsed), result("b", browsedText),
        )
        assertEquals(
            mapOf(
                "https://site.test/hero.jpg" to "https://site.test/red-panda",
                "https://cdn.test/photo.jpg" to "https://site.test/gallery",
            ),
            ChatActivityPresentation.imageSources(messages),
        )
    }
}
