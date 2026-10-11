package me.rerere.rikkahub.service

import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ConversationBoundaryFixTest {
    private suspend fun fixture(block: suspend (ChatService, ConversationRepository, AppDatabase, Conversation) -> Unit) {
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsStore>()
        val original = withTimeout(20000) { settings.settingsFlow.first { !it.init } }
        val owner = settings.createLearnerProfile("QA 保存边界")
        val service = koin.get<ChatService>(); val repo = koin.get<ConversationRepository>(); val db = koin.get<AppDatabase>()
        val id = Uuid.random()
        try {
            withTimeout(20000) { settings.settingsFlow.first { it.learnerProfiles.any { profile -> profile.id == owner } } }
            settings.setLearnerMemoryEnabled(owner, false)
            service.switchLearnerProfile(owner)
            service.addConversationReference(id)
            val conversation = Conversation.ofId(id, original.assistantId).copy(profileId = owner, title = "QA boundary", messageNodes = listOf(UIMessage.user("synthetic original").toMessageNode()))
            repo.insertConversation(conversation)
            service.initializeConversation(id)
            block(service, repo, db, conversation)
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS qa_boundary_write_failure")
            repo.getConversationById(id, owner)?.let { service.deleteConversation(it) }
            service.removeConversationReference(id)
            restoreMemoryProfileFixture(original, settings)
        }
    }

    @Test fun staleGeneratedMetadataDoesNotReplaceNewMessagesOrManualTitle() = runBlocking {
        fixture { service, repo, _, old ->
            val newer = old.copy(title = "manual", messageNodes = old.messageNodes + UIMessage.user("new persisted branch").toMessageNode())
            service.saveConversation(old.id, newer)
            assertFalse(repo.updateGeneratedMetadata(old, title = "late", suggestions = listOf("late")))
            assertFalse(repo.updateGeneratedMetadata(old, suggestions = listOf("stale source")))
            assertFalse(repo.updateGeneratedMetadata(newer.copy(title = old.title), title = "overwrite manual"))
            assertEquals(newer.messageNodes, repo.getConversationById(old.id)!!.messageNodes)
            assertEquals("manual", repo.getConversationById(old.id)!!.title)
            assertEquals(newer.messageNodes, service.getConversationFlow(old.id).value.messageNodes)
            assertTrue(repo.updateGeneratedMetadata(newer, title = "generated indexed title"))
            assertEquals(newer.messageNodes, repo.getConversationById(old.id)!!.messageNodes)
            assertEquals("generated indexed title", repo.searchMessages("persisted").single { it.conversationId == old.id.toString() }.title)
        }
    }

    @Test fun deleteAndUndoCannotResurrectOldBackgroundOrQueuedState() = runBlocking {
        fixture { service, repo, _, old ->
            service.deleteConversation(old)
            assertFalse(repo.updateGeneratedMetadata(old, suggestions = listOf("late")))
            assertNull(repo.getConversationById(old.id))
            val memories = GlobalContext.get().get<me.rerere.rikkahub.feature.memory.LearningMemoryStore>()
            assertTrue(memories.read(old.profileId).sources.none { it.conversationId == old.id.toString() && it.valid })
            assertTrue(runCatching { service.saveConversation(old.id, old) }.isFailure)
            assertNull(repo.getConversationById(old.id))
            service.restoreConversation(old)
            assertEquals(old.messageNodes, repo.getConversationById(old.id)!!.messageNodes)
            assertEquals(old.messageNodes, service.getConversationFlow(old.id).value.messageNodes)
        }
    }

    @Test fun databaseFailureRetainsAttachmentAndCommittedDeletionPreservesOtherReferences() = runBlocking {
        fixture { service, repo, db, old ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val file = File(context.filesDir, "upload/qa-boundary-${old.id}.txt").apply { parentFile!!.mkdirs(); writeText("synthetic retained bytes") }
            val part = UIMessagePart.Document(file.toUri().toString(), file.name, "text/plain")
            val attached = old.copy(messageNodes = listOf(UIMessage.user("synthetic attachment").copy(parts = listOf(part)).toMessageNode()))
            service.saveConversation(old.id, attached)
            val removed = attached.copy(messageNodes = emptyList())
            val sqlite = db.openHelper.writableDatabase
            sqlite.execSQL("CREATE TRIGGER qa_boundary_write_failure BEFORE UPDATE ON ConversationEntity WHEN OLD.id='${old.id}' BEGIN SELECT RAISE(ABORT, 'synthetic disk failure'); END")
            assertTrue(runCatching { service.saveConversation(old.id, removed) }.isFailure)
            assertEquals(attached.messageNodes, service.getConversationFlow(old.id).value.messageNodes)
            assertEquals(attached.messageNodes, repo.getConversationById(old.id)!!.messageNodes)
            assertEquals("synthetic retained bytes", file.readText())
            sqlite.execSQL("DROP TRIGGER qa_boundary_write_failure")
            val other = attached.copy(id = Uuid.random())
            repo.insertConversation(other)
            try {
                service.saveConversation(old.id, removed)
                assertTrue("Other conversation reference retained", file.exists())
                repo.deleteConversation(other)
                // Attach again, then remove the final committed reference.
                if (!file.exists()) file.writeText("synthetic retained bytes")
                service.saveConversation(old.id, attached)
                service.saveConversation(old.id, removed)
                assertFalse("Unreferenced file cleaned after commit", file.exists())
            } finally {
                repo.getConversationById(other.id)?.let { repo.deleteConversation(it) }
                file.delete()
            }
        }
    }

    @Test fun failedApprovalCanBeRetriedWithoutReplayingOrReplacingDecision() = runBlocking {
        fixture { service, repo, db, old ->
            val target = UIMessagePart.Tool("qa-target", "ask_user", "{}", approvalState = ToolApprovalState.Pending)
            val blocker = UIMessagePart.Tool("qa-blocker", "ask_user", "{}", approvalState = ToolApprovalState.Pending)
            val pending = old.copy(messageNodes = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(target, blocker)).toMessageNode()))
            service.saveConversation(old.id, pending)
            val sqlite = db.openHelper.writableDatabase
            sqlite.execSQL("CREATE TRIGGER qa_boundary_write_failure BEFORE INSERT ON message_node WHEN NEW.conversation_id='${old.id}' AND NEW.messages LIKE '%\"answered\"%' BEGIN SELECT RAISE(ABORT, 'synthetic approval write failure'); END")
            service.handleToolApproval(old.id, "qa-target", approved = true, answer = "first")
            withTimeout(20000) {
                service.getGenerationJobStateFlow(old.id).first { it == null }
            }
            assertTrue(service.getConversationFlow(old.id).value.currentMessages.single().getTools().first().isPending)
            assertTrue(repo.getConversationById(old.id)!!.currentMessages.single().getTools().first().isPending)
            sqlite.execSQL("DROP TRIGGER qa_boundary_write_failure")
            service.handleToolApproval(old.id, "qa-target", approved = true, answer = "first")
            withTimeout(20000) { service.getGenerationJobStateFlow(old.id).first { it == null } }
            assertEquals(ToolApprovalState.Answered("first"), repo.getConversationById(old.id)!!.currentMessages.single().getTools().first().approvalState)
            service.handleToolApproval(old.id, "qa-target", approved = false, reason = "late conflicting")
            assertEquals(ToolApprovalState.Answered("first"), service.getConversationFlow(old.id).value.currentMessages.single().getTools().first().approvalState)
            assertTrue(service.getConversationFlow(old.id).value.currentMessages.single().getTools().last().isPending)
        }
    }
}
