package me.rerere.rikkahub.service

import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.material.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class MaterialRealModelTest {
    @Test fun configuredImageGenerationProducesDecodedPng() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realModel") == "true")
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsStore>()
        val original = withTimeout(20000) { settings.settingsFlow.first { !it.init } }
        assumeTrue("MISSING_CONFIGURATION: image generation model", original.findModelById(original.imageGenerationModelId) != null)
        val owner = settings.createLearnerProfile("QA 图片生成")
        val evidence = QaEvidenceRun("MaterialRealModelTest.configuredImageGenerationProducesDecodedPng")
        val store = koin.get<MaterialFiles>()
        var status = "FAILED"
        try {
            withTimeout(20000) { settings.settingsFlow.first { it.learnerProfiles.any { p -> p.id == owner } } }
            val before = store.list(owner).map { it.id }.toSet()
            val result = withTimeout(120000) {
                materialTools(store, owner, original, koin.get(), requireNotNull(original.getCurrentChatModel()))
                    .single { it.name == "generate_material_image" }.execute(kotlinx.serialization.json.buildJsonObject {
                        put("prompt", kotlinx.serialization.json.JsonPrimitive("一张白底蓝色三角形图片，无文字"))
                    })
            }
            assertTrue("Image output required; text failure cannot pass", result.any { it is UIMessagePart.Image })
            val item = store.list(owner).single { it.id !in before && it.output && it.extension == "png" && it.state == "SUCCEEDED" }
            val file = store.verifiedFile(owner, item.id)
            val bitmap = requireNotNull(android.graphics.BitmapFactory.decodeFile(file.path))
            try { assertTrue(bitmap.width > 0 && bitmap.height > 0) } finally { bitmap.recycle() }
            file.copyTo(evidence.file("generated.png"))
            status = "PASS"
        } finally { evidence.finish(status); restoreMemoryProfileFixture(original, settings) }
    }

    @Test fun actualAttachmentToolsAndVision() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realModel") == "true")
        val koin = GlobalContext.get(); val settings = koin.get<SettingsStore>()
        val original = withTimeout(20000) { settings.settingsFlow.first { !it.init } }
        require(original.getCurrentChatModel() != null)
        val owner = settings.createLearnerProfile("QA 文件能力")
        val service = koin.get<ChatService>(); val repository = koin.get<ConversationRepository>(); val store = koin.get<MaterialFiles>()
        val id = Uuid.random(); val context = InstrumentationRegistry.getInstrumentation().targetContext
        val transcript = StringBuilder()
        val evidenceRun = QaEvidenceRun("MaterialRealModelTest.actualAttachmentToolsAndVision")
        var evidenceStatus = "FAILED"
        try {
            evidenceRun.syntheticInput(File(context.cacheDir, "material-evidence/fidelity.docx"), "fidelity.docx")
            withTimeout(20000) { settings.settingsFlow.first { it.learnerProfiles.any { p -> p.id == owner } } }
            settings.setLearnerMemoryEnabled(owner, false)
            service.switchLearnerProfile(owner); service.addConversationReference(id); service.initializeConversation(id)
            suspend fun round(parts: List<UIMessagePart>): String {
                val before = repository.getConversationById(id, owner)?.currentMessages?.size ?: 0
                service.sendMessage(id, parts)
                withTimeout(150000) {
                    service.getConversationFlow(id).first { it.currentMessages.size > before && it.currentMessages.lastOrNull()?.generationState in setOf("COMPLETED", "FAILED", "WAITING_TOOL") }
                    while (service.getGenerationJobStateFlow(id).first() != null) delay(100)
                }
                val conversation = repository.getConversationById(id, owner)!!
                transcript.append("\n状态：${conversation.currentMessages.last().generationState}\n")
                service.errors.value.lastOrNull { it.conversationId == id }?.let { failure ->
                    val fingerprint = me.rerere.rikkahub.feature.diagnostics.Diagnostics.failureFingerprint(failure.error).toString()
                    transcript.append("失败分类：$fingerprint\n")
                    InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply { putString("review_failure", fingerprint) })
                }
                assertEquals("COMPLETED", conversation.currentMessages.last().generationState)
                val answer = conversation.currentMessages.last().toText()
                transcript.append("\n回复：$answer\n")
                return answer
            }
            for (ext in if (InstrumentationRegistry.getArguments().getString("extendedOnly") == "true") emptyList() else listOf("md", "docx", "xlsx", "pptx", "pdf")) {
                val source = evidenceRun.file("sample.$ext")
                writeSyntheticMaterialSample(source, ext)
                val item = store.import(owner, source.toUri(), "样例.$ext")
                val before = store.list(owner).filter { it.output }.map { it.id }.toSet()
                round(listOf(UIMessagePart.Text("请分析附件中实际存在的中文内容，再调用 create_material_file 制作一个 $ext 文件，标题为验证$ext。可用 body、rows 或 slides。保留中文，不需要教学练习。必须实际调用工具生成文件，说明未处理内容，不声称这是我的表现。"), UIMessagePart.Document(store.file(item).toUri().toString(), item.name, MaterialWriter.mime(ext))))
                val created = store.list(owner).filter { it.output && it.id !in before }
                assertTrue("$ext actual tool file", created.any { it.extension == ext && it.state == "SUCCEEDED" })
                for (output in created.filter { it.state == "SUCCEEDED" }) store.verifiedFile(owner, output.id).copyTo(evidenceRun.file("real-${output.name}"), overwrite = true)
                transcript.append("实际成果：${created.joinToString { it.name + ":" + it.state }}\n")
            }
            val image = evidenceRun.file("vision.png")
            val bitmap = android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888)
            try { val canvas = android.graphics.Canvas(bitmap); canvas.drawColor(android.graphics.Color.WHITE); canvas.drawText("7", 80f, 190f, android.graphics.Paint().apply { color = android.graphics.Color.BLACK; textSize = 180f }); image.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
            val picture = store.import(owner, image.toUri(), "视觉测试.png")
            val answer = round(listOf(UIMessagePart.Text("只说这张图片里实际显示的阿拉伯数字。"), UIMessagePart.Image(store.file(picture).toUri().toString())))
            assertTrue("actual vision digit", answer.contains("7"))
            // Image-only PDF: no text layer, so the answer requires a real raster tool round trip.
            val scan = evidenceRun.file("scan.pdf")
            val scanBitmap = android.graphics.Bitmap.createBitmap(595, 842, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                val canvas = android.graphics.Canvas(scanBitmap); canvas.drawColor(android.graphics.Color.WHITE)
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; textSize = 48f }
                canvas.drawText("7391", 60f, 160f, paint)
                canvas.drawText("x + 1", 70f, 300f, paint); canvas.drawLine(60f, 315f, 210f, 315f, paint.apply { strokeWidth = 3f }); canvas.drawText("2", 110f, 370f, paint)
                val pdf = android.graphics.pdf.PdfDocument()
                try { val page = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, 1).create()); page.canvas.drawBitmap(scanBitmap, 0f, 0f, null); pdf.finishPage(page); scan.outputStream().use(pdf::writeTo) } finally { pdf.close() }
            } finally { scanBitmap.recycle() }
            val scanItem = store.import(owner, scan.toUri(), "扫描样例.pdf")
            assertTrue(store.readContent(owner, scanItem.id).text.contains("无可提取文本"))
            scan.copyTo(evidenceRun.file("scan-input.pdf"), overwrite = true)
            val beforeScan = store.list(owner).filter { it.output }.map { it.id }.toSet()
            val scanAnswer = round(listOf(UIMessagePart.Text("请调用 read_material_pdf_pages 读取文件 ID=${scanItem.id} 的第1物理页(1页)。这份扫描PDF没有文字层，按实际图片识别页上数字和分式；不要猜测或把文件当我的表现。然后调用create_material_file生成md，标题扫描视觉往返，正文写实际识别结果。")))
            val createdScan = store.list(owner).filter { it.output && it.id !in beforeScan && it.state == "SUCCEEDED" }
            assertTrue(createdScan.any { it.extension == "md" })
            assertTrue("scan identifier from actual visual input", scanAnswer.contains("7391") || createdScan.any { store.verifiedFile(owner, it.id).readText().contains("7391") })
            val scanOutput = createdScan.first { it.extension == "md" }
            val scanText = store.verifiedFile(owner, scanOutput.id).readText()
            assertTrue("scan fraction numerator", scanText.contains("x") && scanText.contains("1") && scanText.contains("2"))
            assertTrue(store.list(owner).any { it.sourceId == scanItem.id && it.pageNumber == 1 })
            store.verifiedFile(owner, scanOutput.id).copyTo(evidenceRun.file("scan-output.md"), overwrite = true)
            transcript.append("扫描PDF视觉工具往返：无文本层→物理页PNG→真实模型→真实MD\n$scanText\n")
            val wordItem = store.import(owner, evidenceRun.file("fidelity.docx").toUri(), "图片公式样例.docx")
            val beforeWord = store.list(owner).filter { it.output }.map { it.id }.toSet()
            val wordAnswer = round(listOf(UIMessagePart.Text("处理Word文件ID=${wordItem.id}：先用read_material_file提取实际公式，再用read_material_word_images读取内嵌图片(start=0,count=1)，必须按实际图片说出数字。之后用create_material_file生成docx，标题图片公式视觉往返。保留原公式为显式equations中fraction和superscript结构，用工具返回的本档案图片ID作为images，保留基本表格。正文写实际图上数字及内容范围。不把文件当我的表现。")))
            val wordOutput = store.list(owner).first { it.output && it.id !in beforeWord && it.extension == "docx" && it.state == "SUCCEEDED" }
            val wordFile = store.verifiedFile(owner, wordOutput.id)
            val wordText = MaterialReader.read(wordFile, "docx").text
            assertTrue(wordAnswer.contains("8642") || wordText.contains("8642"))
            assertWordFormulaStructure(wordFile)
            assertEquals(1, MaterialWordContent.images(wordFile, 0, 1).size)
            assertTrue(store.list(owner).any { it.sourceId == wordItem.id && it.extension == "png" })
            assertWordImagesMatch(evidenceRun.file("fidelity.docx"), wordFile)
            assertWordTablesMatch(evidenceRun.file("fidelity.docx"), wordFile)
            wordFile.copyTo(evidenceRun.file("word-vision-output.docx"), overwrite = true)
            transcript.append("Word实际模型图片/公式处理并重建：$wordText\n")
            val config = settings.settingsFlow.value
            transcript.append("图片服务配置：" + if (config.findModelById(config.imageGenerationModelId) == null) "未选定图片生成模型\n" else "已选图片模型，实际接口待独立探针结果\n")
            // Image generation has its own method/configuration outcome; missing config cannot pass it.
            evidenceStatus = "PASS"
        } finally {
            evidenceRun.file("transcript.md").writeText(transcript.toString())
            evidenceRun.finish(evidenceStatus)
            service.stopGeneration(id)
            repository.getConversationById(id, owner)?.let { repository.deleteConversation(it) }
            service.removeConversationReference(id)
            restoreMemoryProfileFixture(original, settings)
        }
    }
}
