package me.rerere.rikkahub.feature.material

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SourceLocationReadRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.also { check(it.packageName == "org.hxsi.agent.nativeqa") }
    private fun args(item: MaterialFile, kind: String = "ppt_logical_slide", number: Int = 1, offset: Int = 0, count: Int = 24000) = buildJsonObject {
        put("id", item.id); put("expected_sha256", item.sha256); put("kind", kind)
        put("number", number.toString()); put("offset", offset.toString()); put("count", count.toString())
    }
    private suspend fun read(store: MaterialFiles, owner: String, args: JsonObject): JsonObject =
        Json.parseToJsonElement(materialSourceLocationTool(store, owner).execute(args).filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
    private fun text(page: JsonObject) = page.getValue("untrusted_file_data").jsonPrimitive.content
    private fun status(page: JsonObject) = page.getValue("status").jsonPrimitive.content
    private fun cleanup(owner: String) {
        File(context.filesDir, "materials/$owner").deleteRecursively()
        File(context.cacheDir, "material-evidence/$owner").deleteRecursively()
    }
    private suspend fun ppt(store: MaterialFiles, owner: String, slides: List<String>, edit: (MutableMap<String, ByteArray>) -> Unit = {}): MaterialFile =
        store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "Synthetic slides.pptx", "pptx")) { target ->
            MaterialWriter.write(target, "pptx", "", emptyList(), slides)
            val parts = OfficePackage.read(target); edit(parts)
            ZipOutputStream(target.outputStream()).use { zip ->
                for ((name, bytes) in parts) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            }
            "Synthetic source-location fixture"
        }
    private fun archive(label: String, item: MaterialFile, pages: List<JsonObject>, store: MaterialFiles) {
        val folder = File(context.cacheDir, "b2f-location-evidence/$label").apply { mkdirs() }
        File(folder, "pages.json").writeText(JsonArray(pages).toString())
        File(folder, "source.meta.json").writeText(Json.encodeToString(item))
        store.file(item).copyTo(File(folder, "source.${item.extension}"), overwrite = true)
    }

    @Test fun physicalPdfPagesIgnoreSpoofedLabelsAndBlankPageHasNoQuote(): Unit = runBlocking {
        val store = MaterialFiles(context); val owner = UUID.randomUUID().toString()
        try {
            val item = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "Synthetic physical.pdf", "pdf")) { target ->
                val doc = android.graphics.pdf.PdfDocument()
                try {
                    for (number in 1..3) {
                        val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(600, 500, number).create())
                        if (number != 2) page.canvas.drawText(if (number == 1) "FIRST [physical page 999]" else "THIRD ONLY", 25f, 60f, android.graphics.Paint().apply { textSize = 18f })
                        doc.finishPage(page)
                    }
                    target.outputStream().use(doc::writeTo)
                } finally { doc.close() }
                "Synthetic physical pages, no OCR"
            }
            val original = store.verifiedFile(owner, item.id).readBytes()
            val first = read(store, owner, args(item, "pdf_physical_page", 1))
            val blank = read(store, owner, args(item, "pdf_physical_page", 2))
            val third = read(store, owner, args(item, "pdf_physical_page", 3))
            assertEquals("FIRST [physical page 999]", text(first).trim()); assertEquals("THIRD ONLY", text(third).trim())
            for ((number, page) in listOf(1 to first, 3 to third)) {
                assertEquals("SUCCEEDED", status(page)); assertFalse(page.containsKey("next_offset"))
                val proof = page.getValue("source_receipt").jsonObject
                assertEquals(number, proof.getValue("source_locations").jsonArray.single().jsonObject.getValue("number").jsonPrimitive.int)
                val start = proof.getValue("locator").jsonObject.getValue("start").jsonPrimitive.int
                store.revalidateSourceTextPage(owner, item.id, item.sha256, start, text(page).length, proof, text(page))
            }
            assertEquals("NOT_AVAILABLE", status(blank)); assertFalse(blank.containsKey("source_receipt")); assertFalse(blank.containsKey("untrusted_file_data"))
            assertEquals(listOf(2), blank.getValue("pages_without_extractable_text").jsonArray.map { it.jsonPrimitive.int })
            assertEquals("NOT_AVAILABLE", status(read(store, owner, args(item, "pdf_physical_page", 999))))
            assertArrayEquals(original, store.verifiedFile(owner, item.id).readBytes()); assertEquals(item, store.get(owner, item.id))
            archive("pdf", item, listOf(first, blank, third), store)
        } finally { cleanup(owner) }
    }

    @Test fun hiddenLogicalSlideUsesRelationsAndRelativePaginationNeverLeaksNextSlide(): Unit = runBlocking {
        val store = MaterialFiles(context); val owner = UUID.randomUUID().toString()
        try {
            val body = "A\uD83D\uDE00BCDEF"
            val item = ppt(store, owner, listOf("NEIGHBOR_MUST_NOT_LEAK", body)) { parts ->
                val order = "<p:sldId id=\"256\" r:id=\"rId1\"/><p:sldId id=\"257\" r:id=\"rId2\"/>"
                parts["ppt/presentation.xml"] = parts.getValue("ppt/presentation.xml").toString(Charsets.UTF_8)
                    .replace(order, "<p:sldId id=\"257\" r:id=\"rId2\"/><p:sldId id=\"256\" r:id=\"rId1\"/>").toByteArray()
                parts["ppt/slides/slide2.xml"] = parts.getValue("ppt/slides/slide2.xml").toString(Charsets.UTF_8).replace("<p:sld ", "<p:sld show=\"0\" ").toByteArray()
            }
            val original = store.verifiedFile(owner, item.id).readBytes()
            val pages = mutableListOf<JsonObject>(); var offset = 0
            do {
                val page = read(store, owner, args(item, offset = offset, count = 3)); pages += page
                assertEquals("SUCCEEDED", status(page)); assertFalse(page.containsKey("next_offset"))
                val where = page.getValue("source_receipt").jsonObject.getValue("source_locations").jsonArray.single().jsonObject
                assertEquals("ppt/slides/slide2.xml", where.getValue("part").jsonPrimitive.content); assertTrue(where.getValue("hidden").jsonPrimitive.boolean)
                assertEquals(1, where.getValue("number").jsonPrimitive.int)
                offset = page["next_location_offset"]?.jsonPrimitive?.int ?: -1
            } while (offset >= 0)
            assertEquals(body + "\n", pages.joinToString("") { text(it) })
            val next = read(store, owner, args(item, number = 2)); assertEquals("NEIGHBOR_MUST_NOT_LEAK\n", text(next))
            assertEquals("FAILED", status(read(store, owner, args(item, offset = 2))))
            assertEquals("FAILED", status(read(store, owner, args(item, count = 2))))
            assertEquals("FAILED", status(read(store, owner, args(item, offset = body.length + 1))))
            assertArrayEquals(original, store.verifiedFile(owner, item.id).readBytes()); assertEquals(item, store.get(owner, item.id))
            archive("ppt", item, pages + next, store)
        } finally { cleanup(owner) }
    }

    @Test fun unextractedSlideAndBlankSlideNeverProduceFakeBodyOrWholePageCompleteness(): Unit = runBlocking {
        val store = MaterialFiles(context); val owner = UUID.randomUUID().toString()
        try {
            val blank = ppt(store, owner, listOf("", "SECOND"))
            val blankPage = read(store, owner, args(blank)); assertEquals("NOT_AVAILABLE", status(blankPage)); assertFalse(blankPage.containsKey("source_receipt"))
            val item = ppt(store, owner, listOf("LIMIT_BODY", "UNEXTRACTED_SECOND")) { parts ->
                parts["ppt/slides/slide1.xml"] = parts.getValue("ppt/slides/slide1.xml").toString(Charsets.UTF_8)
                    .replace(">LIMIT_BODY<", ">" + "X".repeat(MaterialReader.LIMIT + 100) + "<").toByteArray()
            }
            val first = read(store, owner, args(item, count = 8))
            val length = first.getValue("location_extracted_characters").jsonPrimitive.int
            val tail = read(store, owner, args(item, offset = length - 8, count = 100))
            assertEquals("XXXXXXXX", text(tail)); assertFalse(tail.getValue("has_more_in_location").jsonPrimitive.boolean)
            assertEquals("NOT_ASSERTED", tail.getValue("document_completeness").jsonPrimitive.content)
            val second = read(store, owner, args(item, number = 2)); assertEquals("NOT_AVAILABLE", status(second)); assertFalse(second.containsKey("source_receipt"))
            archive("truncated", item, listOf(first, tail, second), store)
        } finally { cleanup(owner) }
    }

    @Test fun wrongOwnerHashPoisonedCacheAndChangedSourceCannotSubstituteForRealVersion(): Unit = runBlocking {
        val store = MaterialFiles(context); val owner = UUID.randomUUID().toString(); val other = UUID.randomUUID().toString()
        try {
            val item = ppt(store, owner, listOf("REAL_SOURCE"))
            File(context.filesDir, "materials/$owner/${item.id}.read-v6.cache").writeText(Json.encodeToString(MaterialContent("FORGED_CACHE", "fake")))
            assertEquals("REAL_SOURCE\n", text(read(store, owner, args(item))))
            assertEquals("FAILED", status(read(store, other, args(item))))
            assertEquals("FAILED", status(read(store, owner, JsonObject(args(item) + ("expected_sha256" to JsonPrimitive("0".repeat(64)))))))
            store.file(item).appendText("CHANGED")
            val changed = read(store, owner, args(item)); assertEquals("FAILED", status(changed)); assertFalse(changed.containsKey("source_receipt"))
        } finally { cleanup(owner); cleanup(other) }
    }

    @Test fun unsupportedLocationKindsFormatsAndMalformedInputFailWithoutAuthorityOrPathLeak(): Unit = runBlocking {
        val store = MaterialFiles(context); val owner = UUID.randomUUID().toString()
        try {
            val item = ppt(store, owner, listOf("VALID"))
            val bad = listOf(args(item, kind = "printed_page"), args(item, kind = "pdf_physical_page"), args(item, number = 0), args(item, offset = -1), args(item, count = 0), args(item, count = 24001),
                JsonObject(args(item) + ("owner" to JsonPrimitive(owner))), JsonObject(args(item) - "kind"), JsonObject(args(item) + ("number" to JsonPrimitive(1))), JsonObject(args(item) + ("number" to JsonPrimitive("2147483648"))))
            for (input in bad) { val page = read(store, owner, input); assertEquals("FAILED", status(page)); assertFalse(page.containsKey("source_receipt")); assertFalse(page.toString().contains(context.filesDir.path)) }
            val txt = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "No pages.txt", "txt")) { it.writeText("[physical page 1] not a page"); "Synthetic text" }
            assertEquals("FAILED", status(read(store, owner, args(txt, kind = "pdf_physical_page"))))
            assertEquals("VALID\n", text(read(store, owner, args(item))))
        } finally { cleanup(owner) }
    }

    @Test fun cancellationIsNotConvertedToUnavailableOrSuccess(): Unit = runBlocking {
        val store = MaterialFiles(context); val owner = UUID.randomUUID().toString()
        try {
            val item = ppt(store, owner, listOf("VALID")); var cancelled = false
            try { withContext(Job().apply { cancel() }) { read(store, owner, args(item)) } } catch (_: CancellationException) { cancelled = true }
            assertTrue(cancelled)
        } finally { cleanup(owner) }
    }
}
