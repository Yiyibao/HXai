package me.rerere.rikkahub.feature.material

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Synthetic parse protocol data, actual scanned PDF/Word files. Never claims real OCR accuracy. */
class PdfOcrDraftRuntimeTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.also { check(it.packageName == "org.hxsi.agent.nativeqa") }
    private data class Scenario(val context: Context, val files: MaterialFiles, val repository: DocumentServiceRepository, val owner: String, val source: MaterialFile)
    private suspend fun scenario(pageCount: Int = 3, block: suspend (Scenario) -> Unit) {
        val root = File(app.cacheDir, "b03-ocr-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
        }
        try {
            val files = MaterialFiles(context); val owner = UUID.randomUUID().toString()
            val source = files.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "synthetic scan.pdf", "pdf")) { output ->
                val pdf = PdfDocument()
                try {
                    for (number in 1..pageCount) {
                        val page = pdf.startPage(PdfDocument.PageInfo.Builder(300, 420, number).create())
                        if (number <= 2) {
                            val bitmap = Bitmap.createBitmap(600, 840, Bitmap.Config.ARGB_8888)
                            try {
                                bitmap.eraseColor(Color.WHITE); val canvas = android.graphics.Canvas(bitmap)
                                canvas.drawText("SCAN PAGE $number", 40f, 100f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 36f })
                                canvas.drawText(if (number == 1) "x versus multiplication sign" else "Table A 7", 40f, 180f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 25f })
                                canvas.drawRect(40f, 240f, 220f, 320f, Paint().apply { color = if (number == 1) Color.BLUE else Color.GREEN })
                                page.canvas.drawBitmap(bitmap, null, android.graphics.Rect(0, 0, 300, 420), null)
                            } finally { bitmap.recycle() }
                        }
                        pdf.finishPage(page)
                    }
                    output.outputStream().use(pdf::writeTo)
                } finally { pdf.close() }
                "Actual $pageCount-page synthetic scan; raster-only first two pages, remaining pages blank"
            }
            block(Scenario(context, files, DocumentServiceRepository(context, files), owner, source))
        } finally { root.deleteRecursively() }
    }
    private fun document(source: MaterialFile) = ParsedDocument(1, source.sha256, 1, "synthetic-b03-contract-fixture", "1", "basic", 3,
        listOf(1, 3), listOf(1, 2, 3), listOf(3), emptyList(), true, listOf("OCR symbols require original-page review; this is synthetic protocol data."),
        listOf(ParsedBlock("p1-b0", 1, "text", "Recognized candidate lesson A"), ParsedBlock("p1-b1", 1, "equation", "x ? 1", "x\\times 1"), ParsedBlock("p2-b0", 2, "table_body", "Header\tValue\nA\t7")))
    private fun cache(s: Scenario, value: ParsedDocument, authoritative: ParsedDocument = value, expired: Boolean = false): Pair<String, File> {
        val id = UUID.randomUUID().toString(); val directory = File(s.context.noBackupFilesDir, "document-service/${s.owner}/$id.result").apply { mkdirs() }
        val file = File(directory, "document").apply { writeText(Json.encodeToString(value)) }
        val manifest = DocumentManifest(1, s.source.sha256, authoritative.resultRevision, authoritative.parserVersion, authoritative.adapterVersion,
            authoritative.tier, authoritative.totalPages, authoritative.requestedRange, authoritative.processedPages, authoritative.blankPages,
            authoritative.failedPages, authoritative.complete, authoritative.warnings,
            listOf(DocumentAsset("document", "document.json", "application/json", file.length(), MaterialFiles.digest(file))))
        File(directory, "manifest.json").writeText(Json.encodeToString(manifest))
        File(directory.parentFile, "$id.task.json").writeText(Json.encodeToString(DocumentTask(id, s.source.id, s.source.sha256,
            authoritative.requestedRange[0], authoritative.requestedRange[1], "synthetic-offline-cache", remoteId = id,
            state = if (expired) "EXPIRED" else "SUCCEEDED", downloaded = true)))
        return id to directory
    }
    private fun args(source: MaterialFile, ids: List<String>, start: Int = 1, end: Int = 3, title: String = "Scanned candidate Word") = buildJsonObject {
        put("id", source.id); put("expected_sha256", source.sha256); put("operation", "pdf_to_editable_docx"); put("title", title)
        put("spec", buildJsonObject { put("task_ids", JsonArray(ids.map(::JsonPrimitive))); put("start_page", start); put("end_page", end) }.toString())
    }
    @Test fun inconsistentDownloadedCoverageOrSuppressedWarningsAreNeverTrusted(): Unit = runBlocking {
        scenario { s ->
            val good = document(s.source)
            val falseFull = good.copy(totalPages = 1, requestedRange = listOf(1, 1), processedPages = listOf(1), blankPages = emptyList(), blocks = good.blocks.filter { it.page == 1 })
            val duplicateMissing = good.copy(processedPages = listOf(1, 3), failedPages = listOf(2, 2), complete = false, blocks = good.blocks.filter { it.page != 2 })
            val falseBlank = good.copy(blankPages = listOf(1, 3))
            val cases = listOf(
                Triple("total-pages-disagrees", good.copy(totalPages = 2), good),
                Triple("suppressed-symbol-warning", good.copy(warnings = emptyList()), good),
                Triple("adapter-disagrees", good.copy(adapterVersion = "wrong-adapter"), good),
                Triple("false-blank-page", good.copy(blankPages = listOf(1, 3)), good),
                Triple("false-failed-page", good.copy(failedPages = listOf(2)), good),
                Triple("false-whole-source-coverage", falseFull, falseFull),
                Triple("consistent-blank-metadata-with-recognized-content", falseBlank, falseBlank),
                Triple("duplicate-missing-page", duplicateMissing, duplicateMissing))
            val evidence = File(app.cacheDir, "b03-parser-contract-evidence").apply { mkdirs() }
            s.files.verifiedFile(s.owner, s.source.id).copyTo(File(evidence, "source.pdf"), overwrite = true)
            val results = buildJsonArray {
                for ((name, value, authoritative) in cases) {
                    val (id, directory) = cache(s, value, authoritative)
                    val read = runCatching { s.repository.read(s.owner, id) }
                    File(directory, "document").copyTo(File(evidence, "$name-document.json"), overwrite = true)
                    File(directory, "manifest.json").copyTo(File(evidence, "$name-manifest.json"), overwrite = true)
                    add(buildJsonObject { put("case", name); put("accepted", read.isSuccess); put("error", read.exceptionOrNull()?.message) })
                }
            }
            File(evidence, "result.json").writeText(buildJsonObject { put("sourceSha256", s.source.sha256); put("actualSourcePages", 3); put("cases", results); put("realOcr", "NOT_RUN") }.toString())
            assertTrue("Every inconsistent cache must be rejected; see preserved per-case input files", results.all { !it.jsonObject.getValue("accepted").jsonPrimitive.boolean })
        }
    }
    private fun pixels(bitmap: Bitmap): String {
        val bytes = ByteBuffer.allocate(bitmap.byteCount); bitmap.copyPixelsToBuffer(bytes)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
    }
    @Test fun verifiedOfflineCacheProducesEditableCandidateWordWithTrueSourcePagesAndNoFalseOcrClaim(): Unit = runBlocking {
        scenario { s ->
            val good = document(s.source); val (task, directory) = cache(s, good, expired = true)
            val sourceFile = s.files.verifiedFile(s.owner, s.source.id); val original = sourceFile.readBytes()
            val evidence = File(app.cacheDir, "b03-ocr-draft-evidence").apply { mkdirs() }
            sourceFile.copyTo(File(evidence, "source.pdf"), overwrite = true)
            File(directory, "document").copyTo(File(evidence, "synthetic-parsed-document.json"), overwrite = true)
            File(directory, "manifest.json").copyTo(File(evidence, "synthetic-manifest.json"), overwrite = true)
            assertFalse(s.repository.enabled(s.owner))
            val edit = materialEditTools(s.files, s.owner, s.repository, allowRemote = false).single { it.name == "edit_material_file" }
            val input = args(s.source, listOf(task))
            val reply = edit.execute(input)
            assertTrue("An owned, verified offline parse cache must produce an actual editable DOCX", reply.any { it is UIMessagePart.Document })
            val output = s.files.list(s.owner).single { it.output && it.state == "SUCCEEDED" }
            assertEquals("docx", output.extension); assertEquals(s.source.id, output.sourceId)
            val actual = s.files.verifiedFile(s.owner, output.id)
            val receipt = Json.parseToJsonElement(output.detail).jsonObject
            assertEquals("pdf_ocr_editable_draft", receipt.getValue("scope").jsonPrimitive.content)
            assertEquals("UNVERIFIED", receipt.getValue("recognition_correctness").jsonPrimitive.content)
            assertEquals(3, receipt.getValue("source_total_pages").jsonPrimitive.int)
            assertTrue(receipt.getValue("entire_source_selected").jsonPrimitive.boolean)
            assertEquals(listOf(3), receipt.getValue("pages_without_editable_text").jsonArray.map { it.jsonPrimitive.int })
            val text = MaterialReader.read(actual, "docx").text
            assertTrue(text.contains("Recognized candidate lesson A") && text.contains("x\\times 1") && text.contains("p1-b1"))
            assertTrue(text.contains(good.warnings.single()))
            val pictures = MaterialWordContent.images(actual, 0, 5)
            assertEquals(3, pictures.size)
            ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor -> PdfRenderer(descriptor).use { source ->
                pictures.forEachIndexed { index, (_, bytes) ->
                    source.openPage(index).use { page ->
                        val scale = 1536.0 / maxOf(page.width, page.height)
                        val expected = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                        val decoded = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        try { expected.eraseColor(Color.WHITE); page.render(expected, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); assertEquals(pixels(expected), pixels(decoded)) }
                        finally { expected.recycle(); decoded.recycle() }
                    }
                }
            } }
            val succeeded = s.files.list(s.owner).filter { it.output && it.state == "SUCCEEDED" }.map { it.id }.toSet()
            assertTrue(edit.execute(input).any { it is UIMessagePart.Document })
            assertEquals(succeeded, s.files.list(s.owner).filter { it.output && it.state == "SUCCEEDED" }.map { it.id }.toSet())
            val partialReply = edit.execute(args(s.source, listOf(task), 2, 3, "Explicit selected pages"))
            assertTrue(partialReply.any { it is UIMessagePart.Document })
            val selected = s.files.list(s.owner).single { it.output && it.state == "SUCCEEDED" && it.id != output.id }
            val selectedReceipt = Json.parseToJsonElement(selected.detail).jsonObject
            assertFalse(selectedReceipt.getValue("entire_source_selected").jsonPrimitive.boolean)
            assertEquals(listOf(2, 3), selectedReceipt.getValue("selected_source_pages").jsonArray.map { it.jsonPrimitive.int })
            assertFalse(edit.execute(JsonObject(input + ("expected_sha256" to JsonPrimitive("0".repeat(64))))).any { it is UIMessagePart.Document })
            assertFalse(materialEditTools(s.files, UUID.randomUUID().toString(), s.repository).single { it.name == "edit_material_file" }.execute(input).any { it is UIMessagePart.Document })
            val onlyFirst = good.copy(requestedRange = listOf(1, 1), processedPages = listOf(1), blankPages = emptyList(), complete = false, blocks = good.blocks.filter { it.page == 1 })
            val (incompleteTask, _) = cache(s, onlyFirst)
            assertFalse(edit.execute(args(s.source, listOf(incompleteTask))).any { it is UIMessagePart.Document })
            assertFalse(edit.execute(args(s.source, listOf(task, task))).any { it is UIMessagePart.Document })
            assertArrayEquals(original, sourceFile.readBytes())
            actual.copyTo(File(evidence, "editable-draft.docx"), overwrite = true)
            s.files.verifiedFile(s.owner, selected.id).copyTo(File(evidence, "selected-draft.docx"), overwrite = true)
            File(evidence, "output.meta.json").writeText(Json.encodeToString(output))
            File(evidence, "selected.meta.json").writeText(Json.encodeToString(selected))
            actual.appendText("corrupted after successful publication")
            assertFalse(edit.execute(input).any { it is UIMessagePart.Document })
            assertEquals(2, s.files.list(s.owner).count { it.output && it.state == "SUCCEEDED" })
            File(evidence, "result.json").writeText(buildJsonObject { put("status", "PASS"); put("sourceSha256", s.source.sha256); put("artifactSha256", output.sha256); put("selectedArtifactSha256", selected.sha256); put("receipt", receipt); put("selectedReceipt", selectedReceipt); put("realOcr", "NOT_RUN"); put("cacheOrigin", "SYNTHETIC_PROTOCOL_FIXTURE"); put("networkUploadEnabled", false) }.toString())
        }
    }
    @Test fun sixPageDraftKeepsEverySourceReferenceWithoutRelaxingOrdinaryCreateImageLimit(): Unit = runBlocking {
        scenario(pageCount = 6) { s ->
            val first = document(s.source).copy(totalPages = 6, complete = false)
            val second = first.copy(requestedRange = listOf(4, 6), processedPages = (4..6).toList(),
                blankPages = (4..6).toList(), blocks = emptyList())
            val (firstTask, firstDirectory) = cache(s, first, expired = true)
            val (secondTask, secondDirectory) = cache(s, second, expired = true)
            val evidence = File(app.cacheDir, "b03-ocr-six-page-evidence").apply { mkdirs() }
            s.files.verifiedFile(s.owner, s.source.id).copyTo(File(evidence, "source.pdf"), overwrite = true)
            for ((index, directory) in listOf(firstDirectory, secondDirectory).withIndex()) {
                for (name in listOf("document", "manifest.json")) {
                    File(directory, name).copyTo(File(evidence, "chunk-${index + 1}-$name"), overwrite = true)
                }
            }
            val edit = materialEditTools(s.files, s.owner, s.repository, allowRemote = false).single { it.name == "edit_material_file" }
            val reply = edit.execute(args(s.source, listOf(firstTask, secondTask), end = 6))
            assertEquals(reply.toString(), 1, reply.filterIsInstance<UIMessagePart.Document>().size)
            val output = s.files.list(s.owner).single { it.output && it.state == "SUCCEEDED" }
            val receipt = Json.parseToJsonElement(output.detail).jsonObject
            val references = receipt.getValue("reference_images").jsonArray
            assertEquals((1..6).toList(), references.map { it.jsonObject.getValue("source_physical_page").jsonPrimitive.int })
            val actual = s.files.verifiedFile(s.owner, output.id)
            val images = java.util.zip.ZipFile(actual).use { zip ->
                references.map { entry ->
                    val reference = entry.jsonObject
                    zip.getInputStream(zip.getEntry(reference.getValue("image_part").jsonPrimitive.content)).use { stream ->
                        stream.readBytes().also { bytes ->
                            assertEquals(reference.getValue("png_sha256").jsonPrimitive.content,
                                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                        }
                    }
                }
            }
            val ordinary = File(s.context.cacheDir, "ordinary-six-images.docx")
            assertTrue(runCatching { MaterialWriter.write(ordinary, "docx", "Ordinary create must retain its five-image boundary", emptyList(), emptyList(), images = images) }.exceptionOrNull() is IllegalArgumentException)
            assertFalse(ordinary.exists())
            actual.copyTo(File(evidence, "editable-draft.docx"), overwrite = true)
            File(evidence, "output.meta.json").writeText(Json.encodeToString(output))
            File(evidence, "result.json").writeText(buildJsonObject {
                put("status", "PASS"); put("sourceSha256", s.source.sha256); put("artifactSha256", output.sha256)
                put("receipt", receipt); put("realOcr", "NOT_RUN"); put("cacheOrigin", "SYNTHETIC_PROTOCOL_FIXTURE")
                put("ordinaryCreateSixImagesRejectedWithoutOutput", true)
            }.toString())
        }
    }

    private suspend fun cancelOffline(s: Scenario, task: String) {
        assertFalse(s.repository.enabled(s.owner))
        try { s.repository.cancel(s.owner, task) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { /* No remote configuration: the local cancellation intent must still persist. */ }
        assertTrue(s.repository.tasks(s.owner).single { it.id == task }.cancelRequested)
    }

    @Test fun cancelledDownloadedParseCannotCreateNewWord(): Unit = runBlocking {
        scenario { s ->
            val (task, _) = cache(s, document(s.source))
            val original = s.files.verifiedFile(s.owner, s.source.id).readBytes()
            cancelOffline(s, task)
            val edit = materialEditTools(s.files, s.owner, s.repository, allowRemote = false).single { it.name == "edit_material_file" }
            val reply = edit.execute(args(s.source, listOf(task)))
            assertFalse("A persisted cancellation must block a new OCR Word draft", reply.any { it is UIMessagePart.Document })
            assertFalse(s.files.list(s.owner).any { it.output && it.state == "SUCCEEDED" })
            assertArrayEquals(original, s.files.verifiedFile(s.owner, s.source.id).readBytes())
        }
    }

    @Test fun cancellationBlocksDraftReuseWithoutDeletingPreviouslyVerifiedWord(): Unit = runBlocking {
        scenario { s ->
            val (task, _) = cache(s, document(s.source))
            val edit = materialEditTools(s.files, s.owner, s.repository, allowRemote = false).single { it.name == "edit_material_file" }
            val input = args(s.source, listOf(task))
            assertTrue(edit.execute(input).any { it is UIMessagePart.Document })
            val previous = s.files.list(s.owner).single { it.output && it.state == "SUCCEEDED" }
            val previousBytes = s.files.verifiedFile(s.owner, previous.id).readBytes()
            cancelOffline(s, task)
            assertFalse(edit.execute(input).any { it is UIMessagePart.Document })
            assertFalse(edit.execute(args(s.source, listOf(task), title = "After cancellation")).any { it is UIMessagePart.Document })
            assertArrayEquals(previousBytes, s.files.verifiedFile(s.owner, previous.id).readBytes())
            assertEquals(listOf(previous.id), s.files.list(s.owner).filter { it.output && it.state == "SUCCEEDED" }.map { it.id })
        }
    }
}
