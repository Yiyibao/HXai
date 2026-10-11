package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrl

object BingSearchService : SearchService<SearchServiceOptions.BingLocalOptions> {
    override val name: String = "Bing"

    @Composable
    override fun Description() {
        Text(stringResource(R.string.bing_desc))
    }

    override fun parameters(options: SearchServiceOptions.BingLocalOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("maxLength", 1000)
                    put("description", "search keyword")
                })
                listOf("includeDomains", "excludeDomains").forEach { field ->
                    put(field, buildJsonObject {
                        put("type", "array")
                        put("maxItems", 5)
                        put("description", "Optional domains without protocol or path; includes subdomains")
                        put("items", buildJsonObject { put("type", "string") })
                    })
                }
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.BingLocalOptions): InputSchema? = null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.BingLocalOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        try {
            val primitive = params["query"]?.jsonPrimitive
            require(primitive?.isString == true) { "query must be a string" }
            val query = primitive.content.trim()
            require(query.isNotBlank() && query.length <= 1000) { "query must contain 1 to 1000 characters" }
            fun domains(field: String): List<String> = params[field]?.jsonArray?.also { require(it.size <= 5) }
                ?.map { value ->
                    require(value.jsonPrimitive.isString)
                    value.jsonPrimitive.content.lowercase(Locale.ROOT).also {
                        require(it.length <= 253 && it.matches(Regex("[a-z0-9]+(?:[a-z0-9.-]*[a-z0-9])?")) && !it.contains(".."))
                    }
                }.orEmpty()
            val include = domains("includeDomains")
            val exclude = domains("excludeDomains")
            val constrainedQuery = buildString {
                append(query)
                if (include.isNotEmpty()) append(include.joinToString(" OR ", " (", ")") { "site:$it" })
                exclude.forEach { append(" -site:$it") }
            }
            val encoded = URLEncoder.encode(constrainedQuery, "UTF-8")
            val attempts = listOf(
                "Bing" to "https://www.bing.com/search?q=$encoded",
                "Bing RSS" to "https://www.bing.com/search?format=rss&q=$encoded",
                "DuckDuckGo" to "https://html.duckduckgo.com/html/?q=$encoded",
            )
            var lastError: Exception? = null
            for ((index, attempt) in attempts.withIndex()) {
                try {
                    val doc = fetchSearchPage(attempt.second)
                    val items = parseWebResults(doc).filter { item -> matchesDomains(item.url, include, exclude) }
                        .take(commonOptions.resultSize.coerceIn(1, 10))
                    if (items.isEmpty()) { lastError = NoSearchResultsException(); continue }
                    // Image discovery is separate: useful text must never wait for an image endpoint.
                    return@withContext Result.success(SearchResult(items = items, provider = attempt.first,
                        warning = if (index > 0) "首选搜索入口不可用，已使用备用入口。" else null))
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { lastError = e }
            }
            Result.failure(lastError ?: NoSearchResultsException())
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    }

    private suspend fun fetchSearchPage(url: String): org.jsoup.nodes.Document {
        val client = SearchService.httpClient.newBuilder().callTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()
        return client.newCall(okhttp3.Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
            .header("Accept-Language", Locale.getDefault().toLanguageTag()).build()).await().use { response ->
            if (!response.isSuccessful) throw me.rerere.ai.util.HttpException("Search endpoint unavailable", response.code)
            val bytes = response.body.byteStream().use { readSearchBytes(it, 2_000_001) }
            require(bytes.size <= 2_000_000) { "Search response too large" }
            val rss = response.request.url.queryParameter("format") == "rss"
            if (rss) Jsoup.parse(String(bytes, Charsets.UTF_8), response.request.url.toString(), org.jsoup.parser.Parser.xmlParser())
            else Jsoup.parse(java.io.ByteArrayInputStream(bytes), null, response.request.url.toString())
        }
    }

    internal fun parseWebResults(doc: org.jsoup.nodes.Document): List<SearchResultItem> {
        val rows = when {
            doc.select("channel > item").isNotEmpty() -> doc.select("channel > item").map {
                SearchResultItem(it.select("title").text(), it.select("link").text(), it.select("description").text())
            }
            doc.select("li.b_algo").isNotEmpty() -> doc.select("li.b_algo").map {
                SearchResultItem(it.select("h2").text(), decodeSearchUrl(it.select("h2 a").attr("href")), it.select(".b_caption p").text())
            }
            else -> doc.select(".result").map {
                SearchResultItem(it.select(".result__a").text(), decodeSearchUrl(it.select(".result__a").attr("href")), it.select(".result__snippet").text())
            }
        }
        return rows.filter { it.title.isNotBlank() && validWebImageUrl(it.url) != null }
            .distinctBy { it.url }.map { it.copy(title = it.title.take(300), text = it.text.take(1200)) }
    }

    private fun decodeSearchUrl(raw: String): String = runCatching {
        val url = (if (raw.startsWith("//")) "https:$raw" else raw).toHttpUrl()
        val host = url.host
        when {
            host == "duckduckgo.com" || host.endsWith(".duckduckgo.com") -> url.queryParameter("uddg") ?: raw
            (host == "bing.com" || host.endsWith(".bing.com")) && url.encodedPath == "/ck/a" -> {
                val target = url.queryParameter("u").orEmpty()
                if (target.startsWith("a1")) String(java.util.Base64.getUrlDecoder().decode(target.drop(2)), Charsets.UTF_8) else raw
            }
            else -> raw
        }
    }.getOrDefault(raw)

    internal fun matchesDomains(url: String, include: List<String>, exclude: List<String>): Boolean {
        val host = runCatching { url.toHttpUrl().host.lowercase(Locale.ROOT) }.getOrNull() ?: return false
        fun matches(domain: String) = host == domain || host.endsWith(".$domain")
        return (include.isEmpty() || include.any(::matches)) && exclude.none(::matches)
    }

    internal fun parseImageCandidates(doc: org.jsoup.nodes.Document): List<SearchImage> {
        fun url(value: String?): String? = value?.takeIf { candidate -> runCatching {
            val parsed = java.net.URI(candidate)
            parsed.scheme in setOf("http", "https") && !parsed.host.isNullOrEmpty() && parsed.userInfo == null
        }.getOrDefault(false) }
        return doc.select(".iusc[m]").mapNotNull { node ->
            runCatching {
                val metadata = Json.parseToJsonElement(node.attr("m")).jsonObject
                val image = url(metadata["murl"]?.jsonPrimitive?.content) ?: return@runCatching null
                SearchImage(
                    url = image,
                    sourceUrl = url(metadata["purl"]?.jsonPrimitive?.content),
                    sourceTitle = metadata["t"]?.jsonPrimitive?.content?.take(200),
                )
            }.getOrNull()
        }.distinctBy { it.url }.take(10)
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.BingLocalOptions
    ): Result<ScrapedResult> {
        return Result.failure(Exception("Scraping is not supported for Bing"))
    }
}

class NoSearchResultsException : IllegalStateException("没有获得可用的搜索结果，请更换关键词或稍后重试。")
