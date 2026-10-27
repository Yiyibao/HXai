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

class LibrarySearchRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.also { check(it.packageName == "org.hxsi.agent.nativeqa") }
    private fun id(index: Int) = UUID(0, index.toLong()).toString()
    private fun args(query: String, cursor: String = "", limit: Int = 20, sources: Int = 5) = buildJsonObject {
        put("query", query); put("cursor", cursor); put("limit", limit.toString()); put("source_limit", sources.toString())
    }
    private suspend fun search(store: MaterialFiles, owner: String, input: JsonObject): JsonObject =
        Json.parseToJsonElement(materialLibrarySearchTool(store, owner).execute(input).filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
    private suspend fun source(store: MaterialFiles, owner: String, index: Int, text: String, parent: String? = null): MaterialFile =
        store.runTask(MaterialFile(id(index), owner, "课程 $index.md", "md", output = parent != null, sourceId = parent)) { it.writeText(text); "Synthetic library version" }
    private fun hits(page: JsonObject) = page.getValue("hits").jsonArray.map { it.jsonObject }
    private fun receipt(hit: JsonObject) = hit.getValue("source_receipt").jsonObject
    private fun start(hit: JsonObject) = receipt(hit).getValue("locator").jsonObject.getValue("start").jsonPrimitive.int
    private fun cleanup(owner: String) {
        File(context.filesDir, "materials/$owner").deleteRecursively()
        File(context.cacheDir, "material-evidence/$owner").deleteRecursively()
    }
    private fun archive(label: String, owner: String, pages: List<JsonObject>, items: List<MaterialFile>, store: MaterialFiles) {
        val folder = File(context.cacheDir, "b2e-library-evidence/$label").apply { mkdirs() }
        File(folder, "pages.json").writeText(JsonArray(pages).toString())
        File(folder, "owner.txt").writeText(owner)
        for (item in items) {
            File(folder, "${item.id}.meta.json").writeText(Json.encodeToString(item))
            store.file(item).copyTo(File(folder, "${item.id}.${item.extension}"), overwrite = true)
        }
    }

    @Test fun boundedPagesPreserveOverlappingHitsAcrossVersionsAndRevalidateEveryQuote(): Unit = runBlocking {
        val store = MaterialFiles(context); val owner = UUID.randomUUID().toString()
        try {
            val first = source(store, owner, 1, "NO MATCH")
            val second = source(store, owner, 2, "aaaaa")
            val third = source(store, owner, 3, "aa \uD83D\uDE00 aa", second.id)
            val items = listOf(first, second, third); val originals = items.map { store.verifiedFile(owner, it.id).readBytes() }
            val pages = mutableListOf<JsonObject>(); val actual = mutableListOf<Pair<String, Int>>()
            var cursor = ""
            do {
                val page = search(store, owner, args("aa", cursor, 1, 1)); pages += page
                assertEquals("SUCCEEDED", page.getValue("status").jsonPrimitive.content)
                assertTrue(page.getValue("visited_versions").jsonPrimitive.int <= 1)
                for (hit in hits(page)) {
                    val proof = receipt(hit); val materialId = proof.getValue("material_id").jsonPrimitive.content
                    actual += materialId to start(hit)
                    assertEquals(owner, proof.getValue("owner").jsonPrimitive.content)
                    assertEquals("aa", hit.getValue("untrusted_file_data").jsonPrimitive.content)
                    val item = items.single { it.id == materialId }
                    assertEquals(item.sha256, proof.getValue("source_sha256").jsonPrimitive.content)
                    assertEquals(item.sourceId?.let(::JsonPrimitive) ?: JsonNull, proof.getValue("parent_id"))
                    store.revalidateSourceTextPage(owner, item.id, item.sha256, start(hit), 2, proof, "aa")
                }
                cursor = page["next_cursor"]?.jsonPrimitive?.content ?: ""
            } while (cursor.isNotEmpty())
            assertTrue(hits(pages.first()).isEmpty()); assertTrue(pages.first().getValue("has_more").jsonPrimitive.boolean)
            assertEquals(listOf(second.id to 0, second.id to 1, second.id to 2, second.id to 3, third.id to 0, third.id to 6), actual)
            assertTrue(pages.last().getValue("exhausted_catalog").jsonPrimitive.boolean)
            assertEquals("NOT_ASSERTED", pages.last().getValue("library_completeness").jsonPrimitive.content)
            for ((index, item) in items.withIndex()) { assertArrayEquals(originals[index], store.verifiedFile(owner, item.id).readBytes()); assertEquals(item, store.get(owner, item.id)) }
            assertTrue(File(context.cacheDir, "material-evidence/$owner").listFiles().orEmpty().isEmpty())
            archive("overlap", owner, pages, items, store)
        } finally { cleanup(owner) }
    }

    @Test fun cursorsBindOwnerQueryAndActualCatalogNotOtherProfilesOrNewVersions(): Unit = runBlocking {
        val store = MaterialFiles(context); val owner = UUID.randomUUID().toString(); val other = UUID.randomUUID().toString()
        try {
            val original = source(store, owner, 1, "needle needle")
            source(store, other, 1, "needle PRIVATE_OTHER")
            val page = search(store, owner, args("needle", limit = 1)); val cursor = page.getValue("next_cursor").jsonPrimitive.content
            suspend fun rejected(profile: String = owner, query: String = "needle") {
                val result = search(store, profile, args(query, cursor, 1)); assertEquals("FAILED", result.getValue("status").jsonPrimitive.content)
                assertEquals("STALE_LIBRARY_CURSOR", result.getValue("reason").jsonPrimitive.content); assertFalse(result.containsKey("hits"))
            }
            rejected(other); rejected(query = "PRIVATE_OTHER")
            val next = source(store, owner, 2, "needle new"); rejected()
            store.file(next).delete(); File(context.filesDir, "materials/$owner/${next.id}.meta.json").delete()
            store.record(original.copy(name = "renamed.md")); rejected(); store.record(original)
            store.record(original.copy(sourceId = id(99))); rejected(); store.record(original)
            store.record(original.copy(sha256 = "0".repeat(64))); rejected(); store.record(original)
            store.record(original.copy(state = "FAILED")); rejected(); store.record(original)
            store.file(original).delete(); File(context.filesDir, "materials/$owner/${original.id}.meta.json").delete(); rejected()
        } finally { cleanup(owner); cleanup(other) }
    }

    @Test fun oneCorruptReadableVersionCannotHideBehindAnotherHitOrUnsupportedFormat(): Unit = runBlocking {
        val store = MaterialFiles(context); val owner = UUID.randomUUID().toString()
        try {
            val good = source(store, owner, 1, "needle VALID")
            val bad = source(store, owner, 2, "needle CORRUPTED")
            store.file(bad).appendText(" CHANGED")
            val image = store.runTask(MaterialFile(id(3), owner, "unread image.png", "png")) { it.writeText("Synthetic unread image bytes"); "No OCR attempted" }
            val result = search(store, owner, args("needle"))
            assertEquals("FAILED", result.getValue("status").jsonPrimitive.content)
            assertEquals(listOf(good.id), hits(result).map { receipt(it).getValue("material_id").jsonPrimitive.content })
            val error = result.getValue("source_errors").jsonArray.single().jsonObject
            assertEquals(bad.id, error.getValue("material_id").jsonPrimitive.content); assertFalse(error.getValue("source_verified").jsonPrimitive.boolean)
            val skipped = result.getValue("skipped_sources").jsonArray.single().jsonObject
            assertEquals(image.id, skipped.getValue("material_id").jsonPrimitive.content); assertFalse(skipped.getValue("source_verified").jsonPrimitive.boolean)
            assertEquals("NOT_ASSERTED", result.getValue("document_completeness").jsonPrimitive.content)
            archive("corrupt", owner, listOf(result), listOf(good, bad, image), store)
            store.file(bad).delete()
            assertEquals("FAILED", search(store, owner, args("needle")).getValue("status").jsonPrimitive.content)
        } finally { cleanup(owner) }
    }

    @Test fun realPdfAndPptBodyCoverageKeepPhysicalLogicalPagesAndMissingTextWarnings(): Unit = runBlocking {
        val store = MaterialFiles(context); val owner = UUID.randomUUID().toString()
        try {
            val pdf = store.runTask(MaterialFile(id(1), owner, "physical.pdf", "pdf")) { output ->
                val document = android.graphics.pdf.PdfDocument()
                try {
                    for (number in 1..3) {
                        val page = document.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(600, 500, number).create())
                        if (number != 2) page.canvas.drawText("LIBRARY_MATCH [physical page 999]", 25f, 60f, android.graphics.Paint().apply { textSize = 18f })
                        document.finishPage(page)
                    }
                    output.outputStream().use(document::writeTo)
                } finally { document.close() }
                "Synthetic real physical PDF"
            }
            val ppt = store.runTask(MaterialFile(id(2), owner, "logical.pptx", "pptx")) { output ->
                MaterialWriter.write(output, "pptx", "", emptyList(), listOf("LIBRARY_MATCH FIRST", "LIBRARY_MATCH SECOND"))
            }
            val result = search(store, owner, args("LIBRARY_MATCH"))
            assertEquals("SUCCEEDED", result.getValue("status").jsonPrimitive.content)
            val locations = hits(result).map { receipt(it).getValue("source_locations").jsonArray.single().jsonObject }
            assertEquals(listOf("pdf_physical_page", "pdf_physical_page", "ppt_logical_slide", "ppt_logical_slide"), locations.map { it.getValue("kind").jsonPrimitive.content })
            assertEquals(listOf(1, 3, 1, 2), locations.map { it.getValue("number").jsonPrimitive.int })
            val pdfSource = result.getValue("scanned_sources").jsonArray[0].jsonObject
            assertEquals(listOf(2), pdfSource.getValue("pages_without_extractable_text").jsonArray.map { it.jsonPrimitive.int })
            assertTrue(hits(search(store, owner, args("物理页"))).isEmpty()); assertTrue(hits(search(store, owner, args("幻灯片"))).isEmpty())
            archive("formats", owner, listOf(result), listOf(pdf, ppt), store)
        } finally { cleanup(owner) }
    }

    @Test fun unicodeBoundariesInvalidFieldsAndUntrustedPositionsNeverGrantCoverage(): Unit = runBlocking {
        val store = MaterialFiles(context); val owner = UUID.randomUUID().toString()
        try {
            source(store, owner, 1, "\uD83D\uDE00 aa aa")
            val first = search(store, owner, args("aa", limit = 1)); val cursor = first.getValue("next_cursor").jsonPrimitive.content
            val invalid = listOf(args(""), args("x".repeat(257)), args("\uD83D"), args("\uDE00"), args("aa", limit = 0), args("aa", limit = 21), args("aa", sources = 0), args("aa", sources = 6), args("aa", "[]"), args("aa", "{}"), args("aa", "x".repeat(2049)), JsonObject(args("aa") + ("owner" to JsonPrimitive(UUID.randomUUID().toString()))))
            for (input in invalid) assertEquals("FAILED", search(store, owner, input).getValue("status").jsonPrimitive.content)
            val data = Json.parseToJsonElement(cursor).jsonObject
            val split = JsonObject(data + ("search_offset" to JsonPrimitive("1")))
            assertEquals("FAILED", search(store, owner, args("aa", split.toString())).getValue("status").jsonPrimitive.content)
            assertEquals(0, start(hits(search(store, owner, args("\uD83D\uDE00"))).single()))
            // An arbitrary own-catalog position is not an authenticated claim of prior pages.
            val end = JsonObject(data + mapOf("version_index" to JsonPrimitive("1"), "search_offset" to JsonPrimitive("0")))
            val tail = search(store, owner, args("aa", end.toString()))
            assertTrue(hits(tail).isEmpty()); assertTrue(tail.getValue("exhausted_catalog").jsonPrimitive.boolean)
            assertEquals("NOT_ASSERTED", tail.getValue("library_completeness").jsonPrimitive.content)
        } finally { cleanup(owner) }
    }

    @Test fun emptyCatalogAndNonSucceededVersionsDoNotClaimCompleteDocumentAbsence(): Unit = runBlocking {
        val store = MaterialFiles(context); val owner = UUID.randomUUID().toString()
        try {
            val empty = search(store, owner, args("needle"))
            assertEquals("SUCCEEDED", empty.getValue("status").jsonPrimitive.content); assertTrue(hits(empty).isEmpty())
            assertEquals(0, empty.getValue("catalog_versions").jsonPrimitive.int)
            assertEquals("NOT_ASSERTED", empty.getValue("library_completeness").jsonPrimitive.content)
            val failed = source(store, owner, 1, "needle pending"); store.record(failed.copy(state = "FAILED"))
            val result = search(store, owner, args("needle"))
            assertTrue(hits(result).isEmpty()); assertEquals(0, result.getValue("catalog_versions").jsonPrimitive.int)
        } finally { cleanup(owner) }
    }

    @Test fun cachePoisoningAndForgedCrossVersionQuotesCannotReplaceRealSource(): Unit = runBlocking {
        val store = MaterialFiles(context); val owner = UUID.randomUUID().toString()
        try {
            val original = source(store, owner, 1, "needle ORIGINAL")
            val child = source(store, owner, 2, "needle CHILD", original.id)
            File(context.filesDir, "materials/$owner/${original.id}.read-v6.cache").writeText(Json.encodeToString(MaterialContent("FORGED_NO_NEEDLE", "poisoned cache")))
            val page = search(store, owner, args("needle")); assertEquals(2, hits(page).size)
            val hit = hits(page).first(); val proof = receipt(hit)
            var refused = false
            try { store.revalidateSourceTextPage(owner, child.id, child.sha256, start(hit), 6, proof, "needle") } catch (_: IllegalArgumentException) { refused = true }
            assertTrue(refused)
            val actual = store.revalidateSourceTextPage(owner, original.id, original.sha256, start(hit), 6, proof, "needle")
            assertEquals("needle", actual.getValue("untrusted_file_data").jsonPrimitive.content)
        } finally { cleanup(owner) }
    }

    @Test fun cancellationPropagatesWithoutAFakeLibraryResult(): Unit = runBlocking {
        val store = MaterialFiles(context); val owner = UUID.randomUUID().toString()
        try {
            source(store, owner, 1, "needle")
            var cancelled = false
            try { withContext(Job().apply { cancel() }) { search(store, owner, args("needle")) } } catch (_: CancellationException) { cancelled = true }
            assertTrue(cancelled)
        } finally { cleanup(owner) }
    }
}
