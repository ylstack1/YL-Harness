package com.androidharness.app.agent

import org.junit.Assert.*
import org.junit.Test

class TodoStoreTest {
    @Test fun `new prompt in the same chat clears the previous run list`() {
        val store = TodoStore()
        store.beginRun("chat-a")
        store.setAll(listOf(TodoItem("Previous work", TodoItem.Status.COMPLETED)))
        store.beginRun("chat-a")
        assertEquals("chat-a", store.owner.value)
        assertTrue(store.todos.value.isEmpty())
    }

    @Test fun `switching chats clears tasks before publishing the new owner`() {
        val store = TodoStore()
        store.beginRun("chat-a")
        store.setAll(listOf(TodoItem("Private task", TodoItem.Status.IN_PROGRESS)))
        store.beginRun("chat-b")
        assertEquals("chat-b", store.owner.value)
        assertTrue(store.todos.value.isEmpty())
    }
}
