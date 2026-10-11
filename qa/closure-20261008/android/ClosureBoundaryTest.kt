package me.rerere.rikkahub.service

import androidx.core.net.toUri
import androidx.datastore.preferences.core.edit
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.files.*
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.studychat.LearnerProfile
import me.rerere.rikkahub.utils.JsonInstant
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File
import kotlin.uuid.Uuid

class ClosureBoundaryTest {
    private val koin get() = GlobalContext.get()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun registry(service: ChatService) = ChatService::class.java.getDeclaredField("sessionManager").apply { isAccessible = true }.get(service) as ConversationSessionManager
    private suspend fun register(settings: SettingsStore, original: Settings, owner: String) {
        @Suppress("UNCHECKED_CAST")
        val data = SettingsStore::class.java.getDeclaredField("dataStore").apply { isAccessible = true }.get(settings) as androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
        data.edit { it[SettingsStore.LEARNER_PROFILES] = JsonInstant.encodeToString(original.learnerProfiles + LearnerProfile(owner, "QA closure", memoryEnabled = false)) }
        withTimeout(20000) { settings.settingsFlow.first { it.learnerProfiles.any { p -> p.id == owner } } }
    }
    @Test fun deletionCreationRestoreAndIndependentIncarnation() = runBlocking {
        val args = InstrumentationRegistry.getArguments(); val fixture = requireNotNull(args.getString("fixtureId")); val phase = requireNotNull(args.getString("phase"))
        require(java.util.UUID.fromString(fixture).toString() == fixture && phase in listOf("seed", "verify"))
        val evidence = QaEvidenceRun("ClosureBoundaryTest.deletionCreationRestoreAndIndependentIncarnation")
        val manifestFile = File(context.cacheDir, "closure-lifecycle-$fixture.json")
        val settings = koin.get<SettingsStore>(); val original = settings.settingsFlow.first { !it.init }; val repo = koin.get<ConversationRepository>(); val service = koin.get<ChatService>()
        val record = if (phase == "seed") JSONObject().put("owner", Uuid.random().toString()) else JSONObject(manifestFile.readText())
        val owner = record.getString("owner"); var status = "FAILED"
        try {
            register(settings, original, owner); service.switchLearnerProfile(owner)
            if (phase == "seed") {
                val created = Conversation.ofId(Uuid.random(), original.assistantId, newConversation = true).copy(profileId = owner, title = "QA_$fixture", messageNodes = listOf(UIMessage.user("original").toMessageNode()))
                record.put("stale", JsonInstant.encodeToString(created)); manifestFile.writeText(record.toString())
                service.saveConversation(created.id, created)
                val updated = repo.getConversationById(created.id, owner)!!.copy(title = "QA_${fixture}_updated")
                service.saveConversation(created.id, updated); assertEquals(updated.title, repo.getConversationById(created.id, owner)!!.title)
                service.deleteConversation(updated)
                val old = registry(service).get(created.id)!!; registry(service).removeIfIdle(old)
                assertNull(registry(service).get(created.id))
                assertTrue(runCatching { service.saveConversation(created.id, created) }.isFailure)
                assertTrue(runCatching { repo.commitRuntimeState(created) }.isFailure)
                assertFalse(repo.updateGeneratedMetadata(created, suggestions = listOf("late")))
                assertNull(repo.getConversationById(created.id, owner))
                service.restoreConversation(updated)
                val restored = repo.getConversationById(created.id, owner)!!
                assertEquals(1L, restored.lifecycleRevision); assertEquals(updated.messageNodes, restored.messageNodes)
                assertTrue(runCatching { service.saveConversation(created.id, created) }.isFailure)
                assertTrue(runCatching { repo.checkpointMessages(created) }.isFailure)
                assertFalse(repo.updateGeneratedMetadata(created, title = "stale title"))
                assertEquals(restored, repo.getConversationById(created.id, owner))
                service.saveConversation(restored.id, restored.copy(title = "QA_${fixture}_restored"))
                service.deleteConversation(repo.getConversationById(created.id, owner)!!)
                record.put("deletedRevision", restored.lifecycleRevision); manifestFile.writeText(record.toString())
            } else {
                val stale = JsonInstant.decodeFromString<Conversation>(record.getString("stale"))
                assertNull(repo.getConversationById(stale.id, owner))
                assertTrue(runCatching { service.initializeConversation(stale.id); service.saveConversation(stale.id, stale) }.isFailure)
                assertTrue(runCatching { repo.insertConversation(stale) }.isFailure)
                assertTrue(runCatching { repo.commitRuntimeState(stale) }.isFailure)
                assertNull(repo.getConversationById(stale.id, owner))
                service.restoreConversation(stale)
                val restored = repo.getConversationById(stale.id, owner)!!
                assertEquals(record.getLong("deletedRevision") + 1, restored.lifecycleRevision)
                assertTrue(runCatching { repo.updateConversation(stale) }.isFailure)
                service.deleteConversation(restored)
                manifestFile.delete()
            }
            evidence.file("assertions.json").writeText(record.put("phase", phase).put("pid", android.os.Process.myPid()).put("assertionsPassed", true).toString()); status = "PASS"
        } finally { restoreMemoryProfileFixture(original, settings); evidence.finish(status) }
    }

