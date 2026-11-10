package com.androidharness.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoPromptTest {

    @Test
    fun `empty list produces nothing`() {
        assertEquals("", TodoPrompt.format(emptyList()))
    }

    @Test
    fun `live todos are injected with status`() {
        val text = TodoPrompt.format(
            listOf(
                TodoItem("Fix ignore walk", TodoItem.Status.IN_PROGRESS),
                TodoItem("Install APK", TodoItem.Status.PENDING),
            ),
        )
        assertTrue(text.contains("Current task list"))
        assertTrue(text.contains("[in_progress] Fix ignore walk"))
        assertTrue(text.contains("[pending] Install APK"))
    }

    @Test fun `unfinished work gets a progress reminder after three tool rounds`() {
        val items = listOf(TodoItem("Check changes", TodoItem.Status.IN_PROGRESS), TodoItem("Test"))
        assertTrue(!TodoPrompt.format(items, 2).contains("Progress check:"))
        val overdue = TodoPrompt.format(items, 3)
        assertTrue(overdue.contains("Progress check:"))
        assertTrue(overdue.contains("Only mark work completed when tool results support it"))
        assertTrue(!TodoPrompt.format(items, 0).contains("Progress check:"))
        assertEquals(TodoItem.Status.IN_PROGRESS, items.first().status)
    }

    @Test fun `empty and finished lists do not get stale progress reminders`() {
        assertEquals("", TodoPrompt.format(emptyList(), 10))
        assertTrue(!TodoPrompt.format(listOf(TodoItem("Verified", TodoItem.Status.COMPLETED)), 10).contains("Progress check:"))
    }
}
