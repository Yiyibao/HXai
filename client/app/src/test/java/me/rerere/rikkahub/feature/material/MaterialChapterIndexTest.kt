package me.rerere.rikkahub.feature.material

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MaterialChapterIndexTest {
    private fun item(ext: String = "md") = MaterialFile("00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002", "synthetic.$ext", ext, state = "SUCCEEDED", sha256 = "a".repeat(64))
    @Test fun repeatedHeadingsHaveDistinctRealPositionsAndNextHeadingBoundaries(): Unit = runBlocking {
        val text = "preface\n# Same 😀\nbody Chapter 9 not a heading\n# Same 😀\nend\n"
        val index = MaterialChapterIndex.build(item(), MaterialContent(text, "synthetic complete text"))
        val chapters = index.getValue("chapters").jsonArray
        assertEquals(2, chapters.size)
        val a = chapters[0].jsonObject; val b = chapters[1].jsonObject
        assertNotEquals(a["chapter_id"], b["chapter_id"]); assertEquals(a["title"], b["title"])
        assertEquals(b["start"], a["end"]); assertEquals(text.length, b.getValue("end").jsonPrimitive.int)
        for (chapter in chapters.map { it.jsonObject }) {
            val evidence = chapter.getValue("heading_evidence").jsonObject
            assertEquals(chapter["title"], evidence["untrusted_file_data"])
            assertEquals(chapter["start"], evidence.getValue("source_receipt").jsonObject.getValue("locator").jsonObject["start"])
        }
        assertEquals("NOT_ASSERTED", index.getValue("document_completeness").jsonPrimitive.content)
    }
    @Test fun generatedPdfMarkersAndPlaceholderTextCannotBecomeHeadings(): Unit = runBlocking {
        val prefix = "# FAKE READER LABEL\n"; val body = "Chapter 1 Real\nbody\n"; val suffix = "# FAKE EMPTY PAGE\n"
        val content = MaterialContent(prefix + body + suffix, "partial", totalPages = 3, extractedPages = 2,
            pagesWithoutExtractableText = listOf(2), textLocations = listOf(MaterialTextLocation("pdf_physical_page", 1, prefix.length, prefix.length + body.length)))
        val index = MaterialChapterIndex.build(item("pdf"), content)
        val chapter = index.getValue("chapters").jsonArray.single().jsonObject
        assertEquals("Chapter 1 Real", chapter.getValue("title").jsonPrimitive.content)
        assertEquals(prefix.length, chapter.getValue("start").jsonPrimitive.int)
        assertEquals(JsonArray(listOf(JsonPrimitive(2))), index["pages_without_extractable_text"])
        assertEquals(1, chapter.getValue("heading_evidence").jsonObject.getValue("source_receipt").jsonObject.getValue("source_locations").jsonArray.single().jsonObject.getValue("number").jsonPrimitive.int)
    }
    @Test fun truncatedIndexStopsAtFirstOmittedHeadingRatherThanSwallowingItsBody(): Unit = runBlocking {
        val text = (1..514).joinToString("\n") { "# Chapter $it\nBODY_$it" }
        val index = MaterialChapterIndex.build(item(), MaterialContent(text, "full extracted text"))
        val chapters = index.getValue("chapters").jsonArray
        assertEquals(512, chapters.size); assertTrue(index.getValue("index_truncated").jsonPrimitive.boolean)
        assertEquals(text.indexOf("# Chapter 513"), chapters.last().jsonObject.getValue("end").jsonPrimitive.int)
        assertEquals(chapters.last().jsonObject["end"], index["unindexed_tail_start"])
        assertEquals("NEXT_DETECTED_HEADING", chapters.last().jsonObject.getValue("boundary_basis").jsonPrimitive.content)
    }
    @Test fun differentHeadingConventionsStayLiteralAndNoHeadingDoesNotMeanCompleteBook(): Unit = runBlocking {
        val text = "第十二章 连续性\n第2篇 实数\nChapter IV Limits\n## 微分\nordinary text\n"
        val index = MaterialChapterIndex.build(item(), MaterialContent(text, "synthetic"))
        assertEquals(listOf("第十二章 连续性", "第2篇 实数", "Chapter IV Limits", "## 微分"), index.getValue("chapters").jsonArray.map { it.jsonObject.getValue("title").jsonPrimitive.content })
        val empty = MaterialChapterIndex.build(item(), MaterialContent("no detected heading", "synthetic"))
        assertTrue(empty.getValue("chapters").jsonArray.isEmpty()); assertEquals("NOT_ASSERTED", empty.getValue("document_completeness").jsonPrimitive.content)
    }
}
