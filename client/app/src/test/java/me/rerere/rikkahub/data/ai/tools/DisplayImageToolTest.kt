package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.feature.interactive.InteractiveSurface
import org.junit.Assert.*
import org.junit.Test

class DisplayImageToolTest {
    @Test fun returnsImagePartWithCaptionForChatBridge() = runBlocking {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/figure.png") {
            it.responseHeaders.add("Content-Type", "image/png")
            it.sendResponseHeaders(200, 3)
            it.responseBody.use { output -> output.write(byteArrayOf(1, 2, 3)) }
        }
        server.start()
        try {
        val url = "http://127.0.0.1:${server.address.port}/figure.png"
        val parts = displayImageTool(me.rerere.search.WebImageService(decode = { 40 to 30 })).execute(buildJsonObject {
            put("url", url)
            put("caption", "结构图")
            put("sourceUrl", "https://example.org/article")
            put("sourceTitle", "来源文章")
        })
        val output = parts.first() as UIMessagePart.Image
        assertEquals(url, output.url)
        assertEquals("结构图", output.metadata?.get("caption")?.jsonPrimitive?.content)
        assertEquals(40, output.metadata?.get("width")?.jsonPrimitive?.int)
        assertEquals("https://example.org/article", output.metadata?.get("sourceUrl")?.jsonPrimitive?.content)
        assertTrue((parts[1] as UIMessagePart.Text).text.contains("[来源文章](<https://example.org/article>)"))
        assertTrue(displayImageTool(me.rerere.search.WebImageService(decode = { null }))
            .execute(buildJsonObject { put("url", url) }).single() is UIMessagePart.Text)
        } finally { server.stop(0) }
    }

    @Test fun rejectsInvalidImageAddresses() = runBlocking {
        listOf("javascript:alert(1)", "file:///private/photo.png", "https:///photo.png").forEach { url ->
            assertTrue(displayImageTool().execute(buildJsonObject { put("url", url) }).single() is UIMessagePart.Text)
        }
    }

    @Test fun imageComponentValidatesWithoutAddingInteractionState() {
        val definition = """{"version":3,"title":"图片","components":[{"id":"figure","type":"image","url":"https://example.org/figure.png","caption":"结构图"}]}"""
        val parsed = InteractiveSurface.validate(definition)
        assertTrue(InteractiveSurface.validateState(parsed, buildJsonObject {}).isEmpty())
        assertTrue(runCatching { InteractiveSurface.validate(definition.replace("https://example.org/figure.png", "javascript:alert(1)")) }.isFailure)
        assertTrue(runCatching { InteractiveSurface.validate(definition.replace("\"caption\":\"结构图\"", "\"caption\":42")) }.isFailure)
    }
}
