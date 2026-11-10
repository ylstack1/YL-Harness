package com.androidharness.app.tools

import org.junit.Assert.*
import org.junit.Test

class StructuredPageTextTest {
    @Test fun pageSuppliedJsonLdArticlesAndNestedRepliesRemainReadable() {
        val html = """<html><body><main>Please enable JavaScript</main><script type="application/ld+json">{"@context":"https://schema.org","@type":"DiscussionForumPosting","headline":"A useful discussion","articleBody":"A detailed original post about restoring a project safely without losing the work already saved.","author":{"name":"Writer"},"comment":[{"@type":"Comment","text":"Save a backup first.","author":{"name":"Reader"},"comment":{"@type":"Comment","text":"That worked for me."}}]}</script></body></html>"""
        val text = requireNotNull(StructuredPageText.extract(html))
        assertTrue(text.contains("restoring a project safely")); assertTrue(text.contains("Save a backup first"))
        assertTrue(text.contains("That worked for me")); assertTrue(text.contains("Author: Writer"))
        assertFalse(text.contains("@context")); assertFalse(text.contains("enable JavaScript"))
    }
    @Test fun linkedGraphsAndHtmlEntitiesAreHandledButExecutableAndUnrelatedDataAreIgnored() {
        val html = """<SCRIPT TYPE='application/ld+json'>{"@graph":[{"@type":"WebPage","mainEntity":{"@type":"NewsArticle","headline":"Actual news","articleBody":"An actual article with enough detail to be useful to the reader &amp; its source is the current document, not a search snippet."}},{"@type":"Organization","description":"Ignore this boilerplate."}]}</SCRIPT><script>steal_secret()</script>"""
        val text = requireNotNull(StructuredPageText.extract(html))
        assertTrue(text.contains("reader & its source")); assertFalse(text.contains("steal_secret")); assertFalse(text.contains("boilerplate"))
        assertNull(StructuredPageText.extract("<script>document.body.innerText='invented'</script>"))
        assertNull(StructuredPageText.extract("<script type='application/ld+json'>{bad json}</script>"))
        assertNull(StructuredPageText.extract("""<script type='application/ld+json'>{"@type":"Person","name":"Only a name"}</script>"""))
        assertEquals("Visible article", htmlToText("<ARTICLE>Visible article</ARTICLE><SCRIPT>secret()</SCRIPT><STYLE>hidden</STYLE>", "html"))
    }
    @Test fun suppliedRedditJsonIncludesPostAndNestedCommentsWithoutPrivateOrDeletedBodies() {
        val raw = """[{"kind":"Listing","data":{"children":[{"kind":"t3","data":{"title":"Android project advice","author":"op","selftext":"How do I back up a project before changing files?"}}]}},{"kind":"Listing","data":{"children":[{"kind":"t1","data":{"author":"helper","body":"Copy the folder first.","replies":{"kind":"Listing","data":{"children":[{"kind":"t1","data":{"author":"reply","body":"Keep the archive elsewhere."}}]}}}},{"kind":"t1","data":{"author":"removed","body":"[deleted]"}}]}}]"""
        val text = requireNotNull(StructuredPageText.reddit(raw, "https://www.reddit.com/r/android/comments/123/post.json"))
        assertTrue(text.contains("2 supplied comments")); assertTrue(text.contains("partial thread"))
        assertTrue(text.contains("Copy the folder first")); assertTrue(text.contains("Keep the archive elsewhere"))
        assertFalse(text.contains("[deleted]")); assertFalse(text.contains("\"kind\""))
        assertNull(StructuredPageText.reddit(raw, "https://notreddit.com/thread"))
        assertNull(StructuredPageText.reddit("{}", "https://reddit.com/thread"))
    }
    @Test fun loadingPlaceholdersAreNotSuccessfulReadsAndRealErrorArticlesStillAre() {
        for (text in listOf("Loading…", "Please wait...", "Something went wrong", "Page not found")) assertNotNull(text, ReadableWebPage.problem(text))
        assertNull(ReadableWebPage.problem("An article about why a website says something went wrong, with examples and recovery advice."))
    }
}
