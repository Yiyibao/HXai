package me.rerere.search

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import javax.imageio.ImageIO

class WebImageServiceTest {
    @Test fun imageSearchEndpointReturnsProvenanceThenVerifiesImageBytes() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"
        val png = java.io.ByteArrayOutputStream().also {
            ImageIO.write(java.awt.image.BufferedImage(2, 1, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray()
        server.createContext("/images/search") {
            assertEquals("q=aurora+nasa", it.requestURI.rawQuery)
            val html = """<a class="iusc" m='{"murl":"$base/photo.png","purl":"$base/source","t":"NASA"}'></a>""".toByteArray()
            it.responseHeaders.add("Content-Type", "text/html")
            it.sendResponseHeaders(200, html.size.toLong())
            it.responseBody.use { output -> output.write(html) }
        }
        server.createContext("/photo.png") {
            it.responseHeaders.add("Content-Type", "image/png")
            it.sendResponseHeaders(200, png.size.toLong())
            it.responseBody.use { output -> output.write(png) }
        }
        server.start()
        try {
            val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                if (request.url.host == "www.bing.com") chain.proceed(request.newBuilder()
                    .url("$base${request.url.encodedPath}?${request.url.encodedQuery}").build())
                else chain.proceed(request)
            }.build()
            val service = WebImageService(decode = { bytes ->
                ImageIO.read(java.io.ByteArrayInputStream(bytes))?.let { it.width to it.height }
            }, client = client)
            val result = service.verify(service.search("aurora nasa")).single()
            assertEquals("$base/photo.png", result.image.url)
            assertEquals("$base/source", result.image.sourceUrl)
            assertEquals("NASA", result.image.sourceTitle)
            assertEquals(2, result.width)
        } finally { server.stop(0) }
    }

    @Test fun getDecodeRedirectAndFailureFiltering() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val png = java.io.ByteArrayOutputStream().also {
            ImageIO.write(java.awt.image.BufferedImage(4, 3, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray()
        fun route(path: String, status: Int, type: String, body: ByteArray) {
            server.createContext(path) { exchange ->
                assertEquals("GET", exchange.requestMethod)
                assertNull(exchange.requestHeaders.getFirst("Referer"))
                exchange.responseHeaders.add("Content-Type", type)
                exchange.sendResponseHeaders(status, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
        route("/image", 200, "image/png", png)
        route("/fake", 200, "image/png", "not an image".toByteArray())
        route("/html", 200, "text/html", png)
        route("/forbidden", 403, "image/png", png)
        route("/large", 200, "image/png", ByteArray(8 * 1024 * 1024 + 1))
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "/image")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val service = WebImageService(decode = { bytes ->
                ImageIO.read(java.io.ByteArrayInputStream(bytes))?.let { it.width to it.height }
            })
            val images = listOf("image", "redirect", "fake", "html", "forbidden", "large", "image")
                .map { SearchImage("$base/$it", "$base/page", "Title") }
            val result = service.verify(images)
            assertEquals(listOf("$base/image", "$base/redirect"), result.map { it.image.url })
            assertTrue(result.all { it.width == 4 && it.height == 3 && it.contentType == "image/png" })
            assertTrue(result.all { it.image.sourceUrl == "$base/page" && it.verifiedAt.isNotBlank() })
        } finally { server.stop(0) }
    }

    @Test fun extractsRealPageSourcesRelativeLazyAndSrcsetWithoutInventingMetadata() {
        val doc = Jsoup.parse("""
            <title>Article</title><meta property="og:image" content="/hero.png">
            <img src="/hero.png"><img data-src="../lazy.jpg" alt="图示">
            <img srcset="/small.png 1x, /large.png 2x"><img src="data:image/png;base64,bad">
            <img src="https://user:pass@host/private.jpg"><img src="javascript:bad">
        """.trimIndent(), "https://source.org/articles/page")
        val images = WebImageService.extractCandidates(doc)
        assertEquals(listOf("https://source.org/hero.png", "https://source.org/lazy.jpg", "https://source.org/small.png", "https://source.org/large.png"), images.map { it.url })
        assertTrue(images.all { it.sourceUrl == "https://source.org/articles/page" && it.sourceTitle == "Article" })
        assertEquals("图示", images[1].alt)
        assertTrue(images.all { it.author == null && it.publishedDate == null })
    }

    @Test fun extractionUsesFinalPageAfterRedirect() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/old") { it.responseHeaders.add("Location", "/article/page"); it.sendResponseHeaders(302, -1); it.close() }
        server.createContext("/article/page") {
            val html = "<title>Final</title><img src='figure.png'>".toByteArray()
            it.responseHeaders.add("Content-Type", "text/html")
            it.sendResponseHeaders(200, html.size.toLong())
            it.responseBody.use { output -> output.write(html) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val image = WebImageService(decode = { null }).extract("$base/old").single()
            assertEquals("$base/article/figure.png", image.url)
            assertEquals("$base/article/page", image.sourceUrl)
        } finally { server.stop(0) }
    }
}
