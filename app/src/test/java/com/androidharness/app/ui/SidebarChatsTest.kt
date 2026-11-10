package com.androidharness.app.ui

import com.androidharness.app.agent.RunManager
import com.androidharness.app.data.db.ProjectEntity
import com.androidharness.app.data.db.SessionEntity
import org.junit.Assert.*
import org.junit.Test

class SidebarChatsTest {
    private fun chat(id: String, project: String? = null, plan: String? = null) = SessionEntity(id, id, 0, 0, projectId = project, pendingPlan = plan)
    private val projects = listOf(ProjectEntity("a", "One", "APP", null, 0), ProjectEntity("b", "Two", "APP", null, 0))

    @Test fun pinnedArchivedAndLegacyChatsAreNeitherDuplicatedNorLost() {
        val sessions = listOf(chat("pinned", "a"), chat("active", "a"), chat("other-project", "b"),
            chat("archived-pin", "a"), chat("no-project"), chat("missing-project", "removed"))
        val result = SidebarChats.of(projects, sessions, setOf("pinned", "archived-pin"), setOf("archived-pin"))
        assertEquals(listOf("pinned"), result.pinned.map { it.id })
        assertEquals(listOf("active"), result.projects.getValue("a").map { it.id })
        assertEquals(listOf("other-project"), result.projects.getValue("b").map { it.id })
        assertEquals(listOf("archived-pin"), result.archived.map { it.id })
        assertEquals(listOf("no-project", "missing-project"), result.other.map { it.id })
        val flattened = result.pinned + result.projects.values.flatten() + result.other + result.archived
        assertEquals(sessions.map { it.id }.toSet(), flattened.map { it.id }.toSet())
        assertEquals(sessions.size, flattened.size)
    }

    @Test fun emptyAndDuplicateProjectNamesKeepIdentityAndInputOrder() {
        val sameNames = projects.map { it.copy(name = "Same") }
        val result = SidebarChats.of(sameNames, listOf(chat("new", "b"), chat("old", "b")), emptySet(), emptySet())
        assertTrue(result.projects["a"].isNullOrEmpty())
        assertEquals(listOf("new", "old"), result.projects.getValue("b").map { it.id })
        assertEquals(listOf("new", "old"), SidebarChats.of(emptyList(), listOf(chat("new", "b"), chat("old", "b")), emptySet(), emptySet()).other.map { it.id })
    }

    @Test fun onlyLiveWorkOrAnActualPendingPlanGetsAStatus() {
        assertNull(sidebarActivity(false, null))
        assertNull(sidebarActivity(false, " "))
        assertEquals(SidebarActivity.WORKING, sidebarActivity(true, null))
        assertEquals(SidebarActivity.NEEDS_YOU, sidebarActivity(true, "Review this plan"))
        assertEquals(SidebarActivity.NEEDS_YOU, sidebarActivity(false, "Review this plan"))
        assertEquals(SidebarActivity.NEEDS_YOU, sidebarActivity(true, null, RunManager.LiveRunState("a", running = true, pendingPlan = "A plan")))
        assertNull(sidebarActivity(false, null, RunManager.LiveRunState("a", error = "Old error")))
    }
}
