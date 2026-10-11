package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationSessionTest {
    @Test fun `approval retry commits current safety pause and concurrent queued input atomically`() = runBlocking {
        val initial = Conversation.ofId(Uuid.random())
        val session = ConversationSession(initial.id, initial, this, {})
        session.initialize { initial }
        session.updateQueue({}) { it.enqueue(listOf(UIMessagePart.Text("queued before failure"))) }
        session.messageQueue.pause()
        val persisted = mutableListOf<Conversation>()
        val accepted = requireNotNull(session.commitToolApproval({ it.copy(title = "accepted") }, { persisted += it }))
        assertTrue(accepted.queue.paused)
        assertEquals(session.messageQueue.persisted(), persisted.single().queue)
        assertEquals(persisted.single(), session.state.value)
        val before = session.state.value
        val failure = runCatching { session.commitToolApproval({ it.copy(title = "must not publish") }, { error("synthetic save failure") }) }
        assertTrue(failure.isFailure)
        assertEquals(before, session.state.value)
        assertEquals(null, session.commitToolApproval({ null }, { error("A stale decision must not persist") }))
        assertEquals(before, session.state.value)
    }
    @Test fun `first direct conversation save restores pending input paused`() = runBlocking {
        val initial = Conversation.ofId(Uuid.random())
        val queued = me.rerere.rikkahub.data.model.PendingInput(Uuid.random(), listOf(UIMessagePart.Text("pending")))
        val session = ConversationSession(initial.id, initial, this, {})
        session.updateConversation(initial.copy(queue = me.rerere.rikkahub.data.model.ConversationQueue(listOf(queued))))
        assertEquals(queued.id, session.messageQueue.state.value.messages.single().id)
        assertTrue(session.messageQueue.state.value.paused)
        assertEquals(session.messageQueue.persisted(), session.state.value.queue)
    }

    @Test fun `queue commits before publication and restores paused with stable IDs`() = runBlocking {
        val initial = Conversation.ofId(Uuid.random())
        val session = ConversationSession(initial.id, initial, this, {})
        var stored = initial
        session.updateQueue({ stored = it }) { it.enqueue(listOf(UIMessagePart.Text("queued"))) }
        val queued = session.messageQueue.state.value.messages.single()
        val reopened = ConversationSession(initial.id, initial, this, {})
        reopened.initialize(persistRecovery = { stored = it }) { stored }
        assertTrue(reopened.messageQueue.state.value.paused)
        assertEquals(queued.id, reopened.messageQueue.state.value.messages.single().id)
        assertEquals(stored.queue, reopened.state.value.queue)
        reopened.updateQueue({ stored = it }) { it.resume() }
        reopened.commitQueuedInput(reopened.messageQueue.state.value.messages.single(), queued.parts) { stored = it }
        assertTrue(stored.queue.messages.isEmpty())
        assertEquals(queued.id, stored.currentMessages.single().id)
    }

    @Test fun `failed queue commit preserves item and does not publish user input`() = runBlocking {
        val initial = Conversation.ofId(Uuid.random())
        val session = ConversationSession(initial.id, initial, this, {})
        session.updateQueue({}) { it.enqueue(listOf(UIMessagePart.Text("queued"))) }
        val queued = session.messageQueue.state.value.messages.single()
        assertTrue(runCatching { session.commitQueuedInput(queued, queued.parts) { error("disk full") } }.isFailure)
        assertEquals(queued.id, session.messageQueue.state.value.messages.single().id)
        assertTrue(session.state.value.currentMessages.isEmpty())
        assertTrue(runCatching { session.updateQueue({ error("disk full") }) { it.remove(queued.id) } }.isFailure)
        assertEquals(queued.id, session.messageQueue.state.value.messages.single().id)
    }

    @Test fun `running tool is recovered unknown while empty completed result is retained`() = runBlocking {
        val initial = Conversation.ofId(Uuid.random())
        val session = ConversationSession(initial.id, initial, this, {})
        val reply = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Tool("done", "tool", "{}", executionState = me.rerere.ai.ui.ToolExecutionState.COMPLETED),
            UIMessagePart.Tool("running", "tool", "{}", executionState = me.rerere.ai.ui.ToolExecutionState.RUNNING),
        ))
        var stored = initial
        session.checkpointGeneration(listOf(reply)) { stored = it }
        val reopened = ConversationSession(initial.id, initial, this, {})
        reopened.initialize(persistRecovery = { stored = it }) { stored }
        val tools = stored.currentMessages.single().getTools()
        assertTrue(tools.first().isExecuted)
        assertTrue(tools.last().isExecutionUncertain)
        assertFalse(tools.last().canResumeExecution)
        assertEquals("INTERRUPTED", stored.currentMessages.single().generationState)
    }

    @Test
    fun `checkpoint commits before publication and survives a new session`() = runBlocking {
        val initial = Conversation.ofId(Uuid.random()).copy(profileId = "learner-a")
        val session = ConversationSession(initial.id, initial, this, {})
        val reply = partialReply()
        var stored = initial
        val entered = CompletableDeferred<Unit>()
        val commit = CompletableDeferred<Unit>()
        val writer = launch {
            session.checkpointGeneration(listOf(reply)) {
                entered.complete(Unit)
                commit.await()
                stored = it
            }
        }
        entered.await()
        assertTrue(session.state.value.messageNodes.isEmpty())
        commit.complete(Unit)
        writer.join()
        val reopened = ConversationSession(initial.id, initial, this, {})
        reopened.initialize { stored }
        assertEquals(session.state.value.withGenerationState("INTERRUPTED", onlyRunning = true), reopened.state.value)
        assertEquals("learner-a", reopened.state.value.profileId)
        assertEquals(reply.id, reopened.state.value.currentMessages.single().id)
    }

    @Test
    fun `failed checkpoint does not publish uncommitted text`() = runBlocking {
        val initial = Conversation.ofId(Uuid.random())
        val session = ConversationSession(initial.id, initial, this, {})
        val result = runCatching {
            session.checkpointGeneration(listOf(partialReply())) { error("disk full") }
        }
        assertTrue(result.isFailure)
        assertEquals(initial, session.state.value)
    }

    @Test
    fun `metadata edit waits for checkpoint and preserves its messages`() = runBlocking {
        val initial = Conversation.ofId(Uuid.random())
        val session = ConversationSession(initial.id, initial, this, {})
        val entered = CompletableDeferred<Unit>()
        val commit = CompletableDeferred<Unit>()
        var stored = initial
        val checkpoint = launch {
            session.checkpointGeneration(listOf(partialReply())) {
                entered.complete(Unit)
                commit.await()
                stored = it
            }
        }
        entered.await()
        val edit = launch {
            session.updateMetadata({ it.copy(isPinned = true) }) { stored = it }
        }
        commit.complete(Unit)
        checkpoint.join()
        edit.join()
        assertTrue(stored.isPinned)
        assertEquals(session.state.value.messageNodes, stored.messageNodes)
        assertEquals(1, stored.currentMessages.size)
    }

    @Test
    fun `metadata edits survive reopening and final generation save`() = runBlocking {
        val id = Uuid.random()
        val oldAssistant = Uuid.random()
        val targetAssistant = Uuid.random()
        var persisted = Conversation.ofId(id, assistantId = oldAssistant).copy(folderId = Uuid.random())
        val session = ConversationSession(id, Conversation.ofId(id), this, {})
        session.initialize { persisted }
        val streaming = session.state.value.updateCurrentMessages(listOf(partialReply()))
        session.updateConversation(streaming)

        session.updateMetadata(
            update = { it.copy(isPinned = !it.isPinned) },
            persist = { persisted = persisted.copy(isPinned = it.isPinned) },
        )
        session.updateMetadata(
            update = { it.copy(assistantId = targetAssistant, folderId = null) },
            persist = { persisted = persisted.copy(assistantId = it.assistantId, folderId = null) },
        )

        session.initialize { error("Returning to an active conversation must not reload its messages") }
        assertTrue(session.state.value.isPinned)
        assertEquals(targetAssistant, session.state.value.assistantId)
        assertNull(session.state.value.folderId)
        assertEquals(streaming.messageNodes, session.state.value.messageNodes)
        assertTrue(persisted.messageNodes.isEmpty())

        session.finishGeneration { persisted = it }
        assertTrue(persisted.isPinned)
        assertEquals(targetAssistant, persisted.assistantId)
        assertNull(persisted.folderId)
        assertEquals(streaming.currentMessages.single().id, persisted.currentMessages.single().id)
    }

    @Test
    fun `metadata edit loads history when the conversation has no initialized session`() = runBlocking {
        val id = Uuid.random()
        var persisted = Conversation.ofId(id, assistantId = Uuid.random())
            .updateCurrentMessages(listOf(UIMessage.user("existing history")))
        val session = ConversationSession(id, Conversation.ofId(id), this, {})
        session.initialize { persisted }
        session.updateMetadata(
            update = { it.copy(isPinned = !it.isPinned) },
            persist = { persisted = persisted.copy(isPinned = it.isPinned) },
        )
        session.initialize { error("Must keep the initialized session") }
        assertEquals(persisted, session.state.value)
        assertEquals("existing history", session.state.value.currentMessages.single().toText())
    }

    @Test
    fun `concurrent pin toggles persist in order without replacing streamed messages`() = runBlocking {
        val id = Uuid.random()
        var persisted = Conversation.ofId(id)
        val session = ConversationSession(id, persisted, this, {})
        session.initialize { persisted }
        val allowFirstSave = CompletableDeferred<Unit>()
        val writtenPins = mutableListOf<Boolean>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            session.updateMetadata(
                update = { it.copy(isPinned = !it.isPinned) },
                persist = {
                    allowFirstSave.await()
                    persisted = persisted.copy(isPinned = it.isPinned)
                    writtenPins.add(it.isPinned)
                },
            )
        }
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            session.updateMetadata(
                update = { it.copy(isPinned = !it.isPinned) },
                persist = {
                    persisted = persisted.copy(isPinned = it.isPinned)
                    writtenPins.add(it.isPinned)
                },
            )
        }
        assertTrue(session.state.value.isPinned)
        assertFalse(second.isCompleted)
        val streaming = session.state.value.updateCurrentMessages(listOf(partialReply()))
        session.updateConversation(streaming)
        allowFirstSave.complete(Unit)
        first.join()
        second.join()
        assertEquals(listOf(true, false), writtenPins)
        assertFalse(persisted.isPinned)
        assertFalse(session.state.value.isPinned)
        assertEquals(streaming.messageNodes, session.state.value.messageNodes)
    }

    @Test
    fun `returning to a generating conversation retains reasoning and partial reply`() = runBlocking {
        val id = Uuid.random()
        val persisted = Conversation.ofId(id)
        val session = ConversationSession(id, persisted, this, {})
        session.initialize { persisted }
        val streaming = persisted.updateCurrentMessages(listOf(partialReply()))
        session.updateConversation(streaming)

        val otherId = Uuid.random()
        val other = ConversationSession(otherId, Conversation.ofId(otherId), this, {})
        other.initialize { Conversation.ofId(otherId, assistantId = Uuid.random()) }

        // Android 重新创建 ViewModel、Web 重连及再次发送都会调用初始化。
        repeat(3) { session.initialize { error("Must not reload an active session") } }
        assertEquals(streaming, session.state.value)
        assertTrue(other.state.value.currentMessages.isEmpty())
    }

    @Test
    fun `concurrent initialization loads once without blocking another conversation`() = runBlocking {
        val id = Uuid.random()
        val initial = Conversation.ofId(id)
        val session = ConversationSession(id, initial, this, {})
        val loaded = CompletableDeferred<Conversation>()
        var loads = 0
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            session.initialize { loads++; loaded.await() }
        }
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            session.initialize { loads++; initial }
        }
        val otherId = Uuid.random()
        val other = ConversationSession(otherId, Conversation.ofId(otherId), this, {})
        other.initialize { Conversation.ofId(otherId) }
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)

        val persisted = initial.updateCurrentMessages(listOf(UIMessage.user("history")))
        loaded.complete(persisted)
        first.join()
        second.join()
        assertEquals(1, loads)
        assertEquals(persisted, session.state.value)
    }

    @Test
    fun `late database load cannot overwrite a newer conversation update`() = runBlocking {
        val id = Uuid.random()
        val initial = Conversation.ofId(id)
        val session = ConversationSession(id, initial, this, {})
        val loaded = CompletableDeferred<Conversation>()
        val loading = launch(start = CoroutineStart.UNDISPATCHED) {
            session.initialize { loaded.await() }
        }
        val updated = initial.updateCurrentMessages(listOf(partialReply()))
        session.updateConversation(updated)
        loaded.complete(initial)
        loading.join()
        assertEquals(updated, session.state.value)
    }

    @Test
    fun `cancelled initial load can be retried`() = runBlocking {
        val id = Uuid.random()
        val initial = Conversation.ofId(id)
        val session = ConversationSession(id, initial, this, {})
        val loading = launch(start = CoroutineStart.UNDISPATCHED) {
            session.initialize { awaitCancellation() }
        }
        loading.cancel()
        loading.join()
        val persisted = initial.updateCurrentMessages(listOf(UIMessage.user("history")))
        session.initialize { persisted }
        assertEquals(persisted, session.state.value)
    }

    @Test
    fun `failed stream persists partial content for a recreated session`() = runBlocking {
        val id = Uuid.random()
        var persisted = Conversation.ofId(id)
        val session = ConversationSession(id, persisted, this, {})
        session.initialize { persisted }
        val failure = IllegalStateException("connection lost")
        val result = runCatching {
            flow {
                emit(partialReply())
                throw failure
            }.onCompletion {
                session.finishGeneration { persisted = it }
            }.collect { message ->
                session.updateConversation(session.state.value.updateCurrentMessages(listOf(message)))
            }
        }
        assertSame(failure, result.exceptionOrNull())
        val reopened = ConversationSession(id, Conversation.ofId(id), this, {})
        reopened.initialize { persisted }
        val parts = reopened.state.value.currentMessages.single().parts
        assertEquals("thinking so far", (parts[0] as UIMessagePart.Reasoning).reasoning)
        assertNotNull((parts[0] as UIMessagePart.Reasoning).finishedAt)
        assertEquals(UIMessagePart.Text("partial reply"), parts[1])
        val tool = parts[2] as UIMessagePart.Tool
        assertEquals("edit_file", tool.toolName)
        assertEquals(listOf(UIMessagePart.Text("file updated")), tool.output)
    }

    @Test
    fun `cancelled generation waits for partial content to be persisted`() = runBlocking {
        val id = Uuid.random()
        var persisted = Conversation.ofId(id)
        val session = ConversationSession(id, persisted, this, {})
        session.initialize { persisted }
        val saving = CompletableDeferred<Unit>()
        val allowSave = CompletableDeferred<Unit>()
        val generation = launch(start = CoroutineStart.UNDISPATCHED) {
            flow {
                emit(partialReply())
                awaitCancellation()
            }.onCompletion {
                session.finishGeneration {
                    saving.complete(Unit)
                    allowSave.await()
                    persisted = it
                }
            }.collect { message ->
                session.updateConversation(session.state.value.updateCurrentMessages(listOf(message)))
            }
        }
        session.setJob(generation)
        generation.cancel()
        saving.await()
        assertFalse(generation.isCompleted)
        assertTrue(session.isInUse)
        allowSave.complete(Unit)
        generation.join()
        assertNull(session.getJob())
        assertEquals(session.state.value, persisted)
        assertEquals("partial reply", (persisted.currentMessages.single().parts[1] as UIMessagePart.Text).text)
        session.cleanup()
    }

    private fun partialReply() = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Reasoning("thinking so far", finishedAt = null),
            UIMessagePart.Text("partial reply"),
            UIMessagePart.Tool(
                toolCallId = "edit-1",
                toolName = "edit_file",
                input = "{}",
                output = listOf(UIMessagePart.Text("file updated")),
            ),
        ),
    )

    @Test
    fun `stop does not resume queued approval when predecessor cancels immediately`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id), scope, {})
        try {
            var saved = false
            val first = scope.launch(start = CoroutineStart.LAZY) { awaitCancellation() }
            session.setJob(first)
            val second = scope.launch(start = CoroutineStart.LAZY) {
                afterPreviousGeneration(first) { saved = true }
            }
            session.setJob(second, cancelPrevious = false)
            session.cancelJobs().forEach { it.join() }
            assertFalse(saved)
            assertTrue(first.isCancelled)
            assertTrue(second.isCancelled)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `queued approvals preserve a decision while its save is suspended`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val id = Uuid.random()
        val completions = mutableListOf<Throwable?>()
        val session = ConversationSession(id, Conversation.ofId(id), scope, {},
            { _, cause -> completions.add(cause) })
        try {
            val save = CompletableDeferred<Unit>()
            val decisions = mutableListOf<String>()
            val first = scope.launch(start = CoroutineStart.LAZY) {
                afterPreviousGeneration(null) {
                    save.await()
                    decisions.add("A")
                }
            }
            session.setJob(first, cancelPrevious = false)
            val second = scope.launch(start = CoroutineStart.LAZY) {
                afterPreviousGeneration(first) { decisions.add("B") }
            }
            session.setJob(second, cancelPrevious = false)
            assertTrue(first.isActive)
            assertTrue(decisions.isEmpty())
            save.complete(Unit)
            second.join()
            assertEquals(listOf("A", "B"), decisions)
            assertEquals(listOf<Throwable?>(null), completions)
            assertNull(session.getJob())
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `stopping queued approvals waits for all predecessors to stop`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id), scope, {})
        try {
            val cleanup = CompletableDeferred<Unit>()
            val first = scope.launch(start = CoroutineStart.LAZY) {
                afterPreviousGeneration(null) {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) { cleanup.await() }
                    }
                }
            }
            session.setJob(first, cancelPrevious = false)
            val second = scope.launch(start = CoroutineStart.LAZY) {
                afterPreviousGeneration(first) { error("Must not execute") }
            }
            session.setJob(second, cancelPrevious = false)
            val third = scope.launch(start = CoroutineStart.LAZY) {
                afterPreviousGeneration(second) { error("Must not execute") }
            }
            session.setJob(third, cancelPrevious = false)
            val stopped = session.cancelJobs()
            assertTrue(first.isCancelled)
            assertTrue(second.isCancelled)
            assertFalse(third.isCompleted)
            cleanup.complete(Unit)
            stopped.forEach { it.join() }
            assertTrue(third.isCompleted)
            assertTrue(first.isCompleted)
            assertTrue(second.isCompleted)
            assertNull(session.getJob())
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `replaced job finishing late cannot clear successor or advance queue`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val completions = mutableListOf<Throwable?>()
        val id = Uuid.random()
        val session = ConversationSession(
            id,
            Conversation.ofId(id),
            scope,
            {},
            { _, cause -> completions.add(cause) })
        try {
            val releaseOld = CompletableDeferred<Unit>()
            val releaseNew = CompletableDeferred<Unit>()
            val old = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { releaseOld.await() }
                }
            }
            session.setJob(old)
            val successor = scope.launch(start = CoroutineStart.LAZY) { releaseNew.await() }
            session.setJob(successor)

            releaseOld.complete(Unit)
            old.join()
            assertSame(successor, session.getJob())
            assertTrue(completions.isEmpty())

            releaseNew.complete(Unit)
            successor.join()
            assertNull(session.getJob())
            assertEquals(listOf<Throwable?>(null), completions)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `instant completion does not leave a stale generation job`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id), scope, {})
        try {
            session.setJob(scope.launch(start = CoroutineStart.LAZY) {})
            assertNull(session.getJob())
            assertFalse(session.isGenerating)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `pending messages retain session even when queue is paused and page has no references`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id), scope, {})
        try {
            assertFalse(session.isInUse)
            session.messageQueue.enqueue(listOf(UIMessagePart.Text("next")))
            session.messageQueue.pause()
            assertTrue(session.isInUse)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }
}
