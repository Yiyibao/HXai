package me.rerere.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
data class VerifiedWebImage(
    val image: SearchImage,
    val width: Int,
    val height: Int,
    val contentType: String,
    val verifiedAt: String,
    val bytes: ByteArray,
)

/** Returns the downloaded and decoded bytes; consumers must not fetch the URL again. */
class WebImageService internal constructor(
    private val decode: (ByteArray) -> Pair<Int, Int>?,
    private val transport: PublicWebTransport,
) {
    constructor(decode: (ByteArray) -> Pair<Int, Int>?) : this(decode, PublicWebTransport())
    private val slots = Semaphore(3)

    suspend fun verify(images: List<SearchImage>): List<VerifiedWebImage> = coroutineScope {
        images.filter { validWebImageUrl(it.url) != null }.distinctBy { it.url }.take(10).map { image ->
            async { slots.withPermit { verifyOne(image) } }
        }.awaitAll().filterNotNull()
    }

    private suspend fun verifyOne(image: SearchImage): VerifiedWebImage? = withContext(Dispatchers.IO) {
        try {
            transport.get(image.url, "image/png,image/jpeg,image/webp,image/gif").use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body
                val type = body.contentType()?.toString()?.substringBefore(';').orEmpty()
                if (type !in IMAGE_TYPES || body.contentLength() > MAX_BYTES) return@withContext null
                val bytes = body.byteStream().use { readSearchBytes(it, MAX_BYTES + 1) }
                if (bytes.isEmpty() || bytes.size > MAX_BYTES) return@withContext null
                val size = decode(bytes) ?: return@withContext null
                if (size.first !in 1..8192 || size.second !in 1..8192 ||
                    size.first.toLong() * size.second > 32_000_000) return@withContext null
                VerifiedWebImage(image, size.first, size.second, type, java.time.Instant.now().toString(), bytes)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    suspend fun search(query: String): List<SearchImage> = withContext(Dispatchers.IO) {
        require(query.isNotBlank() && query.length <= 1000)
        val url = "https://www.bing.com/images/search?q=" + java.net.URLEncoder.encode(query, "UTF-8")
        BingSearchService.parseImageCandidates(fetchPage(url))
    }

    suspend fun extract(url: String): List<SearchImage> = withContext(Dispatchers.IO) {
        require(validWebImageUrl(url) != null)
        extractCandidates(fetchPage(url))
    }

    private suspend fun fetchPage(url: String): org.jsoup.nodes.Document {
        transport.get(url, "text/html,application/xhtml+xml").use { response ->
            require(response.isSuccessful) { "网页无法访问" }
            val body = response.body
            require(body.contentType()?.type == "text" || body.contentType()?.subtype == "xhtml+xml") { "Only text web pages are supported" }
            val bytes = body.byteStream().use { readSearchBytes(it, 2_000_001) }
            require(bytes.size <= 2_000_000) { "网页过大" }
            return Jsoup.parse(java.io.ByteArrayInputStream(bytes), null, response.request.url.toString())
        }
    }

    companion object {
        private const val MAX_BYTES = 8 * 1024 * 1024
        private val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")


        internal fun extractCandidates(doc: org.jsoup.nodes.Document): List<SearchImage> {
            val source = validWebImageUrl(doc.location()) ?: return emptyList()
            val title = doc.title().take(300)
            val candidates = mutableListOf<SearchImage>()
            fun add(raw: String, alt: String? = null) {
                if (raw.isBlank()) return
                val resolved = runCatching { java.net.URI(source).resolve(raw.trim()).toString() }.getOrNull()
                validWebImageUrl(resolved)?.let { candidates += SearchImage(it, source, title, alt = alt?.take(500)) }
            }
            doc.select("meta[property=og:image], meta[name=twitter:image]").forEach { add(it.attr("content")) }
            doc.select("img, picture source").forEach { node ->
                val alt = node.attr("alt").takeIf { it.isNotBlank() }
                listOf("data-src", "data-original", "src").forEach { attr ->
                    if (node.hasAttr(attr)) add(node.attr(attr), alt)
                }
                node.attr("srcset").split(',').forEach { entry ->
                    val value = entry.trim().substringBefore(' ')
                    if (value.isNotBlank()) add(value, alt)
                }
            }
            return candidates.distinctBy { it.url }.take(10)
        }
    }
}

fun validWebImageUrl(raw: String?): String? = raw?.trim()?.takeIf { url ->
    url.length in 1..16000 && runCatching {
        val uri = java.net.URI(url)
        uri.scheme in setOf("http", "https") && !uri.host.isNullOrEmpty() && uri.userInfo == null
    }.getOrDefault(false)
}
