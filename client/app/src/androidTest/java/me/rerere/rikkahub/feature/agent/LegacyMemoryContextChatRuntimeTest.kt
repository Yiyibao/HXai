package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
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
class LegacyMemoryContextChatRuntimeTest {
    @Test fun blockedMemoryNeverReachesProviderAndOrdinaryChatStillCompletes() = scenario(true)
    @Test fun healthyMemoryStillReachesProviderWithTheSameOrdinaryCompletion() = scenario(false)

    private fun scenario(blocked: Boolean): Unit = runBlocking {
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsStore>()
        val original = withTimeout(20_000) { settings.settingsFlow.first { !it.init } }
        val owner = original.activeProfileId
        val store = koin.get<LearningMemoryStore>()
        val previous = store.read(owner)
        val release = CountDownLatch(1)
        val server = LocalModelServer { LocalModelServer.completion("你好，可以继续学习。") }.apply { barrier = release }
        val model = Model(modelId = "qa-legacy-context", abilities = listOf(ModelAbility.TOOL))
        val provider = ProviderSetting.OpenAI(name = "QA legacy loopback", baseUrl = server.baseUrl, apiKey = "synthetic", models = listOf(model))
        val assistant = Assistant(name = "QA legacy context", chatModelId = model.id, reasoningLevel = ReasoningLevel.OFF,
            streamOutput = false, localTools = emptyList(), enableWebSearch = false)
        val service = koin.get<ChatService>()
        val repo = koin.get<ConversationRepository>()
        val id = Uuid.random()
        val oldText = "旧约束阻塞哨兵${Uuid.random()}"
        try {
            val memoryId = store.addManual(owner, "legacy-${Uuid.random()}", MemoryType.PREFERENCE, oldText)
            val marker = store.fingerprint(owner, "opaque-legacy-marker")
            if (blocked) store.change(owner) { state -> state.copy(recoveryBlocked = true, suppressedFacts = state.suppressedFacts + marker) to Unit }
            val protected = store.read(owner)
            settings.update(original.copy(providers = listOf(provider), assistants = original.assistants + assistant,
                assistantId = assistant.id, chatModelId = model.id, fastModelId = model.id, enableSuggestion = false,
                networkSetting = original.networkSetting.copy(proxyUrl = "", enableAutoRetry = false)))
            settings.settingsFlow.first { it.assistantId == assistant.id }
            // Generic settings updates deliberately preserve profile controls. Use the real
            // durable profile command, then serialize the request while memory is enabled.
            settings.setLearnerMemoryEnabled(owner, true)
            service.addConversationReference(id)
            service.saveConversation(id, Conversation.ofId(id, assistant.id).copy(profileId = owner, title = "QA legacy", newConversation = true))
            service.sendMessageCommitted(id, listOf(UIMessagePart.Text("你好")), Uuid.random())
            withTimeout(20_000) { while (server.bodies.isEmpty()) delay(20) }
            // Stop background extraction only after actual context serialization. Each
            // fixture has exactly one ordinary chat response, no extraction model call.
            settings.setLearnerMemoryEnabled(owner, false)
            release.countDown()
            val scheduler = AgentScheduler.shared(koin.get())
            val task = withTimeout(30_000) {
                while (true) {
                    val current = scheduler.current(service.getConversationFlow(id).first())
                    if (current != null && current.state !in setOf("RUNNING", "QUEUED") && service.getGenerationJobStateFlow(id).first() == null) return@withTimeout current
                    delay(20)
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
            assertEquals(1, server.bodies.size)
            assertEquals("Memory projection must follow the profile barrier", !blocked, server.bodies.single().contains(oldText))
            // ChatService's existing plain-text outcome is COMPLETED_UNVERIFIED; no tool
            // or goal acceptance was fabricated for this ordinary greeting.
            assertEquals("COMPLETED_UNVERIFIED", task.state)
            val message = repo.getConversationById(id)!!.currentMessages.last()
            assertEquals("COMPLETED", message.generationState)
            assertEquals("你好，可以继续学习。", message.toText())
            val errors = service.errors.value.filter { it.conversationId == id }
            assertTrue(errors.joinToString { "${it.title}:${it.error.javaClass.simpleName}" }, errors.isEmpty())
            val after = store.read(owner)
            assertEquals(blocked, after.recoveryBlocked)
            assertEquals(protected.items, after.items)
            assertEquals(protected.suppressedFacts, after.suppressedFacts)
            if (blocked) assertEquals(protected.usedMemories, after.usedMemories)
            else assertEquals(listOf(memoryId), after.usedMemories[id.toString()])
            assertTrue(after.jobs.none { it.conversationId == id.toString() })
        } finally {
            release.countDown()
            service.stopGeneration(id)
            repo.getConversationById(id)?.let { service.deleteConversation(it) }
            service.removeConversationReference(id)
            store.change(owner) { previous to Unit }
            settings.update(original)
            settings.setLearnerMemoryEnabled(owner, original.learnerProfiles.single { it.id == owner }.memoryEnabled)
            settings.settingsFlow.first { it.assistantId == original.assistantId }
            server.close()
        }
    }
}
