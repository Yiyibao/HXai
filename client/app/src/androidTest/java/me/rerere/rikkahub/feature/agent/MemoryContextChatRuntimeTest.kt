package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.memory.*
import me.rerere.rikkahub.service.ChatService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.util.concurrent.CountDownLatch
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class MemoryContextChatRuntimeTest {
    @Test fun realChatRequestExcludesRevivedMemoryAndRetainsFreshPreference(): Unit = runBlocking {
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsStore>()
        val original = withTimeout(20_000) { settings.settingsFlow.first { !it.init } }
        val owner = original.activeProfileId
        val store = koin.get<LearningMemoryStore>()
        val previous = store.read(owner)
        val release = CountDownLatch(1)
        val server = LocalModelServer { LocalModelServer.completion("你好，可以开始学习。") }.apply { barrier = release }
        val model = Model(modelId = "qa-memory-context")
        val provider = ProviderSetting.OpenAI(name = "QA context loopback", baseUrl = server.baseUrl, apiKey = "synthetic", models = listOf(model))
        val assistant = Assistant(name = "QA memory context", chatModelId = model.id, reasoningLevel = ReasoningLevel.OFF, streamOutput = false, localTools = emptyList())
        val service = koin.get<ChatService>()
        val repo = koin.get<ConversationRepository>()
        val conversationId = Uuid.random()
        val oldText = "旧删除背景哨兵${Uuid.random()}"
        val freshText = "合法新偏好哨兵${Uuid.random()}"
        try {
            val id = store.addManual(owner, "deleted-${Uuid.random()}", MemoryType.PREFERENCE, oldText)
            val deleted = store.read(owner).items.single { it.id == id }
            store.delete(owner, id, 1)
            val fresh = store.addManual(owner, "fresh-${Uuid.random()}", MemoryType.PREFERENCE, freshText)
            store.change(owner) { state -> state.copy(items = state.items + deleted.copy(type = MemoryType.BACKGROUND)) to Unit }
            settings.update(original.copy(providers = listOf(provider), assistants = original.assistants + assistant,
                assistantId = assistant.id, chatModelId = model.id, fastModelId = model.id, enableSuggestion = false,
                networkSetting = original.networkSetting.copy(proxyUrl = "", enableAutoRetry = false)))
            settings.settingsFlow.first { it.assistantId == assistant.id }
            settings.setLearnerMemoryEnabled(owner, true)
            service.addConversationReference(conversationId)
            service.saveConversation(conversationId, Conversation.ofId(conversationId, assistant.id).copy(profileId = owner, title = "QA context", newConversation = true))
            service.sendMessageCommitted(conversationId, listOf(UIMessagePart.Text("你好")), Uuid.random())
            withTimeout(20_000) { while (server.bodies.isEmpty()) delay(20) }
            // The request was actually serialized with memory enabled. Disable only background
            // extraction before releasing the synthetic response; no extra model fixtures are needed.
            settings.setLearnerMemoryEnabled(owner, false)
            release.countDown()
            withTimeout(30_000) {
                service.getConversationFlow(conversationId).first { it.currentMessages.lastOrNull()?.generationState == "COMPLETED" }
                while (service.getGenerationJobStateFlow(conversationId).first() != null) delay(20)
            }
            assertEquals(1, server.bodies.size)
            assertTrue(server.bodies.single().contains(freshText))
            assertFalse("Deleted content reached the provider request", server.bodies.single().contains(oldText))
            assertEquals(listOf(fresh), store.read(owner).usedMemories[conversationId.toString()])
            assertEquals(oldText, store.read(owner).items.single { it.id == id }.content)
            assertEquals("你好，可以开始学习。", repo.getConversationById(conversationId)!!.currentMessages.last().toText())
        } finally {
            release.countDown()
            service.stopGeneration(conversationId)
            service.removeConversationReference(conversationId)
            repo.getConversationById(conversationId)?.let { repo.deleteConversation(it) }
            store.change(owner) { previous to Unit }
            settings.update(original)
            settings.setLearnerMemoryEnabled(owner, original.learnerProfiles.single { it.id == owner }.memoryEnabled)
            settings.settingsFlow.first { it.assistantId == original.assistantId }
            server.close()
        }
    }
}
