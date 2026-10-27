package me.rerere.rikkahub.feature.material

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class SourceSearchRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.also { check(it.packageName == "org.hxsi.agent.nativeqa") }
    private suspend fun search(store: MaterialFiles, owner: String, item: MaterialFile, query: String, offset: Int = 0, limit: Int = 20, hash: String = item.sha256): JsonObject {
        val args = buildJsonObject { put("id", item.id); put("expected_sha256", hash); put("query", query); put("offset", offset.toString()); put("limit", limit.toString()) }
        return Json.parseToJsonElement(materialSourceSearchTool(store, owner).execute(args).filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
    }
    private fun hits(page: JsonObject) = page.getValue("hits").jsonArray.map { it.jsonObject }
    private fun start(hit: JsonObject) = hit.getValue("source_receipt").jsonObject.getValue("locator").jsonObject.getValue("start").jsonPrimitive.int
    private fun cleanup(owner: String) {
        File(context.filesDir, "materials/$owner").deleteRecursively()
        File(context.cacheDir, "material-evidence/$owner").deleteRecursively()
    }

    @Test fun literalOverlappingUnicodeHitsPaginateAndRevalidateAgainstRealVersionNotCache(): Unit = runBlocking {
        val store = MaterialFiles(context); val owner = UUID.randomUUID().toString()
        try {
            val text = "aaaaa \uD83D\uDE00 aaa a.b [x]\n" + "TEXTBOOK EVIDENCE. ".repeat(2000) + "TAIL_KEYWORD"
            val source = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "Search.md", "md")) { it.writeText(text); "Synthetic source" }
            val original = store.verifiedFile(owner, source.id).readBytes()
            File(context.filesDir, "materials/$owner/${source.id}.read-v6.cache").writeText(Json.encodeToString(MaterialContent("FORGED ONLY CACHE", "poisoned cache")))
            val first = search(store, owner, source, "aa", limit = 2)
            assertEquals(listOf(0, 1), hits(first).map(::start)); assertEquals(2, first.getValue("next_search_offset").jsonPrimitive.int)
            val positions = mutableListOf<Int>(); var page = first
            while (true) {
                for (hit in hits(page)) {
                    positions += start(hit)
                    assertEquals("aa", hit.getValue("untrusted_file_data").jsonPrimitive.content)
                    val receipt = hit.getValue("source_receipt").jsonObject
                    val verified = store.revalidateSourceTextPage(owner, source.id, source.sha256, start(hit), 2, receipt, "aa")
                    assertEquals("aa", verified.getValue("untrusted_file_data").jsonPrimitive.content)
                    assertEquals(source.sha256, receipt.getValue("source_sha256").jsonPrimitive.content)
                }
                if (!page.getValue("has_more").jsonPrimitive.boolean) break
                page = search(store, owner, source, "aa", page.getValue("next_search_offset").jsonPrimitive.int, 2)
            }
            assertEquals(listOf(0, 1, 2, 3, 9, 10), positions)
            assertFalse(page.containsKey("next_search_offset"))
            assertEquals(listOf(6), hits(search(store, owner, source, "\uD83D\uDE00")).map(::start))
            assertEquals(listOf(13), hits(search(store, owner, source, "a.b")).map(::start))
            assertTrue(hits(search(store, owner, source, "a.b.*")).isEmpty())
            assertTrue(hits(search(store, owner, source, "textbook")).isEmpty())
            val tail = search(store, owner, source, "TAIL_KEYWORD")
            assertEquals(listOf(text.length - 12), hits(tail).map(::start))
            suspend fun refused(query: String = "aa", offset: Int = 0, limit: Int = 20, profile: String = owner, hash: String = source.sha256) {
                val result = search(store, profile, source, query, offset, limit, hash)
                assertEquals("FAILED", result.getValue("status").jsonPrimitive.content); assertFalse(result.containsKey("hits"))
            }
            refused(query = ""); refused(query = "x".repeat(257)); refused(query = "\uD83D"); refused(query = "\uDE00")
            refused(offset = 7); refused(offset = -1); refused(offset = text.length + 1); refused(limit = 0); refused(limit = 21)
            refused(hash = "0".repeat(64)); refused(profile = UUID.randomUUID().toString())
            store.file(source).appendText("MUTATED"); refused(); store.file(source).writeBytes(original)
            val child = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "Search child.md", "md", output = true, sourceId = source.id)) { it.writeText("CHILD TAIL_KEYWORD"); "Synthetic child" }
            val childHit = hits(search(store, owner, child, "TAIL_KEYWORD")).single()
            assertEquals(source.id, childHit.getValue("source_receipt").jsonObject.getValue("parent_id").jsonPrimitive.content)
            assertEquals(child.id, childHit.getValue("source_receipt").jsonObject.getValue("version_id").jsonPrimitive.content)
            assertEquals(listOf(0, 1, 2, 3, 9, 10), hits(search(store, owner, source, "aa")).map(::start))
            assertArrayEquals(original, store.verifiedFile(owner, source.id).readBytes())
            assertTrue(File(context.cacheDir, "material-evidence/$owner").listFiles().orEmpty().isEmpty())
            val evidence = File(context.cacheDir, "b2d-source-evidence").apply { mkdirs() }
            File(evidence, "md-search.json").writeText(first.toString()); File(evidence, "md-tail.json").writeText(tail.toString())
            File(evidence, "source.md").writeBytes(original)
        } finally { cleanup(owner) }
    }

    @Test fun actualPdfAndPptBodyHitsExcludeGeneratedHeadersAndKeepPhysicalLogicalHiddenDistinctions(): Unit = runBlocking {
        val store = MaterialFiles(context); val owner = UUID.randomUUID().toString()
        try {
            val pdf = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "Search.pdf", "pdf")) { output ->
                val doc = android.graphics.pdf.PdfDocument()
                try {
                    for (number in 1..3) {
                        val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(600, 500, number).create())
                        if (number != 2) page.canvas.drawText("SAME [physical page 999] END", 25f, 60f, android.graphics.Paint().apply { textSize = 18f })
                        doc.finishPage(page)
                    }
                    output.outputStream().use(doc::writeTo)
                } finally { doc.close() }
                "Synthetic physical PDF"
            }
            fun locations(page: JsonObject) = hits(page).map { it.getValue("source_receipt").jsonObject.getValue("source_locations").jsonArray.single().jsonObject }
            val pdfHits = search(store, owner, pdf, "SAME")
            assertEquals(listOf(1, 3), locations(pdfHits).map { it.getValue("number").jsonPrimitive.int })
            assertEquals(listOf(1, 3), locations(search(store, owner, pdf, "[physical page 999]")).map { it.getValue("number").jsonPrimitive.int })
            assertTrue(hits(search(store, owner, pdf, "物理页")).isEmpty()); assertTrue(hits(search(store, owner, pdf, "无可提取文本")).isEmpty())
            assertEquals(listOf(2), pdfHits.getValue("pages_without_extractable_text").jsonArray.map { it.jsonPrimitive.int })
            val ppt = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "Search.pptx", "pptx")) { output ->
                InstrumentationRegistry.getInstrumentation().context.assets.open("source-search-logical-hidden.pptx").use { input ->
                    output.outputStream().use { input.copyTo(it) }
                }
                "Synthetic logical PPT"
            }
            val hidden = locations(search(store, owner, ppt, "HIDDEN LOGICAL FIRST")).single()
            assertEquals(1, hidden.getValue("number").jsonPrimitive.int); assertTrue(hidden.getValue("hidden").jsonPrimitive.boolean)
            assertEquals("ppt/slides/slide2.xml", hidden.getValue("part").jsonPrimitive.content)
            val pptHits = search(store, owner, ppt, "DUPLICATE")
            assertEquals(listOf(63, 73, 83), hits(pptHits).map(::start))
            assertEquals(setOf(2), locations(pptHits).map { it.getValue("number").jsonPrimitive.int }.toSet())
            assertEquals(setOf("ppt/slides/slide9.xml"), locations(pptHits).map { it.getValue("part").jsonPrimitive.content }.toSet())
            assertTrue(locations(pptHits).none { it.getValue("hidden").jsonPrimitive.boolean })
            assertTrue(hits(search(store, owner, ppt, "幻灯片")).isEmpty())
            val evidence = File(context.cacheDir, "b2d-source-evidence").apply { mkdirs() }
            File(evidence, "pdf-search.json").writeText(pdfHits.toString()); File(evidence, "ppt-search.json").writeText(pptHits.toString())
            store.verifiedFile(owner, pdf.id).copyTo(File(evidence, "source.pdf"), overwrite = true)
            assertTrue(File(context.cacheDir, "material-evidence/$owner").listFiles().orEmpty().isEmpty())
        } finally { cleanup(owner) }
    }

    @Test fun missingWordBeyondRealExtractionLimitIsNotReportedAsCompleteDocumentAbsence(): Unit = runBlocking {
        val store = MaterialFiles(context); val owner = UUID.randomUUID().toString()
        try {
            val source = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "Truncated search.pptx", "pptx")) { output ->
                MaterialWriter.write(output, "pptx", "", emptyList(), listOf("B2_CAP_BODY", "UNEXTRACTED_SECOND"))
                val parts = OfficePackage.read(output)
                parts["ppt/slides/slide1.xml"] = parts.getValue("ppt/slides/slide1.xml").toString(Charsets.UTF_8).replace(">B2_CAP_BODY<", ">" + "X".repeat(MaterialReader.LIMIT + 100) + "<").toByteArray(Charsets.UTF_8)
                java.util.zip.ZipOutputStream(output.outputStream()).use { zip ->
                    for ((name, bytes) in parts) { zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
                }
                "Synthetic bounded extraction"
            }
            val original = OfficePackage.read(store.verifiedFile(owner, source.id))
            assertTrue(original.getValue("ppt/slides/slide2.xml").toString(Charsets.UTF_8).contains("UNEXTRACTED_SECOND"))
            val result = search(store, owner, source, "UNEXTRACTED_SECOND")
            assertTrue(hits(result).isEmpty()); assertFalse(result.getValue("has_more").jsonPrimitive.boolean)
            assertEquals(MaterialReader.LIMIT, result.getValue("extracted_characters").jsonPrimitive.int)
            assertEquals("NOT_ASSERTED", result.getValue("document_completeness").jsonPrimitive.content)
            assertEquals("extracted_body_suffix", result.getValue("scope").jsonPrimitive.content)
            val tail = search(store, owner, source, "XXX", MaterialReader.LIMIT - 4, 20)
            assertEquals(listOf(MaterialReader.LIMIT - 4, MaterialReader.LIMIT - 3), hits(tail).map(::start))
            assertEquals("XXX", hits(tail).last().getValue("untrusted_file_data").jsonPrimitive.content)
            assertTrue(File(context.cacheDir, "material-evidence/$owner").listFiles().orEmpty().isEmpty())
            val evidence = File(context.cacheDir, "b2d-source-evidence").apply { mkdirs() }; File(evidence, "truncated-search.json").writeText(result.toString())
            store.verifiedFile(owner, source.id).copyTo(File(evidence, "truncated-source.pptx"), overwrite = true)
        } finally { cleanup(owner) }
    }
}
