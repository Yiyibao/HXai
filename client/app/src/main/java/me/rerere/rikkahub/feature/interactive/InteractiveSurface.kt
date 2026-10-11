package me.rerere.rikkahub.feature.interactive

import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.security.MessageDigest

/** A small versioned catalog; definitions are data and never executable code. */
object InteractiveSurface {
    const val TOOL = "render_interactive_ui"
    const val CREATE_TOOL = "create_interactive_surface"
    const val MATH_TOOL = "render_math_plot"
    fun isSurfaceTool(name: String) = name == TOOL || name == CREATE_TOOL || name == MATH_TOOL || name == "revise_interactive_surface"

    /** Both tool schemas share the same persisted state and client protocol. */
    fun definition(tool: UIMessagePart.Tool): String {
        require(isSurfaceTool(tool.toolName))
        val input = Json.parseToJsonElement(tool.input).jsonObject
        val raw = if (tool.toolName == TOOL || tool.toolName == MATH_TOOL || tool.toolName == "revise_interactive_surface") input.getValue("definition").jsonPrimitive.content
        else {
            // Keep older reference outputs readable without losing their exact definition.
            tool.output.filterIsInstance<UIMessagePart.InteractiveSurfaceRef>().singleOrNull()?.definitionJson
                ?: JsonObject(input.filterKeys { it in setOf("version", "title", "components", "layout") }).toString()
        }
        validate(raw)
        return raw
    }

    fun clientOutput(tool: UIMessagePart.Tool): String {
        require(tool.executionState == me.rerere.ai.ui.ToolExecutionState.COMPLETED)
        val raw = definition(tool)
        require(tool.output.any { part ->
            part is UIMessagePart.InteractiveSurfaceRef || (part is UIMessagePart.Text &&
                runCatching { Json.parseToJsonElement(part.text).jsonObject["kind"]?.jsonPrimitive?.content == "interactive_surface" }.getOrDefault(false))
        }) { "界面没有成功生成" }
        return buildJsonObject { put("kind", "interactive_surface"); put("revision", hash(raw)); put("definition", validate(raw)); tool.output.filterIsInstance<UIMessagePart.Text>().firstNotNullOfOrNull { runCatching { Json.parseToJsonElement(it.text).jsonObject["parent"] }.getOrNull() }?.let { put("parent", it) } }.toString()
    }
    fun hash(definition: String) = MessageDigest.getInstance("SHA-256").digest(definition.toByteArray())
        .joinToString("") { "%02x".format(it) }

    const val STATE_KEY = "interactive_state"
    fun mergeClientState(messages: List<me.rerere.ai.ui.UIMessage>, previous: List<me.rerere.ai.ui.UIMessage>): List<me.rerere.ai.ui.UIMessage> = messages.map { message ->
        val old = previous.singleOrNull { it.id == message.id }
        message.copy(parts = message.parts.map { part ->
            if (part !is UIMessagePart.Tool || !isSurfaceTool(part.toolName)) part else {
                val saved = old?.parts?.filterIsInstance<UIMessagePart.Tool>()?.singleOrNull { it.toolCallId == part.toolCallId && it.toolName == part.toolName && it.input == part.input }?.metadata?.get(STATE_KEY)
                if (saved == null) part else part.copy(metadata = JsonObject(part.metadata.orEmpty() + (STATE_KEY to saved)))
            }
        })
    }

