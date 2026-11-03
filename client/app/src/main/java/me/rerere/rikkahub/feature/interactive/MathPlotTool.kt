package me.rerere.rikkahub.feature.interactive

import androidx.core.net.toUri
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.feature.material.MaterialFiles
import me.rerere.rikkahub.feature.material.MaterialFile
import java.util.UUID

const val MATH_VISUAL_SKILL = "math-visual-explainer"

fun mathPlotTool(store: MaterialFiles, owner: String) = Tool(
    name = InteractiveSurface.MATH_TOOL,
    description = "为图片或文字数学题绘制准确的函数/圆锥曲线，返回交互界面和可保存的PNG。适用时先用use_skill读取math-visual-explainer。definition为version:4交互界面JSON字符串，presentation:panel，template:exploration（兼容version:3），含一个math_plot及可选text/slider/button。math_plot字段id,type,xMin,xMax,yMin,yMax,equalAspect默认true,curves,points可选。curves最多6条：{kind:function,label,expression,min/max可选限制x}；{kind:parametric,label,x,y,min,max限制t}；{kind:implicit,label,expression表示F(x,y)=0}。公式使用显式*、+ - / ^、括号、sin/cos/tan/sqrt/abs/ln/log/exp，常量pi/e，弧度；变量x或t或x/y及同界面slider id。圆/椭圆推荐参数曲线；双曲线两分支；分段函数分别限制定义域。points:[{x,y,label}]只标注推导核对的固定坐标。带参数函数演示将math_plot放在slider前，固定视窗，equalAspect:false；几何圆锥曲线需要等比例时为true。最小二次函数示例：{version:4,title:二次函数探索,presentation:panel,template:exploration,components:[{id:plot,type:math_plot,xMin:-5,xMax:5,yMin:-80,yMax:80,equalAspect:false,curves:[{kind:function,label:二次函数,expression:a*x^2}]},{id:a,type:slider,label:调整系数a,min:-3,max:3,value:1}]}，实际传参所有键和字符串使用双引号，不能含未登记字段。滑块自动重算曲线；默认参数PNG为固定快照，不会随拖动更新。数值图像不替代精确推导，不支持任意代码或LaTeX作为公式输入。",
    systemPrompt = { _, _ -> "数学题（含图片题）涉及函数、圆锥曲线、交点、切线或参数且配图有助理解时，若 math-visual-explainer 在可用技能清单中，先自动读取，再用 render_math_plot 配图；若技能不可用，按本工具说明直接绘图，不调用缺失的技能。用户无需要求使用技能。先核对题干，模糊且影响结论时才询问。纯计算题不强制绘图。遵循用户当次不画图、只提示等要求。" },
    parameters = { InputSchema.Obj(buildJsonObject { put("definition", buildJsonObject { put("type", "string"); put("maxLength", 65536) }) }, listOf("definition")) },
    execute = { args -> withContext(Dispatchers.Default) {
        try {
            val raw = args.jsonObject.getValue("definition").jsonPrimitive.also { require(it.isString) }.content
            val surface = InteractiveSurface.validate(raw)
            val components = surface.getValue("components").jsonArray.map { it.jsonObject }
            val plot = components.single { it.getValue("type").jsonPrimitive.content == "math_plot" }
            require(components.all { it.getValue("type").jsonPrimitive.content in setOf("math_plot", "text", "card", "slider", "button") })
            val values = components.filter { it.getValue("type").jsonPrimitive.content == "slider" }.associate {
                it.getValue("id").jsonPrimitive.content to it.getValue("value").jsonPrimitive.double
            }
            val title = surface.getValue("title").jsonPrimitive.content
            val key = InteractiveSurface.hash("math-plot-v1:$raw")
            val existing = store.list(owner).firstOrNull { it.output && it.requestKey == key && it.state == "SUCCEEDED" }
            val image = if (existing != null) { store.verifiedFile(owner,existing.id); existing } else store.runTask(
                MaterialFile(UUID.randomUUID().toString(),owner,"${title.take(100)}.png","png",output=true,requestKey=key)
            ) { target -> MathPlotImage.write(target,title,plot,values); "数学图像；默认参数快照，数值绘图不替代推导" }
            listOf(UIMessagePart.Text(buildJsonObject {
                put("kind","interactive_surface"); put("revision",InteractiveSurface.hash(raw)); put("definition",surface)
                put("material_id",image.id); put("snapshot","默认参数 PNG；交互后不改写快照")
            }.toString()), UIMessagePart.Document(store.verifiedFile(owner,image.id).toUri().toString(),image.name,"image/png"))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { listOf(UIMessagePart.Text(buildJsonObject { put("status","FAILED"); put("error", "数学绘图失败：${e.message?.take(180) ?: "公式或结构无效"}；请修正后重试，未交付成功图像。") }.toString())) }
    } },
)
