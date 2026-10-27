package me.rerere.rikkahub.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.agent.LocalModelServer
import me.rerere.rikkahub.feature.memory.LearningMemoryStore
import me.rerere.rikkahub.feature.studychat.ChatContextBudget
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

/** Synthetic payloads and a loopback model; no real accounts or private messages. */
@RunWith(AndroidJUnit4::class)
class HistorySourceSuppressionTest {
    private val k get() = GlobalContext.get()
    private val store get() = k.get<LearningMemoryStore>()
    private fun clear(owner: String) = k.get<AppDatabase>().openHelper.writableDatabase.execSQL(
        "DELETE FROM learning_memory_state WHERE profile_id = ?", arrayOf(owner))

    @Test fun portableMessageTombstoneSurvivesMissingSourceRow(): Unit = runBlocking {
        val owner = "qa-history-${Uuid.random()}"
        val message = UIMessage.user("synthetic orphan source")
        try {
            val marker = store.fingerprint(owner, "message:${message.id}")
            store.change(owner) { it.copy(suppressedSources = setOf(marker)) to Unit }
            assertTrue(store.isSuppressed(owner, message.id.toString(), message.toText()))
            assertFalse(store.isSuppressed("$owner-other", message.id.toString(), message.toText()))
        } finally { clear(owner) }
    }

    @Test fun portableCompositeTombstoneSurvivesMissingSourceRow(): Unit = runBlocking {
        val owner = "qa-history-${Uuid.random()}"
        val message = UIMessage.user("synthetic composite source")
        try {
            val hash = store.fingerprint(owner, "content:${message.toText()}")
            val marker = store.fingerprint(owner, "${message.id}:$hash")
            store.change(owner) { it.copy(suppressedSources = setOf(marker)) to Unit }
            assertTrue(store.isSuppressed(owner, message.id.toString(), message.toText()))
            assertFalse(store.isSuppressed(owner, Uuid.random().toString(), message.toText()))
            assertFalse(store.isSuppressed(owner, message.id.toString(), "different content"))
        } finally { clear(owner) }
    }

    @Test fun deletionBeforeCommitNeverInvokesThePublisher(): Unit = runBlocking {
        val owner = "qa-history-${Uuid.random()}"
        val old = UIMessage.user("synthetic pre-commit source")
        val messages = listOf(old, UIMessage.assistant("old result"), UIMessage.user("fresh input"))
        try {
            val expected = store.projectHistory(owner, messages)
            store.change(owner) { it.copy(suppressedSources = setOf(store.fingerprint(owner, "message:${old.id}"))) to Unit }
            val payload = k.get<AppDatabase>().learningMemoryDao().get(owner)!!.payload
            var published = false
            assertTrue(runCatching { store.commitWithHistory(owner, messages, expected) { published = true } }.isFailure)
            assertFalse(published)
            assertEquals(payload, k.get<AppDatabase>().learningMemoryDao().get(owner)!!.payload)
            assertEquals(3, messages.size)
        } finally { clear(owner) }
    }

    @Test fun failedLocalPublisherRollsBackWithoutChangingOriginalMemory(): Unit = runBlocking {
        val owner = "qa-history-${Uuid.random()}"
        val messages = listOf(UIMessage.user("healthy source"))
        try {
            store.addManual(owner, "original", me.rerere.rikkahub.feature.memory.MemoryType.PREFERENCE, "healthy original preference")
            val payload = k.get<AppDatabase>().learningMemoryDao().get(owner)!!.payload
            assertTrue(runCatching {
                store.commitWithHistory(owner, messages, store.projectHistory(owner, messages)) {
                    store.addManual(owner, "failed-publish", me.rerere.rikkahub.feature.memory.MemoryType.PREFERENCE, "must roll back")
                    error("synthetic local publisher failure")
                }
            }.isFailure)
            assertEquals(payload, k.get<AppDatabase>().learningMemoryDao().get(owner)!!.payload)
            assertEquals(messages, store.projectHistory(owner, messages))
        } finally { clear(owner) }
    }

    @Test fun mainRequestOmitsOrphanSourceAndItsAnswer() = mainRequest(protected = true, orphan = true)
    @Test fun mainRequestOmitsUntraceableSummaryUnderSourceDeletion() = mainRequest(protected = true, orphan = false)
    @Test fun healthySummaryAndSameTextNewMessageRemainUsable() = mainRequest(protected = false, orphan = false)
    @Test fun automaticSummaryOnlySeesFilteredHistory() = mainRequest(protected = true, orphan = true, largeHistory = true)
    @Test fun deletedCurrentRequestCannotDispatch() = mainRequest(protected = true, orphan = true, deletedCurrent = true)
    @Test fun deletionDuringAutomaticSummaryPreventsTheMainRequest() = mainRequest(protected = false, orphan = false, largeHistory = true, deleteDuringSummary = true)

