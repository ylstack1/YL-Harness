package com.androidharness.app.data

import com.androidharness.app.data.db.MemoryEntity
import com.androidharness.app.data.db.SnippetEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryBackupTest {

    private fun existing(id: String, text: String, scope: String = "GLOBAL", projectId: String? = null) =
        MemoryEntity(id = id, text = text, scope = scope, projectId = projectId, createdAt = 1, updatedAt = 1)

    private fun incoming(
        id: String,
        text: String,
        scope: String = "GLOBAL",
        projectId: String? = null,
        category: String = "FACT",
    ) = BackupMemory(
        id = id,
        text = text,
        category = category,
        scope = scope,
        projectId = projectId,
        createdAt = 1,
        updatedAt = 1,
    )

    @Test
    fun `plan skips existing ids and the same normalized fact`() {
        val plan = MemoryBackupMerge.plan(
            listOf(existing("a", "Likes concise answers")),
            listOf(
                incoming("a", "Likes concise answers"),
                incoming("b", "likes   CONCISE answers"),
                incoming("c", "Prefers Kotlin"),
            ),
        )
        assertEquals(listOf("c"), plan.map { it.id })
    }

    @Test
    fun `the same fact in different projects is kept`() {
        val plan = MemoryBackupMerge.plan(
            listOf(existing("a", "Uses Room", "PROJECT", "p1")),
            listOf(incoming("b", "Uses Room", "PROJECT", "p2")),
        )
        assertEquals(listOf("b"), plan.map { it.id })
    }

    @Test
    fun `a project memory whose project is gone becomes global`() {
        val plan = MemoryBackupMerge.plan(
            emptyList(),
            listOf(incoming("a", "Uses Room", "PROJECT", "gone")),
        ) { null }
        assertEquals("GLOBAL", plan.single().scope)
        assertNull(plan.single().projectId)
    }

    @Test
    fun `a remapped project keeps its project scope`() {
        val plan = MemoryBackupMerge.plan(
            emptyList(),
            listOf(incoming("a", "Uses Room", "PROJECT", "old")),
        ) { if (it == "old") "new" else it }
        assertEquals("PROJECT", plan.single().scope)
        assertEquals("new", plan.single().projectId)
    }

    @Test
    fun `normalize drops blank text and repairs an unknown category`() {
        assertNull(MemoryBackupMerge.normalize(incoming("a", "   ")))
        val plan = MemoryBackupMerge.plan(emptyList(), listOf(incoming("b", "Fact", category = "nonsense")))
        assertEquals("FACT", plan.single().category)
    }

    @Test
    fun `snippet plan skips existing ids and names`() {
        val plan = SnippetBackupMerge.plan(
            listOf(SnippetEntity("a", "review", "Review this")),
            listOf(
                BackupSnippet("a", "other", "Different body"),
                BackupSnippet("b", "Review", "Different body"),
                BackupSnippet("c", "summary", "Summarize"),
            ),
        )
        assertEquals(listOf("c"), plan.map { it.id })
    }
}
