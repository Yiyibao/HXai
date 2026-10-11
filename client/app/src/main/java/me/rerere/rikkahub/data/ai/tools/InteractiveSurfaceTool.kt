package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.InteractiveSurface
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.InputSchema

/**
 * 智能交互界面工具 - 让AI生成可交互的动态UI
 *
 * 这是实现 ChatGPT/Claude 式智能 Chat 的核心工具。
 * 模型可以创建包含图表、表格、滑块等组件的界面,用户操作后继续对话。
 */
fun CreateInteractiveSurfaceTool(): Tool {
    val json = Json { ignoreUnknownKeys = true }
    val name = "create_interactive_surface"

    val description = """
创建智能交互界面,包含可视化图表、数据表格、交互控件等组件。
用户可以操作界面(调整滑块、选择选项、点击数据点等),然后点击"继续分析"按钮将当前状态发送给你继续对话。

适用场景:
- 数据可视化与探索(折线图、柱状图、散点图)
- 参数调整与实时计算(滑块控制参数,本地计算结果)
- 数据对比与筛选(表格+筛选器)
- 交互式学习(数学函数、物理模拟)
- 预算规划、方案对比等

组件类型:
1. 展示类:
   - text: 纯文本说明
   - card: 卡片样式的重点内容
   - table: 数据表格(支持筛选)
   - image: 图片(url来自用户或工具返回的真实http/https图片地址，caption可选；不得编造地址)

2. 图表类(可选selectable=true让用户点击数据点):
   - line_chart: 折线图
   - bar_chart: 柱状图
   - scatter_chart: 散点图

3. 交互类:
   - slider: 滑块(调整数值参数)
   - select: 下拉选择器
   - input: 文本输入框
   - calculation: 本地计算显示(根据滑块参数实时计算)
   - button: 按钮(提交当前状态继续对话)

设计原则:
- 每个组件需要唯一的id(字母开头,字母数字下划线)
- 图表的scale字段可以引用slider的id,实现动态缩放
- calculation可引用slider的parameter,实现实时计算
- table的filter可引用select的id,实现数据筛选
- 布局可选,不指定则按顺序排列
- 一个界面最多24个组件

交互流程:
1. 你创建界面并返回定义
2. 用户看到渲染的界面,可以操作滑块、选择器等
3. 用户点击"继续分析"按钮
4. 当前状态(所有参数值)会随用户消息发回给你
5. 你根据新状态继续分析或生成新界面
""".trimIndent()

    val parameters = """
{
  "type": "object",
  "required": ["title", "version", "components"],
  "properties": {
    "title": {
      "type": "string",
      "maxLength": 200,
      "description": "界面标题,简明描述界面用途"
    },
    "version": {
      "type": "integer",
      "enum": [1, 2, 3],
      "description": "协议版本,推荐使用3(支持布局和新特性)"
    },
    "components": {
      "type": "array",
      "minItems": 1,
      "maxItems": 24,
      "description": "组件列表",
      "items": {
        "type": "object",
        "required": ["id", "type"],
        "properties": {
          "id": {
            "type": "string",
            "pattern": "^[A-Za-z][A-Za-z0-9_]{0,39}$",
            "description": "组件唯一标识"
          },
          "type": {
            "type": "string",
            "enum": ["text", "card", "image", "table", "line_chart", "bar_chart", "scatter_chart", "slider", "select", "input", "calculation", "button"]
          },
          "text": {"type": "string", "maxLength": 4000},
          "url": {"type": "string", "maxLength": 16000},
          "caption": {"type": "string", "maxLength": 2000},
          "label": {"type": "string", "maxLength": 200},
          "columns": {"type": "array"},
          "rows": {"type": "array"},
          "filter": {"type": "string"},
          "filterColumn": {"type": "integer"},
          "points": {
            "type": "array",
            "description": "数据点数组,每项为[x, y]",
            "items": {
              "type": "array",
              "minItems": 2,
              "maxItems": 2,
              "items": {"type": "number"}
            }
          },
          "xLabel": {"type": "string"},
          "yLabel": {"type": "string"},
          "scale": {"type": "string", "description": "引用slider的id实现缩放"},
          "selectable": {"type": "boolean", "description": "是否允许用户点击选择数据点"},
          "min": {"type": "number"},
          "max": {"type": "number"},
          "value": {"type": ["number", "string"]},
          "options": {"type": "array", "items": {"type": "string"}},
          "parameter": {"type": "string"},
          "a": {"type": "number"},
          "b": {"type": "number"},
          "expression": {"type": "object"},
          "action": {"type": "string", "enum": ["continue"]}
        }
      }
    },
    "layout": {
      "type": "array",
      "description": "布局定义(version 3),每行最多3列",
      "items": {
        "type": "array",
        "maxItems": 3,
        "items": {"type": "string"}
      }
    }
  }
}
""".trimIndent()

    suspend fun execute(arguments: JsonElement): List<UIMessagePart> {
        return try {
            val args = arguments.jsonObject

            // 解析基础字段
            val title = args["title"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("缺少 title 字段")
            val version = args["version"]?.jsonPrimitive?.content?.toIntOrNull() ?: 3
            val componentsJson = args["components"]
                ?: throw IllegalArgumentException("缺少 components 字段")
            val layoutJson = args["layout"]

            val components = componentsJson as? JsonArray
                ?: throw IllegalArgumentException("components 必须为数组")
            require(components.size in 1..24) { "组件数量必须在1-24之间" }
            val definition = JsonObject(buildMap {
                put("version", JsonPrimitive(version))
                put("title", JsonPrimitive(title))
                put("components", components)
                if (layoutJson != null) put("layout", layoutJson)
            })
            val definitionJsonStr = definition.toString()
            me.rerere.rikkahub.feature.interactive.InteractiveSurface.validate(definitionJsonStr)

            // 创建InteractiveSurfaceRef消息部分
            listOf(
                UIMessagePart.InteractiveSurfaceRef(
                    surfaceId = "surface_${kotlin.uuid.Uuid.random()}",
                    title = title,
                    definitionJson = definitionJsonStr
                )
            )
        } catch (e: Exception) {
            listOf(
                UIMessagePart.Text(
                    json.encodeToString(
                        kotlinx.serialization.json.buildJsonObject {
                            put("error", kotlinx.serialization.json.JsonPrimitive(
                                "创建交互界面失败: ${e.message}\n" +
                                "请检查组件定义格式是否正确。\n" +
                                "错误详情: ${e.stackTraceToString()}"
                            ))
                        }
                    )
                )
            )
        }
    }

    fun systemPrompt(model: Model, messages: List<UIMessage>): String {
        return """
【智能交互界面能力】
你可以使用 create_interactive_surface 工具创建可交互的动态界面。

典型场景举例:

1. 数学函数探索:
   - slider控制参数a和b
   - calculation显示 y = a*x + b 的计算
   - line_chart展示函数曲线
   - button让用户提交参数继续讨论

2. 数据分析:
   - table展示数据
   - select作为筛选器
   - bar_chart展示统计结果
   - 用户筛选后点击button继续分析

3. 预算对比:
   - card展示不同方案
   - slider调整预算参数
   - table对比详细数据
   - button提交选择的方案

设计要点:
- 优先使用版本3(支持layout和selectable等新特性)
- 组件id必须字母开头,只含字母数字下划线
- 图表points格式: [[x1,y1], [x2,y2], ...]
- slider/select/input会保存状态,用户操作后可继续对话
- calculation依赖slider实现本地实时计算(不调用你)
- button的action目前只支持"continue"

重要: 这是通用Chat功能,不要仅为数学设计。任何需要数据展示、参数调整、交互探索的场景都可以使用。
用户操作界面后点击按钮,你会收到最新的状态值,据此继续对话。
""".trimIndent()
    }
    val schema = json.parseToJsonElement(parameters).jsonObject
    return Tool(
        name = name,
        description = description,
        parameters = { InputSchema.Obj(
            properties = schema.getValue("properties").jsonObject,
            required = listOf("title", "version", "components"),
        ) },
        systemPrompt = { model, messages -> systemPrompt(model, messages) },
        execute = { arguments -> execute(arguments) },
    )

}
