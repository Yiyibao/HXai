package me.rerere.rikkahub.data.ai.tools

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.search.WebImageService
import org.junit.Assert.*
import org.junit.Test

class WebImageToolsTest {
    @Test fun pageExtractionToVerifiedContractToInlineImageAndSource() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/page") {
            val html = "<title>Article</title><img src='/ok.png' alt='diagram'><img src='/bad.png'>".toByteArray()
            it.responseHeaders.add("Content-Type", "text/html")
            it.sendResponseHeaders(200, html.size.toLong())
            it.responseBody.use { output -> output.write(html) }
        }
        listOf("ok", "bad").forEach { name -> server.createContext("/$name.png") {
            it.responseHeaders.add("Content-Type", "image/png")
            it.sendResponseHeaders(200, 1)
            it.responseBody.use { output -> output.write(if (name == "ok") 1 else 0) }
        } }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val service = WebImageService(decode = { if (it.contentEquals(byteArrayOf(1))) 20 to 10 else null })
            val tools = createWebImageTools(service)
            assertEquals(setOf("search_images", "extract_web_images"), tools.map { it.name }.toSet())
            val output = tools.single { it.name == "extract_web_images" }
                .execute(buildJsonObject { put("url", "$base/page") }).single() as UIMessagePart.Text
            val payload = Json.parseToJsonElement(output.text).jsonObject
            assertEquals("verified", payload["imageStatus"]?.jsonPrimitive?.content)
            val image = payload.getValue("images").jsonArray.single().jsonObject
            assertEquals("$base/ok.png", image["url"]?.jsonPrimitive?.content)
            assertEquals("$base/page", image["sourceUrl"]?.jsonPrimitive?.content)
            assertEquals(20, image["width"]?.jsonPrimitive?.int)
            assertTrue(image.getValue("verifiedAt").jsonPrimitive.content.isNotBlank())
            assertTrue(payload.getValue("imageWarning").jsonPrimitive.content.contains("过滤"))
            val displayed = displayImageTool(service).execute(buildJsonObject {
                put("url", image.getValue("url")); put("sourceUrl", image.getValue("sourceUrl"))
                put("sourceTitle", image.getValue("sourceTitle"))
            })
            assertEquals("$base/ok.png", (displayed.first() as UIMessagePart.Image).url)
            assertTrue((displayed[1] as UIMessagePart.Text).text.contains("$base/page"))
        } finally { server.stop(0) }
    }
}
