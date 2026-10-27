package me.rerere.rikkahub.feature.agent

import kotlinx.serialization.Serializable

@Serializable
internal data class AgentPlan(
    val steps: List<PlanStep> = emptyList(),
    val acceptance: List<AcceptancePredicate> = emptyList(),
    val explanation: String = "",
) {
    fun validate(availableTools: Set<String>) {
        require(steps.size<=64 && acceptance.size<=64 && explanation.length<=4000)
        require(steps.map { it.id }.distinct().size==steps.size)
        steps.forEach { require(it.id.matches(Regex("[a-zA-Z0-9_-]{1,80}")) && it.tool in availableTools && it.purpose.length in 1..2000)
            TrustedToolRegistry.require(it.tool) }
        acceptance.forEach {
            require(it.stepId in steps.map { step -> step.id })
            require(it.kind=="artifact_content" || it.kind=="memory_content" || it.kind=="tool_verified")
            if(it.kind=="artifact_content") require(it.format in setOf("md","txt","docx","xlsx","pptx","pdf") && it.contains.isNotEmpty())
            if(it.kind=="memory_content") require(steps.single { step -> step.id==it.stepId }.tool=="save_explicit_memory" && it.contains.isNotEmpty())
            require(it.contains.size<=32 && it.contains.all { text -> text.length in 1..4000 })
        }
    }
}
@Serializable internal data class PlanStep(val id: String, val tool: String, val purpose: String)
@Serializable internal data class AcceptancePredicate(val stepId: String, val kind: String, val format: String? = null,
    val contains: List<String> = emptyList(), val sourceId: String? = null, val sourceHash: String? = null)
internal data class AgentControl(val authorization: String, val grantEpoch: Long, val inputBlocked: Boolean,
    val deviceBlocked: Boolean, val deviceConfirmation: Boolean, val cancelRequested: Boolean,
    val approvalBlocked: Boolean)
internal data class VerifiedStep(val id: String, val tool: String, val callId: String?, val state: String, val resultJson: String?)
internal data class PersistedToolDecision(val callId: String,val tool: String,val inputHash: String,
    val grantEpoch: Long,val decision: me.rerere.ai.ui.ToolApprovalState)
internal data class PersistedPlan(val goal: String, val revision: Long, val plannedRevision: Long, val plan: AgentPlan?,
    val steps: List<VerifiedStep>, val acceptanceVerified: Boolean)
internal data class CredentialStatus(val requestId: String,val state: String)
internal data class AgentTaskSnapshot(val task: AgentTask,val control: AgentControl,val plan: PersistedPlan,
    val credential: CredentialStatus?,val approvalCallIds: List<String>)
