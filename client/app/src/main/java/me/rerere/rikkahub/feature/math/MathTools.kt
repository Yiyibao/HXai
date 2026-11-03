package me.rerere.rikkahub.feature.math

import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

fun mathVerifyTool(): Tool = Tool(
    name = "math_verify",
    description = "真正执行限定Q[x]精确核验，不调用模型。operation必填identity/derivative/equal_expression_chain：identity传left/right字符串；derivative传expression/claimed_derivative字符串；chain传expressions的2–50字符串数组。只传该操作所需字段。仅整数、变量x、+/-/*、除非零常数、括号、^或**字面非负整数幂；每式4096字符/AST256/次数和幂32/系数1024bits/嵌套64。没有eval/正则执行/采样证明。返回VERIFIED/DISPROVED/UNSUPPORTED、固定scope、精确系数/反例、input_sha256和已核验前缀；未知前缀不能声称首错。变量分母、超越函数、方程等价、小数、多变量/不清符号不支持，须另行澄清/工具，不把UNSUPPORTED当错误。不是一般数学证明器、OCR、评分或权限凭据，不能把DISPROVED/UNSUPPORTED自动算任务成功。",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        put("operation", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { add("identity"); add("derivative"); add("equal_expression_chain") }) })
        for (key in listOf("left", "right", "expression", "claimed_derivative")) put(key, buildJsonObject { put("type", "string"); put("maxLength", QxLimits.CHARS) })
        put("expressions", buildJsonObject { put("type", "array"); put("minItems", 2); put("maxItems", 50); put("items", buildJsonObject { put("type", "string"); put("maxLength", QxLimits.CHARS) }) })
    }, required = listOf("operation")) },
    execute = { input ->
        val objectInput = input as? JsonObject ?: throw IllegalArgumentException("数学输入必须为JSON对象")
        val result = MathVerification.verify(objectInput)
        listOf(UIMessagePart.Text(result.toString()))
    },
)