    private fun mainRequest(protected: Boolean, orphan: Boolean, largeHistory: Boolean = false,
                            deletedCurrent: Boolean = false, deleteDuringSummary: Boolean = false): Unit = runBlocking {
        val settings = withTimeout(20_000) { k.get<SettingsStore>().settingsFlow.first { !it.init } }
        val owner = "qa-history-${Uuid.random()}"
        val old = UIMessage.user("OLD_SOURCE_SENTINEL_${Uuid.random()}")
        val oldAnswer = UIMessage.assistant("OLD_ANSWER_SENTINEL")
        val summary = ChatContextBudget.summaryMessage("OLD_SUMMARY_SENTINEL")
        val summaryAnswer = UIMessage.assistant("SUMMARY_ANSWER_SENTINEL")
        val fresh = UIMessage.user("fresh question; same quoted text: ${old.toText()}")
        val older = if (largeHistory) (1..4).flatMap { listOf(UIMessage.user("healthy older $it " + "abc ".repeat(3000)), UIMessage.assistant("healthy older answer $it")) } else emptyList()
        val messages = if (deletedCurrent) listOf(old, oldAnswer) else older + listOf(old, oldAnswer, summary, summaryAnswer, fresh)
        val chat = Conversation(assistantId = settings.assistantId, profileId = owner, messageNodes = messages.map { it.toMessageNode() })
        val server = LocalModelServer {
            if (deleteDuringSummary) runBlocking { store.change(owner) { state -> state.copy(suppressedSources =
                state.suppressedSources + store.fingerprint(owner, "message:${old.id}")) to Unit } }
            LocalModelServer.completion("safe synthetic answer")
        }
        val model = Model(modelId = "qa-history")
        val provider = ProviderSetting.OpenAI(baseUrl = server.baseUrl, apiKey = "synthetic", models = listOf(model))
        val assistant = Assistant(chatModelId = model.id, reasoningLevel = ReasoningLevel.OFF,
            streamOutput = false, localTools = emptyList(), enableWebSearch = false)
        try {
            store.reconcile(chat)
            val marker = store.fingerprint(owner, "message:${old.id}")
            store.change(owner) { it.copy(sources = if (orphan) emptyList() else it.sources,
                suppressedSources = if (protected) setOf(marker) else emptySet()) to Unit }
            val before = k.get<AppDatabase>().learningMemoryDao().get(owner)!!.payload
            val config = settings.copy(activeProfileId = owner, providers = listOf(provider),
                learnerProfiles = settings.learnerProfiles + me.rerere.rikkahub.feature.studychat.LearnerProfile(owner, "QA history", false),
                chatModelId = model.id, compressModelId = model.id,
                networkSetting = settings.networkSetting.copy(proxyUrl = "", enableAutoRetry = false))
            val generated = runCatching { LeasedGenerationFixture(config, assistant, chat.id, owner).use { lease ->
                lease.run {
                    k.get<GenerationLoop>().generateText(settings = config, model = model, messages = messages,
                        assistant = assistant, profileId = owner, conversationId = chat.id,
                        tools = if (largeHistory) emptyList() else listOf(me.rerere.ai.core.Tool(
                            name = "qa_history_prompt", description = "Synthetic prompt boundary; never dispatched",
                            systemPrompt = { _, visible -> visible.filter { it.id == oldAnswer.id || it.id == summary.id || it.id == summaryAnswer.id }.joinToString { it.toText() } },
                            execute = { error("The fixture must not dispatch tools") })),
                        hiddenSourceMessageIds = store.hiddenMessageIds(owner)).collect()
                }
            } }
            if (deletedCurrent || deleteDuringSummary) {
                assertTrue("Changed/deleted input must stop before further requests", generated.isFailure)
                assertEquals(if (deletedCurrent) 0 else 1, server.bodies.size)
                assertEquals(messages, chat.currentMessages)
                if (deletedCurrent) assertEquals(before, k.get<AppDatabase>().learningMemoryDao().get(owner)!!.payload)
                return@runBlocking
            }
            assertTrue(generated.exceptionOrNull()?.stackTraceToString(), generated.isSuccess)
            if (largeHistory) assertTrue("Automatic summary was not exercised", server.bodies.size >= 2) else assertEquals(1, server.bodies.size)
            val body = server.bodies.last()
            assertTrue("New explicitly supplied content remains available", body.contains(fresh.toText()))
            assertEquals(!protected, body.contains("OLD_ANSWER_SENTINEL"))
            assertEquals(!protected, body.contains("OLD_SUMMARY_SENTINEL"))
            assertEquals(!protected, body.contains("SUMMARY_ANSWER_SENTINEL"))
            if (protected) assertTrue(server.bodies.none { "OLD_ANSWER_SENTINEL" in it || "OLD_SUMMARY_SENTINEL" in it })
            assertEquals(before, k.get<AppDatabase>().learningMemoryDao().get(owner)!!.payload)
        } finally { server.close(); clear(owner) }
    }

