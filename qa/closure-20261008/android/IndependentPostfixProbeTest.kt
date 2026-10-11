package me.rerere.rikkahub.service

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.data.repository.ConversationRepository
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

class IndependentPostfixProbeTest {
    private fun manager(service: ChatService) = ChatService::class.java.getDeclaredField("sessionManager").apply { isAccessible = true }.get(service) as ConversationSessionManager

    @Test fun deletedConversationMustRejectStaleSaveAfterSessionEviction() = runBlocking {
        val koin = GlobalContext.get(); val settings = koin.get<SettingsStore>(); val service = koin.get<ChatService>(); val repo = koin.get<ConversationRepository>()
        val original = withTimeout(20000) { settings.settingsFlow.first { !it.init } }
        val evidence = QaEvidenceRun("IndependentPostfixProbeTest.deletedConversationMustRejectStaleSaveAfterSessionEviction")
        val owner = settings.createLearnerProfile("QA deletion ${evidence.runId.take(8)}")
        val stale = Conversation.ofId(Uuid.random(), original.assistantId).copy(profileId = owner, title = "QA_${evidence.runId}_deleted", messageNodes = listOf(UIMessage.user("synthetic stale save").toMessageNode()))
        var status = "FAILED"
        try {
            withTimeout(20000) { settings.settingsFlow.first { it.learnerProfiles.any { p -> p.id == owner } } }
            service.switchLearnerProfile(owner); repo.insertConversation(stale); service.addConversationReference(stale.id); service.initializeConversation(stale.id)
            service.deleteConversation(stale); assertNull(repo.getConversationById(stale.id, owner))
            val registry = manager(service); val old = requireNotNull(registry.get(stale.id))
            assertTrue(old.deleted)
            service.removeConversationReference(stale.id)
            registry.removeIfIdle(old) // deterministic equivalent of the production idle callback
            assertNull(registry.get(stale.id))
            val saved = runCatching { service.saveConversation(stale.id, stale) }
            val revived = repo.getConversationById(stale.id, owner)
            evidence.file("result.txt").writeText("removedSession=true\nsaveSucceeded=${saved.isSuccess}\nrowRevived=${revived != null}\nmessageCount=${revived?.currentMessages?.size}\n")
            assertNull("Deleted row must not be reinserted by a stale save after eviction", revived)
            status = "PASS"
        } finally {
            repo.getConversationById(stale.id, owner)?.let { service.deleteConversation(it) }
            service.removeConversationReference(stale.id)
            evidence.finish(status); restoreMemoryProfileFixture(original, settings)
        }
    }

