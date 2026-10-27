package me.rerere.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

internal fun readSearchBytes(input: java.io.InputStream, limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < limit) {
        val count = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (count < 0) break
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}

/** Public web pages only; each redirect is checked before issuing the next request. */
class WebPageReader internal constructor(private val transport: PublicWebTransport) {
    constructor() : this(PublicWebTransport())

    suspend fun read(rawUrl: String): ScrapedResult = withContext(Dispatchers.IO) {
        transport.get(rawUrl, "text/html,application/xhtml+xml,text/plain").use { response ->
            val url = response.request.url
            if (!response.isSuccessful) throw me.rerere.ai.util.HttpException("Web page unavailable", response.code)
            val body = response.body
            require(body.contentType()?.type == "text" || body.contentType()?.subtype == "xhtml+xml") {
                "Only text web pages are supported"
            }
            val bytes = body.byteStream().use { readSearchBytes(it, 1_000_001) }
            require(bytes.size <= 1_000_000) { "Web page too large" }
            val doc = Jsoup.parse(java.io.ByteArrayInputStream(bytes), null, url.toString())
            val date = doc.select("meta[property=article:published_time],meta[name=date]").first()?.attr("content")?.takeIf { it.isNotBlank() }
            val title = doc.title().take(300)
            doc.select("script,style,noscript,nav,footer,header,form").remove()
            val text = (doc.select("article,main").first() ?: doc.body()).text()
            require(text.isNotBlank()) { "Web page has no readable text" }
            return@withContext ScrapedResult(listOf(ScrapedResultUrl(url.toString(), text.take(16000),
                ScrapedResultMetadata(title = title, publishedDate = date))), java.time.Instant.now().toString())
        }
    }

}
