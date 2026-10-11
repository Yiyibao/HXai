package me.rerere.rikkahub.feature.interactive
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid
class SurfaceRevisionToolTest {
    @Test fun preservesSourceAndRejectsStaleRevision() = runBlocking {
        val raw = """{"version":4,"title":"old","components":[{"id":"text","type":"rich_text","text":"old"}]}"""
        val original = UIMessagePart.Tool(toolCallId = "source", toolName = InteractiveSurface.TOOL, input = buildJsonObject { put("definition", raw) }.toString(), output = listOf(UIMessagePart.Text("""{"kind":"interactive_surface"}""")), executionState = ToolExecutionState.COMPLETED)
        val message = UIMessage.assistant("").copy(parts = listOf(original))
        val conv = Conversation(assistantId = Uuid.random(), messageNodes = listOf(MessageNode(messages = listOf(message))))
        val tool = surfaceRevisionTool { conv }
        fun args(revision: String) = buildJsonObject { put("message_id", message.id.toString()); put("tool_call_id", "source"); put("expected_revision", revision); put("definition", raw.replace("old", "new")) }
        val output = tool.execute(args(InteractiveSurface.hash(raw))).single() as UIMessagePart.Text
        val result = Json.parseToJsonElement(output.text).jsonObject
        assertEquals("new", result.getValue("definition").jsonObject.getValue("title").jsonPrimitive.content)
        assertEquals("source", result.getValue("parent").jsonObject.getValue("toolCallId").jsonPrimitive.content)
        assertEquals(raw, InteractiveSurface.definition(original))
        val stale = tool.execute(args("stale")).single() as UIMessagePart.Text
        assertEquals("FAILED", Json.parseToJsonElement(stale.text).jsonObject.getValue("status").jsonPrimitive.content)
    }
}
