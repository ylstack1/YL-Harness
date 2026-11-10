package com.androidharness.app.data

import com.androidharness.app.data.db.MemoryEntity
import org.junit.Assert.*
import org.junit.Test

class MemoryReliabilityTest {
    private fun memory(id: String, text: String, section: String = "CHAT", scope: String = "GLOBAL",
        category: String = "FACT", updated: Long = 1, enabled: Boolean = true, parent: String? = null, summary: Boolean = false) =
        MemoryEntity(id, text, category, scope, projectId = if (scope == "PROJECT") "project" else null,
            createdAt = 1, updatedAt = updated, section = section, enabled = enabled, consolidatedInto = parent, isSummary = summary)

    @Test fun ownershipAndCrossLookupRespectBothSectionsAndMasterSwitch() {
        val prefs = AppSettings()
        assertTrue(MemoryAccess.canRecall(prefs, MemorySection.CHAT, MemorySection.CHAT))
        assertTrue(MemoryAccess.canRecall(prefs, MemorySection.CODE, MemorySection.SHARED))
        assertFalse(MemoryAccess.canRecall(prefs, MemorySection.CHAT, MemorySection.CODE))
        val cross = prefs.copy(chatRecallCodeMemory = true)
        assertTrue(MemoryAccess.canRecall(cross, MemorySection.CHAT, MemorySection.CODE))
        assertFalse(MemoryAccess.canRecall(cross, MemorySection.CODE, MemorySection.CHAT))
        assertFalse(MemoryAccess.canRecall(cross.copy(codeMemoryEnabled = false), MemorySection.CHAT, MemorySection.CODE))
        assertFalse(MemoryAccess.canRecall(cross.copy(chatMemoryEnabled = false), MemorySection.CHAT, MemorySection.SHARED))
        assertFalse(MemoryAccess.canRecall(cross.copy(memoryEnabled = false), MemorySection.CHAT, MemorySection.CODE))
        assertFalse(MemoryAccess.belongsTo(memory("a", "Code fact", section = "CODE"), MemorySection.CHAT))
        assertTrue(MemoryAccess.belongsTo(memory("b", "Legacy fact", section = "SHARED"), MemorySection.CHAT))
    }

    @Test fun duplicateKeysKeepTechnicalIdentifiersNegationAndNumbersDistinct() {
        assertEquals(MemoryMatch.key("  I prefer Kotlin. "), MemoryMatch.key("I prefer KOTLIN!"))
        assertNotEquals(MemoryMatch.key("Use C++"), MemoryMatch.key("Use C#"))
        assertNotEquals(MemoryMatch.key("Use version 1.2"), MemoryMatch.key("Use version 1-2"))
        assertNotEquals(MemoryMatch.key("Use dark mode"), MemoryMatch.key("Do not use dark mode"))
        assertNotEquals(MemoryMatch.key("Port 8022"), MemoryMatch.key("Port 8023"))
        assertTrue(MemoryMatch.similar("I prefer concise Kotlin examples for Android development", "Use concise Kotlin examples for Android development"))
    }

    @Test fun relevantOlderFactBeatsRepeatedlyUsedUnrelatedMemories() {
        val old = memory("old", "The garden uses drip irrigation", updated = 1)
        val recent = memory("recent", "The vacation is in December", updated = 500).copy(lastUsedAt = 10000, useCount = 50)
        val prompt = MemoryPrompt.build(listOf(recent, old), maxChars = 80, query = "garden irrigation")
        assertEquals(listOf("old"), prompt.usedIds)
        assertTrue(prompt.text.contains("id:old"))
        assertFalse(prompt.text.contains("vacation"))
    }

    @Test fun archivedOriginalsAndDisabledFactsNeverEnterPrompt() {
        val block = MemoryPrompt.build(listOf(memory("off", "Private", enabled = false),
            memory("archived", "Preserved original", parent = "digest")))
        assertTrue(block.usedIds.isEmpty())
        assertTrue(block.text.isEmpty())
    }

