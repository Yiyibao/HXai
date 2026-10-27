package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.rerere.ai.core.ProviderRequestGuard
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.memory.*
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

/** Real auxiliary task/physical provider/Room readback, using synthetic local data only. */
@RunWith(AndroidJUnit4::class)
class MemoryExtractionSourceTest {
    @Test fun healthyExtractionPersists() = scenario("healthy")
    @Test fun explicitlyRestoredConversationCanExtract() = scenario("healthy-restored")
    @Test fun freshMessageWithSameTextRemainsAllowed() = scenario("fresh")
    @Test fun sourceTombstoneWithoutEpochChangePreventsRequest() = scenario("pre-deleted")
    @Test fun legacyProtectionPreventsRequest() = scenario("pre-legacy")
    @Test fun factProtectionPreventsRequest() = scenario("pre-protected")
    @Test fun cancelledJobCannotDispatch() = scenario("pre-cancelled")
    @Test fun deletionAtPhysicalAdmissionPreventsRequestAndUsage() = scenario("physical-deleted")
    @Test fun disableAtPhysicalAdmissionPreventsRequestAndUsage() = scenario("physical-disabled")
    @Test fun queuedDeletionPreventsRequest() = scenario("queued-deleted")
    @Test fun queuedProtectionPreventsRequest() = scenario("queued-protected")
    @Test fun queuedBranchChangePreventsRequest() = scenario("queued-branch")
    @Test fun lateDeletionDoesNotPublishSuccess() = scenario("late-deleted")
    @Test fun lateProtectionCancelsStaleJob() = scenario("late-protected")
    @Test fun changedStoredMessageWithoutReconcileRejectsLateCandidate() = scenario("late-raw-edit")
    @Test fun deletedConversationWithoutReconcileRejectsLateCandidate() = scenario("late-raw-delete")
    @Test fun sourceFromAnotherOwnerIsRejected() = scenario("pre-other-owner")
    @Test fun nonTargetMetadataChangePreservesExtraction() = scenario("late-metadata")
    @Test fun changedAttachmentRejectsLateCandidate() = scenario("late-attachment")
    @Test fun changedConversationIncarnationRejectsLateCandidate() = scenario("late-incarnation")
    @Test fun oldAttemptCannotCancelOrPublishForReplacementJob() = scenario("late-replaced")

