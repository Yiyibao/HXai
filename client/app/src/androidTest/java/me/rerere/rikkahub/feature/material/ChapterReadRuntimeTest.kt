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

class ChapterReadRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.also { check(it.packageName == "org.hxsi.agent.nativeqa") }
    private fun args(item: MaterialFile, index: JsonObject, chapter: Int = 0, offset: Int = 0, count: Int = 24000) = buildJsonObject {
        put("id", item.id); put("expected_sha256", item.sha256); put("index_sha256", index.getValue("index_sha256"))
        put("chapter_id", index.getValue("chapters").jsonArray[chapter].jsonObject.getValue("chapter_id"))
        put("offset", offset.toString()); put("count", count.toString())
    }
    private suspend fun read(store: MaterialFiles, owner: String, input: JsonObject) =
        Json.parseToJsonElement(materialChapterReadTool(store, owner).execute(input).filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
    private fun text(page: JsonObject) = page.getValue("untrusted_file_data").jsonPrimitive.content
    private fun status(page: JsonObject) = page.getValue("status").jsonPrimitive.content
    private suspend fun markdown(store: MaterialFiles, owner: String, body: String) =
        store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "synthetic.md", "md")) { it.writeText(body); "synthetic chapter read" }
    private fun cleanup(owner: String) { File(context.filesDir, "materials/$owner").deleteRecursively(); File(context.cacheDir, "material-evidence/$owner").deleteRecursively() }
    private suspend fun verify(store: MaterialFiles, owner: String, item: MaterialFile, page: JsonObject) {
        val proof = page.getValue("source_receipt").jsonObject
        val offset = proof.getValue("locator").jsonObject.getValue("start").jsonPrimitive.int
        store.revalidateSourceTextPage(owner, item.id, item.sha256, offset, text(page).length, proof, text(page))
    }
    private fun archive(label: String, item: MaterialFile, store: MaterialFiles, index: JsonObject, pages: List<JsonObject>) {
        val folder = File(context.cacheDir, "b-chapter-read-evidence/$label").apply { mkdirs() }
        store.verifiedFile(item.profileId, item.id).copyTo(File(folder, "source.${item.extension}"), overwrite = true)
        File(folder, "source.meta.json").writeText(Json.encodeToString(item)); File(folder, "index-page.json").writeText(index.toString())
        File(folder, "pages.json").writeText(JsonArray(pages).toString())
        File(context.filesDir, "materials/${item.profileId}/${item.id}.chapters-v1.cache").copyTo(File(folder, "persisted-index.json"), overwrite = true)
    }
    @Test fun duplicateMarkdownTitlesReadDifferentBodiesWithExactBoundariesAndSourceReceipts(): Unit = runBlocking {
        val owner = UUID.randomUUID().toString(); val store = MaterialFiles(context)
        try {
            val item = markdown(store, owner, "preface\n# Same\nFIRST_BODY\n# Same\nSECOND_BODY\n")
            val before = store.verifiedFile(owner, item.id).readBytes(); val index = store.chapterIndex(owner, item.id, item.sha256, 0, 20)
            val a = read(store, owner, args(item, index)); val b = read(store, owner, args(item, index, chapter = 1))
            assertEquals("# Same\nFIRST_BODY\n", text(a)); assertEquals("# Same\nSECOND_BODY\n", text(b))
            assertFalse(a.getValue("has_more_in_chapter").jsonPrimitive.boolean); assertFalse(b.getValue("has_more_in_chapter").jsonPrimitive.boolean)
            assertFalse(a.containsKey("next_offset")); verify(store, owner, item, a); verify(store, owner, item, b)
            assertArrayEquals(before, store.verifiedFile(owner, item.id).readBytes()); assertEquals(item, store.get(owner, item.id))
            archive("markdown", item, store, index, listOf(a, b))
        } finally { cleanup(owner) }
    }
    @Test fun chapterSpanningPdfBlankPageReadsOnlyPhysicalBodyChunksAndNeverTheNextChapter(): Unit = runBlocking {
        val owner = UUID.randomUUID().toString(); val store = MaterialFiles(context)
        try {
            val item = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "synthetic.pdf", "pdf")) { target ->
                val doc = android.graphics.pdf.PdfDocument()
                try {
                    for (number in 1..4) {
                        val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(500, 500, number).create())
                        val lines = when (number) { 1 -> listOf("Chapter 1", "BODY_ONE"); 3 -> listOf("CONTINUED_BODY"); 4 -> listOf("Chapter 2", "DO_NOT_LEAK"); else -> emptyList() }
                        val paint = android.graphics.Paint().apply { textSize = 18f }
                        lines.forEachIndexed { n, line -> page.canvas.drawText(line, 25f, 60f + n * 35, paint) }
                        doc.finishPage(page)
                    }
                    target.outputStream().use(doc::writeTo)
                } finally { doc.close() }
                "synthetic chapter with blank physical page"
            }
            val index = store.chapterIndex(owner, item.id, item.sha256, 0, 20)
            val first = read(store, owner, args(item, index))
            val second = read(store, owner, args(item, index, offset = first.getValue("next_chapter_offset").jsonPrimitive.int))
            assertTrue(text(first).contains("Chapter 1") && text(first).contains("BODY_ONE"))
            assertEquals("CONTINUED_BODY", text(second).trim())
            assertTrue(second.getValue("chapter_offset").jsonPrimitive.int > second.getValue("requested_chapter_offset").jsonPrimitive.int)
            assertFalse(second.getValue("has_more_in_chapter").jsonPrimitive.boolean)
            for ((number, page) in listOf(1 to first, 3 to second)) {
                assertFalse(text(page).contains("物理页") || text(page).contains("无可提取") || text(page).contains("Chapter 2"))
                assertEquals(number, page.getValue("source_receipt").jsonObject.getValue("source_locations").jsonArray.single().jsonObject.getValue("number").jsonPrimitive.int)
                verify(store, owner, item, page)
            }
            val end = read(store, owner, args(item, index, offset = first.getValue("chapter_span_characters").jsonPrimitive.int))
            assertEquals("NOT_AVAILABLE", status(end)); assertFalse(end.containsKey("source_receipt")); assertFalse(end.containsKey("untrusted_file_data"))
            archive("pdf", item, store, index, listOf(first, second, end))
        } finally { cleanup(owner) }
    }
    @Test fun hiddenReorderedPptChapterRetainsLogicalLocationWithoutCrossingIntoNeighbor(): Unit = runBlocking {
        val owner = UUID.randomUUID().toString(); val store = MaterialFiles(context)
        try {
            val item = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "synthetic.pptx", "pptx")) { target ->
                MaterialWriter.write(target, "pptx", "", emptyList(), listOf("# Second\nDO_NOT_LEAK", "# First\nHIDDEN_BODY"))
                val parts = OfficePackage.read(target)
                parts["ppt/presentation.xml"] = parts.getValue("ppt/presentation.xml").toString(Charsets.UTF_8)
                    .replace("<p:sldId id=\"256\" r:id=\"rId1\"/><p:sldId id=\"257\" r:id=\"rId2\"/>", "<p:sldId id=\"257\" r:id=\"rId2\"/><p:sldId id=\"256\" r:id=\"rId1\"/>").toByteArray()
                parts["ppt/slides/slide2.xml"] = parts.getValue("ppt/slides/slide2.xml").toString(Charsets.UTF_8).replace("<p:sld ", "<p:sld show=\"0\" ").toByteArray()
                ZipOutputStream(target.outputStream()).use { zip -> parts.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
                "synthetic hidden chapter"
            }
            val index = store.chapterIndex(owner, item.id, item.sha256, 0, 20); val page = read(store, owner, args(item, index))
            assertEquals("# First\nHIDDEN_BODY\n", text(page)); assertFalse(page.getValue("has_more_in_chapter").jsonPrimitive.boolean)
            val loc = page.getValue("source_receipt").jsonObject.getValue("source_locations").jsonArray.single().jsonObject
            assertEquals("ppt/slides/slide2.xml", loc.getValue("part").jsonPrimitive.content); assertTrue(loc.getValue("hidden").jsonPrimitive.boolean)
            verify(store, owner, item, page); archive("ppt", item, store, index, listOf(page))
        } finally { cleanup(owner) }
    }
    @Test fun unicodeBoundariesRejectHalfCharacterButValidWholeSymbolStaysReadable(): Unit = runBlocking {
        val owner = UUID.randomUUID().toString(); val store = MaterialFiles(context)
        try {
            val body = "# One\n😀Z\n"; val item = markdown(store, owner, body); val index = store.chapterIndex(owner, item.id, item.sha256, 0, 20)
            val offset = body.indexOf("😀")
            assertEquals("FAILED", status(read(store, owner, args(item, index, offset = offset, count = 1))))
            assertEquals("FAILED", status(read(store, owner, args(item, index, offset = offset + 1, count = 2))))
            val page = read(store, owner, args(item, index, offset = offset, count = 2)); assertEquals("😀", text(page)); verify(store, owner, item, page)
        } finally { cleanup(owner) }
    }
    @Test fun wrongSnapshotUnknownChapterForeignOwnerAndChangedSourceCannotIssueQuote(): Unit = runBlocking {
        val owner = UUID.randomUUID().toString(); val other = UUID.randomUUID().toString(); val store = MaterialFiles(context)
        try {
            val item = markdown(store, owner, "# Real\nBODY\n"); val index = store.chapterIndex(owner, item.id, item.sha256, 0, 20)
            val base = args(item, index)
            assertEquals("FAILED", status(read(store, other, base)))
            for (input in listOf(JsonObject(base + ("index_sha256" to JsonPrimitive("0".repeat(64)))), JsonObject(base + ("chapter_id" to JsonPrimitive("heading-999"))), JsonObject(base + ("owner" to JsonPrimitive(owner))), JsonObject(base + ("count" to JsonPrimitive(2))), JsonObject(base + ("offset" to JsonPrimitive("2147483648"))))) {
                val page = read(store, owner, input); assertEquals("FAILED", status(page)); assertFalse(page.containsKey("source_receipt")); assertFalse(page.toString().contains(context.filesDir.path))
            }
            File(context.filesDir, "materials/$owner/${item.id}.chapters-v1.cache").writeText("FORGED_CHAPTER")
            assertEquals("# Real\nBODY\n", text(read(store, owner, base)))
            store.file(item).appendText("CHANGED")
            assertEquals("FAILED", status(read(store, owner, base)))
        } finally { cleanup(owner); cleanup(other) }
    }
    @Test fun cancellationPropagatesAndSuccessfulSnapshotsLeaveNoTemporaryInputs(): Unit = runBlocking {
        val owner = UUID.randomUUID().toString(); val store = MaterialFiles(context)
        try {
            val item = markdown(store, owner, "# Real\nBODY\n"); val index = store.chapterIndex(owner, item.id, item.sha256, 0, 20)
            assertEquals("SUCCEEDED", status(read(store, owner, args(item, index))))
            var cancelled = false
            try { withContext(Job().apply { cancel() }) { read(store, owner, args(item, index)) } } catch (_: CancellationException) { cancelled = true }
            assertTrue(cancelled)
            assertTrue(File(context.cacheDir, "material-evidence/$owner").listFiles().orEmpty().none { it.name.startsWith("source-") })
            assertEquals(item.sha256, MaterialFiles.digest(store.verifiedFile(owner, item.id))); assertEquals(item, store.get(owner, item.id))
        } finally { cleanup(owner) }
    }
    @Test fun smallChunksRoundTripOneChapterWithEmojiWithoutRepeatingOrLeakingNeighbor(): Unit = runBlocking {
        val owner = UUID.randomUUID().toString(); val store = MaterialFiles(context)
        try {
            val expected = "# A\nABCDE😀FGHI\n"
            val item = markdown(store, owner, expected + "# B\nNEIGHBOR\n")
            val index = store.chapterIndex(owner, item.id, item.sha256, 0, 20)
            var offset = 0; val pages = mutableListOf<JsonObject>()
            do {
                val page = read(store, owner, args(item, index, offset = offset, count = 4))
                assertEquals("SUCCEEDED", status(page)); assertEquals(offset, page.getValue("chapter_offset").jsonPrimitive.int)
                verify(store, owner, item, page); pages += page
                offset = page["next_chapter_offset"]?.jsonPrimitive?.int ?: -1
            } while (offset >= 0)
            assertEquals(expected, pages.joinToString("") { text(it) })
            assertFalse(pages.last().getValue("has_more_in_chapter").jsonPrimitive.boolean)
            archive("pagination", item, store, index, pages)
        } finally { cleanup(owner) }
    }
}