    fun validate(raw: String): JsonObject {
        require(raw.length <= 65536) { "交互界面过大" }
        val surface = Json.parseToJsonElement(raw).jsonObject
        val versionNumber = surface["version"]?.jsonPrimitive?.intOrNull ?: error("Invalid version")
        require(surface.keys.all { it in setOf("version", "title", "components", "layout") + if (versionNumber >= 4) setOf("presentation", "density", "template", "composition") else emptySet() })
        require(surface["version"]?.jsonPrimitive?.let { !it.isString && it.intOrNull in setOf(1, 2, 3, 4) } == true)
        val version = surface.getValue("version").jsonPrimitive.int
        surface["presentation"]?.let { require(it.jsonPrimitive.isString && it.jsonPrimitive.content in setOf("inline", "panel")) }
        surface["density"]?.let { require(it.jsonPrimitive.isString && it.jsonPrimitive.content in setOf("compact", "comfortable")) }
        surface["template"]?.let { require(it.jsonPrimitive.isString && it.jsonPrimitive.content in setOf("analysis", "comparison", "explanation", "exploration")) }
        require(!surface.containsKey("composition") || !surface.containsKey("layout"))
        require(version >= 3 || !surface.containsKey("layout"))
        require(surface["title"]?.jsonPrimitive?.let { it.isString && it.content.length in 1..200 } == true)
        val components = surface.getValue("components").jsonArray
        require(components.size in 1..24)
        val ids = mutableSetOf<String>()
        val parameters = mutableSetOf<String>()
        val selectors = mutableSetOf<String>()
        components.forEach { element ->
            val c = element.jsonObject
            val id = c.getValue("id").jsonPrimitive.content
            require(c.getValue("id").jsonPrimitive.isString && id.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,39}")) && ids.add(id))
            val type = c.getValue("type").jsonPrimitive.content
            val allowed = when (type) {
                "text", "card" -> setOf("text")
                "image" -> setOf("url", "caption") + if (version >= 4) SurfaceData.imageFields else emptySet()
                "math_plot" -> MathPlot.fields
                "table" -> setOf("columns", "rows") + (if (version >= 2) setOf("filter", "filterColumn") else emptySet()) + (if (version >= 4) SurfaceData.tableFields else emptySet())
                "line_chart", "bar_chart", "scatter_chart" -> setOf("points", "scale", "xLabel", "yLabel") + (if (version >= 3) setOf("selectable") else emptySet()) + (if (version >= 4) SurfaceData.chartFields else emptySet())
                "slider" -> setOf("min", "max", "value", "label")
                "select" -> setOf("options", "value", "label")
                "input" -> setOf("value", "label")
                "calculation" -> setOf("parameter", "a", "b", "label") + if (version >= 3) setOf("expression") else emptySet()
                "button" -> setOf("label", "action")
                else -> if (version >= 4) SurfaceSemantics.fields[type] ?: error("不支持的交互组件 $type") else error("不支持的交互组件 $type")
            } + setOf("id", "type") + if (version >= 4) SurfaceLayout.styleFields else emptySet()
            require(c.keys.all { it in allowed }) { "组件包含未登记字段" }
            if (version >= 4) SurfaceLayout.validateStyle(c)
            if (type in SurfaceSemantics.fields) SurfaceSemantics.validate(c)
            fun text(key: String, max: Int = 4000) = c.getValue(key).jsonPrimitive.also {
                require(it.isString && it.content.length <= max)
            }.content
            fun number(key: String) = c.getValue(key).jsonPrimitive.also { require(!it.isString) }.double.also { require(it.isFinite() && kotlin.math.abs(it) <= 1e12) }
            when (type) {
                "text", "card" -> text("text")
                "image" -> {
                    val uri = java.net.URI(text("url", 16000))
                    require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrEmpty())
                    c["caption"]?.let { text("caption", 2000) }
                    if (version >= 4) SurfaceData.validateImage(c)
                }
                "table" -> {
                    val columns = c.getValue("columns").jsonArray
                    val rows = c.getValue("rows").jsonArray
                    require(columns.size in 1..12 && rows.size <= 200)
                    columns.forEach { require(it.jsonPrimitive.isString && it.jsonPrimitive.content.length <= 200) }
                    rows.forEach { row -> require(row.jsonArray.size == columns.size); row.jsonArray.forEach { cell -> require(cell is JsonPrimitive && cell.content.length <= 2000) } }
                    if (version >= 4) SurfaceData.validateTable(c)
                }
                "line_chart", "bar_chart", "scatter_chart" -> {
                    c["selectable"]?.let { require(!it.jsonPrimitive.isString && it.jsonPrimitive.booleanOrNull != null) }
                    if (version >= 4) SurfaceData.validateChart(c)
                    val points = SurfaceData.points(c)
                    require(points.size in 1..500)
                    points.forEach { point -> require(point.jsonArray.size == 2); point.jsonArray.forEach { require(!it.jsonPrimitive.isString && it.jsonPrimitive.double.isFinite() && kotlin.math.abs(it.jsonPrimitive.double) <= 1e12) } }
                    listOf("xLabel", "yLabel").forEach { key -> c[key]?.let { require(it.jsonPrimitive.isString && it.jsonPrimitive.content.length <= 200) } }
                }
                "slider" -> {
                    val min = number("min"); val max = number("max"); val value = number("value")
                    require(min < max && value in min..max); text("label", 200); parameters += id
                }
                "select" -> {
                    val options = c.getValue("options").jsonArray
                    require(options.size in 1..30 && options.distinct().size == options.size)
                    options.forEach { require(it.jsonPrimitive.isString && it.jsonPrimitive.content.length in 1..200) }
                    require(JsonPrimitive(text("value", 200)) in options); text("label", 200); selectors += id
                }
                "input" -> { text("value"); text("label", 200) }
                "calculation" -> { if (!c.containsKey("expression")) { number("a"); number("b"); require(c.containsKey("parameter")) } else require(c.keys.none { it in setOf("a", "b", "parameter") }); text("label", 200) }
                "button" -> { require(text("action") == "continue"); text("label", 200) }
            }
        }
        components.forEach {
            val c = it.jsonObject
            if (c.getValue("type").jsonPrimitive.content == "math_plot") {
                require(version >= 3) { "数学图像需要版本 3" }
                MathPlot.validate(c, parameters)
            }
            if (c.containsKey("filter") || c.containsKey("filterColumn")) {
                require(c.getValue("filter").jsonPrimitive.isString && c.getValue("filter").jsonPrimitive.content in selectors)
                val column = c.getValue("filterColumn").jsonPrimitive
                require(!column.isString && column.int in 0 until c.getValue("columns").jsonArray.size)
            }
            (c["scale"] ?: c["parameter"])?.let { reference -> require(reference.jsonPrimitive.content in parameters) { "计算必须引用本界面滑块" } }
            c["expression"]?.let { expression ->
                var nodes = 0
                fun validateExpression(e: JsonElement, depth: Int) {
                    require(++nodes <= 64 && depth <= 8)
                    if (e is JsonPrimitive) { require(!e.isString && e.double.isFinite() && kotlin.math.abs(e.double) <= 1e12); return }
                    val obj = e.jsonObject
                    if (obj.keys == setOf("parameter")) { require(obj.getValue("parameter").jsonPrimitive.content in parameters); return }
                    require(obj.keys == setOf("op", "args") && obj.getValue("op").jsonPrimitive.content in setOf("add", "subtract", "multiply", "divide", "power", "min", "max"))
                    val args = obj.getValue("args").jsonArray; require(args.size == 2)
                    args.forEach { validateExpression(it, depth + 1) }
                }
                validateExpression(expression, 0)
            }
        }
        surface["layout"]?.let { layout ->
            val rows = layout.jsonArray; require(rows.size in 1..24)
            val arranged = rows.flatMap { row -> require(row.jsonArray.size in 1..3); row.jsonArray.map { it.jsonPrimitive.also { id -> require(id.isString) }.content } }
            require(arranged.size == ids.size && arranged.toSet() == ids)
        }
        surface["composition"]?.let { SurfaceLayout.validateComposition(it, ids) }
        return surface
    }

    fun validateState(surface: JsonObject, state: JsonObject): JsonObject {
        val controls = surface.getValue("components").jsonArray.map { it.jsonObject }
            .filter { it["type"]?.jsonPrimitive?.content in setOf("slider", "select", "input") || it["selectable"]?.jsonPrimitive?.booleanOrNull == true }
        require(state.keys == controls.map { it.getValue("id").jsonPrimitive.content }.toSet())
        controls.forEach { c ->
            val value = state.getValue(c.getValue("id").jsonPrimitive.content).jsonPrimitive
            when (c.getValue("type").jsonPrimitive.content) {
                "slider" -> require(!value.isString && value.double.isFinite() && value.double in c.getValue("min").jsonPrimitive.double..c.getValue("max").jsonPrimitive.double)
                "select" -> require(value in c.getValue("options").jsonArray)
                "input" -> require(value.isString && value.content.length <= 4000)
                "line_chart", "bar_chart", "scatter_chart" -> require(!value.isString && value.int in -1 until SurfaceData.pointCount(c))
            }
        }
        return state
    }
}

