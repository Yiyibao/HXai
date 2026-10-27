package me.rerere.rikkahub.feature.material

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.agent.LocalModelServer
import me.rerere.rikkahub.flutter.StudyHostFacade
import me.rerere.rikkahub.flutter.generated.SendMessageRequest
import me.rerere.rikkahub.service.ChatService
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File
import kotlin.uuid.Uuid

class MaterialReadChatRuntimeTest {
    @Test fun twoRealChatSessionsReceiveActualLibraryVersionNotForgedDiskExtraction(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "org.hxsi.agent.nativeqa")
        val k = GlobalContext.get(); val settings = k.get<SettingsStore>(); val original = settings.settingsFlow.first { !it.init }
        val owner = original.activeProfileId; val store = k.get<MaterialFiles>()
        val body = "SYNTHETIC ACTUAL LIBRARY SOURCE\nThe original equation is x², not x³."
        val item = store.runTask(MaterialFile(Uuid.random().toString(), owner, "同一教材跨会话.md", "md")) {
            it.writeText(body); "Synthetic long-lived library source"
        }
        val diskCache = File(context.filesDir, "materials/$owner/${item.id}.read-v6.cache")
        diskCache.writeText(Json.encodeToString(MaterialContent("FORGED_OLD_EXTRACT_SENTINEL", "All 999 pages completely recognized")))
        val server = LocalModelServer { LocalModelServer.completion("Synthetic reply; no independent math or full document verification claimed.") }
        val model = Model(modelId = "qa-library-read")
        val provider = ProviderSetting.OpenAI(name = "QA local library", baseUrl = server.baseUrl, apiKey = "synthetic", models = listOf(model))
        val assistant = Assistant(name = "QA library", chatModelId = model.id, reasoningLevel = ReasoningLevel.OFF, streamOutput = false, localTools = emptyList())
        val service = k.get<ChatService>(); val repo = k.get<ConversationRepository>(); val ids = mutableListOf<Uuid>()
        var host: StudyHostFacade? = null
        try {
            settings.update(original.copy(providers = listOf(provider), assistants = original.assistants + assistant, assistantId = assistant.id,
                chatModelId = model.id, fastModelId = model.id, enableSuggestion = false,
                learnerProfiles = original.learnerProfiles.map { it.copy(memoryEnabled = false) },
                networkSetting = original.networkSetting.copy(proxyUrl = "", enableAutoRetry = false)))
            settings.settingsFlow.first { it.assistantId == assistant.id }
            val facade = StudyHostFacade(context, settings, repo, service, k.get(), store, k.get(), pickFiles = { emptyList() })
            host = facade
            val epoch = facade.getBootstrap(owner, 1).contextEpoch
            for (number in 1..2) {
                val result = facade.sendMessage(SendMessageRequest(Uuid.random().toString(), owner, epoch,
                    "Local attachment question $number: what text does this document contain? Do not upload or use any external service.", "CHAT",
                    attachmentPaths = listOf(store.verifiedFile(owner, item.id).absolutePath)))
                assertTrue(result.success)
                val id = Uuid.parse(requireNotNull(result.idResult)); ids += id
                withTimeout(45_000) {
                    service.getConversationFlow(id).first { it.currentMessages.lastOrNull()?.generationState == "COMPLETED" }
                }
                withTimeout(10_000) { service.getGenerationJobStateFlow(id).first { it == null } }
            }
            val captured = server.bodies.map { Json.parseToJsonElement(it).jsonObject }
            val evidence = File(context.cacheDir, "b04a-library-chat-evidence").apply { mkdirs() }
            store.verifiedFile(owner, item.id).copyTo(File(evidence, "source.md"), overwrite = true)
            File(evidence, "actual-provider-requests.json").writeText(JsonArray(captured).toString())
            File(evidence, "result.json").writeText(buildJsonObject {
                put("owner", owner); put("id", item.id); put("source_sha256", item.sha256)
                put("conversation_ids", JsonArray(ids.map { JsonPrimitive(it.toString()) })); put("expected_body", body)
            }.toString())
            for (number in 1..2) {
                val request = captured.first { entry ->
                    entry.getValue("messages").jsonArray.any { message ->
                        val fields = message.jsonObject
                        fields["role"]?.jsonPrimitive?.content == "user" && fields["content"] is JsonArray &&
                            fields["content"].toString().contains("Local attachment question $number:")
                    }
                }
                val content = request.getValue("messages").toString()
                assertTrue(content.contains("SYNTHETIC ACTUAL LIBRARY SOURCE"))
                assertTrue(content.contains("The original equation is x², not x³."))
                assertFalse(content.contains("FORGED_OLD_EXTRACT_SENTINEL"))
                assertFalse(content.contains("All 999 pages completely recognized"))
                assertEquals(model.modelId, request.getValue("model").jsonPrimitive.content)
            }
            assertFalse(captured.toString().contains("FORGED_OLD_EXTRACT_SENTINEL"))
            assertEquals(item.sha256, MaterialFiles.digest(store.verifiedFile(owner, item.id)))
        } finally {
            host?.close()
            for (id in ids) {
                service.stopGeneration(id)
                repo.getConversationById(id, owner)?.let { service.deleteConversation(it) }
                service.removeConversationReference(id)
            }
            me.rerere.rikkahub.service.restoreMemoryProfileFixture(original, settings)
            server.close()
            store.file(item).delete(); File(context.filesDir, "materials/$owner/${item.id}.meta.json").delete(); diskCache.delete()
        }
    }
}
