package me.rerere.rikkahub.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.agent.LocalModelServer
import me.rerere.rikkahub.feature.agent.ModelRequestRunner
import me.rerere.rikkahub.feature.memory.LearningMemoryStore
import me.rerere.rikkahub.feature.studychat.ChatContextBudget
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

/** Real public Chat action, scheduler, loopback provider and Room readback. Synthetic data only. */
@RunWith(AndroidJUnit4::class)
class TranslationSourceSuppressionTest {
    @Test fun deletedUserCannotBeTranslated() = scenario("deleted-user")
    @Test fun answerFromDeletedTurnCannotBeTranslated() = scenario("deleted-answer")
    @Test fun oldSummaryCannotBypassSourceDeletion() = scenario("summary")
    @Test fun callerCannotSubstituteMessageBody() = scenario("forged")
    @Test fun deletionDuringRequestRejectsLateTranslation() = scenario("late-delete")
    @Test fun healthyTranslationPersistsAndPreservesOriginal() = scenario("healthy")
    @Test fun freshExplicitQuoteRemainsAllowed() = scenario("fresh")
    @Test fun legacyUnmappedSourceBlocksTranslation() = scenario("legacy")
    @Test fun unselectedBranchCannotBeTranslated() = scenario("unselected")
    @Test fun otherOwnerCannotBeTranslated() = scenario("other-owner")
    @Test fun editDuringRequestKeepsNewBodyAndOldTranslation() = scenario("late-edit")
    @Test fun branchChangeDuringRequestRejectsLateTranslation() = scenario("late-branch")
    @Test fun profileChangeDuringRequestRejectsLateTranslation() = scenario("late-profile")
    @Test fun deletedConversationCannotBeRecreatedByTranslation() = scenario("late-conversation-delete")
    @Test fun concurrentMetadataIsPreserved() = scenario("late-metadata")
    @Test fun healthyStreamingTranslationPersists() = scenario("stream")
    @Test fun deletedSourceRejectsStreamingResult() = scenario("stream-delete")
    @Test fun deletionWhileQueuedPreventsPhysicalDispatch() = scenario("queued-delete")

