package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.flutter.StudyHostFacade
import me.rerere.rikkahub.service.ChatService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class RegenerationCheckpointRuntimeTest {
    @Test fun regeneratedCheckpointResumesOriginalTaskAndLockedModel() = scenario(false)
    @Test fun earlierUserRegenerationAcceptsTargetGoalInsteadOfLaterUser() = scenario(true)
    @Test fun queuedRegenerationKeepsModelAcceptedBeforePickerChanges() = scenario(false, queued = true)
    @Test fun branchChangedWhileWaitingForSlotCannotDispatchRegeneration() = scenario(false, queued = true, mutate = true)
    private fun scenario(earlier: Boolean, queued: Boolean = false, mutate: Boolean = false): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "org.hxsi.agent.nativeqa")
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsStore>(); val original = settings.settingsFlow.first { !it.init }
        val service = koin.get<ChatService>(); val repo = koin.get<ConversationRepository>()
        val scheduler = AgentScheduler.shared(context); val owner = original.activeProfileId
        val id = Uuid.random(); var host: StudyHostFacade? = null
        var blocker: Job? = null
        val release = CompletableDeferred<Unit>()
        LocalModelServer { LocalModelServer.completion("synthetic regeneration checkpoint") }.use { server ->
            val locked = Model(modelId = "qa-regen-locked"); val changed = Model(modelId = "qa-regen-picker")
            val provider = ProviderSetting.OpenAI(baseUrl = server.baseUrl, apiKey = "synthetic", models = listOf(locked, changed))
            val assistant = me.rerere.rikkahub.data.model.Assistant(chatModelId = locked.id, reasoningLevel = ReasoningLevel.OFF,
                streamOutput = false, localTools = emptyList())
            val accepted = original.copy(providers = listOf(provider), assistants = listOf(assistant), assistantId = assistant.id,
                chatModelId = locked.id, enableSuggestion = false, learnerProfiles = original.learnerProfiles.map { it.copy(memoryEnabled = false) },
                networkSetting = original.networkSetting.copy(proxyUrl = "", enableAutoRetry = false))
            try {
                settings.update(accepted); settings.settingsFlow.first { it.chatModelId == locked.id }
                val source = UIMessage.user("synthetic regeneration input")
                val answer = UIMessage.assistant("old synthetic answer").copy(generationState = "COMPLETED")
                val conversation = Conversation.ofId(id, assistant.id).copy(profileId = owner, newConversation = true,
                    messageNodes = listOf(MessageNode(messages = listOf(source)), MessageNode(messages = listOf(answer))) +
                        if (earlier) listOf(MessageNode(messages = listOf(UIMessage.user("later unrelated input"))),
                            MessageNode(messages = listOf(UIMessage.assistant("later unrelated answer")))) else emptyList())
                service.addConversationReference(id); service.saveConversation(id, conversation)
                if (queued) {
                    val ready = CompletableDeferred<Unit>()
                    val occupied = scheduler.accept(conversation.copy(id = Uuid.random()), Uuid.random().toString(), "synthetic slot holder", accepted)
                    blocker = launch { scheduler.run(occupied, { settings.settingsFlow.value }, {}, { "COMPLETED_UNVERIFIED" }, { false }) {
                        ready.complete(Unit); release.await()
                    } }
                    withTimeout(10_000) { ready.await() }
                }
                service.regenerateAtMessage(id, if (earlier) source else answer)
                if (queued) {
                    settings.update(accepted.copy(chatModelId = changed.id, assistants = listOf(assistant.copy(chatModelId = changed.id))))
                    settings.settingsFlow.first { it.chatModelId == changed.id }
                    val replacement = UIMessage.user("synthetic regeneration input")
                    if (mutate) service.saveConversation(id, conversation.copy(messageNodes = listOf(
                        conversation.messageNodes.first().copy(messages = listOf(replacement)), conversation.messageNodes[1])))
                    release.complete(Unit); blocker?.join()
                    if (mutate) {
                        withTimeout(20_000) { while (service.getGenerationJobStateFlow(id).first() != null) delay(20) }
                        assertEquals(0, server.bodies.size)
                        assertEquals(replacement.id, service.getConversationFlow(id).value.currentMessages.first().id)
                        return@use
                    }
                }
                withTimeout(20_000) { while (server.bodies.isEmpty() || service.getGenerationJobStateFlow(id).first() != null) delay(20) }
                val task = requireNotNull(scheduler.current(service.getConversationFlow(id).value))
                assertNotEquals(source.id.toString(), task.requestId)
                assertEquals(kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(
                    me.rerere.ai.ui.UIMessagePart.serializer()), source.parts), scheduler.plan(owner, task.id).goal)
                assertTrue(server.bodies.none { it.contains("generationTaskId") || it.contains("regen1:") || it.contains("later unrelated input") })
                val paused = scheduler.command(owner, task.id, "pause-original", task.revision, task.fence, "pause", "")
                settings.update(accepted.copy(chatModelId = changed.id, assistants = listOf(assistant.copy(chatModelId = changed.id))))
                settings.settingsFlow.first { it.chatModelId == changed.id }
                host = StudyHostFacade(context, settings, koin.get(), service, koin.get(), koin.get(), koin.get(), pickFiles = { emptyList() })
                val epoch = host.getBootstrap(owner, 1L).contextEpoch
                val ack = host.taskCommand(owner, id.toString(), task.id, "resume-original", epoch, paused.revision, paused.fence, "resume", "")
                assertTrue("A regenerated checkpoint must retain its original task identity", ack.success)
                withTimeout(20_000) { while (server.bodies.size < 2 || service.getGenerationJobStateFlow(id).first() != null) delay(20) }
                assertEquals(2, server.bodies.size)
                assertTrue(server.bodies.all { kotlinx.serialization.json.Json.parseToJsonElement(it)
                    .let { body -> (body as kotlinx.serialization.json.JsonObject)["model"].toString() == "\"qa-regen-locked\"" } })
                assertTrue(host.taskCommand(owner, id.toString(), task.id, "resume-original", epoch, paused.revision, paused.fence, "resume", "").success)
                delay(150); assertEquals(2, server.bodies.size)
            } finally {
                release.complete(Unit); blocker?.join()
                host?.close(); service.stopGeneration(id)
                withTimeout(15_000) { while (service.getGenerationJobStateFlow(id).first() != null) delay(20) }
                service.removeConversationReference(id); repo.getConversationById(id)?.let { repo.deleteConversation(it) }; settings.update(original)
            }
        }
    }
}
