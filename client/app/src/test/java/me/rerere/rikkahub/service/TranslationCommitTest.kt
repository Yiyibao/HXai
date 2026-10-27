package me.rerere.rikkahub.service

import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class TranslationCommitTest {
    @Test fun failedOrRejectedPersistenceCannotPublishOrEraseExistingTranslation() = runBlocking {
        val message = UIMessage.user("original").copy(translation = "existing")
        val before = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(message.toMessageNode()))
        val session = ConversationSession(before.id, before, this, {})
        session.initialize { before }
        val token = session.backgroundToken()
        assertFalse(session.applyTranslation(before, token, message.id, "unverified", { true }) { false })
        assertEquals(before.messageNodes, session.state.value.messageNodes)
        assertTrue(runCatching {
            session.applyTranslation(before, token, message.id, "unverified", { true }) { error("synthetic storage failure") }
        }.isFailure)
        assertEquals(before.messageNodes, session.state.value.messageNodes)
    }

    @Test fun translationPreservesConcurrentQueueMetadataAndUnselectedAlternatives() = runBlocking {
        val message = UIMessage.user("original")
        val other = UIMessage.user("unselected").copy(translation = "keep alternative")
        val before = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(message.toMessageNode().copy(messages = listOf(message, other))))
        val session = ConversationSession(before.id, before, this, {})
        session.initialize { before }
        val token = session.backgroundToken()
        session.updateMetadata({ it.copy(title = "new title") }, {})
        session.updateQueue({}) { it.enqueue(listOf(UIMessagePart.Text("queued"))) }
        var persisted = false
        assertTrue(session.applyTranslation(before, token, message.id, "verified", { true }) {
            assertEquals("new title", it.title)
            assertEquals(1, it.queue.messages.size)
            assertNull(session.state.value.currentMessages.single().translation)
            persisted = true
            true
        })
        assertTrue(persisted)
        assertEquals("verified", session.state.value.currentMessages.single().translation)
        assertEquals(other, session.state.value.messageNodes.single().messages[1])
        assertEquals("new title", session.state.value.title)
        assertEquals(1, session.state.value.queue.messages.size)
    }

    @Test fun changedEpochCannotCommitEvenAfterReturningToSameOwner() = runBlocking {
        val message = UIMessage.user("original")
        val before = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(message.toMessageNode()))
        val session = ConversationSession(before.id, before, this, {})
        session.initialize { before }
        val token = session.backgroundToken()
        session.invalidateBackgroundResults()
        assertFalse(session.applyTranslation(before, token, message.id, "stale", { true }) { error("Old epoch must not persist") })
        assertEquals(before.messageNodes, session.state.value.messageNodes)
    }
}
