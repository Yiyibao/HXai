package me.rerere.rikkahub.feature.agent

import kotlinx.serialization.json.*

/** Untrusted proposal syntax only. Narrative is never a dispatch slot or a receipt. */
internal object PlanProposal {
    private val json=Json { ignoreUnknownKeys=false }
    fun decode(raw: String): AgentPlan {
        require(raw.length<=262144) { "规划过大" }
        val proposal=json.parseToJsonElement(raw).jsonObject
        val steps=proposal["steps"] as? JsonArray ?: return json.decodeFromString<AgentPlan>(raw)
        require(steps.size<=64)
        val narrative=mutableListOf<Pair<String,String>>()
        val executable=buildJsonArray {
            for(value in steps) {
                val step=value.jsonObject
                if(step.containsKey("tool") && step["tool"]==JsonNull) {
                    require(step.keys==setOf("id","tool","purpose"))
                    val id=(step["id"] as? JsonPrimitive)?.takeIf {it.isString}?.content ?: error("说明步骤缺少标识")
                    val purpose=(step["purpose"] as? JsonPrimitive)?.takeIf {it.isString}?.content ?: error("说明步骤缺少内容")
                    require(id.matches(Regex("[a-zA-Z0-9_-]{1,80}")) && purpose.isNotBlank() && purpose.length<=2000)
                    narrative+=id to purpose
                } else add(value)
            }
        }
        if(narrative.isEmpty()) return json.decodeFromString<AgentPlan>(raw)
        val allIds=steps.map { it.jsonObject.getValue("id") }
        require(allIds.distinct().size==allIds.size) { "规划步骤标识重复" }
        val normalized=json.decodeFromJsonElement<AgentPlan>(JsonObject(proposal+mapOf("steps" to executable)))
        require(normalized.acceptance.none { p -> narrative.any {it.first==p.stepId} }) { "说明不能作为验收证据" }
        val explanation=(listOf(normalized.explanation).filter {it.isNotEmpty()}+narrative.map {"[${it.first}] ${it.second}"}).joinToString("\n")
        require(explanation.length<=4000)
        return normalized.copy(explanation=explanation)
    }
}
