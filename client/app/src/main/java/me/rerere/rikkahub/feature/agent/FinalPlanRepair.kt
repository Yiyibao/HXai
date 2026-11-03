package me.rerere.rikkahub.feature.agent

import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart

/** Eligibility only. No tool result, authorization, journal state or goal is promoted here. */
internal object FinalPlanRepair {
    const val PROJECTION_CHARS = 262144
    data class Candidate(val verified: List<VerifiedStep>, val rejectedMath: List<VerifiedStep>,
        val outputs: Map<String,List<UIMessagePart>>)

    fun candidate(persisted: PersistedPlan): Candidate? {
        val plan=persisted.plan ?: return null
        if(plan.acceptance.isEmpty() || persisted.revision!=persisted.plannedRevision ||
            persisted.steps.isEmpty() || persisted.steps.size>64 ||
            persisted.steps.any {it.state !in setOf("VERIFIED","RETURNED")} ||
            persisted.steps.sumOf {it.resultJson?.length?.toLong() ?: PROJECTION_CHARS.toLong()+1}>PROJECTION_CHARS) return null
        val verified=persisted.steps.filter {it.state=="VERIFIED"}
        if(verified.isEmpty())return null
        val outputs=try {persisted.steps.associate {it.id to Json.decodeFromString<List<UIMessagePart>>(it.resultJson ?: return null)}}
            catch(_: IllegalArgumentException) {return null}
        val rejected=persisted.steps.filter {it.state=="RETURNED"}
        if(rejected.any {step ->
            if(step.tool!="math_verify")true else {
                val text=outputs.getValue(step.id).singleOrNull() as? UIMessagePart.Text
                val status=try {text?.let {Json.parseToJsonElement(it.text).jsonObject["status"]?.jsonPrimitive?.content}}
                    catch(_: IllegalArgumentException) {null}
                status !in setOf("DISPROVED","UNSUPPORTED")
            }
        })return null
        val misplacedArtifact=plan.acceptance.any {it.kind=="artifact_content" &&
            outputs[it.stepId]?.none {part -> part is UIMessagePart.Document}==true} &&
            verified.any {outputs.getValue(it.id).any {part -> part is UIMessagePart.Document}}
        return if(rejected.isNotEmpty() || misplacedArtifact)Candidate(verified,rejected,outputs) else null
    }

    fun validate(candidate: Candidate, proposal: AgentPlan) {
        val retained=candidate.verified.associate {it.id to it.tool}
        proposal.validate(retained.values.toSet())
        require(proposal.acceptance.isNotEmpty() && proposal.steps.size==retained.size &&
            proposal.steps.associate {it.id to it.tool}==retained) {"Final planning cannot add dispatch slots or omit verified steps"}
        proposal.acceptance.filter {it.kind=="artifact_content"}.forEach {predicate ->
            require(candidate.outputs.getValue(predicate.stepId).any {it is UIMessagePart.Document}) {
                "Artifact acceptance must bind an actual document-producing step"
            }
        }
    }
}
