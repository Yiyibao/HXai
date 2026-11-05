package me.rerere.rikkahub.feature.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Host extraction for one polynomial recurrence. Model prose is not the certificate. */
internal object InductionGoal {
    const val KIND = "induction_delivery"
    const val STEP = "_induction"

    data class Spec(val start: String, val initial: String, val next: String, val claimed: String) {
        fun request(): JsonObject = buildJsonObject {
            put("operation", "professional_induction")
            put("request", """{"start":"$start","initial":"$initial","next":"$next","claimed":"$claimed"}""")
        }
    }

    fun parse(goal: String): Spec? {
        if (listOf("同上", "圆锥", "单调", "反例", "是否", "核验", "讨论", "分类").any { it in goal }) return null
        if (!goal.contains("证明") || !goal.contains("非负整数")) return null
        val compact = goal.replace(" ", "").replace("\\", "")
        val base = Regex("""a_\{?(\d+)\}?=([0-9]+(?:/[1-9][0-9]*)?)""").find(compact) ?: return null
        if (base.groupValues[1] != "0") return null
        val recurrence = Regex("""a_\(n\+1\)=([^，。；;]+)""").find(compact)
            ?: Regex("""a_\{n\+1\}=([^，。；;]+)""").find(compact) ?: return null
        val claim = Regex("""证明a_n=([^，。；;]+)""").find(compact) ?: return null
        val claimed = normalize(claim.groupValues[1], sequence = false) ?: return null
        val next = normalize(recurrence.groupValues[1], sequence = true) ?: return null
        if (claimed.any { it.isLetter() && it != 'n' }) return null
        return Spec(base.groupValues[1], base.groupValues[2], next, claimed)
    }

    private fun normalize(raw: String, sequence: Boolean): String? {
        if (raw.isBlank() || raw.length > 200) return null
        var text = raw.replace("a_{n}", "u").replace("a_n", "u")
        if (!sequence && "u" in text) return null
        if (sequence && "a_" in text) return null
        text = Regex("""(\d)([A-Za-z])""").replace(text) { "${it.groupValues[1]}*${it.groupValues[2]}" }
        if (!text.all { it.isLetterOrDigit() || it in "+-*/^()." }) return null
        return text
    }

    fun quoted(reply: String, spec: Spec): Boolean {
        if (!VisibleMathClaim.domainAllows(reply, spec.start)) return false
        val claims = VisibleMathClaim.affirmativeFormulas(reply)
        if (claims.isEmpty() || !claims.all { VisibleMathClaim.same(it, claims.first(), listOf("n")) }) return false
        return VisibleMathClaim.same(claims.first(), spec.claimed, listOf("n"))
    }
}
