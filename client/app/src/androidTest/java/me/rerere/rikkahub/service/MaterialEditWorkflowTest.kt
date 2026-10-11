package me.rerere.rikkahub.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.rikkahub.feature.material.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MaterialEditWorkflowTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun wordExcelPdfEditsAndConversionDeliverRealCopies() = runBlocking {
        val owner = UUID.randomUUID().toString(); val store = MaterialFiles(context)
        val evidence = File(context.cacheDir, "core-v1-edit-evidence").apply { mkdirs() }
        try {
            val tools = materialEditTools(store, owner)
            val editor = tools.single { it.name == "edit_material_file" }
            val inspector = tools.single { it.name == "inspect_material_edit" }
            val word = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "original.docx", "docx")) { file -> MaterialWriter.write(file, "docx", "Original paragraph\nKeep second paragraph", emptyList(), emptyList()) }
            val inspection = inspector.execute(buildJsonObject { put("id", word.id); put("selection", "{\"offset\":0,\"count\":2}") }).single() as UIMessagePart.Text
            assertTrue(inspection.text.contains(word.sha256))
            val wordSpec = """{"index":1,"expectedText":"Original paragraph","text":"Edited paragraph"}"""
            fun args(item: MaterialFile, operation: String, spec: String) = buildJsonObject { put("id", item.id); put("expected_sha256", item.sha256); put("operation", operation); put("spec", spec); put("title", "QA edited") }
            val changedWord = editor.execute(args(word, "replace_paragraph", wordSpec)).filterIsInstance<UIMessagePart.Document>().single()
            val wordOutput = store.list(owner).single { it.output && it.extension == "docx" }
            assertTrue(MaterialReader.read(store.verifiedFile(owner, wordOutput.id), "docx").text.contains("Edited paragraph"))
            assertTrue(MaterialReader.read(store.verifiedFile(owner, wordOutput.id), "docx").text.contains("Keep second paragraph"))
            assertEquals(word.sha256, MaterialFiles.digest(store.verifiedFile(owner, word.id)))
            assertEquals(changedWord.url, editor.execute(args(word, "replace_paragraph", wordSpec)).filterIsInstance<UIMessagePart.Document>().single().url)
            val rejected = editor.execute(JsonObject(args(word, "replace_paragraph", wordSpec) + ("expected_sha256" to JsonPrimitive("wrong")))).single() as UIMessagePart.Text
            assertEquals("FAILED", Json.parseToJsonElement(rejected.text).jsonObject.getValue("status").jsonPrimitive.content)
            val options = Json.parseToJsonElement("""{"rows":[["Name","Count","Enabled","Code"],[" Alice ",2,true,"001"]],"sheetName":"Data"}""").jsonObject
            val excel = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "original.xlsx", "xlsx")) { file -> MaterialWriter.write(file, "xlsx", "", listOf(listOf("Name","Count","Enabled","Code"),listOf(" Alice ","2","true","001")), emptyList(), options) }
            editor.execute(args(excel, "set_cells", """{"sheet":"Data","changes":[{"cell":"A2","expected":" Alice ","value":"Alice"},{"cell":"B2","expected":2,"value":4},{"cell":"C2","expected":true,"value":false}]}"""))
            val excelOutput = store.list(owner).single { it.output && it.extension == "xlsx" }
            val cells = MaterialEditor.excelCells(store.verifiedFile(owner, excelOutput.id), "Data", 0, 20)["cells"]!!.jsonArray.associate { it.jsonObject["cell"]!!.jsonPrimitive.content to it.jsonObject["value"] }
            assertEquals(JsonPrimitive("Alice"), cells["A2"]); assertEquals(JsonPrimitive(4), cells["B2"]); assertEquals(JsonPrimitive(false), cells["C2"]); assertEquals(JsonPrimitive("001"), cells["D2"])
            suspend fun pdf(labels: List<String>): MaterialFile = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "source.pdf", "pdf")) { file ->
                val document = android.graphics.pdf.PdfDocument()
                try { labels.forEachIndexed { index, text -> val page = document.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(300, 400, index + 1).create()); page.canvas.drawText(text, 30f, 60f, android.graphics.Paint().apply { textSize = 18f }); document.finishPage(page) }; file.outputStream().use { document.writeTo(it) } } finally { document.close() }
                "synthetic PDF pages"
            }
            val first = pdf(listOf("PAGE_ONE", "PAGE_TWO")); val second = pdf(listOf("PAGE_THREE"))
            val spec = buildJsonObject { put("sources", buildJsonArray { add(buildJsonObject { put("id", first.id); put("sha256", first.sha256); put("pages", buildJsonArray { add(2) }) }); add(buildJsonObject { put("id", second.id); put("sha256", second.sha256); put("pages", buildJsonArray { add(1) }) }) }) }
            val output = editor.execute(args(first, "pdf_select_pages", spec.toString())).filterIsInstance<UIMessagePart.Document>().single()
            val merged = store.list(owner).single { it.output && it.extension == "pdf" }
            val text = MaterialReader.read(store.verifiedFile(owner, merged.id), "pdf").text
            assertFalse(text.contains("PAGE_ONE")); assertTrue(text.indexOf("PAGE_TWO") >= 0 && text.indexOf("PAGE_THREE") > text.indexOf("PAGE_TWO"))
            assertTrue(output.url.startsWith("file:"))
            val converter = tools.single { it.name == "export_material_text_pdf" }
            converter.execute(buildJsonObject { put("id", wordOutput.id); put("expected_sha256", wordOutput.sha256); put("title", "Converted text") })
            val converted = store.list(owner).single { it.name == "Converted text.pdf" }
            assertTrue(MaterialReader.read(store.verifiedFile(owner, converted.id), "pdf").text.contains("Edited paragraph"))
            for (item in listOf(word, wordOutput, excel, excelOutput, first, second, merged, converted)) store.verifiedFile(owner, item.id).copyTo(File(evidence, "${if (item.output) "output" else "source"}-${item.name}"), overwrite = true)
            File(evidence, "results.json").writeText(buildJsonObject { put("word_preserved_source", true); put("excel_types", true); put("pdf_order", true); put("conversion", true); put("files", buildJsonArray { listOf(wordOutput, excelOutput, merged, converted).forEach { add(buildJsonObject { put("name", it.name); put("sha256", it.sha256) }) } }) }.toString())
        } finally { File(context.filesDir, "materials/$owner").deleteRecursively() }
    }
}
