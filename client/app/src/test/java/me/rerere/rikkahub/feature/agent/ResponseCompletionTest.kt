package me.rerere.rikkahub.feature.agent

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ResponseCompletionTest {
    private val digest="a".repeat(64)
    private fun decision(outcome: String="delivered", external: Boolean=false) = buildJsonObject {
        put("outcome",outcome);put("goalRevision",3);put("evidenceHash",digest)
        put("responseQuote","你好");put("reason","Complete text response");put("requiresIndependentEvidence",external)
    }
    @Test fun exactBoundDeliveryAndInputWaitAreDistinct() {
        assertEquals("delivered",ResponseCompletion.disposition(decision(),3,digest,"你好！"))
        assertEquals("needs_input",ResponseCompletion.disposition(decision("needs_input",true),3,digest,"你好，请提供讲义"))
        assertEquals("unverified",ResponseCompletion.disposition(decision("unverified",true),3,digest,"你好，证明未核验"))
    }
    @Test fun independentEvidenceRequirementCannotBeDeclaredDelivered() {
        assertNull(ResponseCompletion.disposition(decision(external=true),3,digest,"你好"))
    }
    @Test fun mismatchedGoalHashOrQuoteCannotBindAnotherReply() {
        assertNull(ResponseCompletion.disposition(decision(),4,digest,"你好"))
        assertNull(ResponseCompletion.disposition(decision(),3,"b".repeat(64),"你好"))
        assertNull(ResponseCompletion.disposition(decision(),3,digest,"另一条回答"))
    }
    @Test fun unknownAndMalformedDecisionsCannotGrantCompletion() {
        assertNull(ResponseCompletion.disposition(JsonObject(decision()+mapOf("approved" to JsonPrimitive(true))),3,digest,"你好"))
        assertNull(ResponseCompletion.disposition(decision("success"),3,digest,"你好"))
        assertNull(ResponseCompletion.disposition(JsonObject(decision()-"requiresIndependentEvidence"),3,digest,"你好"))
    }
    @Test fun deliveryPredicateCannotAttachToAnyToolOrSource() {
        AgentPlan(acceptance=listOf(AcceptancePredicate(ResponseCompletion.STEP,ResponseCompletion.KIND,contains=listOf(digest)))).validate(emptySet())
        for(predicate in listOf(
            AcceptancePredicate("tool",ResponseCompletion.KIND,contains=listOf(digest)),
            AcceptancePredicate(ResponseCompletion.STEP,ResponseCompletion.KIND,contains=listOf("plain text")),
            AcceptancePredicate(ResponseCompletion.STEP,ResponseCompletion.KIND,contains=listOf(digest),sourceId="source"))) {
            try {AgentPlan(acceptance=listOf(predicate)).validate(emptySet());fail("Invalid delivery predicate accepted")}
            catch(_: IllegalArgumentException) {}
        }
    }
}