    @Test fun approvalEveryTransactionStepRollsBackBeforePublication() = runBlocking {
        val evidence = QaEvidenceRun("ClosureBoundaryTest.approvalEveryTransactionStepRollsBackBeforePublication")
        val settings = koin.get<SettingsStore>(); val original = settings.settingsFlow.first { !it.init }; val owner = Uuid.random().toString()
        val repo = koin.get<ConversationRepository>(); val service = koin.get<ChatService>(); val db = koin.get<AppDatabase>(); val ids = mutableListOf<Uuid>(); val results = StringBuilder(); var status = "FAILED"
        suspend fun sql(value: String) = db.withTransaction { db.openHelper.writableDatabase.execSQL(value) }
        try {
            // These two names belong exclusively to this registered QA fixture.
            // A failed native commit in an earlier candidate can have blocked its finally.
            sql("DROP TRIGGER IF EXISTS qa_closure_fault"); sql("DROP TABLE IF EXISTS qa_closure_commit")
            register(settings, original, owner); service.switchLearnerProfile(owner)
            for (step in listOf("row", "delete", "insert", "memory", "commit")) for (decision in listOf("approve", "deny", "answer")) {
                val tool = UIMessagePart.Tool("target", "ask_user", "{}", approvalState = ToolApprovalState.Pending)
                val c = Conversation.ofId(Uuid.random(), original.assistantId).copy(profileId = owner, title = "QA_${evidence.runId}_$step", messageNodes = listOf(UIMessage.user("synthetic source").toMessageNode(), UIMessage.assistant("").copy(parts = listOf(tool)).toMessageNode()))
                ids += c.id; repo.insertConversation(c); service.addConversationReference(c.id); service.initializeConversation(c.id)
                val s = registry(service).get(c.id)!!; s.updateQueue(repo::commitRuntimeState) { it.enqueue(listOf(UIMessagePart.Text("queued"))); it.pause() }
                if (step == "memory") koin.get<me.rerere.rikkahub.feature.memory.LearningMemoryStore>().change(owner) { state ->
                    state.copy(sources = state.sources.map { if(it.conversationId == c.id.toString()) it.copy(valid = false) else it }) to Unit
                }
                val before = repo.getConversationById(c.id, owner)!!; val memory = db.learningMemoryDao().get(owner)
                when(step) {
                    "row" -> sql("CREATE TRIGGER qa_closure_fault BEFORE UPDATE ON ConversationEntity WHEN OLD.id='${c.id}' BEGIN SELECT RAISE(ABORT, 'QA row'); END")
                    "delete" -> sql("CREATE TRIGGER qa_closure_fault BEFORE DELETE ON message_node WHEN OLD.conversation_id='${c.id}' BEGIN SELECT RAISE(ABORT, 'QA delete'); END")
                    "insert" -> sql("CREATE TRIGGER qa_closure_fault BEFORE INSERT ON message_node WHEN NEW.conversation_id='${c.id}' BEGIN SELECT RAISE(ABORT, 'QA insert'); END")
                    "memory" -> sql("CREATE TRIGGER qa_closure_fault BEFORE INSERT ON learning_memory_state WHEN NEW.profile_id='$owner' BEGIN SELECT RAISE(ABORT, 'QA memory'); END")
                    "commit" -> {
                        sql("CREATE TABLE qa_closure_commit (id TEXT REFERENCES ConversationEntity(id) DEFERRABLE INITIALLY DEFERRED)")
                        sql("CREATE TRIGGER qa_closure_fault AFTER UPDATE ON ConversationEntity WHEN OLD.id='${c.id}' BEGIN INSERT INTO qa_closure_commit VALUES ('qa-missing-parent'); END")
                    }
                }
                try {
                    val state = when(decision) { "approve" -> ToolApprovalState.Approved; "deny" -> ToolApprovalState.Denied("QA"); else -> ToolApprovalState.Answered("QA") }
                    val failure = runCatching { s.commitToolApproval({ current -> current.copy(messageNodes = current.messageNodes.map { node -> node.copy(messages = node.messages.map { message -> if(message.getTools().isEmpty()) message else message.copy(parts = listOf(tool.copy(approvalState = state))) }) }) }, repo::commitToolApproval) }
                    evidence.file("exceptions.txt").appendText("$step/$decision\n" + (failure.exceptionOrNull()?.stackTraceToString() ?: "NO FAILURE") + "\n")
                    assertTrue("$step/$decision must fail", failure.isFailure)
                    assertEquals(before, repo.getConversationById(c.id, owner)); assertEquals(before.messageNodes, s.state.value.messageNodes); assertEquals(before.queue, s.messageQueue.persisted()); assertEquals(memory, db.learningMemoryDao().get(owner))
                    results.append("$step/$decision failure=true DB/memory/queue unchanged\n")
                    evidence.file("assertions.txt").writeText(results.toString())
                } finally { sql("DROP TRIGGER IF EXISTS qa_closure_fault"); if(step == "commit") sql("DROP TABLE qa_closure_commit") }
            }
            status = "PASS"
        } finally {
            sql("DROP TRIGGER IF EXISTS qa_closure_fault"); sql("DROP TABLE IF EXISTS qa_closure_commit")
            ids.forEach { id -> repo.getConversationById(id, owner)?.let { service.deleteConversation(it) }; service.removeConversationReference(id) }
            restoreMemoryProfileFixture(original, settings); evidence.file("assertions.txt").writeText(results.toString()); evidence.finish(status)
        }
    }

