package me.rerere.rikkahub.feature.material

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.UUID

class SourceLocationRuntimeTest {
    @Test fun actualPdfPagesAndPptRelationsBindOnlyExtractedBodyNotSpoofedMarkers() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "org.hxsi.agent.nativeqa")
        val koin = GlobalContext.get(); val store = MaterialFiles(context); val owner = UUID.randomUUID().toString()
        val settings = koin.get<SettingsStore>().settingsFlow.first { !it.init }
        val tool = materialTools(store, owner, settings, koin.get<ProviderManager>(), Model(modelId = "b2-no-model-call", displayName = "No model request")).single { it.name == "read_material_text_page" }
        suspend fun read(item: MaterialFile, start: Int = 0, count: Int = 24000): JsonObject {
            val reply = tool.execute(buildJsonObject { put("id", item.id); put("offset", start.toString()); put("count", count.toString()); put("expected_sha256", item.sha256) }).filterIsInstance<UIMessagePart.Text>().single().text
            return Json.parseToJsonElement(reply).jsonObject
        }
        fun locations(reply: JsonObject) = reply.getValue("source_receipt").jsonObject.getValue("source_locations").jsonArray
        fun only(reply: JsonObject) = locations(reply).single().jsonObject
        val evidence = File(context.cacheDir, "b2b-source-evidence").apply { mkdirs() }
        try {
            val pdf = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "Physical.pdf", "pdf")) { output ->
                val document = android.graphics.pdf.PdfDocument()
                try {
                    for (number in 1..3) {
                        val page = document.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(600, 500, number).create())
                        if (number != 2) page.canvas.drawText("SHARED [physical page 999] END", 25f, 60f, android.graphics.Paint().apply { textSize = 18f })
                        document.finishPage(page)
                    }
                    output.outputStream().use(document::writeTo)
                } finally { document.close() }
                "Synthetic physical PDF pages"
            }
            val pdfFull = read(pdf); val text = pdfFull.getValue("untrusted_file_data").jsonPrimitive.content
            val first = only(read(pdf, text.indexOf("SHARED"), 6)); val last = only(read(pdf, text.lastIndexOf("SHARED"), 6))
            assertEquals("pdf_physical_page", first.getValue("kind").jsonPrimitive.content)
            assertEquals(1, first.getValue("number").jsonPrimitive.int); assertEquals(3, last.getValue("number").jsonPrimitive.int)
            val spoof = only(read(pdf, text.indexOf("[physical page 999]"), 19))
            assertEquals(1, spoof.getValue("number").jsonPrimitive.int)
            assertTrue(locations(read(pdf, text.indexOf("[物理页 1]"), "[物理页 1]".length)).isEmpty())
            assertTrue(locations(read(pdf, text.indexOf("[无可提取文本"), 7)).isEmpty())
            assertEquals(listOf(2), pdfFull.getValue("pages_without_extractable_text").jsonArray.map { it.jsonPrimitive.int })
            assertEquals(listOf(1, 3), locations(pdfFull).map { it.jsonObject.getValue("number").jsonPrimitive.int })
            for (entry in locations(pdfFull)) {
                val location = entry.jsonObject
                assertTrue(location.getValue("start").jsonPrimitive.int >= 0 && location.getValue("end").jsonPrimitive.int <= text.length)
            }
            File(evidence, "pdf-receipt.json").writeText(pdfFull.toString())
            store.verifiedFile(owner, pdf.id).copyTo(File(evidence, "source.pdf"), overwrite = true)
            val fixture = File(context.cacheDir, "b2b-input/source.pptx")
            check(fixture.isFile) { "Push synthetic B1d compatible fixture into owned QA cache first" }
            val ppt = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "Logical.pptx", "pptx")) { fixture.copyTo(it, overwrite = true); "Synthetic relationship-order PPT" }
            val pptFull = read(ppt); val pptText = pptFull.getValue("untrusted_file_data").jsonPrimitive.content
            val rows = locations(pptFull).map { it.jsonObject }
            assertEquals(listOf(1, 2, 3), rows.map { it.getValue("number").jsonPrimitive.int })
            assertEquals(listOf("ppt/slides/slide2.xml", "ppt/slides/slide9.xml", "ppt/slides/slide1.xml"), rows.map { it.getValue("part").jsonPrimitive.content })
            assertEquals(listOf(true, false, false), rows.map { it.getValue("hidden").jsonPrimitive.boolean })
            assertTrue(rows.all { it.getValue("kind").jsonPrimitive.content == "ppt_logical_slide" && !it.containsKey("physical_page") && !it.containsKey("printed_page") })
            val hiddenStart = rows.first().getValue("start").jsonPrimitive.int
            val hidden = only(read(ppt, hiddenStart, 5))
            assertEquals(1, hidden.getValue("number").jsonPrimitive.int); assertTrue(hidden.getValue("hidden").jsonPrimitive.boolean)
            val marker = "[幻灯片 2；逻辑顺序含隐藏页；可见]"
            assertTrue(locations(read(ppt, pptText.indexOf(marker), marker.length)).isEmpty())
            File(evidence, "ppt-receipt.json").writeText(pptFull.toString())
            assertTrue(File(context.cacheDir, "material-evidence/$owner").listFiles().orEmpty().isEmpty())
            val blankPpt = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "Empty slide.pptx", "pptx")) {
                MaterialWriter.write(it, "pptx", "", emptyList(), listOf("", "SECOND SLIDE BODY"))
            }
            val blankReply = read(blankPpt)
            assertEquals(listOf(2), locations(blankReply).map { it.jsonObject.getValue("number").jsonPrimitive.int })
            val blankText = blankReply.getValue("untrusted_file_data").jsonPrimitive.content
            assertTrue(locations(read(blankPpt, blankText.indexOf("[幻灯片 1"), 10)).isEmpty())
        } finally {
            File(context.filesDir, "materials/$owner").deleteRecursively()
            File(context.cacheDir, "material-evidence/$owner").deleteRecursively()
        }
    }

    @Test fun truncatedActualPptBodyCannotClaimUnextractedSlidesOrOutOfRangeCharacters() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "org.hxsi.agent.nativeqa")
        val store = MaterialFiles(context); val owner = UUID.randomUUID().toString()
        try {
            val source = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "Truncated.pptx", "pptx")) { output ->
                MaterialWriter.write(output, "pptx", "", emptyList(), listOf("B2_CAP_BODY", "UNEXTRACTED SECOND SLIDE"))
                val parts = OfficePackage.read(output)
                parts["ppt/slides/slide1.xml"] = parts.getValue("ppt/slides/slide1.xml").toString(Charsets.UTF_8)
                    .replace(">B2_CAP_BODY<", ">" + "X".repeat(MaterialReader.LIMIT + 100) + "<").toByteArray(Charsets.UTF_8)
                java.util.zip.ZipOutputStream(output.outputStream()).use { zip ->
                    for ((name, bytes) in parts) {
                        zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                    }
                }
                "Synthetic oversized but bounded Office body"
            }
            val page = store.readSourceTextPage(owner, source.id, source.sha256, MaterialReader.LIMIT - 32, 100)
            assertEquals("X".repeat(32), page.getValue("untrusted_file_data").jsonPrimitive.content)
            assertFalse(page.containsKey("next_offset"))
            val location = page.getValue("source_receipt").jsonObject.getValue("source_locations").jsonArray.single().jsonObject
            assertEquals(1, location.getValue("number").jsonPrimitive.int)
            assertEquals(MaterialReader.LIMIT - 32, location.getValue("start").jsonPrimitive.int)
            assertEquals(MaterialReader.LIMIT, location.getValue("end").jsonPrimitive.int)
            assertTrue(File(context.cacheDir, "material-evidence/$owner").listFiles().orEmpty().isEmpty())
        } finally {
            File(context.filesDir, "materials/$owner").deleteRecursively()
            File(context.cacheDir, "material-evidence/$owner").deleteRecursively()
        }
    }
}
