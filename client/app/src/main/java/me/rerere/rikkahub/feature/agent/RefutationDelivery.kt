package me.rerere.rikkahub.feature.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/** A recomputed counterexample can satisfy a check-or-refute goal. It never promotes the false claim. */
internal object RefutationDelivery {
    const val KIND = "refutation_delivery"
    const val STEP = "_refutation"

    fun counterexample(certificate: JsonObject): JsonObject? {
        if (certificate["status"]?.jsonPrimitive?.content != "DISPROVED") return null
        if (certificate["verified"]?.jsonPrimitive?.booleanOrNull != false) return null
        if (certificate["claim_status"]?.jsonPrimitive?.content == "NOT_EVALUATED") return null
        val example = certificate["counterexample"] as? JsonObject ?: return null
        if (example.isEmpty() || example.values.any { value ->
                value !is JsonPrimitive || value.content.isBlank()
            }) return null
        return example
    }

    fun asksForCheck(goal: String): Boolean {
        if (listOf("核验", "是否", "反例").any { it in goal }) return true
        return Regex("""(?<![A-Za-z])(counterexample|whether)(?![A-Za-z])""").containsMatchIn(goal)
    }

    fun claimInGoal(goal: String, input: JsonObject): Boolean {
        val request = input["request"]?.jsonPrimitive?.takeIf { it.isString }?.content?.let { raw ->
            runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
        } ?: input
        val present = listOf("left", "right", "expression", "claimed", "claimed_derivative", "claimed_value").mapNotNull { key ->
            (request[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.replace(" ", "")
        }.filter { it.length >= 3 }
        if (present.isEmpty()) return false
        val compact = goal.replace(" ", "").replace("\\", "")
        return present.all { field -> field in compact || field.replace("*", "") in compact }
    }

    fun binds(goal: String, input: JsonObject, certificate: JsonObject, reply: String): Boolean {
        val example = counterexample(certificate) ?: return false
        if (!asksForCheck(goal) || !claimInGoal(goal, input)) return false
        val sentences = reply.split(Regex("[。！？；\\n]")).map { it.trim() }.filter { it.isNotEmpty() }
        if (sentences.any(::affirms) || sentences.none(::denies)) return false
        val compact = reply.replace(" ", "")
        return example.entries.all { (key, value) ->
            val text = (value as JsonPrimitive).content
            if (key == "x") "x=$text" in compact else text in reply
        }
    }

    private fun affirms(sentence: String): Boolean {
        val text = sentence.replace(" ", "")
        for (word in listOf("验证通过", "命题为真", "是恒等式", "成立")) {
            var index = 0
            while (true) {
                val at = text.indexOf(word, index)
                if (at < 0) break
                if (word == "成立" && text.substring(maxOf(0, at - 4), at).endsWith("反例")) {
                    index = at + word.length
                    continue
                }
                val prefix = text.substring(maxOf(0, at - 2), at)
                if (prefix != "不是" && !prefix.endsWith("不") && !prefix.endsWith("非")) return true
                index = at + word.length
            }
        }
        return false
    }

    private fun denies(sentence: String): Boolean {
        val text = sentence.replace(" ", "")
        if ("不是反例" in text || "没有反例" in text) return false
        return listOf("不是", "不成立", "反例", "不能").any { it in text }
    }
}

internal data class SettledRefutation(val callId: String, val input: String, val certificate: JsonObject)
