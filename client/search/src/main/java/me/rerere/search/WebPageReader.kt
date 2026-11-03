package me.rerere.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import java.net.InetAddress
import java.util.concurrent.TimeUnit

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
class WebPageReader(client: OkHttpClient = OkHttpClient()) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(10, TimeUnit.SECONDS).dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).also {
                require(it.isNotEmpty() && it.all(::isPublicAddress)) { "Only public web pages are supported" }
            }
        }).build()

    suspend fun read(rawUrl: String): ScrapedResult = withContext(Dispatchers.IO) {
        var url = checkedUrl(rawUrl)
        repeat(5) {
            client.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0")
                .header("Accept", "text/html,application/xhtml+xml,text/plain").build()).await().use { response ->
                if (response.code in listOf(301, 302, 303, 307, 308)) {
                    url = checkedUrl(requireNotNull(url.resolve(requireNotNull(response.header("Location")))).toString())
                } else {
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
        error("Too many redirects")
    }

    companion object {
        private fun isPublicAddress(address: InetAddress) = !address.isAnyLocalAddress && !address.isLoopbackAddress &&
            !address.isSiteLocalAddress && !address.isLinkLocalAddress && !address.isMulticastAddress &&
            !(address.address.size == 16 && (address.address[0].toInt() and 0xfe) == 0xfc) &&
            !(address.address.size == 4 && (address.address[0].toInt() and 255) == 100 &&
                (address.address[1].toInt() and 255) in 64..127)

        internal fun checkedUrl(raw: String): okhttp3.HttpUrl {
            require(validWebImageUrl(raw) != null) { "Invalid web URL" }
            val url = raw.toHttpUrl()
            require(url.port in listOf(80, 443) && url.host != "localhost" && !url.host.endsWith(".local"))
            if (url.host.contains(':') || url.host.matches(Regex("[0-9.]+"))) {
                require(isPublicAddress(InetAddress.getByName(url.host))) { "Only public web pages are supported" }
            }
            return url
        }
    }
}
