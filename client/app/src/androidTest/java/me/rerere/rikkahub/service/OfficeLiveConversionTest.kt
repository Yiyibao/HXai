package me.rerere.rikkahub.service

import android.util.AtomicFile
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.model.Conversation
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

/** Actual model -> deployed LibreOffice -> verified PDF cards, using only synthetic fixtures. */
class OfficeLiveConversionTest {
    @Test fun threeOfficeAttachmentsConvertAndDeliverAutomatically(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val koin = GlobalContext.get(); val settings = koin.get<SettingsStore>(); val service = koin.get<ChatService>(); val repository = koin.get<ConversationRepository>(); val files = koin.get<MaterialFiles>(); val docs = koin.get<DocumentServiceRepository>()
        val marker = File(context.noBackupFilesDir, "office-test-restore.json")
        val vault = CredentialVault(context, "office-test-fixture")
        if (marker.exists()) {
            val saved = Json.parseToJsonElement(marker.readText()).jsonObject
            restoreMemoryProfileFixture(JsonInstant.decodeFromString<Settings>(vault.resolve(saved.getValue("reference").jsonPrimitive.content)), settings)
            marker.delete()
        }
        val original = settings.settingsFlow.first { !it.init }; assertNotNull(original.getCurrentChatModel())
        val reference = vault.protect(JsonInstant.encodeToString(Settings.serializer(), original))
        val atomic = AtomicFile(marker); val snapshot = atomic.startWrite()
        snapshot.write(buildJsonObject { put("reference", reference) }.toString().toByteArray()); atomic.finishWrite(snapshot)
        val owner = settings.createLearnerProfile("Office conversion QA"); val id = Uuid.random()
        val assistant = original.getCurrentAssistant().copy(id = Uuid.random(), name = "Office conversion QA", systemPrompt = me.rerere.rikkahub.feature.studychat.StudyTutorDefaults.systemPrompt, localTools = emptyList(), enabledSkills = emptySet(), enableMemory = false)
        val evidence = File(context.cacheDir, "office-live-output").apply { mkdirs() }
        var facade: StudyHostFacade? = null
        try {
            settings.update(original.copy(assistants = original.assistants + assistant, assistantId = assistant.id, enableSuggestion = false))
            withTimeout(20_000) { settings.selectLearnerProfile(owner) }
            val credential = Json.parseToJsonElement(File(context.cacheDir, "office-service-test-credentials.json").readText()).jsonObject
            docs.configure(owner, credential.getValue("baseUrl").jsonPrimitive.content, credential.getValue("token").jsonPrimitive.content, true)
            val sources = listOf("docx", "xlsx", "pptx").map { extension -> files.import(owner, File(context.cacheDir, "p0-rich-output/rich-output.$extension").toUri(), "rich-edited.$extension") }
            service.addConversationReference(id); service.initializeConversation(id)
            service.saveConversation(id, Conversation(id = id, assistantId = assistant.id, profileId = owner, title = "Office real conversion QA", messageNodes = emptyList(), newConversation = true))
            facade = StudyHostFacade(context, settings, repository, service, koin.get(), files, koin.get(), pickFiles = { emptyList() }, documents = docs)
            val prompt = "请把附件的Word、Excel和PPT分别转换为PDF，保留原文件排版、图片、表格和公式，三个原文件各交付一个PDF。Excel必须计算当前公式结果。不需要重新编写或总结内容，也不要用文字副本替代。请直接完成，遇到兼容性或字体替换时如实说明。"
            assertTrue(facade.sendMessage(SendMessageRequest("office-live-$id", owner, 1, prompt, "CHAT", id.toString(), attachmentPaths = sources.map { files.verifiedFile(owner, it.id).absolutePath })).success)
            assertTrue(facade.watchConversation(owner, id.toString(), 1).success)
            withTimeout(600_000) {
                while (docs.officeConversions.tasks(owner).count { it.downloaded } != 3) {
                    repository.getConversationById(id)?.let { File(evidence, "conversation.json").writeText(JsonInstant.encodeToString(Conversation.serializer(), it)) }
                    File(evidence, "tasks.json").writeText(JsonInstant.encodeToString(docs.officeConversions.tasks(owner)))
                    delay(1000)
                }
            }
            val tasks = docs.officeConversions.tasks(owner)
            assertEquals(3, tasks.size); assertEquals(sources.map { it.id }.toSet(), tasks.map { it.sourceId }.toSet())
            tasks.forEach { task ->
                val item = docs.officeConversions.artifact(owner, task.id)
                files.verifiedFile(owner, item.id).copyTo(File(evidence, "${task.extension}.pdf"), overwrite = true)
                assertEquals(task.sourceSha256, MaterialFiles.digest(files.verifiedFile(owner, task.sourceId)))
            }
            withTimeout(180_000) { while (service.getGenerationJobStateFlow(id).first() != null) delay(200) }
            val detail = facade.getConversationDetails(owner, id.toString())
            assertTrue(detail.nodes.flatMap { it.branches }.flatMap { it.parts }.count { it.type == "file" && it.artifactId != null } >= 3)
            val before = repository.getConversationById(id)!!.currentMessages.size
            facade.watchConversation(owner, id.toString(), 1); delay(12_000)
            assertEquals("Reopening must not send duplicate continuations", before, repository.getConversationById(id)!!.currentMessages.size)
            File(evidence, "conversation.json").writeText(JsonInstant.encodeToString(Conversation.serializer(), repository.getConversationById(id)!!))
            File(evidence, "tasks.json").writeText(JsonInstant.encodeToString(docs.officeConversions.tasks(owner)))
            File(evidence, "result.json").writeText(buildJsonObject { put("realModel", original.getCurrentChatModel()!!.modelId); put("realRenderer", true); put("outputs", 3); put("originalsUnchanged", true); put("automaticDelivery", true); put("noDuplicateContinuationOnReopen", true); put("syntheticOnly", true) }.toString())
        } finally {
            facade?.close(); service.stopGeneration(id)
            docs.officeConversions.tasks(owner).forEach { task -> runCatching { if (!task.downloaded) docs.officeConversions.cancel(owner, task.id) }; runCatching { docs.officeConversions.cleanupRemote(owner, task.id) } }
            repository.getConversationById(id, owner)?.let { service.deleteConversation(it) }; service.removeConversationReference(id)
            File(context.filesDir, "materials/$owner").deleteRecursively(); File(context.noBackupFilesDir, "document-service/$owner").deleteRecursively()
            restoreMemoryProfileFixture(original, settings); marker.delete()
            val recordId = reference.removePrefix("credential-v1:"); require(java.util.UUID.fromString(recordId).toString() == recordId)
            File(context.noBackupFilesDir, "credentials-v1/$recordId.bin").delete()
        }
    }
}
