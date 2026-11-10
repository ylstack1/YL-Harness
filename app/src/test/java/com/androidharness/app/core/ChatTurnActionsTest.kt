package com.androidharness.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatTurnActionsTest {
    private fun user(turnId: String, text: String = "question") =
        ChatMessage(role = Role.USER, text = text, turnId = turnId, id = "u-$turnId")

    private fun assistant(turnId: String) =
        ChatMessage(role = Role.ASSISTANT, text = "answer", turnId = turnId, id = "a-$turnId")

    @Test fun findsTheUserMessageForTheTurn() {
        val messages = listOf(user("t1"), assistant("t1"), user("t2"), assistant("t2"))
        assertEquals("u-t2", ChatTurnActions.userMessageFor(messages, "t2")?.id)
    }

    @Test fun ignoresAssistantMessagesAndOtherTurns() {
        val messages = listOf(user("t1"), assistant("t2"))
        assertEquals("u-t1", ChatTurnActions.userMessageFor(messages, "t1")?.id)
        assertNull(ChatTurnActions.userMessageFor(messages, "missing"))
        assertNull(ChatTurnActions.userMessageFor(messages, null))
    }

    @Test fun nullTurnIdHasNoQuestion() {
        val messages = listOf(ChatMessage(role = Role.USER, text = "no turn", id = "u"))
        assertNull(ChatTurnActions.userMessageFor(messages, null))
    }
    @Test fun versionGroupsKeepRetriesTogetherButSeparateDifferentConversationPrefixes() {
        assertEquals(ChatTurnActions.answerVersionKey("prefix", "Question"), ChatTurnActions.answerVersionKey("prefix", "Question"))
        assertNotEquals(ChatTurnActions.answerVersionKey("prefix", "Question"), ChatTurnActions.answerVersionKey("other-prefix", "Question"))
        assertNotEquals(ChatTurnActions.answerVersionKey(null, "Question"), ChatTurnActions.answerVersionKey(null, "Edited question"))
    }
}
