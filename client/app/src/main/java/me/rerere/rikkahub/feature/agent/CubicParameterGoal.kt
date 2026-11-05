package me.rerere.rikkahub.feature.agent

import kotlinx.serialization.json.*

/** Host extraction for one cubic parameter monotonicity discussion. Model prose is not the certificate. */
internal object CubicParameterGoal {
    const val KIND = "cubic_parameter_monotonicity"
    const val STEP = "_cubic_monotonicity"

    data class Spec(val expression: String, val variable: String, val parameter: String) {
        fun request(): JsonObject = buildJsonObject {
            put("operation", "professional_multivariate")
            put("request", """{"action":"cubic_monotonicity","variables":["$variable","$parameter"],"expression":"$expression","variable":"$variable","parameter":"$parameter"}""")
        }
    }

    fun parse(goal: String): Spec? {
        if (!goal.contains("完整讨论") || !goal.contains("单调") || !goal.contains("退化")) return null
        if (listOf("同上", "圆锥", "是否", "归纳", "数列").any { it in goal }) return null
        val compact = goal.replace(" ", "").replace("\\", "")
        val raw = Regex("""f_\{?a\}?\(x\)=([0-9A-Za-z+*/^().-]+)""").find(compact)?.groupValues?.get(1)
            ?: Regex("""f\(x\)=([0-9A-Za-z+*/^().-]+)""").find(compact)?.groupValues?.get(1) ?: return null
        val expression = normalize(raw.replace("{", "").replace("}", "")) ?: return null
        if (!expression.contains("a") || !expression.contains("x")) return null
        return Spec(expression, "x", "a")
    }

    fun quoted(reply: String, certificate: JsonObject): Boolean {
        val text = chart(reply)
        val cases = certificate["cases"]?.jsonArray ?: return false
        val parameter = certificate["parameter"]?.jsonPrimitive?.content ?: return false
        for (item in cases) {
            val cell = item.jsonObject
            val marker = "a${cell.getValue("parameter").jsonPrimitive.content}"
            val slice = if (marker == "aall") text else slice(text, marker) ?: return false
            when (cell.getValue("kind").jsonPrimitive.content) {
                "strict", "strict_isolated_stationary" ->
                    if (!hasClaim(slice, word(cell.getValue("direction").jsonPrimitive.content))) return false
                "three_branches" -> {
                    val radius = cell["radius_squared"]?.jsonPrimitive?.content ?: return false
                    if (!radiusMatches(slice, radius, parameter)) return false
                    if (!near(slice, word(cell.getValue("outer").jsonPrimitive.content)) { window ->
                        listOf("|x|>", "x^2>", "外侧", "∞").any { it in window }
                    }) return false
                    if (!near(slice, word(cell.getValue("middle").jsonPrimitive.content)) { window ->
                        listOf("|x|<", "x^2<", "中间").any { it in window } ||
                            ("sqrt($parameter)" in window && "∞" !in window && "|x|>" !in window)
                    }) return false
                }
                else -> return false
            }
        }
        return true
    }

    private fun radiusMatches(slice: String, radiusSquared: String, parameter: String): Boolean {
        val coefficient = radiusSquared.removeSuffix("*parameter")
        if (coefficient.isEmpty() || coefficient.any { it != '/' && !it.isDigit() }) return false
        val compact = slice.replace("*", "")
        if (coefficient == "1") {
            if (Regex("""[0-9]sqrt\($parameter\)""").containsMatchIn(compact)) return false
            if (Regex("""x\^2=[2-9][0-9]*$parameter""").containsMatchIn(compact)) return false
            return affirmedRadius(compact, "x^2=$parameter") || affirmedRadius(compact, "sqrt($parameter)")
        }
        return affirmedRadius(compact, "x^2=$coefficient$parameter") || affirmedRadius(slice, "x^2=$coefficient*$parameter")
    }

    private fun affirmedRadius(text: String, needle: String): Boolean {
        var index = 0
        while (true) {
            val at = text.indexOf(needle, index)
            if (at < 0) return false
            val prefix = text.substring(maxOf(0, at - 6), at)
            val denied = prefix.endsWith("不是") || prefix.endsWith("并非") || prefix.endsWith("不能") || prefix.endsWith("不")
            if (!denied && (at == 0 || !text[at - 1].isDigit())) return true
            index = at + needle.length
        }
    }

    private fun chart(reply: String): String = VisibleMathClaim.compact(reply).replace("\\", "")
        .replace("sqrt{a}", "sqrt(a)").replace("sqrta", "sqrt(a)")
        .replace("infty", "∞")

    private fun near(slice: String, word: String, accept: (String) -> Boolean): Boolean =
        slice.split('。', '；', ';').any { sentence ->
            var index = 0
            while (true) {
                val at = sentence.indexOf(word, index)
                if (at < 0) return@any false
                val prefix = sentence.substring(maxOf(0, at - 4), at)
                if (!prefix.endsWith("不是") && !prefix.endsWith("并非") && !prefix.endsWith("不能") && !prefix.endsWith("不") &&
                    accept(span(sentence, at, word))) return true
                index = at + word.length
            }
            false
        }

    private fun span(sentence: String, at: Int, word: String): String {
        val opposite = if (word == "严格递增") "严格递减" else "严格递增"
        val previous = if (at == 0) -1 else sentence.lastIndexOf(opposite, at - 1)
        val start = if (previous < 0) 0 else previous + opposite.length
        val next = sentence.indexOf(opposite, at + word.length)
        return sentence.substring(start, if (next < 0) sentence.length else next)
    }

    private fun slice(text: String, marker: String): String? {
        val at = text.indexOf(marker)
        if (at < 0) return null
        val rest = text.substring(at + marker.length)
        val next = listOf("a<0", "a=0", "a>0").map { rest.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: rest.length
        return rest.substring(0, next)
    }

    private fun hasClaim(text: String, word: String): Boolean {
        var index = 0
        val opposite = if (word == "严格递增") "严格递减" else "严格递增"
        var affirmed = false
        while (true) {
            val at = text.indexOf(word, index)
            if (at < 0) break
            val prefix = text.substring(maxOf(0, at - 4), at)
            if (!prefix.endsWith("不是") && !prefix.endsWith("并非") && !prefix.endsWith("不能") && !prefix.endsWith("不")) affirmed = true
            index = at + word.length
        }
        return affirmed && !text.contains(opposite)
    }

    private fun word(direction: String) = if (direction == "increasing") "严格递增" else "严格递减"

    private fun normalize(raw: String): String? {
        var current = raw.trim()
        if (current.isEmpty() || current.length > 200) return null
        repeat(8) {
            val next = current.replace(Regex("""(\d)([A-Za-z(])"""), "$1*$2")
                .replace(Regex("""([A-Za-z)])([A-Za-z(])"""), "$1*$2")
            if (next == current) return@repeat
            current = next
        }
        if (!current.all { it.isLetterOrDigit() || it in "+-*/^()." }) return null
        return current
    }
}
