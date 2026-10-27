package me.rerere.rikkahub.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import me.rerere.ai.core.ProviderRequestGuard
import me.rerere.ai.core.ProviderRequestAccounting
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
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

/** Exercises the real role scheduler, provider and metadata commit, using only loopback data. */
@RunWith(AndroidJUnit4::class)
class HistoryAuxiliarySuppressionTest {
    @Test fun titleOmitsDeletedTurnAndSummary() = role("title", protected = true)
    @Test fun suggestionOmitsDeletedTurnAndSummary() = role("suggestion", protected = true)
    @Test fun healthyTitleKeepsHistoryAndPublishes() = role("title", protected = false)
    @Test fun healthySuggestionKeepsHistoryAndPublishes() = role("suggestion", protected = false)
    @Test fun deletionDuringTitleRejectsLateMetadata() = role("title", protected = false, lateDeletion = true)
    @Test fun deletionDuringSuggestionRejectsLateMetadata() = role("suggestion", protected = false, lateDeletion = true)
    @Test fun fullyDeletedTitleDoesNotDispatch() = role("title", protected = true, onlyDeleted = true)
    @Test fun fullyDeletedSuggestionDoesNotDispatch() = role("suggestion", protected = true, onlyDeleted = true)

    @Test fun titleDeletedAtPhysicalEncodingDoesNotDispatch() = role("title", false, physicalDeletion = true)
    @Test fun suggestionDeletedAtPhysicalEncodingDoesNotDispatch() = role("suggestion", false, physicalDeletion = true)

    private fun role(role: String, protected: Boolean, lateDeletion: Boolean = false, onlyDeleted: Boolean = false,
                     physicalDeletion: Boolean = false): Unit = runBlocking {
        val k = GlobalContext.get()
        val settings = k.get<SettingsStore>()
        val original = withTimeout(20_000) { settings.settingsFlow.first { !it.init } }
        val owner = original.activeProfileId
        val store = k.get<LearningMemoryStore>()
        val previous = store.read(owner)
        val old = UIMessage.user("ROLE_OLD_SOURCE_SENTINEL")
        val fresh = UIMessage.user("ROLE_FRESH_SOURCE; explicitly quoted ${old.toText()}")
        val messages = listOf(old, UIMessage.assistant("ROLE_OLD_ANSWER_SENTINEL"),
            // Reopened summaries carry the persisted flag; isSynthetic is intentionally transient.
            ChatContextBudget.summaryMessage("ROLE_OLD_SUMMARY_SENTINEL").copy(isSynthetic = false)) + if (onlyDeleted) emptyList() else listOf(fresh)
        val marker = store.fingerprint(owner, "message:${old.id}")
        val server = LocalModelServer {
            if (lateDeletion) runBlocking { store.change(owner) { state -> state.copy(suppressedSources = state.suppressedSources + marker) to Unit } }
            LocalModelServer.completion("ROLE_RESULT_SENTINEL")
        }
        val model = Model(modelId = "qa-history-role")
        val provider = ProviderSetting.OpenAI(baseUrl = server.baseUrl, apiKey = "synthetic", models = listOf(model))
        val assistant = Assistant(chatModelId = model.id, streamOutput = false, localTools = emptyList(), enableWebSearch = false)
        val service = k.get<ChatService>()
        val repo = k.get<ConversationRepository>()
        val db = k.get<AppDatabase>()
        val id = Uuid.random()
        try {
            settings.update(original.copy(providers = listOf(provider), assistants = original.assistants + assistant,
                assistantId = assistant.id, chatModelId = model.id, fastModelId = model.id, enableSuggestion = true,
                networkSetting = original.networkSetting.copy(proxyUrl = "", enableAutoRetry = false)))
            settings.settingsFlow.first { it.assistantId == assistant.id }
            service.addConversationReference(id)
            service.saveConversation(id, Conversation.ofId(id, assistant.id).copy(profileId = owner,
                title = "original title", newConversation = true, messageNodes = messages.map { it.toMessageNode() }))
            store.change(owner) { previous.copy(fingerprintVersion = 1, sources = emptyList(), recoveryBlocked = false,
                suppressedSources = if (protected) setOf(marker) else emptySet()) to Unit }
            val payload = db.learningMemoryDao().get(owner)!!.payload
            val before = service.getConversationFlow(id).value
            assertEquals("Fixture must match the durable message inventory", before.messageNodes, requireNotNull(repo.getConversationById(id)).messageNodes)
            val nodes = db.messageNodeDao().getNodesOfConversation(id.toString())
            withContext(ProviderRequestGuard {
                if (physicalDeletion && currentCoroutineContext()[ProviderRequestAccounting] != null)
                    store.change(owner) { it.copy(suppressedSources = it.suppressedSources + marker) to Unit }
            }) {
                if (role == "title") service.generateTitle(id, before, force = true) else service.generateSuggestion(id, before)
            }
            val after = service.getConversationFlow(id).value
            assertEquals(before.messageNodes, after.messageNodes)
            assertEquals(nodes, db.messageNodeDao().getNodesOfConversation(id.toString()))
            if (onlyDeleted || physicalDeletion) {
                assertTrue("No eligible source must mean no paid role request", server.bodies.isEmpty())
            } else {
                assertEquals(1, server.bodies.size)
                val body = server.bodies.single()
                assertTrue(body.contains(fresh.toText()))
                assertEquals(!protected, body.contains("ROLE_OLD_ANSWER_SENTINEL"))
                assertEquals(!protected, body.contains("ROLE_OLD_SUMMARY_SENTINEL"))
            }
            val publish = !lateDeletion && !onlyDeleted && !physicalDeletion
            assertEquals(if (publish && role == "title") "ROLE_RESULT_SENTINEL" else "original title", after.title)
            assertEquals(if (publish && role == "suggestion") listOf("ROLE_RESULT_SENTINEL") else emptyList<String>(), after.chatSuggestions)
            val persisted = requireNotNull(repo.getConversationById(id))
            assertEquals(after.title, persisted.title)
            assertEquals(after.chatSuggestions, persisted.chatSuggestions)
            if (!lateDeletion && !physicalDeletion) assertEquals(payload, db.learningMemoryDao().get(owner)!!.payload)
            assertEquals(if (protected || lateDeletion || physicalDeletion) setOf(marker) else emptySet<String>(), store.read(owner).suppressedSources)
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
