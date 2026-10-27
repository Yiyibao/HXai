package me.rerere.rikkahub.feature.material

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MaterialChapterSearchTest {
    private fun item(ext: String = "md") = MaterialFile("00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002", "synthetic.$ext", ext, state = "SUCCEEDED", sha256 = "a".repeat(64))
    private suspend fun search(text: String, query: String, offset: Int = 0, limit: Int = 20, chapter: Int = 0): JsonObject {
        val content = MaterialContent(text, "synthetic"); val index = MaterialChapterIndex.build(item(), content)
        return MaterialChapterSearch.search(item(), content, index, index.getValue("index_sha256").jsonPrimitive.content,
            index.getValue("chapters").jsonArray[chapter].jsonObject.getValue("chapter_id").jsonPrimitive.content, query, offset, limit)
    }
    private fun offsets(page: JsonObject) = page.getValue("hits").jsonArray.map { it.jsonObject.getValue("chapter_offset").jsonPrimitive.int }

    @Test fun overlappingMatchesPageWithoutLosingOccurrencesOrLeakingDuplicateTitleNeighbor(): Unit = runBlocking {
        val text = "# Same\nAAAA\n# Same\nAAAA\n"
        val first = search(text, "AA", limit = 1); assertEquals(listOf(7), offsets(first))
        val second = search(text, "AA", first.getValue("next_chapter_search_offset").jsonPrimitive.int, 1); assertEquals(listOf(8), offsets(second))
        val third = search(text, "AA", second.getValue("next_chapter_search_offset").jsonPrimitive.int, 1); assertEquals(listOf(9), offsets(third))
        assertFalse(third.getValue("has_more_in_chapter").jsonPrimitive.boolean); assertFalse(third.containsKey("next_chapter_search_offset"))
        val neighbor = search(text, "AA", chapter = 1)
        assertEquals(listOf(7, 8, 9), offsets(neighbor))
        assertNotEquals(first.getValue("chapter_id"), neighbor.getValue("chapter_id"))
        for (page in listOf(first, second, third, neighbor)) for (hit in page.getValue("hits").jsonArray) {
            assertEquals("AA", hit.jsonObject.getValue("untrusted_file_data").jsonPrimitive.content); assertFalse(hit.jsonObject.containsKey("next_offset"))
        }
    }
    @Test fun physicalBodyGapsAndNeighborAreNeverSearchedEvenWhenLiteralSpansAdjacentBodies(): Unit = runBlocking {
        val text = "Chapter 1\nAB[GENERATED]CD\nChapter 2\nABCD"; val next = text.indexOf("Chapter 2")
        val content = MaterialContent(text, "partial", textLocations = listOf(
            MaterialTextLocation("pdf_physical_page", 1, 0, text.indexOf("[")),
            MaterialTextLocation("pdf_physical_page", 3, text.indexOf("CD"), next),
            MaterialTextLocation("pdf_physical_page", 4, next, text.length)))
        val file = item("pdf"); val index = MaterialChapterIndex.build(file, content)
        suspend fun find(query: String) = MaterialChapterSearch.search(file, content, index, index.getValue("index_sha256").jsonPrimitive.content, "heading-0", query, 0, 20)
        assertEquals(emptyList<Int>(), offsets(find("GENERATED"))); assertEquals(emptyList<Int>(), offsets(find("ABCD")))
        val hit = find("CD").getValue("hits").jsonArray.single().jsonObject
        assertEquals(3, hit.getValue("source_receipt").jsonObject.getValue("source_locations").jsonArray.single().jsonObject.getValue("number").jsonPrimitive.int)
    }
    @Test fun supplementaryUnicodeOverlapsAndExactLiteralCaseArePreserved(): Unit = runBlocking {
        val text = "# A\n😀😀😀 a.A\n# B\n😀😀\n"
        val first = search(text, "😀😀", limit = 1); assertEquals(listOf(4), offsets(first))
        val next = first.getValue("next_chapter_search_offset").jsonPrimitive.int
        assertEquals(6, next); assertEquals(listOf(6), offsets(search(text, "😀😀", next, 1)))
        assertEquals(listOf(11), offsets(search(text, "a.A"))); assertEquals(emptyList<Int>(), offsets(search(text, "A.a")))
        assertTrue(runCatching { search(text, "😀", 5) }.isFailure)
        assertTrue(runCatching { search(text, "\uD800") }.isFailure)
    }
    @Test fun chapterEndAndEmptySuffixDoNotInventCompletenessOrHits(): Unit = runBlocking {
        val result = search("# A\nx\n# B\nneedle", "needle", 6)
        assertEquals(emptyList<Int>(), offsets(result)); assertFalse(result.getValue("has_more_in_chapter").jsonPrimitive.boolean)
        assertEquals("NOT_ASSERTED", result.getValue("chapter_completeness").jsonPrimitive.content)
        assertTrue(runCatching { search("# A\nx\n# B\nneedle", "needle", 7) }.isFailure)
    }
    @Test fun staleIndexUnknownHeadingAndOutOfContractInputsAreRejected(): Unit = runBlocking {
        val content = MaterialContent("# A\nBODY", "synthetic"); val index = MaterialChapterIndex.build(item(), content)
        val hash = index.getValue("index_sha256").jsonPrimitive.content
        assertTrue(runCatching { MaterialChapterSearch.search(item(), content, index, "0".repeat(64), "heading-0", "BODY", 0, 20) }.isFailure)
        assertTrue(runCatching { MaterialChapterSearch.search(item(), content, index, hash, "heading-999", "BODY", 0, 20) }.isFailure)
        for ((query, offset, limit) in listOf(Triple("", 0, 1), Triple("a".repeat(257), 0, 1), Triple("a", -1, 1), Triple("a", 0, 21))) {
            assertTrue(runCatching { MaterialChapterSearch.search(item(), content, index, hash, "heading-0", query, offset, limit) }.isFailure)
        }
    }
    @Test fun boundedScannerKeepsExistingWholeSourceSearchBehavior(): Unit = runBlocking {
        val content = MaterialContent("# A\nAAA\n# B\nAAA", "synthetic")
        val result = MaterialSourceSearch.search(item(), content, "AA", 0, 20)
        assertEquals(listOf(4, 5, 12, 13), result.getValue("hits").jsonArray.map { it.jsonObject.getValue("offset").jsonPrimitive.int })
        assertFalse(result.getValue("has_more").jsonPrimitive.boolean)
    }
}
