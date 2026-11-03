package me.rerere.rikkahub.feature.agent

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PlanProposalTest {
    private val tool="""{"id":"verify_identity","tool":"math_verify","purpose":"Verify the exact identity"}"""
    private val narrative="""{"id":"explain_scope","tool":null,"purpose":"Explain that algebra does not establish an unspecified geometry theorem"}"""
    private val acceptance="""{"stepId":"verify_identity","kind":"tool_verified","contains":[]}"""
    private fun proposal(steps: String="$tool,$narrative", accept: String=acceptance, explanation: String="Scope retained")=
        """{"steps":[$steps],"acceptance":[$accept],"explanation":${JsonPrimitive(explanation)}}"""
    private fun rejected(raw: String) { try {PlanProposal.decode(raw).validate(setOf("math_verify"));fail("Invalid proposal accepted")}catch(_: Exception) {} }
    @Test fun actualNullToolExplanationKeepsTheExactExecutableStepAndPredicate() {
        val plan=PlanProposal.decode(proposal());plan.validate(setOf("math_verify"))
        assertEquals(listOf(PlanStep("verify_identity","math_verify","Verify the exact identity")),plan.steps)
        assertEquals("verify_identity",plan.acceptance.single().stepId)
        assertTrue(plan.explanation.contains("Explain that algebra"))
    }
    @Test fun canonicalPlanIsUnchangedAndPureNarrativeDoesNotGainAcceptance() {
        val canonical=proposal(tool);assertEquals(Json.decodeFromString<AgentPlan>(canonical),PlanProposal.decode(canonical))
        val text=PlanProposal.decode(proposal(narrative,""));text.validate(emptySet())
        assertTrue(text.steps.isEmpty());assertTrue(text.acceptance.isEmpty())
    }
    @Test fun narrativeCannotClaimToolOrArtifactAcceptance() {
        for(kind in listOf("tool_verified","artifact_content"))
            rejected(proposal(accept="""{"stepId":"explain_scope","kind":"$kind","format":"docx","contains":["claimed"]}"""))
    }
    @Test fun unknownMissingOrMistypedToolIsNotSilentlyDropped() {
        for(step in listOf(narrative.replace("null","\"invented_write\""),narrative.replace("\"tool\":null,",""),
            narrative.replace("null","17"),narrative.replace("\"purpose\":","\"extra\":true,\"purpose\":"),
            narrative.replace("explain_scope","verify_identity"))) rejected(proposal("$tool,$step"))
    }
    @Test fun malformedAndOversizedNarrativeDoesNotHideOtherInvalidData() {
        rejected(proposal(narrative.replace("explain_scope","bad id"),""))
        rejected(proposal(narrative.replace("Explain that algebra does not establish an unspecified geometry theorem",""),""))
        rejected(proposal(explanation="x".repeat(4000)))
        rejected(proposal(accept="""{"stepId":"verify_identity","kind":"invented_permission"}"""))
    }
}