    private fun scenario(mode: String): Unit = runBlocking {
        val k = GlobalContext.get()
        val settings = k.get<SettingsStore>()
        val original = withTimeout(20_000) { settings.settingsFlow.first { !it.init } }
        val owner = original.activeProfileId
        val store = k.get<LearningMemoryStore>()
        val previous = store.read(owner)
        val repo = k.get<ConversationRepository>()
        val db = k.get<AppDatabase>()
        val runner = k.get<MemoryExtractionRunner>()
        runner.chatStarted()
        val id = Uuid.random()
        val message = UIMessage.user("I prefer synthetic worked examples")
        var job: ExtractionJob? = null
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var blocker: Deferred<Unit>? = null
        var action: Deferred<Result<Unit>>? = null
        suspend fun mutate(kind: String) {
            when (kind) {
                "deleted" -> store.change(owner) { it.copy(suppressedSources = it.suppressedSources +
                    store.fingerprint(owner, "message:${message.id}")) to Unit }
                "protected" -> store.change(owner) { it.copy(recoveryBlocked = true) to Unit }
                "disabled" -> settings.setLearnerMemoryEnabled(owner, false)
                "legacy" -> store.change(owner) { it.copy(schemaVersion = 2, recoveryBlocked = true,
                    legacySuppressedSources = setOf("SYNTHETIC_UNMAPPED_V0")) to Unit }
                "cancelled" -> store.change(owner) { it.copy(jobs = it.jobs.map { j -> j.copy(state = "CANCELLED") }) to Unit }
                "replaced" -> store.change(owner) { it.copy(jobs = it.jobs.map { j -> j.copy(attempts = j.attempts + 1) }) to Unit }
                "other-owner" -> db.openHelper.writableDatabase.execSQL(
                    "UPDATE ConversationEntity SET profile_id = ? WHERE id = ?", arrayOf("SYNTHETIC_FOREIGN_OWNER", id.toString()))
                "raw-delete" -> db.openHelper.writableDatabase.execSQL("DELETE FROM ConversationEntity WHERE id = ?", arrayOf(id.toString()))
                "incarnation" -> db.openHelper.writableDatabase.execSQL("UPDATE ConversationEntity SET lifecycle_revision = lifecycle_revision + 1 WHERE id = ?", arrayOf(id.toString()))
                "branch" -> {
                    val node = db.messageNodeDao().getNodesOfConversation(id.toString()).first()
                    db.openHelper.writableDatabase.execSQL("UPDATE message_node SET messages = ?, select_index = 1 WHERE id = ?",
                        arrayOf(JsonInstant.encodeToString(listOf(message, UIMessage.user("NEW_SELECTED_BRANCH"))), node.id))
                }
                "metadata" -> db.conversationDao().updateTitle(id.toString(), owner, "new metadata")
                "raw-edit", "attachment" -> {
                    val node = db.messageNodeDao().getNodesOfConversation(id.toString()).first()
                    val updated = if (kind == "raw-edit") message.copy(parts = UIMessage.user("NEW_EDITED_SOURCE").parts)
                        else message.copy(parts = message.parts + UIMessagePart.Document("file:///synthetic/new.pdf", "new.pdf", "application/pdf"))
                    db.openHelper.writableDatabase.execSQL("UPDATE message_node SET messages = ? WHERE id = ?",
                        arrayOf(JsonInstant.encodeToString(listOf(updated)), node.id))
                }
            }
        }
        val server = LocalModelServer {
            if (mode.startsWith("late-")) runBlocking { mutate(mode.removePrefix("late-")) }
            val sourceId = requireNotNull(job).sourceIds.single()
            LocalModelServer.completion(buildJsonObject {
                put("schema_version", 1)
                put("candidates", buildJsonArray { add(buildJsonObject {
                    put("type", "PREFERENCE"); put("topic_key", "synthetic"); put("fact_key", "examples")
                    put("content", "Prefers synthetic worked examples"); put("evidence_type", "SELF_REPORTED")
                    put("source_ids", buildJsonArray { add(sourceId) })
                }) })
            }.toString())
        }
        val model = Model(modelId = "qa-memory-source")
        val provider = ProviderSetting.OpenAI(baseUrl = server.baseUrl, apiKey = "synthetic", models = listOf(model))
        val assistant = Assistant(chatModelId = model.id, streamOutput = false)
        try {
            settings.update(original.copy(providers = listOf(provider), assistants = original.assistants + assistant,
                assistantId = assistant.id, chatModelId = model.id, fastModelId = model.id, translateModeId = model.id,
                learnerProfiles = original.learnerProfiles.map { it.copy(memoryEnabled = it.id == owner) },
                networkSetting = original.networkSetting.copy(proxyUrl = "", enableAutoRetry = false)))
            settings.settingsFlow.first { it.fastModelId == model.id }
            settings.setLearnerMemoryEnabled(owner, true)
            store.change(owner) { MemoryState(fingerprintVersion = 1) to Unit }
            val conversation = Conversation.ofId(id, assistant.id).copy(profileId = owner, messageNodes = listOf(
                message.toMessageNode(), UIMessage.assistant("synthetic response").copy(generationState = "COMPLETED").toMessageNode()))
            repo.insertConversation(conversation)
            val acceptedConversation = if (mode == "healthy-restored") {
                repo.deleteConversation(conversation)
                repo.restoreConversation(conversation)
            } else conversation
            store.reconcile(acceptedConversation)
            if (mode == "fresh") store.change(owner) { it.copy(suppressedSources = setOf(
                store.fingerprint(owner, "message:${Uuid.random()}"))) to Unit }
            assertEquals(1, store.enqueue(acceptedConversation, true))
            job = requireNotNull(store.claim(owner, true, System.currentTimeMillis()))
            if (mode.startsWith("pre-")) mutate(mode.removePrefix("pre-"))
            if (mode.startsWith("queued-")) {
                val requests = k.get<ModelRequestRunner>()
                val accepted = requests.accept(settings.settingsFlow.value, "translation", "synthetic slot holder")
                blocker = async(Dispatchers.IO) { requests.run(accepted) { _, _, _ -> entered.complete(Unit); release.await() } }
                withTimeout(10_000) { entered.await() }
            }
            action = async(Dispatchers.IO) {
                runCatching {
                    withContext(ProviderRequestGuard {
                        if (mode.startsWith("physical-")) mutate(mode.removePrefix("physical-"))
                    }) { runner.process(owner, requireNotNull(job)) }
                }
            }
            if (mode.startsWith("queued-")) {
                withTimeout(10_000) { while (k.get<ModelRequestRunner>().scheduler.readTaskCatalog(owner, "auxiliary", null, 100).tasks.none {
                    it.conversationId == id.toString() && it.role == "memory" && it.state == "QUEUED"
                }) { if (action.isCompleted) error("Memory request never queued: ${action.await()}"); delay(20) } }
                mutate(mode.removePrefix("queued-")); release.complete(Unit); blocker!!.await()
            }
            val result = withTimeout(20_000) { action.await() }
            val publish = mode in setOf("healthy", "healthy-restored", "fresh", "late-metadata")
            if (publish) result.getOrThrow()
            val sent = publish || mode.startsWith("late-")
            assertEquals("Physical request count: $mode", if (sent) 1 else 0, server.bodies.size)
            val actual = store.read(owner)
            assertEquals("Candidate publication: $mode", if (publish) 1 else 0, actual.items.size)
            val finalJob = actual.jobs.single { it.id == job.id }
            assertEquals("Job completion must reflect verified sources: $mode", if (publish) "SUCCEEDED" else if (mode == "late-replaced") "RUNNING" else "CANCELLED", finalJob.state)
            assertEquals(publish, result.isSuccess)
            if (publish) {
                assertEquals("Prefers synthetic worked examples", actual.items.single().content)
                assertEquals(job.sourceIds, actual.items.single().sourceIds)
                assertTrue(server.bodies.single().contains(message.toText()))
                assertEquals(if (mode == "late-metadata") "new metadata" else "", repo.getConversationById(id, owner)!!.title)
            }
            if (mode == "late-raw-delete") assertNull(repo.getConversationById(id, owner))
            if (mode.endsWith("deleted")) assertTrue(store.fingerprint(owner, "message:${message.id}") in actual.suppressedSources)
            if (mode.startsWith("physical-")) AgentTaskStore(k.get()).use { tasks ->
                tasks.readableDatabase.rawQuery("SELECT COUNT(*) FROM usage WHERE task_id IN (SELECT id FROM tasks WHERE conversation_id = ?)",
                    arrayOf(id.toString())).use { rows -> rows.moveToFirst(); assertEquals("Rejected before accounting", 0, rows.getInt(0)) }
            }
            // Reopen/read through Room rather than trusting a model response or a callback.
            assertEquals(actual, store.read(owner))
        } finally {
            release.complete(Unit); blocker?.cancelAndJoin(); action?.cancelAndJoin()
            db.openHelper.writableDatabase.execSQL("UPDATE ConversationEntity SET profile_id = ? WHERE id = ?", arrayOf(owner, id.toString()))
            repo.getConversationById(id, owner)?.let { repo.deleteConversation(it) }
            store.change(owner) { previous to Unit }
            settings.update(original); settings.settingsFlow.first { it.assistantId == original.assistantId }
            settings.setLearnerMemoryEnabled(owner, original.learnerProfiles.single { it.id == owner }.memoryEnabled)
            runner.chatFinished(); server.close()
        }
    }
}
