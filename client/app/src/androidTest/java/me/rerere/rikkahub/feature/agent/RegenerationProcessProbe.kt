package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
import java.io.FileOutputStream
import kotlin.uuid.Uuid

/** External driver kills the QA process at the durable command → executor gap. */
@RunWith(AndroidJUnit4::class)
class RegenerationProcessProbe {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture get() = requireNotNull(InstrumentationRegistry.getArguments().getString("fixture")).also {
        require(it.matches(Regex("[a-zA-Z0-9_-]+")))
    }
    @Test fun prepare(): Unit = runBlocking {
        check(context.packageName == "org.hxsi.agent.nativeqa")
        val endpoint = requireNotNull(InstrumentationRegistry.getArguments().getString("endpoint"))
        require(endpoint.matches(Regex("http://127\\.0\\.0\\.1:[0-9]+/v1")))
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsStore>()
        val original = settings.settingsFlow.first { !it.init }
        val service = koin.get<ChatService>()
        val scheduler = AgentScheduler.shared(context)
        val owner = original.activeProfileId
        val locked = Model(modelId = "qa-process-locked")
        val picker = Model(modelId = "qa-process-picker")
        val provider = ProviderSetting.OpenAI(baseUrl = endpoint, apiKey = "synthetic", models = listOf(locked, picker))
        val assistant = Assistant(chatModelId = locked.id, reasoningLevel = ReasoningLevel.OFF, streamOutput = false, localTools = emptyList())
        val accepted = original.copy(providers = listOf(provider), assistants = listOf(assistant), assistantId = assistant.id,
            chatModelId = locked.id, enableSuggestion = false,
            learnerProfiles = original.learnerProfiles.map { it.copy(memoryEnabled = false) },
            networkSetting = original.networkSetting.copy(proxyUrl = "", enableAutoRetry = false))
        settings.update(accepted); settings.settingsFlow.first { it.chatModelId == locked.id }
        val id = Uuid.random()
        val source = UIMessage.user("synthetic process-resume goal")
        val conversation = Conversation.ofId(id, assistant.id).copy(profileId = owner, title = "QA resume process gap", newConversation = true,
            messageNodes = listOf(MessageNode(messages = listOf(source)), MessageNode(messages = listOf(
                UIMessage.assistant("synthetic checkpoint").copy(generationState = "INTERRUPTED")))))
        service.addConversationReference(id); service.saveConversation(id, conversation)
        service.regenerateAtMessage(id, conversation.currentMessages.last())
        withTimeout(20_000) {
            while (service.getGenerationJobStateFlow(id).first() != null) delay(20)
        }
        val task = requireNotNull(scheduler.current(service.getConversationFlow(id).value))
        assertTrue(task.requestId.startsWith("regen1:"))
        assertEquals(task.id, service.getConversationFlow(id).value.currentMessages.last().generationTaskId)
        var current = scheduler.get(owner, task.id)
        scheduler.command(owner, task.id, "pause", current.revision, current.fence, "pause", "")
        current = scheduler.get(owner, task.id)
        val request = "lost-resume-$fixture"
        // Exactly the persisted step used by taskCommand, before it can schedule ChatService.
        val applied = scheduler.command(owner, task.id, request, current.revision, current.fence, "resume", "")
        assertEquals("QUEUED", applied.state)
        settings.update(accepted.copy(chatModelId = picker.id, assistants = listOf(assistant.copy(chatModelId = picker.id))))
        settings.settingsFlow.first { it.chatModelId == picker.id }
        val folder = context.cacheDir.resolve("resume-process-$fixture").apply { check(mkdirs()) }
        val marker = listOf(owner, id.toString(), task.id, request, current.revision.toString(), current.fence.toString()).joinToString("\n")
        FileOutputStream(folder.resolve("ready")).use { it.write(marker.toByteArray()); it.fd.sync() }
        while (true) delay(1000)
    }
    @Test fun verify(): Unit = runBlocking {
        check(context.packageName == "org.hxsi.agent.nativeqa")
        val fields = context.cacheDir.resolve("resume-process-$fixture/ready").readLines()
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsStore>()
        settings.settingsFlow.first { !it.init }
        val service = koin.get<ChatService>()
        val scheduler = AgentScheduler.shared(context)
        val id = Uuid.parse(fields[1])
        val host = StudyHostFacade(context, settings, koin.get(), service, koin.get(), koin.get(), koin.get(), pickFiles = { emptyList() })
        try {
            service.addConversationReference(id)
            service.initializeConversation(id)
            val before = scheduler.snapshot(fields[0], fields[2])
            val epoch = host.getBootstrap(fields[0], 1L).contextEpoch
            // Discover the original command from the actual persistent API. The
            // external marker is only an assertion oracle, never the replay input.
            val receipt = requireNotNull(host.getTaskResumeReceipt(fields[0], fields[2], null, epoch))
            assertEquals(fields[3], receipt.requestId)
            assertEquals(fields[4].toLong(), receipt.expectedRevision)
            assertEquals(fields[5].toLong(), receipt.expectedFence)
            assertEquals(before, scheduler.snapshot(fields[0], fields[2]))
            assertTrue(host.taskCommand(receipt.owner, receipt.conversationId, receipt.taskId, receipt.requestId, epoch,
                receipt.expectedRevision, receipt.expectedFence, "resume", "").success)
            withTimeout(20_000) {
                while (scheduler.get(fields[0], fields[2]).state == "QUEUED" || service.getGenerationJobStateFlow(id).first() != null) delay(20)
            }
            val after = scheduler.snapshot(fields[0], fields[2])
            assertEquals("COMPLETED_UNVERIFIED", after.task.state)
            assertEquals("OFF", after.task.reasoning)
            AgentTaskStore(context).use { store ->
                assertEquals(2, store.readUsage(fields[0], fields[2], null, null, null, 20).entries.size)
            }
            if (before.task.state == "COMPLETED_UNVERIFIED") assertEquals(before, after)
            println("RESUME_GAP_REOPEN_ACK_VERIFIED")
        } finally { host.close(); service.removeConversationReference(id) }
    }
}