    @Test fun crossConversationReferencesAndDeleteIoRetry() = runBlocking {
        val evidence = QaEvidenceRun("ClosureBoundaryTest.crossConversationReferencesAndDeleteIoRetry")
        val settings = koin.get<SettingsStore>(); val original = settings.settingsFlow.first { !it.init }; val owner = Uuid.random().toString(); val repo = koin.get<ConversationRepository>(); val service = koin.get<ChatService>(); val files = koin.get<FilesManager>()
        val ids = mutableListOf<Uuid>(); val dir = File(context.filesDir, "upload/qa-${evidence.runId}").apply { mkdirs() }; val source = File(dir, "shared.txt").apply { writeText("QA shared immutable bytes") }; val sha = me.rerere.rikkahub.feature.material.MaterialFiles.digest(source); var status = "FAILED"
        val fileRepo = koin.get<me.rerere.rikkahub.data.repository.FilesRepository>()
        val relative = "upload/qa-${evidence.runId}/shared.txt"
        fileRepo.insert(me.rerere.rikkahub.data.db.entity.ManagedFileEntity(folder="upload",relativePath=relative,displayName="QA shared",mimeType="text/plain",sizeBytes=source.length(),createdAt=System.currentTimeMillis(),updatedAt=System.currentTimeMillis()))
        try {
            register(settings, original, owner); service.switchLearnerProfile(owner)
            val part = UIMessagePart.Document(source.toUri().toString(), "shared.txt", "text/plain")
            repeat(2) { val c = Conversation.ofId(Uuid.random(), original.assistantId).copy(profileId = owner, title = "QA_${evidence.runId}_$it", messageNodes = listOf(UIMessage.user("").copy(parts = listOf(part)).toMessageNode())); ids += c.id; repo.insertConversation(c); service.addConversationReference(c.id); service.initializeConversation(c.id) }
            service.deleteConversation(repo.getConversationById(ids.first(), owner)!!); assertEquals(sha, me.rerere.rikkahub.feature.material.MaterialFiles.digest(source))
            android.system.Os.chmod(dir.path, 0b101000000)
            service.deleteConversation(repo.getConversationById(ids.last(), owner)!!); assertTrue(source.exists()); assertEquals(sha, me.rerere.rikkahub.feature.material.MaterialFiles.digest(source))
            assertNotNull("IO failure retains managed retry record", fileRepo.getByPath(relative))
            android.system.Os.chmod(dir.path, 0b111000000)
            files.deleteChatFiles(listOf(source.toUri())); assertFalse(source.exists())
            withTimeout(20000) { while(fileRepo.getByPath(relative) != null) delay(10) }
            evidence.file("assertions.txt").writeText("crossConversation=true\nlastReferenceIoFailurePreserved=true\nretryRemoved=true\nsourceSha256=$sha\n"); status = "PASS"
        } finally {
            android.system.Os.chmod(dir.path, 0b111000000); source.delete(); dir.delete()
            fileRepo.deleteByPath(relative)
            ids.forEach { id -> repo.getConversationById(id, owner)?.let { service.deleteConversation(it) }; service.removeConversationReference(id) }; restoreMemoryProfileFixture(original, settings); evidence.finish(status)
        }
    }
}
