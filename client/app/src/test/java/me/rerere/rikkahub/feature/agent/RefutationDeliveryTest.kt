package me.rerere.rikkahub.feature.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.feature.math.MathVerification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefutationDeliveryTest {
    private val goal = "请核验 (x+1)^2=x^2+1 是否为恒等式。请以真实核验结果为准并给出反例。"
    private val input = buildJsonObject {
        put("operation", "identity")
        put("request", """{"left":"(x+1)^2","right":"x^2+1"}""")
    }

    @Test fun falseIdentityBindsOnlyWhenTheGoalAsksAndTheReplyQuotesTheWitness() = runBlocking {
        val certificate = MathVerification.verify(input)
        assertEquals("DISPROVED", certificate.getValue("status").jsonPrimitive.content)
        val reply = "不是恒等式。反例：取 x=1，左边为 4，右边为 2。"
        assertTrue(RefutationDelivery.binds(goal, input, certificate, reply))
        assertFalse(RefutationDelivery.binds("证明 (x+1)^2=x^2+1。", input, certificate, reply))
        assertFalse(RefutationDelivery.binds("请把还没有提供的讲义第2页改成定理列表，是否可以？", input, certificate, reply))
        assertFalse(RefutationDelivery.binds(goal, input, certificate, "不是恒等式，但没有给出具体数字。"))
        assertFalse(RefutationDelivery.binds(goal, input, certificate, reply + "验证通过"))
        assertTrue(RefutationDelivery.binds(goal, input, certificate, "反例成立，原命题不成立。x=1时左边是4，右边是2。"))
        assertFalse(RefutationDelivery.asksForCheck("Use exact math_verify on this precise claim. Unsupported or disproved is not success."))
    }

    @Test fun badCertificateOrUnevaluatedClaimCannotRefuteTheProposition() {
        val witness = buildJsonObject { put("x", 1); put("left", "4"); put("right", "2") }
        val reply = "反例 x=1，左边为 4，右边为 2。"
        val disproved = buildJsonObject {
            put("status", "DISPROVED"); put("verified", false); put("counterexample", witness)
        }
        assertTrue(RefutationDelivery.binds(goal, input, disproved, reply))
        assertFalse(RefutationDelivery.binds(goal, input, buildJsonObject {
            put("status", "DISPROVED"); put("verified", false); put("claim_status", "NOT_EVALUATED")
            put("counterexample", witness)
        }, reply))
        assertFalse(RefutationDelivery.binds(goal, input, buildJsonObject {
            put("status", "UNSUPPORTED"); put("verified", false); put("counterexample", witness)
        }, reply))
        assertFalse(RefutationDelivery.binds(goal, input, buildJsonObject {
            put("status", "VERIFIED"); put("verified", true); put("counterexample", witness)
        }, reply))
    }

    @Test fun refutationPredicateIsNotAToolSuccessSlot() {
        AgentPlan(acceptance = listOf(AcceptancePredicate(
            RefutationDelivery.STEP, RefutationDelivery.KIND, contains = listOf("a".repeat(64))
        ))).validate(emptySet())
        assertTrue(runCatching {
            AgentPlan(
                steps = listOf(PlanStep("step1", "math_verify", "check the false identity")),
                acceptance = listOf(AcceptancePredicate("step1", RefutationDelivery.KIND, contains = listOf("a".repeat(64)))),
            ).validate(setOf("math_verify"))
        }.isFailure)
        assertTrue(runCatching {
            AgentPlan(acceptance = listOf(AcceptancePredicate(
                RefutationDelivery.STEP, RefutationDelivery.KIND, contains = listOf("not-a-hash")
            ))).validate(emptySet())
        }.isFailure)
    }
}
