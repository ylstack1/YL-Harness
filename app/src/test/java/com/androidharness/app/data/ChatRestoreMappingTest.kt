package com.androidharness.app.data

import com.androidharness.app.workspace.ChatWorkspaceGrant
import com.androidharness.app.workspace.ChatWorkspaceSelection
import org.junit.Assert.*
import org.junit.Test

class ChatRestoreMappingTest {
    private fun chat() = BackupSession("chat", "Restored chat", 1, 2, projectId = "old-a",
        workspaceSelectionJson = ChatWorkspaceSelection.encode(ChatWorkspaceSelection("old-a",
            listOf(ChatWorkspaceGrant("old-a", true), ChatWorkspaceGrant("old-b", false)))))

    @Test fun `mapping changes identities while preserving primary and read only grants`() {
        val restored = ChatRestoreMapping.remap(chat(), mapOf("old-a" to "new-a", "old-b" to "new-b"))
        val selection = ChatWorkspaceSelection.decode(restored.workspaceSelectionJson!!)
        assertEquals("new-a", restored.projectId)
        assertEquals("new-a", selection.primaryId)
        assertFalse(selection.grants.single { it.projectId == "new-b" }.canEdit)
        assertEquals(setOf("new-a", "new-b"), ChatRestoreMapping.referenced(restored))
    }
    @Test fun `two original roots cannot collapse into one grant`() {
        assertTrue(runCatching { ChatRestoreMapping.remap(chat(), mapOf("old-a" to "same", "old-b" to "same")) }.isFailure)
    }
    @Test fun `legacy chat is explicitly connected and old backup format remains readable`() {
        val legacy = BackupSession("legacy", "Old chat", 1, 2)
        assertEquals(setOf(ChatRestoreMapping.UNASSIGNED), ChatRestoreMapping.referenced(legacy))
        assertEquals("selected", ChatRestoreMapping.remap(legacy, mapOf(ChatRestoreMapping.UNASSIGNED to "selected")).projectId)
        assertTrue(ChatBackupCodec.decode("""{"format":"androidharness-chats","version":2,"sessions":[]}""").workspaces.isEmpty())
        val file = ChatBackupFile(workspaces = listOf(BackupWorkspace("old", "Named project")))
        assertEquals(file, ChatBackupCodec.decode(ChatBackupCodec.encode(file)))
    }
}