    private fun scenario(mode: String): Unit = runBlocking {
        val k = GlobalContext.get()
        val settings = k.get<SettingsStore>()
        val original = withTimeout(20_000) { settings.settingsFlow.first { !it.init } }
        val owner = original.activeProfileId
        val memory = k.get<LearningMemoryStore>()
        val previous = memory.read(owner)
        val old = UIMessage.user("TRANSLATE_OLD_SOURCE_SENTINEL").copy(translation = "EXISTING")
        val answer = UIMessage.assistant("TRANSLATE_OLD_ANSWER_SENTINEL").copy(translation = "EXISTING")
        val summary = ChatContextBudget.summaryMessage("TRANSLATE_OLD_SUMMARY_SENTINEL").copy(isSynthetic = false, translation = "EXISTING")
        val fresh = UIMessage.user("NEW_EXPLICIT_QUOTE ${old.toText()}").copy(translation = "EXISTING")
        val messages = listOf(old, answer, summary, fresh)
        val target = when (mode) { "deleted-answer" -> answer; "summary" -> summary; "fresh" -> fresh; else -> old }
        val supplied = if (mode == "forged") target.copy(parts = UIMessage.user("FORGED_BODY_SENTINEL").parts) else target
        val marker = memory.fingerprint(owner, "message:${old.id}")
        val protected = mode in setOf("deleted-user", "deleted-answer", "summary", "fresh")
        val service = k.get<ChatService>()
        val repo = k.get<ConversationRepository>()
        val id = Uuid.random()
        val blockerEntered = CompletableDeferred<Unit>()
        val releaseBlocker = CompletableDeferred<Unit>()
        var blocker: Deferred<Unit>? = null
        val foreign = if (mode in setOf("other-owner", "late-profile")) settings.createLearnerProfile("QA translation") else owner
        if (foreign != owner) withTimeout(10_000) { settings.settingsFlow.first { it.learnerProfiles.any { profile -> profile.id == foreign } } }
        val stream = mode.startsWith("stream")
        val responseFinished = CountDownLatch(1)
        val server = LocalModelServer(allowClientDisconnect = mode == "late-conversation-delete",
            responseContentType = if (stream) "text/event-stream" else "application/json") {
            try {
            if (mode in setOf("late-delete", "stream-delete")) runBlocking {
                memory.change(owner) { it.copy(suppressedSources = it.suppressedSources + marker) to Unit }
            }
            if (mode.startsWith("late-") && mode != "late-delete") runBlocking {
                val current = service.getConversationFlow(id).value
                when (mode) {
                    "late-edit" -> service.saveConversation(id, current.copy(messageNodes = current.messageNodes.map { node ->
                        node.copy(messages = node.messages.map { if (it.id == target.id) it.copy(parts = UIMessage.user("NEW_EDITED_BODY").parts) else it })
                    }))
                    "late-branch" -> service.saveConversation(id, current.copy(messageNodes = current.messageNodes.mapIndexed { i, node ->
                        if (i == 0) node.copy(messages = node.messages + UIMessage.user("NEW_BRANCH"), selectIndex = 1) else node
                    }))
                    "late-profile" -> settings.selectLearnerProfile(foreign)
                    "late-conversation-delete" -> service.deleteConversation(current)
                    "late-metadata" -> service.saveConversation(id, current.copy(title = "newer title"))
                }
            }
            if (stream) "data: {\"id\":\"qa\",\"model\":\"qa-stream\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"TRANSLATED_RESULT\"},\"finish_reason\":null}]}\n\n" +
                "data: {\"id\":\"qa\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
            else LocalModelServer.completion("TRANSLATED_RESULT")
            } finally { responseFinished.countDown() }
        }
        val model = Model(modelId = if (stream) "qa-stream" else "qwen-mt-qa")
        val provider = ProviderSetting.OpenAI(baseUrl = server.baseUrl, apiKey = "synthetic", models = listOf(model))
        val assistant = Assistant(chatModelId = model.id, streamOutput = false, localTools = emptyList(), enableWebSearch = false)
        try {
            settings.update(original.copy(providers = listOf(provider), assistants = original.assistants + assistant,
                assistantId = assistant.id, chatModelId = model.id, translateModeId = model.id,
                networkSetting = original.networkSetting.copy(proxyUrl = "", enableAutoRetry = false)))
            settings.settingsFlow.first { it.assistantId == assistant.id }
            service.addConversationReference(id)
            service.saveConversation(id, Conversation.ofId(id, assistant.id).copy(profileId = owner,
                title = "preserve title", newConversation = true, messageNodes = messages.mapIndexed { i, msg ->
                    if (mode == "unselected" && i == 0) msg.toMessageNode().copy(messages = listOf(msg, UIMessage.user("SELECTED_BRANCH")), selectIndex = 1)
                    else msg.toMessageNode()
                }))
            memory.change(owner) { previous.copy(fingerprintVersion = 1, sources = emptyList(), recoveryBlocked = false,
                legacySuppressedSources = emptySet(), suppressedSources = if (protected) setOf(marker) else emptySet()) to Unit }
            if (mode == "legacy") memory.change(owner) {
                it.copy(schemaVersion = 2, recoveryBlocked = true, legacySuppressedSources = setOf("UNMAPPED_LEGACY_MARKER")) to Unit
            }
            if (mode == "other-owner") settings.selectLearnerProfile(foreign)
            val before = requireNotNull(repo.getConversationById(id, profileId = owner))
            if (mode == "queued-delete") {
                val requests = k.get<ModelRequestRunner>()
                val accepted = requests.accept(settings.settingsFlow.value, "translation", "synthetic slot holder")
                blocker = async(Dispatchers.IO) { requests.run(accepted) { _, _, _ ->
                    blockerEntered.complete(Unit)
                    releaseBlocker.await()
                } }
                withTimeout(10_000) { blockerEntered.await() }
            }
            // The original public method returned Unit; support that exact old implementation for the negative control.
            val action: Any = service.translateMessage(id, supplied, Locale.ENGLISH)
            if (mode == "queued-delete") {
                val scheduler = k.get<ModelRequestRunner>().scheduler
                withTimeout(10_000) { while (scheduler.readTaskCatalog(owner, "auxiliary", null, 100).tasks.none {
                    it.conversationId == id.toString() && it.role == "translation" && it.state == "QUEUED"
                }) delay(25) }
                memory.change(owner) { it.copy(suppressedSources = it.suppressedSources + marker) to Unit }
                releaseBlocker.complete(Unit)
                withTimeout(10_000) { blocker!!.await() }
            }
            if (action is Job) withTimeout(15_000) { action.join() } else delay(2_000)
            val expectedRequests = if (protected && mode != "fresh" || mode in setOf("forged", "legacy", "unselected", "other-owner", "queued-delete")) 0 else 1
            assertEquals("Unexpected physical translation dispatch for $mode", expectedRequests, server.bodies.size)
            if (expectedRequests == 1) assertTrue("The loopback mutation must finish before readback", responseFinished.await(10, TimeUnit.SECONDS))
            if (mode in setOf("other-owner", "late-profile")) settings.selectLearnerProfile(owner)
            if (mode == "late-conversation-delete") {
                assertNull(repo.getConversationById(id))
                return@runBlocking
            }
            val after = requireNotNull(repo.getConversationById(id))
            val publish = mode in setOf("healthy", "fresh", "late-metadata", "stream")
            assertEquals(if (publish) "TRANSLATED_RESULT" else "EXISTING", after.messageNodes.flatMap { it.messages }.single { it.id == target.id }.translation)
            assertEquals(after.messageNodes, service.getConversationFlow(id).value.messageNodes)
            assertEquals(if (mode == "late-metadata") "newer title" else before.title, after.title)
            if (mode !in setOf("late-edit", "late-branch")) assertEquals(before.currentMessages.map { it.parts }, after.currentMessages.map { it.parts })
            if (mode == "late-edit") assertEquals("NEW_EDITED_BODY", after.currentMessages.first().toText())
            if (mode == "late-branch") assertEquals("NEW_BRANCH", after.currentMessages.first().toText())
            assertEquals(before.messageNodes.map { it.id }, after.messageNodes.map { it.id })
            assertEquals(if (protected || mode in setOf("late-delete", "stream-delete", "queued-delete")) setOf(marker) else emptySet<String>(), memory.read(owner).suppressedSources)
        } finally {
            releaseBlocker.complete(Unit)
            blocker?.cancelAndJoin()
            settings.selectLearnerProfile(owner)
            repo.getConversationById(id)?.let { service.deleteConversation(it) }
            service.removeConversationReference(id)
            memory.change(owner) { previous to Unit }
            settings.update(original); settings.settingsFlow.first { it.assistantId == original.assistantId }
            server.close()
        }
    }
}
