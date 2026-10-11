package me.rerere.rikkahub.service

import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.rikkahub.feature.material.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

class P0RichFileTest {
    @Test fun genuineOfficePackagesPreserveUneditedStructureAndImagePdfPreservesPixels() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtures = File(context.cacheDir, "p0-rich-input")
        val evidence = File(context.cacheDir, "p0-rich-output").apply { mkdirs() }
        val owner = UUID.randomUUID().toString(); val files = MaterialFiles(context); val tools = materialEditTools(files, owner)
        fun parts(file: File): Map<String, ByteArray> = ZipFile(file).use { zip -> zip.entries().asSequence().associate { entry -> entry.name to zip.getInputStream(entry).readBytes() } }
        try {
            suspend fun edit(ext: String, operation: String, spec: String, changed: Set<String>): File {
                val source = File(fixtures, "rich-source.$ext"); assertTrue(source.exists())
                val input = files.import(owner, source.toUri(), source.name)
                val outputs = tools.single { it.name == "edit_material_file" }.execute(buildJsonObject { put("id", input.id); put("expected_sha256", input.sha256); put("operation", operation); put("spec", spec); put("title", "rich-output") })
                assertTrue(outputs.any { it is UIMessagePart.Document })
                val item = files.list(owner).single { it.output && it.extension == ext }; val output = files.verifiedFile(owner, item.id)
                val before = parts(source); val after = parts(output)
                assertEquals(before.keys, after.keys)
                before.filterKeys { it !in changed }.forEach { (path, bytes) -> assertArrayEquals("Changed unselected package part $path", bytes, after[path]) }
                assertEquals(input.sha256, MaterialFiles.digest(files.verifiedFile(owner, input.id)))
                output.copyTo(File(evidence, "rich-output.$ext"), overwrite = true)
                return output
            }
            val word = edit("docx", "replace_text_run", """{"index":1,"expectedText":"Bold source","text":"Bold edited"}""", setOf("word/document.xml"))
            val wordXml = parts(word).getValue("word/document.xml").toString(Charsets.UTF_8)
            assertTrue(wordXml.contains("Bold edited") && wordXml.contains("plain source") && wordXml.contains("oMath") && wordXml.contains("drawing"))
            edit("pptx", "replace_text_run", """{"slide":1,"index":1,"expectedText":"Synthetic slide title","text":"Edited slide title"}""", setOf("ppt/slides/slide1.xml"))
            val excel = edit("xlsx", "set_cells", """{"sheet":"Data","changes":[{"cell":"A2","expected":2,"value":4}]}""", setOf("xl/workbook.xml", "xl/worksheets/sheet1.xml"))
            assertTrue(parts(excel).getValue("xl/worksheets/sheet1.xml").toString(Charsets.UTF_8).contains("A2*3"))
            val image = files.import(owner, File(fixtures, "synthetic.png").toUri(), "synthetic.png")
            val converter = tools.single { it.name == "convert_material_to_pdf" }
            val output = converter.execute(buildJsonObject { put("id", image.id); put("expected_sha256", image.sha256); put("title", "image-preserved") })
            assertTrue(output.any { it is UIMessagePart.Document })
            val pdf = files.list(owner).single { it.extension == "pdf" && it.output }; files.verifiedFile(owner, pdf.id).copyTo(File(evidence, "image-preserved.pdf"), overwrite = true)
            val office = files.list(owner).single { !it.output && it.extension == "docx" }
            val rejected = converter.execute(buildJsonObject { put("id", office.id); put("expected_sha256", office.sha256); put("title", "not-a-fake-conversion") }).single() as UIMessagePart.Text
            assertEquals("FAILED", Json.parseToJsonElement(rejected.text).jsonObject.getValue("status").jsonPrimitive.content)
            File(evidence, "result.json").writeText("""{"actual_packages":true,"unedited_parts_byte_identical":true,"originals_preserved":true,"formulas_preserved_recalculation_required":true,"office_pdf_explicitly_unavailable":true}""")
        } finally { File(context.filesDir, "materials/$owner").deleteRecursively() }
    }
}
