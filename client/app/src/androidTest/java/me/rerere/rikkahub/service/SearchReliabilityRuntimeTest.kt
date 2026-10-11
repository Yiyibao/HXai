package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.searchToolResult
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.search.NoSearchResultsException
import me.rerere.search.SearchServiceOptions
import org.junit.Assert.*
import org.junit.Test

/** Production tools on the emulator; does not modify settings, conversations or call a paid model. */
class SearchReliabilityRuntimeTest {
    @Test fun recoverableSearchErrorAndCancellationHaveDifferentOutcomes() = runBlocking {
        val output = searchToolResult { throw NoSearchResultsException() }.single() as UIMessagePart.Text
        assertEquals("error", Json.parseToJsonElement(output.text).jsonObject["status"]!!.jsonPrimitive.content)
        try {
            searchToolResult { throw CancellationException("stop") }
            fail("Stop must cancel")
        } catch (_: CancellationException) { }
    }

    @Test fun liveDefaultSearchAndSourceReading() = runBlocking {
        val tools = createSearchTools(Settings(searchServices = listOf(SearchServiceOptions.BingLocalOptions())))
        val search = tools.single { it.name == "search_web" }.execute(buildJsonObject { put("query", "Wolfram MathWorld derivative") })
        val result = Json.parseToJsonElement((search.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("Live search must return actual sources", "ok", result["status"]!!.jsonPrimitive.content)
        assertTrue(result["items"]!!.jsonArray.isNotEmpty())
        val fetch = tools.single { it.name == "scrape_web" }.execute(buildJsonObject { put("url", "https://mathworld.wolfram.com/Derivative.html") })
        val page = Json.parseToJsonElement((fetch.single() as UIMessagePart.Text).text).jsonObject
        assertTrue("Original page must be readable", page["urls"]!!.jsonArray.first().jsonObject["content"]!!.jsonPrimitive.content.length > 100)
        println("SEARCH_RUNTIME provider=${result["provider"]} sources=${result["items"]!!.jsonArray.size} originalPageRead=true")
    }
}
