package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class CheckpointBranchRuntimeTest {
    @Test fun historicalSelectedBranchWinsOverNewerFenceAndSameTextCannotImpersonate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scheduler = AgentScheduler.shared(context)
        AgentTaskStore(context).use { store ->
            val source = UIMessage.user("same synthetic text")
            val base = Conversation.ofId(Uuid.random()).copy(profileId = "synthetic-owner", newConversation = true,
                messageNodes = listOf(MessageNode(messages = listOf(source, UIMessage.user("same synthetic text")))))
            fun accepted() = store.accept(base.profileId, base.id.toString(), TaskCheckpointIdentity.newRequest(base.messageNodes),
                "goal", "synthetic-ref", "p", "m", "HIGH", "v1").also { task ->
                store.finish(store.claim(task.owner, task.id), "COMPLETED_UNVERIFIED")
            }.let { store.get(it.owner, it.id) }
            val older = accepted(); val newer = accepted()
            assertTrue(newer.fence > older.fence)
            val node = MessageNode(messages = listOf(
                UIMessage.assistant("older result").copy(generationState = "COMPLETED", generationTaskId = older.id),
                UIMessage.assistant("newer result").copy(generationState = "COMPLETED", generationTaskId = newer.id)))
            val oldBranch = base.copy(messageNodes = base.messageNodes + node)
            val before = store.snapshot(older.owner, older.id)
            assertEquals(older.id, scheduler.current(oldBranch)?.id)
            TaskCheckpointIdentity.requireCheckpoint(older, oldBranch)
            assertEquals(before, store.snapshot(older.owner, older.id))
            val newBranch = oldBranch.copy(messageNodes = base.messageNodes + node.copy(selectIndex = 1))
            assertEquals(newer.id, scheduler.current(newBranch)?.id)
            assertTrue(runCatching { TaskCheckpointIdentity.requireCheckpoint(older, newBranch) }.isFailure)
            val wrongSource = oldBranch.copy(messageNodes = listOf(base.messageNodes.single().copy(selectIndex = 1), node))
            assertNull(scheduler.current(wrongSource))
            assertTrue(runCatching { TaskCheckpointIdentity.requireCheckpoint(older, wrongSource) }.isFailure)
            assertNull(scheduler.current(oldBranch.copy(profileId = "foreign-owner")))
            assertNull(scheduler.current(oldBranch.copy(messageNodes = base.messageNodes + node.copy(messages = listOf(
                node.messages.first().copy(generationTaskId = "missing-task"))))))
        }
    }
}
