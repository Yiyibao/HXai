package me.rerere.rikkahub.service

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.material.*
import me.rerere.rikkahub.feature.interactive.InteractiveSurface
import me.rerere.ai.ui.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File
import kotlin.uuid.Uuid

/** Uses the already configured model only; no credentials are printed or copied into evidence. */
class CoreConfiguredModelTest {
    @Test fun configuredModelProducesVerifiedFileAndInteractiveTool() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveCore") == "true")
        val koin = GlobalContext.get(); val settings = koin.get<SettingsStore>(); val service = koin.get<ChatService>(); val repository = koin.get<ConversationRepository>(); val materials = koin.get<MaterialFiles>()
        val original = settings.settingsFlow.first { !it.init }; val model = original.getCurrentChatModel() ?: error("请先配置模型")
        val id = Uuid.random(); val marker = "QA-core-${id.toString().take(8)}"
        val assistant = original.getCurrentAssistant().copy(id = Uuid.random(), name = marker, systemPrompt = me.rerere.rikkahub.feature.studychat.StudyTutorDefaults.systemPrompt, localTools = emptyList(), enabledSkills = emptySet(), enableMemory = false)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val started = System.currentTimeMillis()
        try {
            settings.update(original.copy(assistants = original.assistants + assistant, assistantId = assistant.id, enableSuggestion = false))
            settings.settingsFlow.first { it.assistantId == assistant.id }
            service.addConversationReference(id); service.initializeConversation(id)
            service.saveConversation(id, Conversation(id = id, assistantId = assistant.id, title = marker, profileId = original.activeProfileId, messageNodes = emptyList(), newConversation = true))
            val prompt = "这是合成验收任务，不涉及本人学习表现。请用实际工具创建Word文件，标题为$marker，正文只包含Synthetic core file和预算验证两段；然后用render_interactive_ui展示人数1到10、默认2的滑块及每人100加固定20的实时预算计算，附继续分析按钮。两项都要真实工具成功结果。无需调用其他工具，无需联网检索。最后一句话确认交付，不超过40字。"
            service.sendMessageCommitted(id, listOf(UIMessagePart.Text(prompt)), Uuid.random())
            withTimeout(180_000) {
                service.getConversationFlow(id).first { it.currentMessages.size > 1 && it.currentMessages.last().generationState in setOf("COMPLETED", "FAILED", "INTERRUPTED") }
                while (service.getGenerationJobStateFlow(id).first() != null) delay(100)
            }
            val conversation = repository.getConversationById(id)!!
            assertEquals("COMPLETED", conversation.currentMessages.last().generationState)
            val tools = conversation.currentMessages.flatMap { it.parts }.filterIsInstance<UIMessagePart.Tool>()
            assertTrue(tools.any { it.toolName == "create_material_file" && it.executionState == ToolExecutionState.COMPLETED })
            val surface = tools.last { it.toolName == InteractiveSurface.TOOL && it.executionState == ToolExecutionState.COMPLETED }
            val spec = kotlinx.serialization.json.Json.parseToJsonElement(surface.input).let { (it as kotlinx.serialization.json.JsonObject).getValue("definition") as kotlinx.serialization.json.JsonPrimitive }.content
            val surfaceDefinition = InteractiveSurface.validate(spec)
            val components = (surfaceDefinition.getValue("components") as kotlinx.serialization.json.JsonArray).map { it as kotlinx.serialization.json.JsonObject }
            val types = components.map { (it.getValue("type") as kotlinx.serialization.json.JsonPrimitive).content }.toSet()
            assertTrue(types.containsAll(setOf("slider", "calculation", "button")))
            val document = tools.flatMap { it.output }.filterIsInstance<UIMessagePart.Document>().first { it.fileName.endsWith(".docx") }
            val artifact = materials.list(original.activeProfileId).single { android.net.Uri.fromFile(materials.file(it)).toString() == document.url }
            assertTrue(MaterialReader.read(materials.verifiedFile(original.activeProfileId, artifact.id), "docx").text.contains("Synthetic core file"))
            File(context.cacheDir, "core-v1-live-model.json").writeText(org.json.JSONObject().put("model", model.modelId).put("ui_tool_completed", true).put("ui_definition", org.json.JSONObject(spec)).put("docx_completed", true).put("artifact_sha256", artifact.sha256).put("elapsed_ms", System.currentTimeMillis() - started).toString())
        } finally {
            service.stopGeneration(id)
            repository.getConversationById(id)?.let { service.deleteConversation(it) }
            service.removeConversationReference(id)
            materials.list(original.activeProfileId).filter { it.output && it.createdAt >= started && it.name.startsWith(marker) }.forEach { materials.file(it).delete(); File(context.filesDir, "materials/${it.profileId}/${it.id}.meta.json").delete() }
            settings.update(original)
        }
    }
}
