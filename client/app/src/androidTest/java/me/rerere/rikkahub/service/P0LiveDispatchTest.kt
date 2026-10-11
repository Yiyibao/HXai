package me.rerere.rikkahub.service

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
import me.rerere.rikkahub.feature.studychat.LearnerProfile
import me.rerere.rikkahub.flutter.StudyHostFacade
import me.rerere.rikkahub.flutter.generated.SendMessageRequest
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File
import kotlin.uuid.Uuid

/** Explicit live test: synthetic raster PDF -> production MinerU -> configured model -> real XLSX. */
class P0LiveDispatchTest {
    @Test fun readablePdfAttachmentOnlyAndLocalRestrictionDoNotUpload() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val secrets = Json.parseToJsonElement(File(context.cacheDir, "document-service-test-credentials.json").readText()).jsonObject
        val koin = GlobalContext.get(); val settings = koin.get<SettingsStore>(); val service = koin.get<ChatService>(); val repository = koin.get<ConversationRepository>(); val files = koin.get<MaterialFiles>()
        val original = settings.settingsFlow.first { !it.init }; assertNotNull(original.getCurrentChatModel())
        val owner = settings.createLearnerProfile("P0 routing"); val ids = mutableListOf<Uuid>(); val assistant = original.getCurrentAssistant().copy(id = Uuid.random(), name = "P0 routing", localTools = emptyList(), enabledSkills = emptySet(), enableMemory = false)
        val docs = DocumentServiceRepository(context, files); var facade: StudyHostFacade? = null
        val evidence = File(context.cacheDir, "p0-routing").apply { mkdirs() }
        try {
            settings.update(original.copy(activeProfileId = owner, learnerProfiles = original.learnerProfiles + LearnerProfile(owner, "P0 routing"), assistants = original.assistants + assistant, assistantId = assistant.id, enableSuggestion = false))
            withTimeout(20_000) { settings.selectLearnerProfile(owner) }
            docs.configure(owner, secrets.getValue("baseUrl").jsonPrimitive.content, secrets.getValue("token").jsonPrimitive.content, true)
            facade = StudyHostFacade(context, settings, repository, service, koin.get(), files, koin.get(), pickFiles = { emptyList() }, documents = docs)
            val readable = files.runTask(MaterialFile(Uuid.random().toString(), owner, "readable.pdf", "pdf")) { file -> MaterialWriter.write(file, "pdf", "Synthetic inventory: amount is 21. No images or missing pages.", emptyList(), emptyList()) }
            val image = files.import(owner, File(context.cacheDir, "p0-rich-input/synthetic.png").toUri(), "synthetic.png")
            suspend fun turn(prompt: String, file: MaterialFile?, label: String): String {
                val id = Uuid.random(); ids += id; service.addConversationReference(id); service.initializeConversation(id)
                service.saveConversation(id, Conversation(id = id, assistantId = assistant.id, profileId = owner, title = label, messageNodes = emptyList(), newConversation = true))
                assertTrue(facade!!.sendMessage(SendMessageRequest(label, owner, 1, prompt, "CHAT", id.toString(), attachmentPaths = file?.let { listOf(files.file(it).absolutePath) }.orEmpty())).success)
                withTimeout(180_000) { service.getConversationFlow(id).first { it.currentMessages.size > 1 && it.currentMessages.last().generationState in setOf("COMPLETED", "FAILED", "INTERRUPTED") }; while (service.getGenerationJobStateFlow(id).first() != null) delay(100) }
                val conv = repository.getConversationById(id)!!
                assertEquals("COMPLETED", conv.currentMessages.last().generationState)
                File(evidence, "$label.json").writeText(me.rerere.rikkahub.utils.JsonInstant.encodeToString(Conversation.serializer(), conv))
                assertTrue("$label unexpectedly uploaded", docs.tasks(owner).isEmpty())
                return conv.currentMessages.last().toText()
            }
            assertTrue(turn("请总结这份文件中的库存数量。", readable, "FD01").contains("21"))
            turn("", image, "FD09")
            turn("仅本地处理，不上传服务器。请描述图中的内容；不能处理时说明原因。", image, "FD08")
            if (me.rerere.ai.provider.Modality.IMAGE in original.getCurrentChatModel()!!.inputModalities) {
                val visual = turn("描述这张图片中的彩色图形。", image, "FD04")
                assertTrue(visual.contains("蓝") || visual.contains("blue", true) || visual.contains("navy", true))
                assertTrue(visual.contains("橙") || visual.contains("orange", true))
            }
            turn("请根据以下合成文字生成四个真实文件：Word、Excel、PPT、PDF。内容为库存表：Apple 数量3，Pear 数量5。每种格式各一份；调用实际文件工具生成，不能只输出下载链接或文件名。", null, "FD05")
            assertTrue(files.list(owner).filter { it.output && it.state == "SUCCEEDED" }.map { it.extension }.toSet().containsAll(setOf("docx", "xlsx", "pptx", "pdf")))
            File(evidence, "result.json").writeText(buildJsonObject { put("FD01_readable_local", true); put("FD09_attachment_only", true); put("FD08_local_restriction", true); put("FD05_four_formats_generated", true); put("FD04_visual_model_available", me.rerere.ai.provider.Modality.IMAGE in original.getCurrentChatModel()!!.inputModalities); put("remote_jobs", docs.tasks(owner).size) }.toString())
        } finally {
            facade?.close(); ids.forEach { id -> service.stopGeneration(id); repository.getConversationById(id, owner)?.let { service.deleteConversation(it) }; service.removeConversationReference(id) }
            File(context.filesDir, "materials/$owner").deleteRecursively(); File(context.noBackupFilesDir, "document-service/$owner").deleteRecursively(); restoreMemoryProfileFixture(original, settings)
        }
    }
    @Test fun scannedSevenPageTaskAutomaticallyContinuesAndDeliversWorkbook() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val credential = File(context.cacheDir, "document-service-test-credentials.json")
        assertTrue("Isolated test credential required", credential.exists())
        val secrets = Json.parseToJsonElement(credential.readText()).jsonObject
        val koin = GlobalContext.get(); val settings = koin.get<SettingsStore>(); val service = koin.get<ChatService>(); val conversations = koin.get<ConversationRepository>(); val materials = koin.get<MaterialFiles>()
        val original = settings.settingsFlow.first { !it.init }
        assertNotNull("Configured real model required", original.getCurrentChatModel())
        val owner = settings.createLearnerProfile("P0 live"); val id = Uuid.random(); val title = "P0-live-${id.toString().take(8)}"
        val assistant = original.getCurrentAssistant().copy(id = Uuid.random(), name = title, systemPrompt = me.rerere.rikkahub.feature.studychat.StudyTutorDefaults.systemPrompt, localTools = emptyList(), enabledSkills = emptySet(), enableMemory = false)
        val docs = DocumentServiceRepository(context, materials)
        val mixed = InstrumentationRegistry.getArguments().getString("mixed") == "true"
        val evidence = File(context.cacheDir, if (mixed) "p0-live-mixed" else "p0-live-dispatch").apply { mkdirs() }
        var facade: StudyHostFacade? = null
        try {
            settings.update(original.copy(learnerProfiles = original.learnerProfiles + LearnerProfile(owner, title), activeProfileId = owner, assistants = original.assistants + assistant, assistantId = assistant.id, enableSuggestion = false))
            withTimeout(20_000) { settings.selectLearnerProfile(owner) }
            docs.configure(owner, secrets.getValue("baseUrl").jsonPrimitive.content, secrets.getValue("token").jsonPrimitive.content, true)
            assertTrue(docs.capabilities(owner).getValue("ready").jsonPrimitive.boolean)
            val sourceFile = File(evidence, "source-seven-scanned.pdf")
            val pdf = android.graphics.pdf.PdfDocument()
            try {
                repeat(7) { i ->
                    val bitmap = android.graphics.Bitmap.createBitmap(800, 1000, android.graphics.Bitmap.Config.ARGB_8888)
                    try {
                        val canvas = android.graphics.Canvas(bitmap); canvas.drawColor(android.graphics.Color.WHITE)
                        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; textSize = 30f; typeface = android.graphics.Typeface.MONOSPACE }
                        canvas.drawText("SYNTHETIC PAGE ${i + 1} OF 7", 60f, 100f, paint)
                        canvas.drawText("Item       Quantity      Price", 60f, 200f, paint)
                        canvas.drawText("PAGE_${i + 1}     ${(i + 1) * 3}            ${(i + 1) * 7}", 60f, 270f, paint)
                        val page = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(800, 1000, i + 1).create())
                        page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                        if (mixed && i == 0) page.canvas.drawText("Readable cover. Tables on following pages are scanned.", 60f, 400f, paint)
                        pdf.finishPage(page)
                    } finally { bitmap.recycle() }
                }
                sourceFile.outputStream().use { pdf.writeTo(it) }
            } finally { pdf.close() }
            val input = materials.import(owner, sourceFile.toUri(), sourceFile.name)
            assertFalse(MaterialReader.read(sourceFile, "pdf").text.contains("PAGE_7"))
            if (mixed) assertTrue(MaterialReader.read(sourceFile, "pdf").text.contains("Readable cover"))
            service.addConversationReference(id); service.initializeConversation(id)
            service.saveConversation(id, Conversation(id = id, assistantId = assistant.id, title = title, profileId = owner, messageNodes = emptyList(), newConversation = true))
            facade = StudyHostFacade(context, settings, conversations, service, koin.get(), materials, koin.get(), pickFiles = { emptyList() }, documents = docs)
            val prompt = "附件是7页合成扫描文件。请提取全部7页的Item、Quantity、Price表格，汇总为一个Excel文件，逐页保留PAGE_1到PAGE_7的7条数据，名称$title。必须处理到第7页，不能只交付前5页，不要让我重复发送任务。解析或生成失败请准确说明。"
            assertTrue(facade.sendMessage(SendMessageRequest("p0-live", owner, 1, prompt, "CHAT", id.toString(), attachmentPaths = listOf(sourceFileFor(materials, input)))).success)
            assertTrue(facade.watchConversation(owner, id.toString(), 1).success)
            val output = withTimeout(1_200_000) {
                while (true) {
                    conversations.getConversationById(id)?.let { current -> File(evidence, "conversation.json").writeText(me.rerere.rikkahub.utils.JsonInstant.encodeToString(Conversation.serializer(), current)) }
                    File(evidence, "tasks.json").writeText(me.rerere.rikkahub.utils.JsonInstant.encodeToString(docs.tasks(owner)))
                    val result = materials.list(owner).firstOrNull { it.output && it.extension == "xlsx" && it.state == "SUCCEEDED" }
                    if (result != null) return@withTimeout result
                    delay(2000)
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
            val batches = docs.tasks(owner).filter { it.downloaded }
            val pages = batches.flatMap { docs.read(owner, it.id).processedPages }.distinct().sorted()
            val workbook = materials.verifiedFile(owner, output.id)
            val text = MaterialReader.read(workbook, "xlsx").text
            (1..7).forEach { assertTrue("Missing item PAGE_$it", text.contains("PAGE_$it")) }
            workbook.copyTo(File(evidence, "output.xlsx"), overwrite = true)
            val conv = conversations.getConversationById(id)!!
            val visualPages = conv.currentMessages.flatMap { it.parts }.filterIsInstance<UIMessagePart.Tool>().filter { it.toolName == "read_material_pdf_pages" && it.executionState == ToolExecutionState.COMPLETED }.flatMap { tool ->
                val input = Json.parseToJsonElement(tool.input).jsonObject
                val start = input.getValue("start_page").jsonPrimitive.content.toInt()
                (start until start + input.getValue("count").jsonPrimitive.content.toInt()).toList()
            }.distinct().sorted()
            val coverage = if (mixed && pages.isEmpty()) visualPages else pages
            assertEquals((1..7).toList(), coverage)
            File(evidence, "conversation.json").writeText(me.rerere.rikkahub.utils.JsonInstant.encodeToString(Conversation.serializer(), conv))
            File(evidence, "result.json").writeText(buildJsonObject { put("real_mineru", pages.isNotEmpty()); put("FD03_mixed_text_scan", mixed); put("real_model", original.getCurrentChatModel()!!.modelId); put("covered_pages", JsonArray(coverage.map(::JsonPrimitive))); put("output_sha256", output.sha256); put("synthetic_only", true) }.toString())
        } finally {
            facade?.close(); service.stopGeneration(id)
            docs.tasks(owner).forEach { task -> runCatching { if (task.remoteId != null) docs.cancel(owner, task.id) }; runCatching { docs.cleanupRemote(owner, task.id) } }
            conversations.getConversationById(id, owner)?.let { service.deleteConversation(it) }
            service.removeConversationReference(id)
            File(context.filesDir, "materials/$owner").deleteRecursively(); File(context.noBackupFilesDir, "document-service/$owner").deleteRecursively()
            restoreMemoryProfileFixture(original, settings)
        }
    }
    private fun sourceFileFor(materials: MaterialFiles, input: MaterialFile) = materials.file(input).absolutePath
}
