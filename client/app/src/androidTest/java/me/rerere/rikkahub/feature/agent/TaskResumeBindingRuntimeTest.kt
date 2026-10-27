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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
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
class TaskResumeBindingRuntimeTest {
    @Test fun selectedDifferentInputCannotResumeOldFencedTask() = scenario("branch")
    @Test fun transformedCheckpointWithStableInputIdentityRemainsResumable() = scenario("transformed")
    @Test fun matchingCheckpointUsesLockedModelAndDuplicateAckDoesNotGenerate() = scenario("matching")
    @Test fun independentWaitsAndRevocationCannotResumeCheckpoint() = scenario("blockers")
    @Test fun checkpointChangedWhileWaitingForGlobalSlotCannotDispatch() = scenario("race")
    @Test fun acceptedUserOnlyCheckpointCanResumeBeforeFirstAssistant() = scenario("user")

    private fun scenario(kind: String): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "org.hxsi.agent.nativeqa")
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsStore>()
        val original = settings.settingsFlow.first { !it.init }
        val service = koin.get<ChatService>()
        val repo = koin.get<ConversationRepository>()
        val scheduler = AgentScheduler.shared(context)
        val owner = original.activeProfileId
        val id = Uuid.random()
        val source = UIMessage.user("original synthetic checkpoint goal")
        var host: StudyHostFacade? = null
        var blocker: Job? = null
        val release = CompletableDeferred<Unit>()
        LocalModelServer { LocalModelServer.completion("synthetic continued answer") }.use { server ->
            val locked = Model(modelId = "qa-checkpoint-locked")
            val changed = Model(modelId = "qa-picker-changed")
            val provider = ProviderSetting.OpenAI(baseUrl = server.baseUrl, apiKey = "synthetic", models = listOf(locked, changed))
            val assistant = Assistant(chatModelId = locked.id, reasoningLevel = ReasoningLevel.OFF,
                streamOutput = false, localTools = emptyList())
            val accepted = original.copy(providers = listOf(provider), assistants = listOf(assistant), assistantId = assistant.id,
                chatModelId = locked.id, enableSuggestion = false,
                learnerProfiles = original.learnerProfiles.map { it.copy(memoryEnabled = false) },
                networkSetting = original.networkSetting.copy(proxyUrl = "", enableAutoRetry = false))
            val input = when (kind) {
                "branch" -> UIMessage.user("different selected branch")
                "transformed" -> source.copy(parts = UIMessage.user("trusted preprocessed checkpoint contents").parts)
                else -> source
            }
            val conversation = Conversation.ofId(id, assistant.id).copy(profileId = owner, title = "QA resume binding",
                newConversation = true, messageNodes = listOf(MessageNode(messages = listOf(input))) +
                    if (kind == "user") emptyList() else listOf(MessageNode(messages = listOf(UIMessage.assistant("synthetic saved checkpoint").copy(generationState = "INTERRUPTED")))))
            try {
                settings.update(accepted); settings.settingsFlow.first { it.chatModelId == locked.id }
                service.addConversationReference(id); service.saveConversation(id, conversation)
                val task = scheduler.accept(conversation, source.id.toString(), Json.encodeToString(source.parts), accepted)
                AgentTaskStore(context).use { store ->
                    val lease = store.claim(owner, task.id)
                    store.finish(lease, "INTERRUPTED")
                }
                var current = scheduler.get(owner, task.id)
                scheduler.command(owner, task.id, "pause", current.revision, current.fence, "pause", "")
                settings.update(accepted.copy(chatModelId = changed.id, assistants = listOf(assistant.copy(chatModelId = changed.id))))
                settings.settingsFlow.first { it.chatModelId == changed.id }
                host = StudyHostFacade(context, settings, koin.get(), service, koin.get(), koin.get(), koin.get(), pickFiles = { emptyList() })
                val epoch = host.getBootstrap(owner, 1L).contextEpoch
                if (kind == "race") {
                    val ready = CompletableDeferred<Unit>()
                    val other = scheduler.accept(conversation.copy(id = Uuid.random()), Uuid.random().toString(), "synthetic slot holder", accepted)
                    blocker = launch {
                        scheduler.run(other, { settings.settingsFlow.value }, {}, { "INTERRUPTED" }, { false }) {
                            ready.complete(Unit); release.await()
                        }
                    }
                    withTimeout(10_000) { ready.await() }
                }
                suspend fun reject(label: String) {
                    val before = scheduler.snapshot(owner, task.id)
                    val ack = host.taskCommand(owner, id.toString(), task.id, label, epoch,
                        before.task.revision, before.task.fence, "resume", "")
                    assertFalse("$label must reject before recording resume", ack.success)
                    assertEquals("TASK_PRECONDITION_REJECTED", ack.error?.code)
                    assertEquals(before, scheduler.snapshot(owner, task.id))
                    assertFalse(scheduler.commandRecorded(owner, task.id, label))
                    assertEquals(0, server.bodies.size)
                }
                if (kind == "blockers") {
                    for (action in listOf("wait_input", "wait_device", "require_device_confirmation")) {
                        current = scheduler.get(owner, task.id)
                        scheduler.command(owner, task.id, action, current.revision, current.fence, action, "")
                        reject(action + "-resume")
                    }
                    current = scheduler.get(owner, task.id)
                    scheduler.command(owner, task.id, "revoke", current.revision, current.fence, "revoke", "")
                    reject("revoked-resume")
                } else if (kind == "branch") reject(kind)
                else {
                    current = scheduler.get(owner, task.id)
                    val request = Uuid.random().toString()
                    assertTrue(host.taskCommand(owner, id.toString(), task.id, request, epoch,
                        current.revision, current.fence, "resume", "").success)
                    if (kind == "race") {
                        withTimeout(10_000) { while (service.getGenerationJobStateFlow(id).first() == null) delay(20) }
                        delay(100)
                        val selected = service.getConversationFlow(id).value
                        // Fault injection below the public branch-edit busy guard: late checkpoint replacement.
                        service.saveConversation(id, selected.copy(messageNodes = listOf(MessageNode(messages = listOf(UIMessage.user("late different input"))))))
                        release.complete(Unit); blocker?.join()
                        withTimeout(15_000) { while (service.getGenerationJobStateFlow(id).first() != null) delay(20) }
                        assertEquals(0, server.bodies.size)
                        assertTrue(scheduler.commandRecorded(owner, task.id, request))
                        assertNotEquals("SUCCEEDED", scheduler.get(owner, task.id).state)
                        return@use
                    }
                    withTimeout(15_000) { while (server.bodies.isEmpty()) delay(20) }
                    withTimeout(15_000) { while (service.getGenerationJobStateFlow(id).first() != null) delay(20) }
                    assertEquals(1, server.bodies.size)
                    assertEquals("qa-checkpoint-locked", Json.parseToJsonElement(server.bodies.single()).jsonObject.getValue("model").jsonPrimitive.content)
                    assertTrue(host.taskCommand(owner, id.toString(), task.id, request, epoch,
                        current.revision, current.fence, "resume", "").success)
                    delay(250)
                    assertEquals(1, server.bodies.size)
                    assertEquals(task.id, scheduler.current(requireNotNull(repo.getConversationById(id)))?.id)
                }
            } finally {
                release.complete(Unit); blocker?.join()
                host?.close(); service.stopGeneration(id)
                withTimeout(15_000) { while (service.getGenerationJobStateFlow(id).first() != null) delay(20) }
                service.removeConversationReference(id)
                repo.getConversationById(id)?.let { repo.deleteConversation(it) }
                settings.update(original)
            }
        }
    }
}
