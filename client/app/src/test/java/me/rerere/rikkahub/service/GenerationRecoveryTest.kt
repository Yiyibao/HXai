package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.uuid.Uuid

class GenerationRecoveryTest {
    @Test fun `skipped unknown result survives restart and cannot be executed again`() = runBlocking {
        val tool = me.rerere.ai.ui.UIMessagePart.Tool("unknown", "qa", "{}",
            executionState = me.rerere.ai.ui.ToolExecutionState.SKIPPED_UNKNOWN)
        val initial = Conversation.ofId(Uuid.random(), messages = listOf(
            UIMessage.assistant("").copy(parts = listOf(tool), generationState = "RUNNING").toMessageNode()))
        val decoded = JsonInstant.decodeFromString<Conversation>(JsonInstant.encodeToString(initial))
        val reopened = ConversationSession(initial.id, initial, this, {})
        reopened.initialize { decoded }
        val restored = reopened.state.value.currentMessages.single().getTools().single()
        assertEquals(me.rerere.ai.ui.ToolExecutionState.SKIPPED_UNKNOWN, restored.executionState)
        org.junit.Assert.assertTrue(restored.isSkippedUnknown)
        org.junit.Assert.assertTrue(restored.isExecuted)
        org.junit.Assert.assertFalse(restored.canResumeExecution)
        org.junit.Assert.assertFalse(restored.isExecutionUncertain)
    }
    @Test fun `regeneration checkpoint preserves interrupted alternative and owns failure state`() = runBlocking {
        val question = UIMessage.user("question")
        val partial = UIMessage.assistant("partial").copy(generationState = "INTERRUPTED")
        val initial = Conversation.ofId(Uuid.random(), messages = listOf(question.toMessageNode(), partial.toMessageNode()))
        val session = ConversationSession(initial.id, initial, this, {})
        var stored = initial
        val request = prepareGenerationMessages(listOf(question), Uuid.random())
        session.checkpointGeneration(request) { stored = it }
        assertEquals("RUNNING", stored.currentMessages.last().generationState)
        assertEquals("INTERRUPTED", stored.messageNodes.last().messages.first { it.id == partial.id }.generationState)
        session.recordGenerationResult(me.rerere.ai.util.HttpException("limit", 429)) { stored = it }
        assertEquals("FAILED", stored.currentMessages.last().generationState)
        assertEquals(429, stored.currentMessages.last().generationErrorCode)
        assertEquals(partial, stored.messageNodes.last().messages.first { it.id == partial.id })
        assertEquals(1, stored.currentMessages.count { it.id == question.id })
    }

    @Test fun `restart recovery is committed before state publication`() = runBlocking {
        val initial = Conversation.ofId(Uuid.random(), messages = listOf(UIMessage.assistant("partial").copy(generationState = "RUNNING").toMessageNode()))
        val session = ConversationSession(initial.id, Conversation.ofId(initial.id), this, {})
        var stored = initial
        session.initialize(persistRecovery = {
            assertEquals(0, session.state.value.currentMessages.size)
            stored = it
        }) { stored }
        assertEquals("INTERRUPTED", stored.currentMessages.single().generationState)
        assertEquals(stored, session.state.value)
    }

    @Test fun `http error code survives restart and next generation clears it`() = runBlocking {
        val initial = Conversation.ofId(Uuid.random(), messages = listOf(UIMessage.user("question").toMessageNode()))
        val session = ConversationSession(initial.id, initial, this, {})
        var stored = initial
        session.recordGenerationResult(me.rerere.ai.util.HttpException("rate limited", 429)) { stored = it }
        val decoded = JsonInstant.decodeFromString<Conversation>(JsonInstant.encodeToString(stored))
        assertEquals(429, decoded.currentMessages.single().generationErrorCode)
        assertEquals("FAILED", decoded.currentMessages.single().generationState)
        session.recordGenerationState("RUNNING") { stored = it }
        assertEquals(null, stored.currentMessages.single().generationErrorCode)
    }

    @Test fun `terminal causes distinguish stop network timeout and provider failure`() {
        assertEquals("COMPLETED", generationFailureState(null))
        assertEquals("CANCELLED", generationFailureState(CancellationException()))
        assertEquals("INTERRUPTED", generationFailureState(IOException()))
        assertEquals("INTERRUPTED", generationFailureState(SocketTimeoutException()))
        assertEquals("FAILED", generationFailureState(IllegalStateException("429")))
    }

    @Test fun `process restart recovers running checkpoint without duplicating user input`() = runBlocking {
        val initial = Conversation.ofId(Uuid.random(), messages = listOf(UIMessage.user("question").toMessageNode()))
        val session = ConversationSession(initial.id, initial, this, {})
        var stored = initial
        session.recordGenerationState("RUNNING") { stored = it }
        session.checkpointGeneration(listOf(initial.currentMessages.single(), UIMessage.assistant("partial"))) { stored = it }
        stored = JsonInstant.decodeFromString<Conversation>(JsonInstant.encodeToString(stored))
        val reopened = ConversationSession(initial.id, initial, this, {})
        reopened.initialize { stored }
        assertEquals("INTERRUPTED", reopened.state.value.currentMessages.last().generationState)
        assertEquals("partial", reopened.state.value.currentMessages.last().toText())
        assertEquals(2, reopened.state.value.currentMessages.size)
        assertEquals(null, reopened.state.value.currentMessages.first().generationState)
    }

    @Test fun `network terminal survives serialization and preserves partial response`() = runBlocking {
        val initial = Conversation.ofId(Uuid.random(), messages = listOf(UIMessage.assistant("partial").toMessageNode()))
        val session = ConversationSession(initial.id, initial, this, {})
        var stored = initial
        session.recordGenerationState("INTERRUPTED") { stored = it }
        val decoded = JsonInstant.decodeFromString<Conversation>(JsonInstant.encodeToString(stored))
        assertEquals("INTERRUPTED", decoded.currentMessages.single().generationState)
        assertEquals("partial", decoded.currentMessages.single().toText())
    }
}