    @Test fun completeServiceFactoryContinuationUsesSyntheticProviderAndFirstDecision() = runBlocking {
        val koin = GlobalContext.get(); val settings = koin.get<SettingsStore>(); val service = koin.get<ChatService>(); val repo = koin.get<ConversationRepository>(); val skills = koin.get<SkillManager>(); val providers = koin.get<ProviderManager>()
        val original = withTimeout(20000) { settings.settingsFlow.first { !it.init } }
        val evidence = QaEvidenceRun("IndependentPostfixProbeTest.completeServiceFactoryContinuationUsesSyntheticProviderAndFirstDecision")
        val owner = settings.createLearnerProfile("QA continuation ${evidence.runId.take(8)}")
        val name = "qa-continuation-${evidence.runId}"
        val oldProvider = providers.getProvider("openai")
        val factoryField = ChatService::class.java.getDeclaredField("chatToolFactory").apply { isAccessible = true }
        val oldFactory = factoryField.get(service)
        val executions = java.util.concurrent.atomic.AtomicInteger()
        val countedFactory = me.rerere.rikkahub.data.ai.tools.ChatToolFactory(koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), decorateTool = { tool ->
            if(tool.name != "use_skill") tool else tool.copy(execute = { input -> executions.incrementAndGet(); tool.execute(input) })
        })
        factoryField.set(service, countedFactory)
        val ids = mutableListOf<Uuid>(); val report = StringBuilder(); var status = "FAILED"
        val model = Model(modelId = "qa-controlled-no-network")
        val assistant = Assistant(name = "QA fixture", chatModelId = model.id, streamOutput = false, localTools = emptyList(), enabledSkills = setOf(name), enableMemory = false)
        try {
            withTimeout(20000) { settings.settingsFlow.first { it.learnerProfiles.any { p -> p.id == owner } } }
            settings.setLearnerMemoryEnabled(owner, false)
            assertNotNull(skills.saveSkill(name, "---\nname: $name\ndescription: synthetic QA\n---\nQA_FACTORY_BODY"))
            settings.update(settings.settingsFlow.value.copy(providers = listOf(ProviderSetting.OpenAI(models = listOf(model), baseUrl = "http://127.0.0.1:1", apiKey = "synthetic")), assistants = listOf(assistant), assistantId = assistant.id, chatModelId = model.id, fastModelId = model.id, enableSuggestion = false))
            withTimeout(20000) { settings.settingsFlow.first { it.assistantId == assistant.id && it.providers.size == 1 } }
            service.switchLearnerProfile(owner)
            for (decision in listOf("approve", "deny", "answer")) {
                executions.set(0)
                val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val calls = java.util.concurrent.atomic.AtomicInteger()
                val fake = object : Provider<ProviderSetting.OpenAI> {
                    override suspend fun listModels(providerSetting: ProviderSetting.OpenAI) = listOf(model)
                    override suspend fun generateText(providerSetting: ProviderSetting.OpenAI, messages: List<UIMessage>, params: TextGenerationParams): TextGenerationResult {
                        calls.incrementAndGet(); entered.complete(Unit); release.await()
                        return TextGenerationResult("qa", "qa", UIMessage.assistant("synthetic completion"))
                    }
                    override suspend fun streamText(providerSetting: ProviderSetting.OpenAI, messages: List<UIMessage>, params: TextGenerationParams): Flow<StreamChunk> = error("Unexpected stream: no network allowed")
                }
                providers.registerProvider("openai", fake)
                val pending = UIMessagePart.Tool("qa-target", "use_skill", "{\"name\":\"$name\"}", approvalState = ToolApprovalState.Pending)
                val c = Conversation.ofId(Uuid.random(), assistant.id).copy(profileId = owner, title = "QA_${evidence.runId}_$decision", messageNodes = listOf(UIMessage.user("synthetic request").toMessageNode(), UIMessage(role = MessageRole.ASSISTANT, parts = listOf(pending)).toMessageNode()))
                ids += c.id; repo.insertConversation(c); service.addConversationReference(c.id); service.initializeConversation(c.id)
                val sibling = Conversation.ofId(Uuid.random(), assistant.id).copy(profileId = owner, title = "QA_${evidence.runId}_sibling_$decision", messageNodes = listOf(UIMessage.user("other task").toMessageNode()))
                ids += sibling.id; repo.insertConversation(sibling); service.addConversationReference(sibling.id); service.initializeConversation(sibling.id)
                val siblingJob = launch { awaitCancellation() }; manager(service).get(sibling.id)!!.setJob(siblingJob)
                try {
                    service.handleToolApproval(c.id, "qa-target", decision != "deny", "QA deny", if (decision == "answer") "QA answer" else null)
                    withTimeout(20000) { entered.await() }
                    // Provider gate keeps the continuation active while duplicate/conflicting callbacks arrive.
                    val activeJob = manager(service).get(c.id)!!.getJob()
                    repeat(3) { service.handleToolApproval(c.id, "qa-target", decision != "deny", "QA deny", if (decision == "answer") "QA answer" else null) }
                    service.handleToolApproval(c.id, "qa-target", decision == "deny", "QA conflict")
                    assertSame(activeJob, manager(service).get(c.id)!!.getJob()); assertSame(siblingJob, manager(service).get(sibling.id)!!.getJob()); assertTrue(siblingJob.isActive)
                    val accepted = repo.getConversationById(c.id, owner)!!.currentMessages.flatMap { it.getTools() }.single()
                    assertEquals(when(decision) { "approve" -> ToolApprovalState.Approved; "deny" -> ToolApprovalState.Denied("QA deny"); else -> ToolApprovalState.Answered("QA answer") }, accepted.approvalState)
                    assertTrue(accepted.isExecuted)
                    assertEquals(decision == "approve", accepted.output.filterIsInstance<UIMessagePart.Text>().any { it.text.contains("QA_FACTORY_BODY") })
                    release.complete(Unit); withTimeout(20000) { activeJob?.join() }
                    assertEquals("COMPLETED", repo.getConversationById(c.id, owner)!!.currentMessages.last().generationState)
                    service.handleToolApproval(c.id, "qa-target", false, "QA late")
                    assertEquals(1, calls.get()); assertSame(siblingJob, manager(service).get(sibling.id)!!.getJob())
                    assertEquals(if(decision == "approve") 1 else 0, executions.get())
                    report.append("$decision firstDecisionPreserved=true factoryToolExecutions=${executions.get()} syntheticProviderCalls=${calls.get()} siblingJobPreserved=true\n")
                } finally { release.complete(Unit); siblingJob.cancelAndJoin(); manager(service).get(sibling.id)?.setJob(null) }
            }
            status = "PASS"
        } finally {
            evidence.file("continuation.txt").writeText(report.toString()); evidence.finish(status)
            ids.forEach { id -> repo.getConversationById(id, owner)?.let { service.deleteConversation(it) }; service.removeConversationReference(id) }
            providers.registerProvider("openai", oldProvider); skills.deleteSkill(name); restoreMemoryProfileFixture(original, settings)
            factoryField.set(service, oldFactory)
        }
    }
}

