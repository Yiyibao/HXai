package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationQueue
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.PendingInput
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.flutter.StudyHostFacade
import me.rerere.rikkahub.service.ChatService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class QueuedTaskCommandRuntimeTest {
    @Test fun resumeQueuedTaskUsesItsAcceptedModelAndDoesNotReplayPreviousTurn() = scenario(false)
    @Test fun inputBlockedQueuedTaskCannotResumeItsQueueOrDispatch() = scenario(true)
    @Test fun supplementQueuedTaskUsesItsModelAndDuplicateAckDoesNotDispatchAgain() = scenario(false, true)

    private fun scenario(inputBlocked: Boolean, supplement: Boolean = false): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "org.hxsi.agent.nativeqa")
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsStore>()
        val original = settings.settingsFlow.first { !it.init }
        val service = koin.get<ChatService>()
        val repo = koin.get<ConversationRepository>()
        val scheduler = AgentScheduler.shared(context)
        val id = Uuid.random()
        val owner = original.activeProfileId
        val user = UIMessage.user("previous synthetic goal")
        val pending = PendingInput(id = Uuid.random(), parts = listOf(UIMessagePart.Text("queued synthetic goal")), answer = true)
        var facade: StudyHostFacade? = null
        LocalModelServer { LocalModelServer.completion("synthetic complete") }.use { server ->
            val previousModel = Model(modelId = "qa-previous-task")
            val queuedModel = Model(modelId = "qa-accepted-queued")
            val changedModel = Model(modelId = "qa-changed-picker")
            val provider = ProviderSetting.OpenAI(baseUrl = server.baseUrl, apiKey = "synthetic",
                models = listOf(previousModel, queuedModel, changedModel))
            val assistant = Assistant(chatModelId = previousModel.id, reasoningLevel = ReasoningLevel.OFF,
                streamOutput = false, localTools = emptyList())
            val accepted = original.copy(providers = listOf(provider), assistants = listOf(assistant),
                assistantId = assistant.id, chatModelId = previousModel.id, enableSuggestion = false,
                learnerProfiles = original.learnerProfiles.map { it.copy(memoryEnabled = false) },
                networkSetting = original.networkSetting.copy(proxyUrl = "", enableAutoRetry = false))
            val conversation = Conversation.ofId(id, assistant.id).copy(profileId = owner, newConversation = true,
                title = "QA selected queued task", messageNodes = listOf(MessageNode(messages = listOf(user)),
                    MessageNode(messages = listOf(UIMessage.assistant("previous reply").copy(generationState = "COMPLETED")))),
                queue = ConversationQueue(messages = listOf(pending), paused = true))
            try {
                settings.update(accepted); settings.settingsFlow.first { it.chatModelId == previousModel.id }
                service.addConversationReference(id); service.saveConversation(id, conversation)
                val previous = scheduler.accept(conversation, user.id.toString(), Json.encodeToString(user.parts), accepted)
                AgentTaskStore(context).use { store ->
                    val lease = store.claim(owner, previous.id)
                    store.finish(lease, "COMPLETED_UNVERIFIED")
                }
                val queuedSettings = accepted.copy(chatModelId = queuedModel.id,
                    assistants = listOf(assistant.copy(chatModelId = queuedModel.id)))
                val queued = scheduler.accept(conversation, pending.id.toString(), Json.encodeToString(pending.parts), queuedSettings)
                suspend fun command(action: String) {
                    val current = scheduler.get(owner, queued.id)
                    scheduler.command(owner, queued.id, Uuid.random().toString(), current.revision, current.fence, action, "")
                }
                if (!supplement) command("pause")
                if (inputBlocked) command("wait_input")
                settings.update(accepted.copy(chatModelId = changedModel.id,
                    assistants = listOf(assistant.copy(chatModelId = changedModel.id))))
                settings.settingsFlow.first { it.chatModelId == changedModel.id }
                facade = StudyHostFacade(context, settings, koin.get(), service, koin.get(), koin.get(), koin.get(),
                    pickFiles = { emptyList() })
                val epoch = facade.getBootstrap(owner, 1L).contextEpoch
                val selected = requireNotNull(facade.getConversationTask(owner, id.toString(), epoch))
                assertEquals(queued.id, selected.id)
                val requestId = Uuid.random().toString()
                val action = if (supplement) "supplement" else "resume"
                val payload = if (supplement) "Keep the queued synthetic goal" else ""
                val result = facade.taskCommand(owner, id.toString(), queued.id, requestId, epoch,
                    selected.revision, selected.fence, action, payload)
                if (inputBlocked) {
                    assertFalse(result.success)
                    delay(300)
                    assertEquals(0, server.bodies.size)
                    assertTrue(requireNotNull(repo.getConversationById(id)).queue.paused)
                    assertTrue(scheduler.get(owner, queued.id).manualPaused)
                } else {
                    assertTrue(result.success)
                    withTimeout(15_000) { while (server.bodies.isEmpty()) delay(20) }
                    withTimeout(15_000) { while (service.getGenerationJobStateFlow(id).first() != null) delay(20) }
                    assertEquals(1, server.bodies.size)
                    assertEquals("qa-accepted-queued", Json.parseToJsonElement(server.bodies.single())
                        .jsonObject.getValue("model").jsonPrimitive.content)
                    val saved = requireNotNull(repo.getConversationById(id))
                    assertEquals(listOf(user.id, pending.id), saved.currentMessages.filter { it.role == MessageRole.USER }.map { it.id })
                    assertTrue(saved.queue.messages.isEmpty())
                    assertEquals(queued.id, requireNotNull(scheduler.current(saved)).id)
                    assertEquals("COMPLETED_UNVERIFIED", scheduler.get(owner, previous.id).state)
                    assertTrue(facade.taskCommand(owner, id.toString(), queued.id, requestId, epoch,
                        selected.revision, selected.fence, action, payload).success)
                    delay(300)
                    assertEquals("Duplicate command ACK must not regenerate", 1, server.bodies.size)
                }
            } finally {
                facade?.close(); service.stopGeneration(id); service.removeConversationReference(id)
                repo.getConversationById(id)?.let { repo.deleteConversation(it) }
                settings.update(original)
            }
        }
    }
}
