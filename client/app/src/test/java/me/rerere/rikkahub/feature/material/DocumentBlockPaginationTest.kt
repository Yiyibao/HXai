package me.rerere.rikkahub.feature.material

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DocumentBlockPaginationTest {
    private val sourceId = "source-1"
    private val taskId = "task-1"
    private fun document(blocks: List<ParsedBlock>) = ParsedDocument(1, "a".repeat(64), 3, "4.0.10", "1", "basic", 5,
        listOf(2, 4), listOf(2, 3), listOf(3), listOf(4), false, listOf("page 4 failed"), blocks)
    private fun text(length: Int) = buildString { repeat(length) { append(('a'.code + it % 26).toChar()) } }
    private data class ReadResult(val fields: Map<Pair<Int, String>, String>, val pages: List<JsonObject>)

    private fun readAll(document: ParsedDocument, count: Int = 20): ReadResult {
        var cursor = ""
        val fields = mutableMapOf<Pair<Int, String>, StringBuilder>()
        val totals = mutableMapOf<Pair<Int, String>, Int>()
        val pages = mutableListOf<JsonObject>()
        val cursors = mutableSetOf<String>()
        do {
            assertTrue("cursor must advance", cursors.add(cursor))
            val raw = DocumentBlockPagination.page(document, sourceId, taskId, cursor, count)
            assertTrue("serialized response including metadata, escaping and cursor must fit", raw.length <= 24_000)
            val page = Json.parseToJsonElement(raw).jsonObject
            pages += page
            assertEquals(sourceId, page.getValue("source_id").jsonPrimitive.content)
            assertEquals(taskId, page.getValue("task_id").jsonPrimitive.content)
            assertEquals(document.resultRevision, page.getValue("result_revision").jsonPrimitive.int)
            assertEquals("UTF-16", page.getValue("offset_unit").jsonPrimitive.content)
            val blockFragments = page.getValue("untrusted_blocks").jsonArray
            assertTrue(blockFragments.map { it.jsonObject.getValue("block_index").jsonPrimitive.int }.distinct().size <= count)
            val fragments = page.getValue("untrusted_metadata").jsonArray + blockFragments
            assertTrue(fragments.isNotEmpty())
            for (element in fragments) {
                val fragment = element.jsonObject
                val key = (fragment["block_index"]?.jsonPrimitive?.int ?: -1) to fragment.getValue("field").jsonPrimitive.content
                val restored = fields.getOrPut(key) { StringBuilder() }
                assertEquals("no gaps or duplicate characters for $key", restored.length, fragment.getValue("char_offset").jsonPrimitive.int)
                val total = fragment.getValue("total_chars").jsonPrimitive.int
                totals[key]?.let { assertEquals(it, total) }; totals[key] = total
                val content = fragment.getValue("content").jsonPrimitive.content
                if (content.isNotEmpty()) {
                    assertFalse("must not start inside a surrogate pair", content.first().isLowSurrogate())
                    assertFalse("must not end inside a surrogate pair", content.last().isHighSurrogate())
                }
                restored.append(content)
                assertTrue(restored.length <= total)
            }
            cursor = page["next_cursor"]?.jsonPrimitive?.content ?: ""
            assertTrue("pagination must terminate", pages.size < 5000)
        } while (cursor.isNotEmpty())
        fields.forEach { (key, restored) -> assertEquals(totals.getValue(key), restored.length) }
        val restoredMetadata = Json.parseToJsonElement(fields.getValue(-1 to "metadata").toString()).jsonObject
        assertEquals(document.complete, restoredMetadata.getValue("complete").jsonPrimitive.boolean)
        assertEquals(JsonArray(document.requestedRange.map(::JsonPrimitive)), restoredMetadata.getValue("requested_range"))
        assertEquals(JsonArray(document.processedPages.map(::JsonPrimitive)), restoredMetadata.getValue("processed_pages"))
        assertEquals(JsonArray(document.blankPages.map(::JsonPrimitive)), restoredMetadata.getValue("blank_pages"))
        assertEquals(JsonArray(document.failedPages.map(::JsonPrimitive)), restoredMetadata.getValue("failed_pages"))
        assertEquals(JsonArray(document.warnings.map(::JsonPrimitive)), restoredMetadata.getValue("warnings"))
        document.blocks.forEachIndexed { index, block ->
            val header = Json.parseToJsonElement(fields.getValue(index to "metadata").toString()).jsonObject
            assertEquals(block.blockId, header.getValue("blockId").jsonPrimitive.content)
            assertEquals(block.page, header.getValue("page").jsonPrimitive.int)
            assertEquals(block.type, header.getValue("type").jsonPrimitive.content)
            assertEquals(block.assetId, header["assetId"]?.jsonPrimitive?.content)
            assertEquals(block.text, fields.getValue(index to "text").toString())
            if (block.latex != null) assertEquals(block.latex, fields.getValue(index to "latex").toString())
            else assertFalse(fields.containsKey(index to "latex"))
        }
        assertEquals(1 + document.blocks.sumOf { if (it.latex == null) 2 else 3 }, fields.size)
        return ReadResult(fields.mapValues { it.value.toString() }, pages)
    }

    @Test fun readsBudgetBoundaryAndMaximumValidSingleBlocksThroughTail() {
        for (length in listOf(24_000, 24_001, 48_001, 1_000_000)) {
            val value = text(length - 4) + "TAIL"
            val result = readAll(document(listOf(ParsedBlock("p2-b1", 2, "table_body", value))), count = 1)
            assertTrue(result.pages.size > 1)
            assertTrue(result.fields.getValue(0 to "text").endsWith("TAIL"))
        }
    }

    @Test fun preservesBlockOrderAndCountAcrossLargeSmallAndEmptyBlocks() {
        val blocks = (0 until 23).map { index -> ParsedBlock("p2-b${index + 1}", 2, if (index % 2 == 0) "table_body" else "text",
            if (index == 1) text(47_999) + "TAIL" else if (index == 3) "" else "block-$index", if (index == 3) "" else null) }
        val doc = document(blocks)
        val single = readAll(doc, 1)
        val many = readAll(doc, 20)
        assertEquals(single.fields, many.fields)
        assertTrue(single.pages.size > many.pages.size)
    }

    @Test fun readsLatexOnlyAndBodyThenLatexWithoutRepeatingBody() {
        val doc = document(listOf(ParsedBlock("p2-b1", 2, "equation", "", "\\frac{a}{b}".repeat(7000) + "LATEX_TAIL", "equation-1"),
            ParsedBlock("p2-b2", 2, "equation", text(24_001), "x^2".repeat(17_000) + "TAIL"),
            ParsedBlock("p2-b3", 2, "text", "final block")))
        val result = readAll(doc, 1)
        assertTrue(result.pages.count { page -> page.getValue("untrusted_blocks").jsonArray.any { it.jsonObject.getValue("field").jsonPrimitive.content == "latex" } } > 2)
    }

    @Test fun budgetsEscapingUnicodeAndOversizedDocumentAndBlockMetadata() {
        val doc = document(listOf(ParsedBlock("p2-b1", 2, "table_body" + "long type".repeat(4000),
            "\"\\\n\t\u0000\u001F😀汉字".repeat(6000) + "TAIL", "\\alpha\n😀".repeat(7000)))).copy(
            warnings = listOf("quoted \"warning\"\n".repeat(6000), "tail warning"))
        val result = readAll(doc)
        assertTrue(result.pages.first().getValue("untrusted_blocks").jsonArray.isEmpty())
        assertTrue(result.pages.size > 5)
    }

    @Test fun emptyDocumentStillDeliversCoverageAndFinishes() {
        val result = readAll(document(emptyList()))
        assertEquals(1, result.pages.size)
        assertNull(result.pages.single()["next_cursor"])
    }

    @Test fun cursorReplaysStablyAndRejectsOtherTasksRevisionsAndInvalidOffsets() {
        val doc = document(listOf(ParsedBlock("p2-b1", 2, "table_body", text(48_001))))
        val first = DocumentBlockPagination.page(doc, sourceId, taskId, "", 1)
        val cursor = Json.parseToJsonElement(first).jsonObject.getValue("next_cursor").jsonPrimitive.content
        assertEquals(DocumentBlockPagination.page(doc, sourceId, taskId, cursor, 1), DocumentBlockPagination.page(doc, sourceId, taskId, cursor, 1))
        assertTrue(runCatching { DocumentBlockPagination.page(doc.copy(resultRevision = 4), sourceId, taskId, cursor, 1) }.isFailure)
        assertTrue(runCatching { DocumentBlockPagination.page(doc.copy(sourceSha256 = "b".repeat(64)), sourceId, taskId, cursor, 1) }.isFailure)
        assertTrue(runCatching { DocumentBlockPagination.page(doc, sourceId, "task-2", cursor, 1) }.isFailure)
        for ((key, value) in listOf("char_offset" to JsonPrimitive(-1), "char_offset" to JsonPrimitive(99_999),
            "block_index" to JsonPrimitive(999), "field" to JsonPrimitive("unknown"))) {
            val bad = JsonObject(Json.parseToJsonElement(cursor).jsonObject + (key to value)).toString()
            assertTrue(runCatching { DocumentBlockPagination.page(doc, sourceId, taskId, bad, 1) }.isFailure)
        }
        for (count in listOf(0, 21)) assertTrue(runCatching { DocumentBlockPagination.page(doc, sourceId, taskId, "", count) }.isFailure)
    }
}