    @Test fun manualCompressionOmitsDeletedTurnsAndOldSummary() = compression(lateDeletion = false, protected = true)
    @Test fun deletionDuringCompressionRejectsLateSummaryWithoutChangingHistory() = compression(lateDeletion = true, protected = false)
    @Test fun healthyCompressionStillPublishesSummary() = compression(lateDeletion = false, protected = false)

    private fun compression(lateDeletion: Boolean, protected: Boolean): Unit = runBlocking {
        val settings = k.get<SettingsStore>()
        val original = withTimeout(20_000) { settings.settingsFlow.first { !it.init } }
        val owner = original.activeProfileId
        val previous = store.read(owner)
        val old = UIMessage.user("COMPRESS_SOURCE_SENTINEL_${Uuid.random()}")
        val messages = listOf(old, UIMessage.assistant("COMPRESS_ANSWER_SENTINEL"),
            ChatContextBudget.summaryMessage("COMPRESS_SUMMARY_SENTINEL"), UIMessage.user("healthy retained question"))
        val marker = store.fingerprint(owner, "message:${old.id}")
        val server = LocalModelServer {
            if (lateDeletion) runBlocking { store.change(owner) { it.copy(suppressedSources = it.suppressedSources + marker) to Unit } }
            LocalModelServer.completion("new synthetic summary")
        }
        val model = Model(modelId = "qa-compression-history")
        val provider = ProviderSetting.OpenAI(baseUrl = server.baseUrl, apiKey = "synthetic", models = listOf(model))
        val assistant = Assistant(chatModelId = model.id, streamOutput = false, localTools = emptyList(), enableWebSearch = false)
        val service = k.get<ChatService>()
        val repo = k.get<ConversationRepository>()
        val id = Uuid.random()
        try {
            settings.update(original.copy(providers = listOf(provider), assistants = original.assistants + assistant,
                assistantId = assistant.id, chatModelId = model.id, compressModelId = model.id, enableSuggestion = false,
                networkSetting = original.networkSetting.copy(proxyUrl = "", enableAutoRetry = false)))
            settings.settingsFlow.first { it.assistantId == assistant.id }
            service.addConversationReference(id)
            service.saveConversation(id, Conversation.ofId(id, assistant.id).copy(profileId = owner, title = "QA history compression",
                newConversation = true, messageNodes = messages.map { it.toMessageNode() }))
            // Simulate a restored source tombstone whose source row is absent.
            store.change(owner) { previous.copy(fingerprintVersion = 1, sources = emptyList(),
                suppressedSources = if (protected) setOf(marker) else emptySet(), recoveryBlocked = false) to Unit }
            val before = service.getConversationFlow(id).value
            val beforePersistedNodes = k.get<AppDatabase>().messageNodeDao().getNodesOfConversation(id.toString())
            val result = service.compressConversation(id, before, "", 1000, keepRecentMessages = 0)
            assertEquals(1, server.bodies.size)
            if (lateDeletion) {
                assertTrue("Deleted-source changes must reject the in-flight compression", result.isFailure)
                assertEquals(before.messageNodes, service.getConversationFlow(id).value.messageNodes)
                assertEquals(beforePersistedNodes, k.get<AppDatabase>().messageNodeDao().getNodesOfConversation(id.toString()))
            } else {
                assertTrue(result.exceptionOrNull()?.stackTraceToString(), result.isSuccess)
                val body = server.bodies.single()
                assertTrue(body.contains("healthy retained question"))
                assertEquals(!protected, body.contains("COMPRESS_SOURCE_SENTINEL"))
                assertEquals(!protected, body.contains("COMPRESS_ANSWER_SENTINEL"))
                assertEquals(!protected, body.contains("COMPRESS_SUMMARY_SENTINEL"))
                assertTrue(service.getConversationFlow(id).value.currentMessages.single().isHistorySummary)
            }
            assertEquals(if (protected || lateDeletion) setOf(marker) else emptySet<String>(), store.read(owner).suppressedSources)
        } finally {
            service.stopGeneration(id)
            repo.getConversationById(id)?.let { service.deleteConversation(it) }
            service.removeConversationReference(id)
            store.change(owner) { previous to Unit }
            settings.update(original); settings.settingsFlow.first { it.assistantId == original.assistantId }
            server.close()
        }
    }
}
