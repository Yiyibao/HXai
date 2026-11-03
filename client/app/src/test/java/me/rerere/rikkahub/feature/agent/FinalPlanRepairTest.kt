package me.rerere.rikkahub.feature.agent

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class FinalPlanRepairTest {
    private fun step(id: String,tool: String="math_verify",state: String="VERIFIED",parts: List<UIMessagePart> = listOf(UIMessagePart.Text("""{"status":"VERIFIED"}"""))) =
        VerifiedStep(id,tool,"call-$id",state,Json.encodeToString(parts))
    private fun plan(steps: List<VerifiedStep>,acceptance: List<AcceptancePredicate> = listOf(AcceptancePredicate("good","tool_verified"))) =
        PersistedPlan("The full unchanged user goal",1,1,AgentPlan(steps.map {PlanStep(it.id,it.tool,"purpose")},acceptance),steps,false)
    private fun failed()=step("bad",state="RETURNED",parts=listOf(UIMessagePart.Text("""{"status":"UNSUPPORTED"}""")))
    @Test fun knownRejectedMathRequiresASeparateVerifiedResultAndDoesNotBecomeProof() {
        val input=plan(listOf(failed(),step("good")))
        val candidate=requireNotNull(FinalPlanRepair.candidate(input))
        assertEquals(listOf("bad"),candidate.rejectedMath.map {it.id})
        assertEquals(listOf("good"),candidate.verified.map {it.id})
        FinalPlanRepair.validate(candidate,AgentPlan(listOf(PlanStep("good","math_verify","Use verified correction")),input.plan!!.acceptance))
        assertEquals("RETURNED",input.steps.first().state)
        assertNull(FinalPlanRepair.candidate(plan(listOf(failed()),listOf(AcceptancePredicate("bad","tool_verified")))))
    }
    @Test fun pendingUnknownOrUnverifiedWritesCannotBeSuperseded() {
        for(state in listOf("PLANNED","IN_FLIGHT","UNKNOWN"))assertNull(FinalPlanRepair.candidate(plan(listOf(failed().copy(state=state),step("good")))))
        for(tool in listOf("create_material_file","parse_material_enhanced","eval_javascript","save_explicit_memory"))
            assertNull(FinalPlanRepair.candidate(plan(listOf(failed().copy(tool=tool),step("good")))))
        assertNull(FinalPlanRepair.candidate(plan(listOf(step("bad",state="RETURNED"),step("good")))))
    }
    @Test fun ordinarySuccessfulOrIncompletePlansDoNotCauseAnExtraModelRequest() {
        assertNull(FinalPlanRepair.candidate(plan(listOf(step("good")))))
        assertNull(FinalPlanRepair.candidate(plan(listOf(failed(),step("good")),emptyList())))
        assertNull(FinalPlanRepair.candidate(plan(listOf(failed(),step("good"))).copy(plannedRevision=0)))
    }
    @Test fun artifactPredicateMustTargetAnActualVerifiedDocumentProducer() {
        val doc=step("write","create_material_file",parts=listOf(UIMessagePart.Document("file:///owned/public.docx","public.docx")))
        val read=step("read","read_material_file",parts=listOf(UIMessagePart.Text("public content")))
        val misplaced=AcceptancePredicate("read","artifact_content","docx",listOf("public"))
        val input=plan(listOf(doc,read),listOf(misplaced));val candidate=requireNotNull(FinalPlanRepair.candidate(input))
        assertTrue(runCatching {FinalPlanRepair.validate(candidate,input.plan!!)}.isFailure)
        FinalPlanRepair.validate(candidate,input.plan!!.copy(acceptance=listOf(misplaced.copy(stepId="write"))))
    }
    @Test fun finalProposalCannotAddDispatchSlotsOrDiscardAnyVerifiedStep() {
        val input=plan(listOf(failed(),step("good"),step("other")));val candidate=requireNotNull(FinalPlanRepair.candidate(input))
        for(steps in listOf(listOf(PlanStep("good","math_verify","omit other")),
            listOf(PlanStep("good","math_verify","good"),PlanStep("other","math_verify","other"),PlanStep("new","math_verify","new slot"))))
            assertTrue(runCatching {FinalPlanRepair.validate(candidate,AgentPlan(steps,input.plan!!.acceptance))}.isFailure)
    }
    @Test fun malformedOrOversizeEvidenceFailsClosedWithoutTruncatingStoredResults() {
        assertNull(FinalPlanRepair.candidate(plan(listOf(failed(),step("good").copy(resultJson="not JSON")))))
        assertNull(FinalPlanRepair.candidate(plan(listOf(failed(),step("good").copy(resultJson="x".repeat(FinalPlanRepair.PROJECTION_CHARS))))))
    }
}
