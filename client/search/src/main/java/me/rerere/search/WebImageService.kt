package me.rerere.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

@Serializable
data class VerifiedWebImage(
    val image: SearchImage,
    val width: Int,
    val height: Int,
    val contentType: String,
    val verifiedAt: String,
)

/** GET and decode the same URL the chat loader uses, without cookies or Referer overrides. */
class WebImageService(
    private val decode: (ByteArray) -> Pair<Int, Int>?,
    client: OkHttpClient = OkHttpClient(),
) {
    private val client = client.newBuilder().callTimeout(8, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
    private val slots = Semaphore(3)

    suspend fun verify(images: List<SearchImage>): List<VerifiedWebImage> = coroutineScope {
        images.filter { validWebImageUrl(it.url) != null }.distinctBy { it.url }.take(10).map { image ->
            async { slots.withPermit { verifyOne(image) } }
        }.awaitAll().filterNotNull()
    }

    private suspend fun verifyOne(image: SearchImage): VerifiedWebImage? = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(image.url).build()).await().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body
                val type = body.contentType()?.toString()?.substringBefore(';').orEmpty()
                if (!type.startsWith("image/") || body.contentLength() > MAX_BYTES) return@withContext null
                val bytes = body.byteStream().use { readBounded(it, MAX_BYTES + 1) }
                if (bytes.isEmpty() || bytes.size > MAX_BYTES) return@withContext null
                val size = decode(bytes) ?: return@withContext null
                if (size.first <= 0 || size.second <= 0) return@withContext null
                VerifiedWebImage(image, size.first, size.second, type, java.time.Instant.now().toString())
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
        client.newCall(Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0").build()).await().use { response ->
            require(response.isSuccessful) { "网页无法访问" }
            val body = requireNotNull(response.body)
            val bytes = body.byteStream().use { readBounded(it, 2_000_001) }
            require(bytes.size <= 2_000_000) { "网页过大" }
            return Jsoup.parse(java.io.ByteArrayInputStream(bytes), null, response.request.url.toString())
        }
    }

    companion object {
        private const val MAX_BYTES = 8 * 1024 * 1024

        private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() < limit) {
                val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }

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
