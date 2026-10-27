package me.rerere.rikkahub.feature.material

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MaterialChapterReadTest {
    private fun item(ext: String = "md") = MaterialFile("00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002", "synthetic.$ext", ext, state = "SUCCEEDED", sha256 = "a".repeat(64))
    private fun hash(index: JsonObject) = index.getValue("index_sha256").jsonPrimitive.content
    private fun id(index: JsonObject) = index.getValue("chapters").jsonArray.first().jsonObject.getValue("chapter_id").jsonPrimitive.content
    @Test fun fullChunkStopsBeforeNextHeadingInSameBodyAndDoesNotAdvertisePhantomContinuation(): Unit = runBlocking {
        val text = "# First\nBODY_ONE\n# Second\nMUST_NOT_LEAK\n"; val content = MaterialContent(text, "synthetic")
        val index = MaterialChapterIndex.build(item(), content)
        val page = MaterialChapterRead.page(item(), content, index, hash(index), id(index), 0, 24000)
        assertEquals("# First\nBODY_ONE\n", page.getValue("untrusted_file_data").jsonPrimitive.content)
        assertFalse(page.getValue("has_more_in_chapter").jsonPrimitive.boolean)
        assertFalse(page.containsKey("next_chapter_offset")); assertFalse(page.containsKey("next_offset"))
    }
    @Test fun pdfContinuationSkipsSyntheticGapsWithoutInventingAQuoteOrNextChapter(): Unit = runBlocking {
        val a = "Chapter 1\nBODY\n"; val gap = "\n[物理页 2]\n[无可提取文本]\n"; val b = "CONTINUED\n"; val next = "Chapter 2\nSTOP\n"
        val startB = a.length + gap.length; val startNext = startB + b.length
        val content = MaterialContent(a + gap + b + next, "partial", textLocations = listOf(
            MaterialTextLocation("pdf_physical_page", 1, 0, a.length), MaterialTextLocation("pdf_physical_page", 3, startB, startNext), MaterialTextLocation("pdf_physical_page", 4, startNext, startNext + next.length)))
        val index = MaterialChapterIndex.build(item("pdf"), content)
        val first = MaterialChapterRead.page(item("pdf"), content, index, hash(index), id(index), 0, 24000)
        val second = MaterialChapterRead.page(item("pdf"), content, index, hash(index), id(index), first.getValue("next_chapter_offset").jsonPrimitive.int, 24000)
        assertEquals(a, first.getValue("untrusted_file_data").jsonPrimitive.content)
        assertEquals(b, second.getValue("untrusted_file_data").jsonPrimitive.content)
        assertEquals(startB, second.getValue("chapter_offset").jsonPrimitive.int)
        assertTrue(second.getValue("chapter_offset").jsonPrimitive.int > second.getValue("requested_chapter_offset").jsonPrimitive.int)
        assertFalse(second.getValue("has_more_in_chapter").jsonPrimitive.boolean)
    }
    @Test fun surrogateBoundaryIsRejectedButWholeCharacterAndEmptyExtractedTailRemainAccurate(): Unit = runBlocking {
        val text = "# One\n😀Z\n"; val content = MaterialContent(text, "synthetic"); val index = MaterialChapterIndex.build(item(), content)
        val emoji = text.indexOf("😀")
        assertTrue(runCatching { MaterialChapterRead.page(item(), content, index, hash(index), id(index), emoji, 1) }.isFailure)
        assertTrue(runCatching { MaterialChapterRead.page(item(), content, index, hash(index), id(index), emoji + 1, 2) }.isFailure)
        assertEquals("😀", MaterialChapterRead.page(item(), content, index, hash(index), id(index), emoji, 2).getValue("untrusted_file_data").jsonPrimitive.content)
        val end = MaterialChapterRead.page(item(), content, index, hash(index), id(index), text.length, 1)
        assertEquals("NOT_AVAILABLE", end.getValue("status").jsonPrimitive.content); assertFalse(end.containsKey("source_receipt"))
    }
    @Test fun wrongIndexAndUnknownHeadingNeverSelectAnotherRange(): Unit = runBlocking {
        val content = MaterialContent("# Known\nbody", "synthetic"); val index = MaterialChapterIndex.build(item(), content)
        assertTrue(runCatching { MaterialChapterRead.page(item(), content, index, "0".repeat(64), id(index), 0, 10) }.isFailure)
        assertTrue(runCatching { MaterialChapterRead.page(item(), content, index, hash(index), "heading-999", 0, 10) }.isFailure)
        assertEquals("# Known\nbod", MaterialChapterRead.page(item(), content, index, hash(index), id(index), 0, 11).getValue("untrusted_file_data").jsonPrimitive.content)
    }
}
