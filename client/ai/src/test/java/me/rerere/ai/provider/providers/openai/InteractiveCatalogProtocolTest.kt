package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.*
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.provider.stream.StreamChunkDecoder
import me.rerere.ai.ui.*
import org.junit.Assert.*
import org.junit.Test

class InteractiveCatalogProtocolTest {
    private val definition = """{"version":1,"title":"预算","components":[{"id":"p","type":"slider","label":"人数","min":1,"max":10,"value":2}]}"""
    private val arguments = buildJsonObject { put("definition", definition) }.toString()
    private fun verify(decoder: StreamChunkDecoder, events: List<JsonObject>) {
        val chunks = events.flatMap { decoder.accept(SseEvent(data = it.toString())).chunks } + decoder.accept(SseEvent(data = "[DONE]")).chunks
        val handler = StreamChunkHandler()
        val messages = chunks.fold(listOf(UIMessage.user("预算"))) { messages, chunk -> handler.handle(messages, chunk) }
        val tool = messages.last().parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("render_interactive_ui", tool.toolName)
        assertEquals(definition, Json.parseToJsonElement(tool.input).jsonObject["definition"]!!.jsonPrimitive.content)
    }
    @Test fun responsesPreservesCatalogDefinition() {
        val item = buildJsonObject { put("type", "function_call"); put("id", "item1"); put("call_id", "call1"); put("name", "render_interactive_ui"); put("arguments", arguments) }
        verify(ResponseApiStreamDecoder(), listOf(buildJsonObject { put("type", "response.output_item.added"); put("item", item) }, buildJsonObject { put("type", "response.output_item.done"); put("item", item) }))
    }
    @Test fun chatCompletionsPreservesCatalogDefinition() {
        val function = buildJsonObject { put("name", "render_interactive_ui"); put("arguments", arguments) }
        val call = buildJsonObject { put("index", 0); put("id", "call1"); put("type", "function"); put("function", function) }
        val delta = buildJsonObject { put("role", "assistant"); put("tool_calls", buildJsonArray { add(call) }) }
        val choice = buildJsonObject { put("index", 0); put("delta", delta); put("finish_reason", "tool_calls") }
        val payload = buildJsonObject { put("choices", buildJsonArray { add(choice) }) }
        verify(ChatCompletionsStreamDecoder(), listOf(payload))
    }
}
