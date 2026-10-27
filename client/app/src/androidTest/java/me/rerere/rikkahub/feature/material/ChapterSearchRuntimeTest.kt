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

class ChapterSearchRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.also { check(it.packageName == "org.hxsi.agent.nativeqa") }
    private fun args(item: MaterialFile, index: JsonObject, query: String, chapter: Int = 0, offset: Int = 0, limit: Int = 20) = buildJsonObject {
        put("id", item.id); put("expected_sha256", item.sha256); put("index_sha256", index.getValue("index_sha256"))
        put("chapter_id", index.getValue("chapters").jsonArray[chapter].jsonObject.getValue("chapter_id"))
        put("query", query); put("offset", offset.toString()); put("limit", limit.toString())
    }
    private suspend fun search(store: MaterialFiles, owner: String, input: JsonObject) =
        Json.parseToJsonElement(materialChapterSearchTool(store, owner).execute(input).filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
    private fun hits(result: JsonObject) = result.getValue("hits").jsonArray.map { it.jsonObject }
    private fun status(result: JsonObject) = result.getValue("status").jsonPrimitive.content
    private suspend fun markdown(store: MaterialFiles, owner: String, body: String) =
        store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "synthetic.md", "md")) { it.writeText(body); "synthetic search" }
    private fun cleanup(owner: String) { File(context.filesDir, "materials/$owner").deleteRecursively(); File(context.cacheDir, "material-evidence/$owner").deleteRecursively() }
    private suspend fun verify(store: MaterialFiles, owner: String, item: MaterialFile, result: JsonObject) {
        for (hit in hits(result)) {
            val quote = hit.getValue("untrusted_file_data").jsonPrimitive.content; val proof = hit.getValue("source_receipt").jsonObject
            val start = proof.getValue("locator").jsonObject.getValue("start").jsonPrimitive.int
            assertEquals(result.getValue("query").jsonPrimitive.content, quote)
            assertTrue(start >= result.getValue("chapter_start").jsonPrimitive.int && start + quote.length <= result.getValue("chapter_end").jsonPrimitive.int)
            store.revalidateSourceTextPage(owner, item.id, item.sha256, start, quote.length, proof, quote)
        }
    }
    private fun archive(label: String, item: MaterialFile, store: MaterialFiles, index: JsonObject, results: List<JsonObject>) {
        val folder = File(context.cacheDir, "b-chapter-search-evidence/$label").apply { mkdirs() }
        store.verifiedFile(item.profileId, item.id).copyTo(File(folder, "source.${item.extension}"), overwrite = true)
        File(folder, "source.meta.json").writeText(Json.encodeToString(item)); File(folder, "index-page.json").writeText(index.toString())
        File(folder, "searches.json").writeText(JsonArray(results).toString())
        File(context.filesDir, "materials/${item.profileId}/${item.id}.chapters-v1.cache").copyTo(File(folder, "persisted-index.json"), overwrite = true)
    }
    @Test fun duplicateTitlesOverlappingPagesAndUnicodeHaveExactSourceReceipts(): Unit = runBlocking {
        val owner = UUID.randomUUID().toString(); val store = MaterialFiles(context)
        try {
            val item = markdown(store, owner, "preface\n# Same\nAAAA 😀😀😀\n# Same\nAAAA NEIGHBOR\n")
            val before = store.verifiedFile(owner, item.id).readBytes(); val index = store.chapterIndex(owner, item.id, item.sha256, 0, 20)
            val results = mutableListOf<JsonObject>(); var offset = 0
            do {
                val result = search(store, owner, args(item, index, "AA", offset = offset, limit = 1)); assertEquals("SUCCEEDED", status(result))
                results += result; verify(store, owner, item, result)
                offset = result["next_chapter_search_offset"]?.jsonPrimitive?.int ?: -1
            } while (offset >= 0)
            assertEquals(listOf(7, 8, 9), results.flatMap(::hits).map { it.getValue("chapter_offset").jsonPrimitive.int })
            val emoji = search(store, owner, args(item, index, "😀😀")); assertEquals(2, hits(emoji).size); verify(store, owner, item, emoji); results += emoji
            val neighbor = search(store, owner, args(item, index, "NEIGHBOR")); assertEquals(emptyList<JsonObject>(), hits(neighbor)); results += neighbor
            val second = search(store, owner, args(item, index, "NEIGHBOR", chapter = 1)); assertEquals(1, hits(second).size); verify(store, owner, item, second); results += second
            assertArrayEquals(before, store.verifiedFile(owner, item.id).readBytes()); assertEquals(item, store.get(owner, item.id))
            archive("markdown", item, store, index, results)
        } finally { cleanup(owner) }
    }
    @Test fun physicalPdfBodiesExcludeBlankGeneratedMarkersAndNextChapter(): Unit = runBlocking {
        val owner = UUID.randomUUID().toString(); val store = MaterialFiles(context)
        try {
            val item = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "synthetic.pdf", "pdf")) { target ->
                val doc = android.graphics.pdf.PdfDocument()
                try {
                    for (n in 1..4) {
                        val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(500, 500, n).create())
                        val lines = when (n) { 1 -> listOf("Chapter 1", "NEEDLE AB"); 3 -> listOf("CD NEEDLE"); 4 -> listOf("Chapter 2", "NEIGHBOR NEEDLE"); else -> emptyList() }
                        val paint = android.graphics.Paint().apply { textSize = 18f }
                        lines.forEachIndexed { i, line -> page.canvas.drawText(line, 25f, 60f + i * 35, paint) }; doc.finishPage(page)
                    }
                    target.outputStream().use(doc::writeTo)
                } finally { doc.close() }; "synthetic PDF search"
            }
            val index = store.chapterIndex(owner, item.id, item.sha256, 0, 20)
            val results = listOf("NEEDLE", "物理页", "无可提取", "ABCD", "NEIGHBOR").map { search(store, owner, args(item, index, it)) }
            assertEquals(listOf(1, 3), hits(results[0]).map { it.getValue("source_receipt").jsonObject.getValue("source_locations").jsonArray.single().jsonObject.getValue("number").jsonPrimitive.int })
            for (result in results) { assertEquals("SUCCEEDED", status(result)); verify(store, owner, item, result) }
            for (result in results.drop(1)) assertEquals(emptyList<JsonObject>(), hits(result))
            archive("pdf", item, store, index, results)
        } finally { cleanup(owner) }
    }
    @Test fun hiddenReorderedPptSearchUsesLogicalSlideAndNotNeighbor(): Unit = runBlocking {
        val owner = UUID.randomUUID().toString(); val store = MaterialFiles(context)
        try {
            val item = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "synthetic.pptx", "pptx")) { target ->
                MaterialWriter.write(target, "pptx", "", emptyList(), listOf("# Second\nNEIGHBOR", "# First\nHIDDEN NEEDLE"))
                val parts = OfficePackage.read(target)
                parts["ppt/presentation.xml"] = parts.getValue("ppt/presentation.xml").toString(Charsets.UTF_8)
                    .replace("<p:sldId id=\"256\" r:id=\"rId1\"/><p:sldId id=\"257\" r:id=\"rId2\"/>", "<p:sldId id=\"257\" r:id=\"rId2\"/><p:sldId id=\"256\" r:id=\"rId1\"/>").toByteArray()
                parts["ppt/slides/slide2.xml"] = parts.getValue("ppt/slides/slide2.xml").toString(Charsets.UTF_8).replace("<p:sld ", "<p:sld show=\"0\" ").toByteArray()
                ZipOutputStream(target.outputStream()).use { zip -> parts.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }; "synthetic slide search"
            }
            val index = store.chapterIndex(owner, item.id, item.sha256, 0, 20)
            val a = search(store, owner, args(item, index, "NEEDLE")); val b = search(store, owner, args(item, index, "NEIGHBOR"))
            val location = hits(a).single().getValue("source_receipt").jsonObject.getValue("source_locations").jsonArray.single().jsonObject
            assertEquals(1, location.getValue("number").jsonPrimitive.int); assertEquals("ppt/slides/slide2.xml", location.getValue("part").jsonPrimitive.content)
            assertTrue(location.getValue("hidden").jsonPrimitive.boolean); assertEquals(emptyList<JsonObject>(), hits(b)); verify(store, owner, item, a)
            archive("ppt", item, store, index, listOf(a, b))
        } finally { cleanup(owner) }
    }
    @Test fun strictInputStaleIndexForeignOwnerAndChangedSourceCannotIssueHits(): Unit = runBlocking {
        val owner = UUID.randomUUID().toString(); val other = UUID.randomUUID().toString(); val store = MaterialFiles(context)
        try {
            val item = markdown(store, owner, "# A\n😀 BODY\n"); val index = store.chapterIndex(owner, item.id, item.sha256, 0, 20)
            val base = args(item, index, "BODY")
            val bad = listOf(JsonObject(base + ("index_sha256" to JsonPrimitive("0".repeat(64)))), JsonObject(base + ("chapter_id" to JsonPrimitive("heading-999"))),
                JsonObject(base + ("owner" to JsonPrimitive(owner))), JsonObject(base + ("offset" to JsonPrimitive(0))), JsonObject(base + ("offset" to JsonPrimitive("5"))),
                JsonObject(base + ("query" to JsonPrimitive("\uD800"))), JsonObject(base + ("limit" to JsonPrimitive("21"))))
            for (input in bad) { val result = search(store, owner, input); assertEquals("FAILED", status(result)); assertFalse(result.containsKey("hits")); assertFalse(result.toString().contains(context.filesDir.path)) }
            assertEquals("FAILED", status(search(store, other, base)))
            File(context.filesDir, "materials/$owner/${item.id}.chapters-v1.cache").writeText("FORGED")
            assertEquals("BODY", hits(search(store, owner, base)).single().getValue("untrusted_file_data").jsonPrimitive.content)
            store.file(item).appendText("CHANGED"); assertEquals("FAILED", status(search(store, owner, base)))
        } finally { cleanup(owner); cleanup(other) }
    }
    @Test fun cancellationPropagatesAndSuccessfulReadsKeepNoFrozenTemporaryInputs(): Unit = runBlocking {
        val owner = UUID.randomUUID().toString(); val store = MaterialFiles(context)
        try {
            val item = markdown(store, owner, "# A\nBODY"); val index = store.chapterIndex(owner, item.id, item.sha256, 0, 20)
            verify(store, owner, item, search(store, owner, args(item, index, "BODY")))
            var cancelled = false
            try { withContext(Job().apply { cancel() }) { search(store, owner, args(item, index, "BODY")) } } catch (_: CancellationException) { cancelled = true }
            assertTrue(cancelled); assertTrue(File(context.cacheDir, "material-evidence/$owner").listFiles().orEmpty().none { it.name.startsWith("source-") })
            assertEquals(item, store.get(owner, item.id)); assertEquals(item.sha256, MaterialFiles.digest(store.verifiedFile(owner, item.id)))
        } finally { cleanup(owner) }
    }
}
