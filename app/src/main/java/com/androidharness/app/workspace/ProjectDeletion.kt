package com.androidharness.app.workspace

import com.androidharness.app.AppContainer
import com.androidharness.app.data.db.ProjectEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class ProjectDeletionPreview(
    val project: ProjectEntity,
    val chatIds: Set<String>,
    val automationIds: Set<String>,
    val blocker: String?,
)

class ProjectDeletion(private val c: AppContainer) {
    suspend fun preview(projectId: String): ProjectDeletionPreview {
        val project = requireNotNull(c.workspace.projects.first().firstOrNull { it.id == projectId }) {
            "This project is no longer available."
        }
        val related = c.workspace.projectChats(projectId)
        val owned = related.filter { it.projectId == projectId }.map { it.id }.toSet()
        val shared = related.filter { it.projectId != projectId }
        val automations = c.automation.repository.tasks.value.filter {
            it.projectId == projectId || it.targetSessionId in owned || it.activeRun?.sessionId in owned
        }.map { it.id }.toSet()
        val blocker = when {
            project.kind == WorkspaceManager.KIND_APP -> "The app workspace cannot be deleted."
            shared.isNotEmpty() -> "Used by chats in other projects: ${shared.joinToString { it.title }}. Remove this workspace from those chats before deleting it. Chats with saved changes must keep their workspace access."
            related.any { c.runManager.isRunning(it.id) || c.runManager.hasPendingFollowUp(it.id) } ->
                "Stop running tasks in this project before deleting it."
            else -> null
        }
        return ProjectDeletionPreview(project, owned, automations, blocker)
    }

    suspend fun delete(reviewed: ProjectDeletionPreview) = withContext(Dispatchers.IO) {
        c.automation.withIdleAutomations {
            c.runManager.withIdleProject(reviewed.project.id) {
                val current = preview(reviewed.project.id)
                check(current.blocker == null) { current.blocker.orEmpty() }
                check(current.chatIds == reviewed.chatIds && current.automationIds == reviewed.automationIds &&
                    current.project.copy(lastUsedAt = 0) == reviewed.project.copy(lastUsedAt = 0)) {
                    "This project changed. Close this dialog and review deletion again."
                }
                // Complete cleanup even if navigation disposes the confirmation dialog.
                withContext(NonCancellable) {
                    c.workspace.deleteProjectAndChats(current.project.id, current.chatIds)
                    current.automationIds.forEach(c.automation::delete)
                    c.automation.repository.detachDeletedChats(current.chatIds)
                    c.settings.removeProjectReferences(current.project.id, current.chatIds)
                }
            }
        }
    }
}
