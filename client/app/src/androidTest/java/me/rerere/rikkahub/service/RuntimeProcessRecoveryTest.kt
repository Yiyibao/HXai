package me.rerere.rikkahub.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolExecutionState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationQueue
import me.rerere.rikkahub.data.model.PendingInput
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

/** Run seed, force-stop from the host, then verify in a different Android process. */
@RunWith(AndroidJUnit4::class)
class RuntimeProcessRecoveryTest {
    @Test fun processRecovery() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val id = Uuid.parse(requireNotNull(args.getString("fixtureId")))
        val phase = requireNotNull(args.getString("phase"))
        InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
            putString("qa_process_pid", android.os.Process.myPid().toString())
        })
        val koin = GlobalContext.get()
        val repository = koin.get<ConversationRepository>()
        val settings = withTimeout(20_000) { koin.get<SettingsStore>().settingsFlow.first { !it.init } }
        if (phase == "seed") {
            assertNull(repository.getConversationById(id))
            val response = UIMessage(role = MessageRole.ASSISTANT, generationState = "RUNNING", parts = listOf(
                UIMessagePart.Text("QA_PROCESS_PARTIAL"),
                UIMessagePart.Tool("completed", "qa", "{}", executionState = ToolExecutionState.COMPLETED),
                UIMessagePart.Tool("uncertain", "qa", "{}", executionState = ToolExecutionState.RUNNING),
            ))
            val queue = ConversationQueue(listOf(PendingInput(Uuid.random(), listOf(UIMessagePart.Text("QA_PROCESS_QUEUED")))))
            repository.insertConversation(Conversation.ofId(id, settings.assistantId).copy(
                profileId = settings.activeProfileId, title = "[QA] runtime process recovery",
                messageNodes = listOf(UIMessage.user("QA_PROCESS_USER").toMessageNode(), response.toMessageNode()), queue = queue,
            ))
            println("QA_SEED_PROCESS=" + android.os.Process.myPid())
        } else {
            assertEquals("verify", phase)
            val stored = repository.getConversationById(id)!!
            try {
                val session = ConversationSession(id, Conversation.ofId(id), this, {})
                session.initialize(persistRecovery = repository::recoverConversation) { repository.getConversationById(id)!! }
                val recovered = repository.getConversationById(id)!!
                assertEquals(stored.currentMessages.map { it.id }, recovered.currentMessages.map { it.id })
                assertEquals(stored.currentMessages.last().toText(), recovered.currentMessages.last().toText())
                assertEquals("QA_PROCESS_PARTIAL", recovered.currentMessages.last().parts.filterIsInstance<UIMessagePart.Text>().single().text)
                assertEquals("INTERRUPTED", recovered.currentMessages.last().generationState)
                assertEquals(stored.queue.messages.single().id, recovered.queue.messages.single().id)
                assertTrue(recovered.queue.paused)
                assertTrue(recovered.currentMessages.last().getTools().first().isExecuted)
                assertEquals(ToolExecutionState.INTERRUPTED, recovered.currentMessages.last().getTools().last().executionState)
                assertFalse(recovered.currentMessages.last().getTools().last().canResumeExecution)
                assertNull(session.getJob())
                println("QA_VERIFY_PROCESS=" + android.os.Process.myPid())
            } finally { repository.deleteConversation(repository.getConversationById(id)!!) }
        }
    }
}
