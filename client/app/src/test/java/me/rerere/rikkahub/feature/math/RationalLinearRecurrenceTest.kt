package me.rerere.rikkahub.feature.math

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import me.rerere.rikkahub.feature.agent.RationalRecurrenceGoal

class RationalLinearRecurrenceTest {
    private val goal = "数列满足a_0=0，a_(n+1)=((n+1)/(n+2))*a_n+(n+1)，n为非负整数。求通项并证明。允许使用归纳或变换后求和，不得因方法不同误判。"

    @Test fun liveToolCallVerifiesTheTelescopingClosedForm() = runBlocking {
        val result = MathVerification.verify(request("0", "0", "((n+1)/(n+2))*u+(n+1)", "n*(n+2)/3"))
        assertEquals("VERIFIED", result.getValue("status").jsonPrimitive.content)
        assertEquals(RationalLinearRecurrence.PROOF_KIND, result.getValue("proof_kind").jsonPrimitive.content)
    }

    @Test fun wrongClosedFormIsDisproved() = runBlocking {
        val result = MathVerification.verify(request("0", "0", "((n+1)/(n+2))*u+(n+1)", "n*(n+2)/2"))
        assertEquals("DISPROVED", result.getValue("status").jsonPrimitive.content)
        assertEquals("false", result.getValue("step_holds").jsonPrimitive.content)
    }

    @Test fun denominatorZeroOnTheDomainIsUnsupported() = runBlocking {
        val cancelled = MathVerification.verify(request("0", "0", "(n/n)*u+(n+1)", "n"))
        assertEquals("UNSUPPORTED", cancelled.getValue("status").jsonPrimitive.content)
        assertEquals("denominator_vanishes_on_integer_domain", cancelled.getValue("reason").jsonPrimitive.content)
        val claimedHole = MathVerification.verify(request("0", "1", "((n)/(n+1))*u", "1/n"))
        assertEquals("UNSUPPORTED", claimedHole.getValue("status").jsonPrimitive.content)
    }

    @Test fun sameReciprocalIsVerifiedWhenTheDomainStartsAtOne() = runBlocking {
        val result = MathVerification.verify(request("1", "1", "((n)/(n+1))*u", "1/n"))
        assertEquals("VERIFIED", result.getValue("status").jsonPrimitive.content)
    }

    @Test fun nonlinearOrSequenceDenominatorIsUnsupported() = runBlocking {
        val nonlinear = MathVerification.verify(request("0", "0", "((n+1)/(n+2))*u*u", "n"))
        assertEquals("UNSUPPORTED", nonlinear.getValue("status").jsonPrimitive.content)
        assertEquals("rational_recurrence_nonlinear_in_u", nonlinear.getValue("reason").jsonPrimitive.content)
        val divided = MathVerification.verify(request("0", "1", "(n+1)/u", "n+1"))
        assertEquals("UNSUPPORTED", divided.getValue("status").jsonPrimitive.content)
        assertEquals("sequence_value_in_denominator", divided.getValue("reason").jsonPrimitive.content)
    }

    @Test fun polynomialInductionKeepsItsOriginalProofKind() = runBlocking {
        val result = MathVerification.verify(request("0", "0", "u+2*n+1", "n^2"))
        assertEquals("VERIFIED", result.getValue("status").jsonPrimitive.content)
        assertEquals("mathematical_induction_exact_polynomial_base_and_step", result.getValue("proof_kind").jsonPrimitive.content)
    }

    @Test fun hostExtractsTheSealedPromptAndLatexReply() = runBlocking {
        val spec = requireNotNull(RationalRecurrenceGoal.parse(goal))
        assertEquals("((n+1)/(n+2))*u+(n+1)", spec.next)
        val reply = "对一切非负整数，通项为 a_n=\\frac{n(n+2)}{3}。另一写法 a_n=\\frac{b_n}{n+1}=\\frac{n(n+2)}{3} 仍是同一闭式。"
        val claim = requireNotNull(RationalRecurrenceGoal.claim(reply))
        val result = MathVerification.verify(spec.request(claim))
        assertEquals("VERIFIED", result.getValue("status").jsonPrimitive.content)
        assertNull(RationalRecurrenceGoal.claim("可以用裂项求和，但没有写出通项。"))
        assertNull(RationalRecurrenceGoal.parse("a_0=0，a_(n+1)=a_n+2n+1，n为非负整数。证明a_n=n^2。"))
        assertNull(RationalRecurrenceGoal.parse(goal.replace("求通项", "求通项并讨论圆锥")))
    }

    private fun request(start: String, initial: String, next: String, claimed: String): JsonObject = buildJsonObject {
        put("operation", "professional_induction")
        put("request", buildJsonObject {
            put("start", start); put("initial", initial); put("next", next); put("claimed", claimed)
        }.toString())
    }
}