fun interactiveUiTool() = Tool(
    name = InteractiveSurface.TOOL,
    description = "按需生成聊天内交互界面，优先version:4。definition为JSON字符串{version:4,title,components:[{id,type,...}],layout可选:[[id,...],...]}。每行1–3组件，所有id恰好出现一次。旧版1/2/3继续兼容。组件：text/card(text)，image(url,caption可选)，table(columns字符串数组,rows二维数组,filter可选select id,filterColumn列下标)，line_chart/bar_chart/scatter_chart(points:[[x,y],...],scale可选slider id,xLabel/yLabel可选,selectable可选bool)，slider(label,min,max,value)，select(label,options,value)，input(label,value)，calculation(label,parameter滑块id,a,b)显示a*参数+b，或expression受限树：数字/{parameter:id}/{op:add/subtract/multiply/divide/power/min/max,args:[左,右]}，最多64节点8层，不混用a/b/parameter；button(label,action:'continue')。v4语义组件见系统说明。最多24组件500图表点。数据真实，图片URL来自用户或工具。代码仅作为code_block展示，不执行。控件应有实际用途；select只筛选绑定的table。先展示界面，再给简短结论，避免重复数据。并排slider显示数值输入，单列slider显示滑块。状态本地保存，继续按钮回传参数。",
    systemPrompt = { _, _ -> "交互界面支持version:4，兼容旧版组件。样式选择规则：两方案优先table或comparison的对照表；三种及以上用comparison独立卡片，text中用每行“字段：内容”对齐字段。信息卡片用card，卡片标题使用Markdown标题并用分隔线组织正文；需要结构化指标时用panel与metric，不把指标塞入纯正文。学习概览用template:analysis、presentation:panel，先给关键结论，再给首个主要metric和其余辅助metric、趋势图；可用composition的tabs组织概览与明细，概览子布局先结论、主指标、辅助指标网格、趋势图。新增rich_text(text:Markdown/LaTeX)、heading(text,level:1/2/3)、metric(label,value:数值或字符串,unit可选,delta可选)、callout(title可选,text,tone:neutral/info/success/warning/error)、steps/comparison(items:[{title,text}])、code_block(code,language可选,collapsed可选)。普通text在v4支持Markdown。优先用明确语义组件，不用两行card文本模拟指标。为渐进预览，definition内依次输出version、title、components，布局与样式放在其后。完整输出每个组件再输出下一个，先内容后交互控件，不要依赖预览阶段的保存或继续操作。\n" + SurfaceLayout.PROMPT + "\n" + SurfaceData.PROMPT },
    parameters = { InputSchema.Obj(buildJsonObject { put("definition", buildJsonObject { put("type", "string") }) }, listOf("definition")) },
    execute = { args ->
        try {
        val definition = args.jsonObject.getValue("definition").jsonPrimitive.content
        val surface = InteractiveSurface.validate(definition)
        listOf(UIMessagePart.Text(buildJsonObject { put("kind", "interactive_surface"); put("revision", InteractiveSurface.hash(definition)); put("definition", surface) }.toString()))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { listOf(UIMessagePart.Text(buildJsonObject { put("status", "FAILED"); put("error", "交互结构无效，请修正版本、组件、字段、数值范围或引用后再试；没有生成可执行界面。") }.toString())) }
    },
)
