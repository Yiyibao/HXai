package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.search.*
import org.junit.Assert.*
import org.junit.Test

class EnhancedSearchToolsTest {
    @Test fun internalDeadlineReturnsTimeoutButOuterStopStillPropagates() = kotlinx.coroutines.runBlocking {
        val output = searchToolResult(timeoutMs = 10) { kotlinx.coroutines.delay(1000); emptyList() }.single() as me.rerere.ai.ui.UIMessagePart.Text
        assertEquals("timeout", Json.parseToJsonElement(output.text).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        try {
            kotlinx.coroutines.withTimeout(10) { searchToolResult { kotlinx.coroutines.delay(1000); emptyList() } }
            fail("Outer cancellation was swallowed")
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) { }
    }
    @Test fun duplicateUnsafeAndOversizedEvidenceIsNormalizedWithStableIds() {
        val items = listOf(
            SearchResult.SearchResultItem("First", "https://source.org/page", "x".repeat(50000)),
            SearchResult.SearchResultItem("Duplicate", "https://source.org/page#section", "other"),
            SearchResult.SearchResultItem("Unsafe", "javascript:bad", "bad"))
        val first = enhanceSearchResult(SearchResult(items = items), buildJsonObject { put("query", "q") })
        val second = enhanceSearchResult(SearchResult(items = items), buildJsonObject { put("query", "q") })
        assertEquals(1, first.items.size)
        assertEquals(first.items.single().id, second.items.single().id)
        assertTrue(first.items.single().text.length <= 1500)
    }
    @Test fun searchFailureReturnsEvidenceErrorInsteadOfAbortingGeneration() = kotlinx.coroutines.runBlocking {
        val output = searchToolResult { throw NoSearchResultsException() }.single() as me.rerere.ai.ui.UIMessagePart.Text
        val data = Json.parseToJsonElement(output.text).jsonObject
        assertEquals("error", data["status"]?.jsonPrimitive?.content)
        assertEquals("no_results", data["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
        assertTrue(data["items"]!!.jsonArray.isEmpty())
        assertNotNull(data["nextAction"])
    }
    @Test fun userStopStillCancelsSearch() = kotlinx.coroutines.runBlocking {
        try {
            searchToolResult { throw kotlinx.coroutines.CancellationException("stop") }
            fail("Cancellation was swallowed")
        } catch (_: kotlinx.coroutines.CancellationException) { }
    }
    @Test fun defaultBingCanReadOriginalWebPages() {
        val tool = createSearchTools(Settings(searchServices = listOf(SearchServiceOptions.BingLocalOptions()))).single { it.name == "scrape_web" }
        assertEquals(listOf("url"), (tool.parameters() as me.rerere.ai.core.InputSchema.Obj).required)
    }
    @Test fun provenanceSummaryAndDatesSurviveEnhancement() {
        val raw = SearchResult(answer = "provider summary", retrievedAt = "retrieved",
            items = listOf(SearchResult.SearchResultItem("Article", "https://source.org/page", "evidence", "2026-01-01", listOf("highlight"))),
            images = listOf("https://cdn.org/image.jpg"),
            imageDetails = listOf(SearchImage("https://cdn.org/image.jpg", "https://source.org/page", "Article", "Author", "2026-01-01")))
        val result = enhanceSearchResult(raw, buildJsonObject { put("query", "query") })
        assertEquals("provider summary", result.answer)
        assertEquals("retrieved", result.retrievedAt)
        assertEquals("2026-01-01", result.items.single().publishedDate)
        assertEquals("highlight", result.items.single().highlights)
        assertEquals("https://source.org/page", result.images.single().sourceUrl)
        assertEquals("Author", result.images.single().author)
        assertEquals("candidates", result.imageStatus)
    }
    @Test fun imageUrlsAreBoundedDeduplicatedAndNeverAssignedGuessedProvenance() {
        val raw = SearchResult(items = emptyList(), images = listOf("javascript:bad", "file:///bad", "https://user:pass@host/image", "https://cdn.org/i.png", "https://cdn.org/i.png") + (1..30).map { "https://cdn.org/$it.png" })
        val result = enhanceSearchResult(raw, buildJsonObject { put("q", "keyword") })
        assertEquals("keyword", result.query)
        assertEquals(10, result.images.size)
        assertNull(result.images.first().sourceUrl)
        assertNull(result.images.first().author)
        assertEquals("https://cdn.org/i.png", result.images.first().url)
        assertEquals(10, result.images.map { it.url }.distinct().size)
    }
    @Test fun emptyImagesDoNotDiscardWebEvidence() {
        val result = enhanceSearchResult(SearchResult(items = listOf(SearchResult.SearchResultItem("Title", "https://source.org", "Text")), imageWarning = "image failure"), buildJsonObject { put("query", "q") })
        assertEquals("unavailable", result.imageStatus)
        assertEquals("Text", result.items.single().text)
        assertEquals("image failure", result.imageWarning)
    }
    @Test fun actualSearchEntryPointUsesEnhancedContractAndPreservesProviderSchema() {
        val tool = createSearchTools(Settings(searchServices = listOf(SearchServiceOptions.ExaOptions()))).single { it.name == "search_web" }
        assertTrue(tool.description.contains("images[].url"))
        assertTrue(tool.description.contains("sourceUrl"))
        assertTrue((tool.parameters() as me.rerere.ai.core.InputSchema.Obj).properties.containsKey("includeDomains"))
    }
}
