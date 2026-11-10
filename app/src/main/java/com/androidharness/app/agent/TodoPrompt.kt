package com.androidharness.app.agent

/** Renders the live todo list for the system prompt. */
object TodoPrompt {
    fun format(items: List<TodoItem>, roundsWithoutUpdate: Int = 0): String {
        if (items.isEmpty()) return ""
        return buildString {
            append("Current task list (keep this updated via todo_write):\n")
            for (item in items) {
                append("- [").append(item.status.name.lowercase()).append("] ")
                    .append(item.content).append('\n')
            }
            if (roundsWithoutUpdate >= 3 && items.any { it.status != TodoItem.Status.COMPLETED }) {
                append("Progress check: the task list has not been updated for $roundsWithoutUpdate tool rounds. ")
                append("Before more work, use todo_write to reflect completed milestones and the current in_progress task. ")
                append("Only mark work completed when tool results support it; keep unfinished or failed work open.\n")
            }
        }
    }
}
