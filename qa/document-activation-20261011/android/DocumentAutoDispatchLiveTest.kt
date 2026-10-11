package me.rerere.rikkahub.service

import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.material.*
import me.rerere.rikkahub.flutter.StudyHostFacade
import me.rerere.rikkahub.flutter.generated.SendMessageRequest
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File
import kotlin.uuid.Uuid

/** Explicit opt-in synthetic live test; model credentials never enter evidence. */
class DocumentAutoDispatchLiveTest {
    @Test fun naturalScanRequestChoosesServiceAndContinuesWithoutAnotherUserTurn() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val privateModel = File(context.cacheDir, "document-auto-model.json")
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsStore>(); val service = koin.get<ChatService>()
        val conversations = koin.get<ConversationRepository>(); val files = koin.get<MaterialFiles>()
        val docs = koin.get<DocumentServiceRepository>()
        val before = settings.settingsFlow.first { !it.init }; val owner = before.activeProfileId
        val providers = JsonInstant.decodeFromString<List<ProviderSetting>>(privateModel.readText())
        privateModel.delete()
        val model = providers.flatMap { it.models }.single { it.modelId == "gpt-6.1-sol" }
        val assistant = before.getCurrentAssistant().copy(id = Uuid.random(), name = "自动文档调度实测", chatModelId = model.id, enableMemory = false, localTools = emptyList(), enabledSkills = emptySet())
        var facade: StudyHostFacade? = null; var conversationId: Uuid? = null
        try {
            settings.update { it.copy(providers = providers, chatModelId = model.id, assistants = it.assistants + assistant, assistantId = assistant.id, enableSuggestion = false) }
            settings.settingsFlow.first { it.assistantId == assistant.id }
            assertTrue(docs.enabled(owner))
            val input = files.import(owner, File(context.cacheDir, "document-activation-synthetic.pdf").toUri(), "自动调度合成扫描讲义.pdf")
            val content = files.readContent(owner, input.id)
            assertEquals(5, content.totalPages)
            assertEquals((1..5).toList(), content.pagesWithoutExtractableText)
            facade = StudyHostFacade(context, settings, conversations, service, koin.get(), files, koin.get(), pickFiles = { emptyList() })
            val epoch = facade.getBootstrap(owner, 1).contextEpoch
            val result = facade.sendMessage(SendMessageRequest(Uuid.random().toString(), owner, epoch,
                "请逐页提取这份五页扫描讲义的文字、公式和表格，按原物理页整理。需要结构化识别，公式保持数学含义，空白页说明为空白，不要凭空补内容。请直接完成，不需要生成文件。", "CHAT", attachmentPaths = listOf(files.file(input).absolutePath)))
            assertTrue(result.success)
            conversationId = Uuid.parse(result.idResult!!)
            assertTrue(facade.watchConversation(owner, conversationId.toString(), epoch).success)
            val completed = withTimeout(420000) {
                while (true) {
                    val current = service.getConversationFlow(conversationId).value
                    val toolParts = current.currentMessages.flatMap { it.parts }.filterIsInstance<UIMessagePart.Tool>()
                    if (toolParts.any { it.toolName == "read_material_parse_blocks" && it.executionState == ToolExecutionState.COMPLETED }
                        && current.currentMessages.lastOrNull()?.generationState == "COMPLETED"
                        && service.getGenerationJobStateFlow(conversationId).first() == null) return@withTimeout current
                    val failure = service.errors.value.lastOrNull { it.conversationId == conversationId }
                    check(failure == null) {
                        var detail = failure?.error?.message.orEmpty()
                        providers.filterIsInstance<ProviderSetting.OpenAI>().forEach { if (it.apiKey.isNotBlank()) detail = detail.replace(it.apiKey, "[redacted]") }
                        "Real chat generation failed: ${failure?.error?.javaClass?.simpleName}: ${detail.take(400)}"
                    }
                    delay(2000)
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
            val toolParts = completed.currentMessages.flatMap { it.parts }.filterIsInstance<UIMessagePart.Tool>()
            assertTrue(toolParts.any { it.toolName == "parse_material_enhanced" && it.executionState == ToolExecutionState.COMPLETED })
            val tasks = docs.tasks(owner).filter { it.sourceId == input.id && it.downloaded }
            assertEquals((1..5).toList(), tasks.flatMap { docs.read(owner, it.id).processedPages }.distinct().sorted())
            val listing = docs.availableResults(owner, input.id, 0, 20)
            assertTrue(listing.getValue("results").jsonArray.any { it.jsonObject.getValue("downloaded").jsonPrimitive.boolean })
            File(context.cacheDir, "document-auto-chat-evidence.json").writeText(buildJsonObject {
                put("realModel", model.modelId); put("naturalRequest", true); put("modelChoseParse", true)
                put("automaticContinuation", true); put("cachedResultDiscoverable", true)
                put("pages", 5); put("toolNames", JsonArray(toolParts.map { JsonPrimitive(it.toolName) }))
                put("answer", completed.currentMessages.last().toText())
            }.toString())
        } finally {
            privateModel.delete(); facade?.close()
            conversationId?.let { id -> service.stopGeneration(id); conversations.getConversationById(id, owner)?.let { service.deleteConversation(it) } }
            settings.update(before)
        }
    }
}