    @Test fun consolidationSeparatesSectionsProjectsAndRetainsAllOriginalFacts() {
        val data = listOf(memory("a", "Name is Sam", category = "PROFILE"),
            memory("b", "Use concise replies", category = "PREFERENCE"),
            memory("c", "Ship weekly", category = "DECISION"), memory("d", "Lives in Kochi"),
            memory("code", "Kotlin code", section = "CODE"), memory("project", "Project fact", scope = "PROJECT"))
        val plan = MemoryConsolidation.plans(data).single()
        assertEquals(setOf("a", "b", "c", "d"), plan.originals.map { it.id }.toSet())
        assertTrue(plan.text.contains("Profile:")); assertTrue(plan.text.contains("Preferences:"))
        assertTrue(plan.text.contains("Facts:")); assertTrue(plan.text.contains("Decisions:"))
        plan.originals.forEach { assertTrue(plan.text.contains(it.text)) }
        assertEquals(data[0], plan.originals[0])
    }

    @Test fun largeDigestContributesRelevantFactsWithinBudgetAndAccurateReceipt() {
        val text = "Facts:\n" + (1..80).joinToString("\n") { "• Detail $it about an unrelated subject" } + "\n• Garden irrigation is automated"
        val entry = memory("digest", text, summary = true)
        val block = MemoryPrompt.build(listOf(entry), maxChars = 180, query = "garden irrigation")
        assertEquals(listOf("digest"), block.usedIds)
        assertTrue(block.text.length <= 180)
        assertTrue(block.text.contains("Garden irrigation"))
        assertTrue(block.supplied.single().text.contains("Garden irrigation"))
        assertFalse(block.supplied.single().text.contains("Detail 80"))
    }

    @Test fun backupKeepsSectionDigestAndRestorableOriginals() {
        val existing = memory("original", "A fact", parent = "digest")
        val digest = memory("digest", "Facts:\n• A fact", summary = true)
        val plan = MemoryBackupMerge.plan(emptyList(), listOf(existing.toBackupMemory(), digest.toBackupMemory()))
        assertEquals("digest", plan.first { it.id == "original" }.consolidatedInto)
        assertEquals("CHAT", plan.first().section)
        assertTrue(plan.first { it.id == "digest" }.isSummary)
        val orphan = MemoryBackupMerge.plan(emptyList(), listOf(existing.toBackupMemory())).single()
        assertNull(orphan.consolidatedInto)
        assertEquals("SHARED", MemoryBackupMerge.normalize(BackupMemory("legacy", "Legacy memory"))!!.section)
        assertEquals(listOf(existing, digest), plan.map { MemoryBackupMerge.entity(it) })
    }
    @Test fun memoryPreferencesRoundTripWithoutEnablingCrossSectionLookup() {
        val prefs = AppSettings(chatMemoryEnabled = false, codeMemoryEnabled = true, chatRecallCodeMemory = false, codeRecallChatMemory = true)
        val encoded = kotlinx.serialization.json.Json.encodeToString(AppSettings.serializer(), prefs)
        val decoded = kotlinx.serialization.json.Json.decodeFromString(AppSettings.serializer(), encoded)
        assertFalse(decoded.chatMemoryEnabled); assertTrue(decoded.codeMemoryEnabled)
        assertFalse(decoded.chatRecallCodeMemory); assertTrue(decoded.codeRecallChatMemory)
        val old = kotlinx.serialization.json.Json.decodeFromString(AppSettings.serializer(), "{}")
        assertTrue(old.chatMemoryEnabled); assertTrue(old.codeMemoryEnabled)
        assertFalse(old.chatRecallCodeMemory); assertFalse(old.codeRecallChatMemory)
    }

    @Test fun receiptDistinguishesSuccessfulUpdatesFromFailedOrUnchangedWrites() {
        val calls = com.androidharness.app.core.ChatMessage(com.androidharness.app.core.Role.ASSISTANT,
            toolCalls = listOf(com.androidharness.app.core.ToolCallData("memory", "remember", "{}")))
        fun result(action: String, failed: Boolean = false) = com.androidharness.app.core.ChatMessage(com.androidharness.app.core.Role.TOOL,
            "[memory_id:a]\n[memory_action:$action]", toolCallId = "memory", toolName = "remember", isError = failed)
        assertEquals("updated", com.androidharness.app.core.ChatActivityPresentation.memoryAction(listOf(calls, result("updated"))))
        assertEquals("unchanged", com.androidharness.app.core.ChatActivityPresentation.memoryAction(listOf(calls, result("unchanged"))))
        assertFalse(com.androidharness.app.core.ChatActivityPresentation.savedMemory(listOf(calls, result("updated", true))))
        assertEquals("saved", com.androidharness.app.core.ChatActivityPresentation.memoryAction(listOf(calls, result("saved"))))
    }

}
