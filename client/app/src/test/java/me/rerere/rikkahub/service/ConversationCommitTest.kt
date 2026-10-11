package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationCommitTest {
    @Test fun `late background results cannot replace checkpoint or branch`() = runBlocking {
        for (title in listOf(true, false)) {
            val original = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(UIMessage.user("old").toMessageNode()))
            val session = ConversationSession(original.id, original, this, {})
            session.initialize { original }
            val token = session.backgroundToken()
            val changed = original.copy(messageNodes = original.messageNodes + UIMessage.user("new branch").toMessageNode())
            session.commitConversation(changed) {}
            var writes = 0
            assertFalse(session.applyBackgroundMetadata(original, token, title = if (title) "late" else null,
                suggestions = if (title) null else listOf("late")) { writes++; true })
            assertEquals(0, writes)
            assertEquals(changed.messageNodes, session.state.value.messageNodes)
        }
    }

    @Test fun `background commit never republishes a stale whole conversation`() = runBlocking {
        val original = Conversation.ofId(Uuid.random())
        val session = ConversationSession(original.id, original, this, {})
        session.initialize { original }
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val token = session.backgroundToken()
        val background = launch(start = CoroutineStart.UNDISPATCHED) {
            session.applyBackgroundMetadata(original, token, title = "generated") {
                entered.complete(Unit); release.await(); true
            }
        }
        entered.await()
        val changed = original.copy(messageNodes = listOf(UIMessage.user("edited while save suspended").toMessageNode()))
        session.updateConversation(changed)
        release.complete(Unit); background.join()
        assertEquals(changed.messageNodes, session.state.value.messageNodes)
    }

    @Test fun `manual title and missing database row reject background publication`() = runBlocking {
        val original = Conversation.ofId(Uuid.random())
        val session = ConversationSession(original.id, original, this, {})
        session.initialize { original }
        val token = session.backgroundToken()
        assertFalse(session.applyBackgroundMetadata(original, token, suggestions = listOf("late")) { false })
        session.commitConversation(original.copy(title = "manual")) {}
        assertFalse(session.applyBackgroundMetadata(original, token, title = "generated") { error("must not save") })
        assertEquals("manual", session.state.value.title)
    }

    @Test fun `delete and undo never accept predelete responses or queued writes`() = runBlocking {
        val original = Conversation.ofId(Uuid.random())
        val session = ConversationSession(original.id, original, this, {})
        session.initialize { original }
        val token = session.backgroundToken()
        session.delete {}
        assertFalse(session.applyBackgroundMetadata(original, token, suggestions = listOf("late")) { error("must not save") })
        assertTrue(runCatching { session.updateQueue({ error("must not persist") }) { it.pause() } }.isFailure)
        assertTrue(runCatching { session.commitConversation(original) { error("must not persist") } }.isFailure)
        session.restore(original) {}
        assertFalse(session.applyBackgroundMetadata(original, token, suggestions = listOf("old")) { error("must not save") })
        assertTrue(session.applyBackgroundMetadata(original, session.backgroundToken(), title = "fresh") { true })
    }

    @Test fun `failed approval commit remains pending and retry survives restart`() = runBlocking {
        for (decision in listOf(ToolApprovalState.Approved, ToolApprovalState.Denied("no"), ToolApprovalState.Answered("answer"))) {
            val tool = UIMessagePart.Tool("call", "ask_user", "{}", approvalState = ToolApprovalState.Pending)
            val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))
            val original = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(message.toMessageNode()))
            val session = ConversationSession(original.id, original, this, {})
            session.initialize { original }
            val updated = original.copy(messageNodes = listOf(message.copy(parts = listOf(tool.copy(approvalState = decision))).toMessageNode()))
            var stored = original
            assertTrue(runCatching { session.commitConversation(updated) { error("synthetic disk failure") } }.isFailure)
            assertTrue(session.state.value.currentMessages.single().getTools().single().isPending)
            assertEquals(original, stored)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val retry = launch(start = CoroutineStart.UNDISPATCHED) {
                session.commitConversation(updated) { entered.complete(Unit); release.await(); stored = it }
            }
            entered.await()
            assertTrue(session.state.value.currentMessages.single().getTools().single().isPending)
            release.complete(Unit); retry.join()
            val reopened = ConversationSession(original.id, original, this, {})
            reopened.initialize { stored }
            assertEquals(decision, reopened.state.value.currentMessages.single().getTools().single().approvalState)
        }
    }
}
