package me.rerere.rikkahub.feature.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
/** Algebraic identity plus an explicit refusal to treat it as a geometric theorem. */
internal object IdentityCaveatGoal {
    const val KIND = "identity_caveat_delivery"
    const val STEP = "_identity_caveat"

    data class Spec(val variables: List<String>, val left: String, val right: String) {
        fun request(): JsonObject = buildJsonObject {
            put("operation", "professional_multivariate")
            put("request", """{"action":"identity","variables":["x","y"],"left":"$left","right":"$right"}""")
        }
    }

    fun parse(goal: String): Spec? {
        if (!goal.contains("验证") || !goal.contains("圆锥")) return null
        if (listOf("同上", "单调", "讨论", "分类", "归纳").any { it in goal }) return null
        val compact = goal.replace(" ", "").replace("\\", "")
        val match = Regex("""验证([^，。；=]+)=([^，。；]+)""").find(compact) ?: return null
        val left = normalize(match.groupValues[1]) ?: return null
        val right = normalize(match.groupValues[2]) ?: return null
        val names = Regex("[A-Za-z]").findAll(left + right).map { it.value }.distinct().toList()
        if (names.toSet() != setOf("x", "y")) return null
        return Spec(listOf("x", "y"), left, right)
    }

    fun quoted(reply: String, spec: Spec): Boolean =
        VisibleMathClaim.clauses(reply).any { clause ->
            !VisibleMathClaim.negated(clause) && !VisibleMathClaim.problemRestatement(clause) &&
                ("等于" in clause || "=" in clause) &&
                VisibleMathClaim.containsExpression(clause, spec.left) &&
                VisibleMathClaim.containsExpression(clause, spec.right)
        }

    fun matches(input: String, spec: Spec): Boolean = runCatching {
        val root = Json.parseToJsonElement(input).jsonObject
        val inner = root["request"]?.jsonPrimitive?.takeIf { it.isString }?.content?.let {
            Json.parseToJsonElement(it).jsonObject
        } ?: root
        val variables = inner["variables"]?.jsonArray?.map { it.jsonPrimitive.content }
        inner["action"]?.jsonPrimitive?.content == "identity" && variables == spec.variables &&
            inner["left"]?.jsonPrimitive?.content == spec.left && inner["right"]?.jsonPrimitive?.content == spec.right
    }.getOrDefault(false)

    fun deniesGeometry(reply: String): Boolean {
        val about = VisibleMathClaim.clauses(reply).filter { "几何" in it || "圆锥" in it }
        if (about.isEmpty() || about.any(::affirmsGeometry)) return false
        return about.any { clause -> listOf("不能", "并非", "不是").any { it in clause } }
    }

    private fun affirmsGeometry(clause: String): Boolean {
        val text = clause.replace(" ", "")
        for (marker in listOf("已经证明", "已经成立", "结论成立")) {
            val at = text.indexOf(marker)
            if (at < 0) continue
            val prefix = text.substring(0, at)
            if (prefix.contains("不能") || prefix.contains("并非") || prefix.contains("不是")) return false
            return true
        }
        return false
    }

    private fun normalize(raw: String): String? {
        if (raw.isBlank() || raw.length > 200) return null
        val text = Regex("""(\d)([A-Za-z])""").replace(raw) { "${it.groupValues[1]}*${it.groupValues[2]}" }
        if (!text.all { it.isLetterOrDigit() || it in "+-*/^()." }) return null
        return text
    }
}
