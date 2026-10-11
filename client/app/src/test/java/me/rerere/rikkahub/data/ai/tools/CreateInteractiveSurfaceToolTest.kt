package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

/**
 * 智能交互界面工具测试
 * 验证各种场景下的界面生成
 */
class CreateInteractiveSurfaceToolTest {

    private val tool = CreateInteractiveSurfaceTool()

    @Test
    fun `test basic surface creation`() {
        val args = """
        {
          "title": "测试界面",
          "version": 3,
          "components": [
            {"id": "text1", "type": "text", "text": "这是一个测试"},
            {"id": "btn1", "type": "button", "label": "继续", "action": "continue"}
          ]
        }
        """.trimIndent()

        val result = runBlocking {
            tool.execute(kotlinx.serialization.json.Json.parseToJsonElement(args))
        }

        assertTrue(result.isNotEmpty())
        assertTrue(result.first() is me.rerere.ai.ui.UIMessagePart.InteractiveSurfaceRef)
    }

    @Test
    fun `test chart with slider scale`() {
        val args = """
        {
          "title": "动态图表",
          "version": 3,
          "components": [
            {"id": "scale", "type": "slider", "label": "缩放", "min": 0.5, "max": 2.0, "value": 1.0},
            {"id": "chart", "type": "line_chart", "points": [[0,0], [1,1], [2,4]],
             "xLabel": "x", "yLabel": "y", "scale": "scale"},
            {"id": "btn", "type": "button", "label": "继续分析", "action": "continue"}
          ]
        }
        """.trimIndent()

        val result = runBlocking {
            tool.execute(kotlinx.serialization.json.Json.parseToJsonElement(args))
        }

        assertTrue(result.first() is me.rerere.ai.ui.UIMessagePart.InteractiveSurfaceRef)
        val surface = result.first() as me.rerere.ai.ui.UIMessagePart.InteractiveSurfaceRef
        assertTrue(surface.definitionJson.contains("scale"))
    }
}
