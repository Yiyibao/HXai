package me.rerere.rikkahub.feature.imagecreation

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ImageCreationProtocolTest {
    @Test fun directImageRequestIsSingleLowQualityWithoutReasoning() {
        val body = ImageCreationProtocol.imageBody("gpt-image-2.5-sunburst", "cat", "1024x1024", "low")
        assertEquals(1, body.getValue("n").jsonPrimitive.int)
        assertEquals("low", body.getValue("quality").jsonPrimitive.content)
        assertFalse(body.containsKey("reasoning"))
        assertEquals("abc", ImageCreationProtocol.imageResult("""{"data":[{"b64_json":"abc"}]}"""))
        assertTrue(runCatching { ImageCreationProtocol.imageResult("""{"error":{"message":"upstream unavailable"}}""") }.isFailure)
    }
    @Test fun lowCostRequestDoesNotStoreOrUsePreviousResponse() {
        val body = ImageCreationProtocol.body("gpt-6.1-sol", "一只猫", "1024x1024", "low", emptyList())
        assertEquals("gpt-6.1-sol", body.getValue("model").jsonPrimitive.content)
        assertFalse(body.getValue("store").jsonPrimitive.boolean)
        assertEquals("low", body.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
        assertEquals("low", body.getValue("tools").jsonArray.single().jsonObject.getValue("quality").jsonPrimitive.content)
        assertFalse(body.containsKey("previous_response_id"))
    }
    @Test fun referenceImagesAreExplicitInput() {
        val body = ImageCreationProtocol.body("gpt-6.1-sol", "改背景", "1024x1024", "low", listOf("data:image/png;base64,abc"))
        val content = body.getValue("input").jsonArray.single().jsonObject.getValue("content").jsonArray
        assertEquals("input_image", content[1].jsonObject.getValue("type").jsonPrimitive.content)
    }
    @Test fun acceptsOnlyFinalCompletedImage() {
        assertEquals("abc", ImageCreationProtocol.result("""{"status":"completed","output":[{"type":"image_generation_call","status":"completed","result":"abc"}]}"""))
        listOf("""{"status":"incomplete","output":[]}""", """{"status":"completed","output":[{"type":"message","content":[]}]}""",
            """{"status":"completed","output":[{"type":"image_generation_call","status":"in_progress","result":"abc"}]}""").forEach { body ->
            assertTrue(runCatching { ImageCreationProtocol.result(body) }.isFailure)
        }
    }
    @Test fun rejectsInvalidParametersBeforeNetwork() {
        assertTrue(runCatching { ImageCreationProtocol.body("m", "", "1024x1024", "low", emptyList()) }.isFailure)
        assertTrue(runCatching { ImageCreationProtocol.body("m", "cat", "1x1", "low", emptyList()) }.isFailure)
        assertTrue(runCatching { ImageCreationProtocol.body("m", "cat", "1024x1024", "auto", emptyList()) }.isFailure)
        assertTrue(runCatching { ImageCreationProtocol.body("m", "cat", "1024x1024", "low", List(5) { "image" }) }.isFailure)
    }
}
