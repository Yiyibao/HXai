package me.rerere.rikkahub.feature.material

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PdfAssemblyRuntimeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun pdf(file: File, count: Int = 3) {
        val doc = PdfDocument()
        try {
            repeat(count) { index ->
                val page = doc.startPage(PdfDocument.PageInfo.Builder(240 + index % 3 * 30, 320, index + 1).create())
                if (index % 3 != 1) {
                    page.canvas.drawRect(20f, 90f, 120f + index % 10, 150f, Paint().apply { color = Color.rgb(index * 17 % 256, 50, 180) })
                    page.canvas.drawText("PHYSICAL_${index + 1}", 20f, 50f, Paint().apply { textSize = 15f })
                    if (index % 3 == 2) {
                        val image = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
                        try {
                            image.eraseColor(Color.GREEN); image.setPixel(16, 16, Color.RED)
                            page.canvas.drawBitmap(image, null, android.graphics.Rect(30, 180, 158, 308), null)
                        } finally { image.recycle() }
                    }
                }
                doc.finishPage(page)
            }
            file.outputStream().use(doc::writeTo)
        } finally { doc.close() }
    }
    private suspend fun source(store: MaterialFiles, owner: String, count: Int = 3) =
        store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "synthetic.pdf", "pdf")) { pdf(it, count); "synthetic" }
    private fun args(first: MaterialFile, vararg groups: Pair<MaterialFile, List<Int>>) = buildJsonObject {
        put("id", first.id); put("expected_sha256", first.sha256); put("title", "assembled")
        put("operation", "pdf_select_pages")
        put("spec", buildJsonObject { put("sources", buildJsonArray { groups.forEach { (item, pages) ->
            add(buildJsonObject { put("id", item.id); put("sha256", item.sha256); put("pages", JsonArray(pages.map(::JsonPrimitive))) })
        } }) }.toString())
    }
    @Test fun samePathCannotDestroyOriginal() = runBlocking {
        val file = File(context.cacheDir, "pdf-preserve-${UUID.randomUUID()}.pdf")
        try {
            pdf(file); val before = MaterialFiles.digest(file)
            val failure = runCatching { MaterialEditor.pdfPages(listOf(file to listOf(3, 1)), file) }.exceptionOrNull()
            assertEquals("original bytes changed", before, MaterialFiles.digest(file))
            assertTrue("same path must be rejected", failure is IllegalArgumentException)
        } finally { file.delete() }
    }
    @Test fun hardLinkCannotDestroyOriginal() = runBlocking {
        val file = File(context.cacheDir, "pdf-link-${UUID.randomUUID()}.pdf"); val link = File(file.path + ".alias")
        try {
            pdf(file)
            try { java.nio.file.Files.createLink(link.toPath(), file.toPath()) }
            catch (e: java.nio.file.AccessDeniedException) { org.junit.Assume.assumeNoException("Android SELinux denies app hard links", e) }
            val before = MaterialFiles.digest(file)
            val failure = runCatching { MaterialEditor.pdfPages(listOf(file to listOf(3)), link) }.exceptionOrNull()
            assertEquals(before, MaterialFiles.digest(file)); assertTrue(failure is IllegalArgumentException)
        } finally { link.delete(); file.delete() }
    }
    @Test fun selectionBeyondTwoHundredSourcePagesRemainsValid() = runBlocking {
        val file = File(context.cacheDir, "pdf-long-${UUID.randomUUID()}.pdf"); val output = File(file.path + ".output")
        try {
            pdf(file, 201); val before = MaterialFiles.digest(file)
            MaterialEditor.pdfPages(listOf(file to listOf(201, 1)), output)
            val content = MaterialReader.read(output, "pdf")
            assertEquals(2, content.totalPages); assertTrue(content.text.indexOf("PHYSICAL_201") < content.text.indexOf("PHYSICAL_1"))
            assertEquals(before, MaterialFiles.digest(file))
        } finally { output.delete(); file.delete() }
    }
    @Test fun realToolPersistsOrderedSourceEvidenceAndReusesVerifiedCopy() = runBlocking {
        val owner = UUID.randomUUID().toString(); val store = MaterialFiles(context)
        try {
            val first = source(store, owner); val second = source(store, owner)
            val tool = materialEditTools(store, owner).single { it.name == "edit_material_file" }
            val input = args(first, first to listOf(3, 2, 3), second to listOf(1))
            val parts = tool.execute(input); assertEquals(1, parts.filterIsInstance<UIMessagePart.Document>().size)
            val item = store.list(owner).single { it.output }; val detail = Json.parseToJsonElement(item.detail).jsonObject
            assertEquals("pdf_original_page_assembly", detail["scope"]!!.jsonPrimitive.content)
            assertEquals(1, detail["contractVersion"]!!.jsonPrimitive.int)
            val pages = detail["pages"]!!.jsonArray
            assertEquals(listOf(3, 2, 3, 1), pages.map { it.jsonObject["source_physical_page"]!!.jsonPrimitive.int })
            assertEquals(listOf(first.id, first.id, first.id, second.id), pages.map { it.jsonObject["source_id"]!!.jsonPrimitive.content })
            assertEquals(4, MaterialReader.read(store.verifiedFile(owner, item.id), "pdf").totalPages)
            assertEquals(parts.filterIsInstance<UIMessagePart.Document>().single().url, tool.execute(input).filterIsInstance<UIMessagePart.Document>().single().url)
            assertEquals(1, store.list(owner).count { it.output })
            val evidence = File(context.cacheDir, "b-pdf-assembly-evidence").apply { mkdirs() }
            for ((name, original) in listOf("first" to first, "second" to second, "output" to item)) {
                store.verifiedFile(owner, original.id).copyTo(File(evidence, "$name.pdf"), overwrite = true)
                File(evidence, "$name.meta.json").writeText(kotlinx.serialization.json.Json.encodeToString(MaterialFile.serializer(), original))
            }
            assertEquals(first.sha256, MaterialFiles.digest(store.verifiedFile(owner, first.id)))
            assertEquals(second.sha256, MaterialFiles.digest(store.verifiedFile(owner, second.id)))
        } finally { File(context.filesDir, "materials/$owner").deleteRecursively() }
    }
    @Test fun invalidSelectionAndForeignSourceNeverPublishArtifact() = runBlocking {
        val owner = UUID.randomUUID().toString(); val other = UUID.randomUUID().toString(); val store = MaterialFiles(context)
        try {
            val first = source(store, owner); val foreign = source(store, other)
            val tool = materialEditTools(store, owner).single { it.name == "edit_material_file" }
            for (input in listOf(args(first, first to emptyList()), args(first, first to listOf(4)), args(first, first to listOf(0)), args(first, first to listOf(1), foreign to listOf(1)), JsonObject(args(first, first to listOf(1)) + ("expected_sha256" to JsonPrimitive("0".repeat(64)))))) {
                val parts = tool.execute(input)
                assertTrue(parts.none { it is UIMessagePart.Document })
                assertEquals("FAILED", Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject["status"]!!.jsonPrimitive.content)
            }
            assertTrue(store.list(owner).none { it.output && it.state == "SUCCEEDED" })
            assertEquals(first.sha256, MaterialFiles.digest(store.verifiedFile(owner, first.id)))
        } finally { listOf(owner, other).forEach { File(context.filesDir, "materials/$it").deleteRecursively() } }
    }
    @Test fun corruptedCachedOutputIsNotRedelivered() = runBlocking {
        val owner = UUID.randomUUID().toString(); val store = MaterialFiles(context)
        try {
            val first = source(store, owner); val tool = materialEditTools(store, owner).single { it.name == "edit_material_file" }
            val input = args(first, first to listOf(3, 1)); tool.execute(input)
            val output = store.list(owner).single { it.output }; store.file(output).writeText("broken PDF")
            assertTrue(tool.execute(input).none { it is UIMessagePart.Document })
            assertEquals(1, store.list(owner).count { it.output })
            assertEquals(first.sha256, MaterialFiles.digest(store.verifiedFile(owner, first.id)))
        } finally { File(context.filesDir, "materials/$owner").deleteRecursively() }
    }
    @Test fun samePageCountWithWrongContentFailsIndependentVerification(): Unit = runBlocking {
        val source = File(context.cacheDir, "pdf-wrong-${UUID.randomUUID()}.pdf"); val output = File(source.path + ".output")
        try {
            pdf(source)
            MaterialEditor.pdfPages(listOf(source to listOf(1, 3)), output)
            assertTrue(runCatching { MaterialPdfAssembly.verify(listOf(source to listOf(3, 1)), output) }.isFailure)
            MaterialPdfAssembly.verify(listOf(source to listOf(1, 3)), output)
        } finally { output.delete(); source.delete() }
    }
    @Test fun forgedCachedPageMapCannotBeRedelivered() = runBlocking {
        val owner = UUID.randomUUID().toString(); val store = MaterialFiles(context)
        try {
            val first = source(store, owner); val tool = materialEditTools(store, owner).single { it.name == "edit_material_file" }
            val input = args(first, first to listOf(3, 1)); tool.execute(input)
            val output = store.list(owner).single { it.output }
            store.record(output.copy(detail = output.detail.replace("\"source_physical_page\":3", "\"source_physical_page\":1")))
            assertTrue(tool.execute(input).none { it is UIMessagePart.Document })
            assertEquals(1, store.list(owner).count { it.output })
        } finally { File(context.filesDir, "materials/$owner").deleteRecursively() }
    }
    @Test fun changedSecondarySourceCannotReusePriorSuccess() = runBlocking {
        val owner = UUID.randomUUID().toString(); val store = MaterialFiles(context)
        try {
            val first = source(store, owner); val second = source(store, owner)
            val tool = materialEditTools(store, owner).single { it.name == "edit_material_file" }
            val input = args(first, first to listOf(3), second to listOf(1))
            assertEquals(1, tool.execute(input).filterIsInstance<UIMessagePart.Document>().size)
            store.file(second).writeText("source changed after successful assembly")
            assertTrue(tool.execute(input).none { it is UIMessagePart.Document })
            assertEquals(1, store.list(owner).count { it.output })
            assertEquals(first.sha256, MaterialFiles.digest(store.verifiedFile(owner, first.id)))
        } finally { File(context.filesDir, "materials/$owner").deleteRecursively() }
    }
    @Test fun cancellationIsNotReportedAsSuccessfulOrFailedFile(): Unit = runBlocking {
        val owner = UUID.randomUUID().toString(); val store = MaterialFiles(context)
        try {
            val first = source(store, owner); val tool = materialEditTools(store, owner).single { it.name == "edit_material_file" }
            val failure = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Job()) {
                    kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.cancel()
                    tool.execute(args(first, first to listOf(3, 1)))
                }
            }.exceptionOrNull()
            assertTrue(failure is kotlinx.coroutines.CancellationException)
            assertTrue(store.list(owner).none { it.output })
            assertEquals(first.sha256, MaterialFiles.digest(store.verifiedFile(owner, first.id)))
        } finally { File(context.filesDir, "materials/$owner").deleteRecursively() }
    }
}
