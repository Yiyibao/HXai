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

class ChapterIndexRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.also { check(it.packageName == "org.hxsi.agent.nativeqa") }
    private fun args(item: MaterialFile, offset: Int = 0, limit: Int = 20) = buildJsonObject {
        put("id", item.id); put("expected_sha256", item.sha256); put("offset", offset.toString()); put("limit", limit.toString())
    }
    private suspend fun index(store: MaterialFiles, owner: String, args: JsonObject): JsonObject =
        Json.parseToJsonElement(materialChapterIndexTool(store, owner).execute(args).filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
    private fun status(value: JsonObject) = value.getValue("status").jsonPrimitive.content
    private suspend fun markdown(store: MaterialFiles, owner: String, body: String = "preface\n# Same 😀\nfirst body\n# Same 😀\nsecond body\n") =
        store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "synthetic.md", "md")) { it.writeText(body); "synthetic headings" }
    private fun cleanup(owner: String) { File(context.filesDir, "materials/$owner").deleteRecursively(); File(context.cacheDir, "material-evidence/$owner").deleteRecursively() }
    private fun archive(label: String, item: MaterialFile, store: MaterialFiles, pages: List<JsonObject>) {
        val target = File(context.cacheDir, "b-chapter-index-evidence/$label").apply { mkdirs() }
        store.verifiedFile(item.profileId, item.id).copyTo(File(target, "source.${item.extension}"), overwrite = true)
        File(target, "source.meta.json").writeText(Json.encodeToString(item))
        File(context.filesDir, "materials/${item.profileId}/${item.id}.chapters-v1.cache").copyTo(File(target, "persisted-index.json"), overwrite = true)
        File(target, "pages.json").writeText(JsonArray(pages).toString())
    }

    @Test fun realMarkdownIndexPersistsAcrossReopenAndPaginationKeepsDuplicateTitlesDistinct(): Unit = runBlocking {
        val store = MaterialFiles(context); val owner = UUID.randomUUID().toString()
        try {
            val item = markdown(store, owner); val before = store.verifiedFile(owner, item.id).readBytes()
            val a = index(store, owner, args(item, limit = 1)); val b = index(MaterialFiles(context), owner, args(item, offset = 1, limit = 1))
            assertEquals("SUCCEEDED", status(a)); assertEquals("CREATED", a.getValue("index_storage").jsonPrimitive.content)
            assertEquals("REVALIDATED", b.getValue("index_storage").jsonPrimitive.content); assertEquals(a["index_sha256"], b["index_sha256"])
            assertTrue(a.getValue("has_more").jsonPrimitive.boolean); assertFalse(b.getValue("has_more").jsonPrimitive.boolean)
            val chapters = a.getValue("chapters").jsonArray + b.getValue("chapters").jsonArray
            assertEquals(chapters[0].jsonObject["title"], chapters[1].jsonObject["title"])
            assertNotEquals(chapters[0].jsonObject["chapter_id"], chapters[1].jsonObject["chapter_id"])
            for (chapter in chapters.map { it.jsonObject }) {
                val page = chapter.getValue("heading_evidence").jsonObject; val quote = page.getValue("untrusted_file_data").jsonPrimitive.content
                store.revalidateSourceTextPage(owner, item.id, item.sha256, chapter.getValue("start").jsonPrimitive.int, quote.length, page.getValue("source_receipt").jsonObject, quote)
            }
            assertArrayEquals(before, store.verifiedFile(owner, item.id).readBytes()); assertEquals(item, store.get(owner, item.id))
            archive("markdown", item, store, listOf(a, b))
        } finally { cleanup(owner) }
    }
    @Test fun pdfIndexReportsRealPhysicalPagesAndBlankCoverageWithoutInventingChapters(): Unit = runBlocking {
        val store = MaterialFiles(context); val owner = UUID.randomUUID().toString()
        try {
            val item = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "synthetic.pdf", "pdf")) { target ->
                val doc = android.graphics.pdf.PdfDocument()
                try {
                    for (number in 1..3) {
                        val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(500, 500, number).create())
                        if (number != 2) {
                            val paint = android.graphics.Paint().apply { textSize = 18f }
                            page.canvas.drawText("Chapter ${if (number == 1) 1 else 2} Real", 25f, 60f, paint)
                            page.canvas.drawText("BODY_$number", 25f, 95f, paint)
                        }
                        doc.finishPage(page)
                    }
                    target.outputStream().use(doc::writeTo)
                } finally { doc.close() }
                "synthetic PDF chapters"
            }
            val page = index(store, owner, args(item)); assertEquals("SUCCEEDED", status(page))
            val chapters = page.getValue("chapters").jsonArray
            assertEquals(listOf(1, 3), chapters.map { it.jsonObject.getValue("heading_evidence").jsonObject.getValue("source_receipt").jsonObject.getValue("source_locations").jsonArray.single().jsonObject.getValue("number").jsonPrimitive.int })
            assertEquals(listOf(2), page.getValue("pages_without_extractable_text").jsonArray.map { it.jsonPrimitive.int })
            assertEquals("NOT_ASSERTED", page.getValue("document_completeness").jsonPrimitive.content)
            assertEquals(item.sha256, MaterialFiles.digest(store.verifiedFile(owner, item.id)))
            archive("pdf", item, store, listOf(page))
        } finally { cleanup(owner) }
    }
    @Test fun reorderedHiddenPptUsesActualLogicalSlideAndPartNotFilenameOrTitleDeduplication(): Unit = runBlocking {
        val store = MaterialFiles(context); val owner = UUID.randomUUID().toString()
        try {
            val item = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "synthetic.pptx", "pptx")) { target ->
                MaterialWriter.write(target, "pptx", "", emptyList(), listOf("# Same\nFIRST_FILE", "# Same\nSECOND_FILE"))
                val parts = OfficePackage.read(target)
                parts["ppt/presentation.xml"] = parts.getValue("ppt/presentation.xml").toString(Charsets.UTF_8)
                    .replace("<p:sldId id=\"256\" r:id=\"rId1\"/><p:sldId id=\"257\" r:id=\"rId2\"/>", "<p:sldId id=\"257\" r:id=\"rId2\"/><p:sldId id=\"256\" r:id=\"rId1\"/>").toByteArray()
                parts["ppt/slides/slide2.xml"] = parts.getValue("ppt/slides/slide2.xml").toString(Charsets.UTF_8).replace("<p:sld ", "<p:sld show=\"0\" ").toByteArray()
                ZipOutputStream(target.outputStream()).use { zip -> parts.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
                "synthetic PPT chapters"
            }
            val page = index(store, owner, args(item)); assertEquals("SUCCEEDED", status(page))
            val chapters = page.getValue("chapters").jsonArray
            assertEquals(2, chapters.size)
            val where = chapters.first().jsonObject.getValue("heading_evidence").jsonObject.getValue("source_receipt").jsonObject.getValue("source_locations").jsonArray.single().jsonObject
            assertEquals("ppt/slides/slide2.xml", where.getValue("part").jsonPrimitive.content); assertTrue(where.getValue("hidden").jsonPrimitive.boolean)
            assertEquals(1, where.getValue("number").jsonPrimitive.int)
            archive("ppt", item, store, listOf(page))
        } finally { cleanup(owner) }
    }
    @Test fun poisonedDerivedAndReadCachesNeverReplaceFrozenOriginal(): Unit = runBlocking {
        val store = MaterialFiles(context); val owner = UUID.randomUUID().toString()
        try {
            val item = markdown(store, owner, "# REAL_SOURCE\nbody\n"); val first = index(store, owner, args(item))
            File(context.filesDir, "materials/$owner/${item.id}.chapters-v1.cache").writeText("FORGED_INDEX")
            File(context.filesDir, "materials/$owner/${item.id}.read-v6.cache").writeText(Json.encodeToString(MaterialContent("# FORGED_CACHE", "fake")))
            val rebuilt = index(MaterialFiles(context), owner, args(item))
            assertEquals("SUCCEEDED", status(rebuilt)); assertEquals(first["index_sha256"], rebuilt["index_sha256"])
            assertEquals("REBUILT_FROM_VERIFIED_SOURCE", rebuilt.getValue("index_storage").jsonPrimitive.content)
            assertEquals("# REAL_SOURCE", rebuilt.getValue("chapters").jsonArray.single().jsonObject.getValue("title").jsonPrimitive.content)
            assertEquals(item, store.get(owner, item.id))
        } finally { cleanup(owner) }
    }
    @Test fun wrongOwnerHashChangedSourceAndMalformedInputNeverSignIndex(): Unit = runBlocking {
        val store = MaterialFiles(context); val owner = UUID.randomUUID().toString(); val other = UUID.randomUUID().toString()
        try {
            val item = markdown(store, owner)
            assertEquals("FAILED", status(index(store, other, args(item))))
            for (input in listOf(JsonObject(args(item) + ("expected_sha256" to JsonPrimitive("0".repeat(64)))), args(item, offset = -1), args(item, limit = 21), JsonObject(args(item) + ("owner" to JsonPrimitive(owner))), JsonObject(args(item) + ("offset" to JsonPrimitive(0))))) {
                val page = index(store, owner, input); assertEquals("FAILED", status(page)); assertFalse(page.containsKey("index_sha256")); assertFalse(page.toString().contains(context.filesDir.path))
            }
            assertEquals("SUCCEEDED", status(index(store, owner, args(item))))
            store.file(item).appendText("# CHANGED\n")
            assertEquals("FAILED", status(index(store, owner, args(item))))
        } finally { cleanup(owner); cleanup(other) }
    }
    @Test fun invalidationAfterSourceDeletionCannotResurrectMaterialOrTouchOtherOwner(): Unit = runBlocking {
        val store = MaterialFiles(context); val owner = UUID.randomUUID().toString(); val other = UUID.randomUUID().toString()
        try {
            val item = markdown(store, owner); val foreign = markdown(store, other)
            index(store, owner, args(item)); index(store, other, args(foreign))
            store.file(item).delete(); File(context.filesDir, "materials/$owner/${item.id}.meta.json").delete()
            store.invalidateChapterIndex(owner, item.id)
            assertFalse(File(context.filesDir, "materials/$owner/${item.id}.chapters-v1.cache").exists())
            assertTrue(File(context.filesDir, "materials/$other/${foreign.id}.chapters-v1.cache").isFile)
            assertTrue(store.list(owner).isEmpty()); assertEquals("FAILED", status(index(store, owner, args(item))))
            assertEquals("SUCCEEDED", status(index(store, other, args(foreign))))
        } finally { cleanup(owner); cleanup(other) }
    }
    @Test fun noDetectedHeadingIsNotWholeBookProofAndCancellationPropagates(): Unit = runBlocking {
        val store = MaterialFiles(context); val owner = UUID.randomUUID().toString()
        try {
            val item = markdown(store, owner, "ordinary body without supported headings")
            val page = index(store, owner, args(item)); assertTrue(page.getValue("chapters").jsonArray.isEmpty())
            assertEquals("NOT_ASSERTED", page.getValue("document_completeness").jsonPrimitive.content)
            var cancelled = false
            try { withContext(Job().apply { cancel() }) { index(store, owner, args(item)) } } catch (_: CancellationException) { cancelled = true }
            assertTrue(cancelled)
        } finally { cleanup(owner) }
    }
    @Test fun unextractedTwoHundredFirstPageIsNeverInventedOrDeclaredCovered(): Unit = runBlocking {
        val store = MaterialFiles(context); val owner = UUID.randomUUID().toString()
        try {
            val item = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "long.pdf", "pdf")) { target ->
                val doc = android.graphics.pdf.PdfDocument()
                try {
                    for (number in 1..201) {
                        val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(300, 300, number).create())
                        if (number == 1 || number == 201) page.canvas.drawText("Chapter $number", 20f, 50f, android.graphics.Paint().apply { textSize = 16f })
                        doc.finishPage(page)
                    }
                    target.outputStream().use(doc::writeTo)
                } finally { doc.close() }
                "synthetic extraction coverage"
            }
            val page = index(store, owner, args(item))
            assertEquals(201, page.getValue("total_pages").jsonPrimitive.int)
            assertEquals(200, page.getValue("extracted_pages").jsonPrimitive.int)
            assertEquals("Chapter 1", page.getValue("chapters").jsonArray.single().jsonObject.getValue("title").jsonPrimitive.content)
            assertEquals("NOT_ASSERTED", page.getValue("document_completeness").jsonPrimitive.content)
        } finally { cleanup(owner) }
    }
}
