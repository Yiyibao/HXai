package me.rerere.rikkahub.feature.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Host extraction for one rational linear recurrence. The closed form comes from the visible reply. */
internal object RationalRecurrenceGoal {
    const val KIND = "rational_recurrence_delivery"
    const val STEP = "_rational_recurrence"

    data class Spec(val start: String, val initial: String, val next: String) {
        fun request(claimed: String): JsonObject = buildJsonObject {
            put("operation", "professional_induction")
            put("request", """{"start":"$start","initial":"$initial","next":"$next","claimed":"$claimed"}""")
        }
    }

    fun parse(goal: String): Spec? {
        if (listOf("同上", "圆锥", "单调", "讨论", "分类", "是否", "反例", "证明a_n=").any { it in goal }) return null
        if (!goal.contains("求通项") || !goal.contains("证明") || !goal.contains("非负整数")) return null
        val compact = goal.replace(" ", "").replace("\\", "")
        val base = Regex("""a_\{?(\d+)\}?=([0-9]+(?:/[1-9][0-9]*)?)""").find(compact) ?: return null
        if (base.groupValues[1] != "0") return null
        val recurrence = Regex("""a_\(n\+1\)=([^，。；;]+)""").find(compact)
            ?: Regex("""a_\{n\+1\}=([^，。；;]+)""").find(compact) ?: return null
        val next = normalize(recurrence.groupValues[1].replace("a_{n}", "u").replace("a_n", "u")) ?: return null
        if (!next.contains("u")) return null
        return Spec(base.groupValues[1], base.groupValues[2], next)
    }

    fun claim(reply: String): String? {
        val found = VisibleMathClaim.affirmativeFormulas(reply).filter { formula ->
            formula.none { it.isLetter() && it != 'n' } &&
                runCatching { me.rerere.rikkahub.feature.math.MultiExpression(formula, listOf("n")).parse() }.isSuccess
        }.distinct()
        if (found.isEmpty() || !found.all { VisibleMathClaim.same(it, found.first(), listOf("n")) }) return null
        if (!VisibleMathClaim.domainAllows(reply, "0")) return null
        return found.first()
    }


    private fun normalize(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty() || text.length > 200) return null
        var current = text
        repeat(8) {
            val next = current.replace(Regex("""(\d)([A-Za-z(])"""), "$1*$2")
                .replace(Regex("""([A-Za-z)])([A-Za-z(])"""), "$1*$2")
                .replace(Regex("""(\))([0-9A-Za-z(])"""), "$1*$2")
            if (next == current) return@repeat
            current = next
        }
        if (!current.all { it.isLetterOrDigit() || it in "+-*/^()." }) return null
        return current
    }
}
