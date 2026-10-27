package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.*
import me.rerere.rikkahub.feature.interactive.InteractiveSurface
import org.junit.Assert.*
import org.junit.Test

class DisplayImageToolTest {

    @Test fun imageComponentValidatesWithoutAddingInteractionState() {
        val definition = """{"version":3,"title":"图片","components":[{"id":"figure","type":"image","url":"https://example.org/figure.png","caption":"结构图"}]}"""
        val parsed = InteractiveSurface.validate(definition)
        assertTrue(InteractiveSurface.validateState(parsed, buildJsonObject {}).isEmpty())
        assertTrue(runCatching { InteractiveSurface.validate(definition.replace("https://example.org/figure.png", "javascript:alert(1)")) }.isFailure)
        assertTrue(runCatching { InteractiveSurface.validate(definition.replace("\"caption\":\"结构图\"", "\"caption\":42")) }.isFailure)
    }
}
