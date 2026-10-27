package me.rerere.rikkahub.feature.interactive

import androidx.core.net.toUri
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.feature.material.MaterialFiles
import me.rerere.rikkahub.feature.material.MaterialFile
import me.rerere.rikkahub.feature.material.MaterialWriter
import java.util.UUID

const val MATH_VISUAL_SKILL = "math-visual-explainer"

fun mathPlotTool(store: MaterialFiles, owner: String) = Tool(
    name = InteractiveSurface.MATH_TOOL,
    description = "绘制真实交互2D/3D数学图形，按需导出PNG、SVG、矢量PDF或原生可编辑采样曲线PPTX，不执行代码。definition为version:4交互界面JSON字符串，含恰好一个math_plot或math_plot_3d，可搭配text/card/slider/button。2D math_plot字段id/type/xMin/xMax/yMin/yMax/equalAspect(默认true)/curves，points可选[{x,y,label,open:可选true表示空心排除点}]。curves最多6条：{kind:function,label,expression,min/max可选,exclude:可选最多32个被排除的x值}、{kind:parametric,label,x,y,min,max,exclude:可选排除的t值}或{kind:implicit,label,expression:F(x,y)=0}。原式孔洞/定义域须先推导，明确传exclude，不得因代数约简抹掉孔洞；数值采样不保证发现未声明孔洞。3D math_plot_3d字段id/type/xMin/xMax/yMin/yMax/zMin/zMax/azimuth(-360至360度)/elevation(-85至85度)/curves；最多4条{kind:surface,label,expression:z=f(x,y)}或{kind:space_curve,label,x,y,z,min,max:t域}。表达式显式*、四则^、sin/cos/tan/sqrt/abs/ln/log/exp、pi/e，弧度，及slider id；不传LaTeX或脚本。分段分别限制域；3D可旋转/缩放和调整参数。formats可选数组png/svg/pdf/pptx，默认png。导出固定定义默认参数/视角，交互不改写已交付版本；PPTX为原生可编辑采样矢量形状，不是解析公式或Office可旋转3D对象。图形/采样不是数学证明。",
    systemPrompt = { _, _ -> "数学题（含图片题）涉及函数、圆锥曲线、交点、切线或参数且配图有助理解时，若 math-visual-explainer 在可用技能清单中，先自动读取，再用 render_math_plot 配图；若技能不可用，按本工具说明直接绘图，不调用缺失的技能。用户无需要求使用技能。先核对题干，模糊且影响结论时才询问。纯计算题不强制绘图。遵循用户当次不画图、只提示等要求。" },
    parameters = { InputSchema.Obj(buildJsonObject {
        put("definition", buildJsonObject { put("type", "string"); put("maxLength", 65536) })
        put("formats", buildJsonObject { put("type", "array"); put("minItems", 1); put("maxItems", 4); put("items", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { for (format in listOf("png", "svg", "pdf", "pptx")) add(format) }) }) })
    }, listOf("definition")) },
    execute = { args -> withContext(Dispatchers.Default) {
        try {
            val raw = args.jsonObject.getValue("definition").jsonPrimitive.also { require(it.isString) }.content
            val surface = InteractiveSurface.validate(raw)
            val components = surface.getValue("components").jsonArray.map { it.jsonObject }
            val plot = components.single { it.getValue("type").jsonPrimitive.content in setOf("math_plot", "math_plot_3d") }
            require(components.all { it.getValue("type").jsonPrimitive.content in setOf("math_plot", "math_plot_3d", "text", "card", "slider", "button") })
            val values = components.filter { it.getValue("type").jsonPrimitive.content == "slider" }.associate {
                it.getValue("id").jsonPrimitive.content to it.getValue("value").jsonPrimitive.double
            }
            val title = surface.getValue("title").jsonPrimitive.content
            require(args.jsonObject.keys.all { it in setOf("definition", "formats") })
            val formats = args.jsonObject["formats"]?.jsonArray?.map { it.jsonPrimitive.also { p -> require(p.isString) }.content } ?: listOf("png")
            require(formats.size in 1..4 && formats.distinct().size == formats.size && formats.all { it in setOf("png", "svg", "pdf", "pptx") })
            val exporter by lazy(LazyThreadSafetyMode.NONE) { MathPlotExport(title, plot, values) }
            val artifacts = formats.map { format ->
                currentCoroutineContext().ensureActive()
                val key = InteractiveSurface.hash(if (format == "png") "math-plot-v1:$raw" else "math-plot-export-v1:$format:$raw")
                val existing = store.list(owner).firstOrNull { it.output && it.requestKey == key && it.state == "SUCCEEDED" }
                if (existing != null) { store.verifiedFile(owner, existing.id); existing } else store.runTask(
                    MaterialFile(UUID.randomUUID().toString(), owner, "${title.take(100)}.$format", format, output = true, requestKey = key)
                ) { target ->
                    if (format == "png" && plot["type"] == JsonPrimitive("math_plot")) MathPlotImage.write(target, title, plot, values)
                    else exporter.write(target, format)
                    "数学图形默认参数快照；${if (format == "pptx") "原生可编辑采样矢量形状，不是解析公式" else "数值绘图不替代推导"}"
                }
            }
            listOf(UIMessagePart.Text(buildJsonObject {
                put("kind", "interactive_surface"); put("revision", InteractiveSurface.hash(raw)); put("definition", surface)
                put("material_id", artifacts.first().id); put("snapshot", "默认参数固定版本；交互后不改写已导出文件")
                put("exports", JsonArray(artifacts.map { item -> buildJsonObject { put("id", item.id); put("format", item.extension); put("sha256", item.sha256) } }))
            }.toString())) + artifacts.map { item -> UIMessagePart.Document(store.verifiedFile(owner, item.id).toUri().toString(), item.name, MaterialWriter.mime(item.extension)) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { listOf(UIMessagePart.Text(buildJsonObject { put("status","FAILED"); put("error", "数学绘图失败：${e.message?.take(180) ?: "公式或结构无效"}；请修正后重试，未交付成功图像。") }.toString())) }
    } },
)

/** Bind every delivered format to the actual definition, ownership and read-back geometry. */
internal fun verifyMathPlotDelivery(store: MaterialFiles, owner: String, args: JsonObject, output: List<UIMessagePart>): Boolean {
    require(args.keys.all { it in setOf("definition", "formats") })
    val raw = args.getValue("definition").jsonPrimitive.also { require(it.isString) }.content
    val surface = InteractiveSurface.validate(raw)
    val formats = args["formats"]?.jsonArray?.map { it.jsonPrimitive.also { p -> require(p.isString) }.content } ?: listOf("png")
    require(formats.size in 1..4 && formats.distinct().size == formats.size && formats.all { it in setOf("png", "svg", "pdf", "pptx") })
    val text = output.filterIsInstance<UIMessagePart.Text>().singleOrNull() ?: return false
    val result = Json.parseToJsonElement(text.text).jsonObject
    if (result["kind"] != JsonPrimitive("interactive_surface") || result["revision"] != JsonPrimitive(InteractiveSurface.hash(raw)) || result["definition"] != surface) return false
    val documents = output.filterIsInstance<UIMessagePart.Document>()
    if (documents.size != formats.size || output.size != documents.size + 1) return false
    val components = surface.getValue("components").jsonArray.map { it.jsonObject }
    val plot = components.single { it["type"] in setOf(JsonPrimitive("math_plot"), JsonPrimitive("math_plot_3d")) }
    val values = components.filter { it["type"] == JsonPrimitive("slider") }.associate { it.getValue("id").jsonPrimitive.content to it.getValue("value").jsonPrimitive.double }
    val exporter by lazy(LazyThreadSafetyMode.NONE) { MathPlotExport(surface.getValue("title").jsonPrimitive.content, plot, values) }
    val owned = store.list(owner)
    val artifacts = documents.mapIndexed { index, document ->
        val item = owned.singleOrNull { store.file(it).toUri().toString() == document.url } ?: return false
        val format = formats[index]
        if (!item.output || item.state != "SUCCEEDED" || item.extension != format || document.mime != MaterialWriter.mime(format) || document.fileName != item.name) return false
        val key = InteractiveSurface.hash(if (format == "png") "math-plot-v1:$raw" else "math-plot-export-v1:$format:$raw")
        if (item.requestKey != key) return false
        val file = store.verifiedFile(owner, item.id)
        if (format == "png" && plot["type"] == JsonPrimitive("math_plot")) {
            val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return false
            try { if (bitmap.width != 1000 || bitmap.height != 1140) return false } finally { bitmap.recycle() }
        } else exporter.verify(file, format)
        item
    }
    if (result["material_id"] != JsonPrimitive(artifacts.first().id)) return false
    result["exports"]?.let { exports ->
        if (exports != JsonArray(artifacts.map { item -> buildJsonObject { put("id", item.id); put("format", item.extension); put("sha256", item.sha256) } })) return false
    }
    return true
}
