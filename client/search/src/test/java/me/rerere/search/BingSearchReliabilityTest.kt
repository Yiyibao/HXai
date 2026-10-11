package me.rerere.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class BingSearchReliabilityTest {
    @Test fun domainControlsApplyToActualHostsIncludingSubdomains() {
        assertTrue(BingSearchService.matchesDomains("https://docs.source.org/page", listOf("source.org"), emptyList()))
        assertFalse(BingSearchService.matchesDomains("https://source.org.evil.org/page", listOf("source.org"), emptyList()))
        assertFalse(BingSearchService.matchesDomains("https://source.org/page", emptyList(), listOf("source.org")))
    }
    @Test fun blockedHtmlFallsBackToRssAndPreservesSource() = runBlocking {
        val previous = SearchService.httpClient
        val paths = mutableListOf<String>()
        try {
            SearchService.httpClient = OkHttpClient.Builder().addInterceptor { chain ->
                val rss = chain.request().url.queryParameter("format") == "rss"
                paths += chain.request().url.toString()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body((if (rss) "<rss><channel><item><title>Official evidence</title><link>https://source.org/page</link><description>Source text</description></item></channel></rss>" else "<html>captcha</html>").toResponseBody("text/html".toMediaType())).build()
            }.build()
            val result = BingSearchService.search(buildJsonObject { put("query", "test") }, SearchCommonOptions(), SearchServiceOptions.BingLocalOptions()).getOrThrow()
            assertEquals(2, paths.size)
            assertEquals("Bing RSS", result.provider)
            assertNotNull(result.warning)
            assertEquals("https://source.org/page", result.items.single().url)
            assertNull(result.items.single().publishedDate)
        } finally { SearchService.httpClient = previous }
    }

    @Test fun allEndpointsEmptyReturnFailureAndDoNotLoop() = runBlocking {
        val previous = SearchService.httpClient
        var calls = 0
        try {
            SearchService.httpClient = OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("<html>no results</html>".toResponseBody("text/html".toMediaType())).build()
            }.build()
            val result = BingSearchService.search(buildJsonObject { put("query", "test") }, SearchCommonOptions(), SearchServiceOptions.BingLocalOptions())
            assertTrue(result.exceptionOrNull() is NoSearchResultsException)
            assertEquals(3, calls)
        } finally { SearchService.httpClient = previous }
    }

    @Test fun parsesDuckDuckGoRedirectAndFiltersUnsafeOrDuplicateLinks() {
        val doc = Jsoup.parse("""<div class="result"><a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fsource.org%2Fpage">Article</a><span class="result__snippet">Evidence</span></div><div class="result"><a class="result__a" href="javascript:bad">Bad</a></div>""")
        assertEquals("https://source.org/page", BingSearchService.parseWebResults(doc).single().url)
    }
}
