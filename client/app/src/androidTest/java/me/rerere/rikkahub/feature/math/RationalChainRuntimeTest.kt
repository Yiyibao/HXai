package me.rerere.rikkahub.feature.math

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RationalChainRuntimeTest {
    private fun input(vararg expressions: String) = buildJsonObject {
        put("operation", "rational_expression_chain"); put("expressions", JsonArray(expressions.map(::JsonPrimitive)))
    }
    private suspend fun execute(args: JsonObject): JsonObject {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "org.hxsi.agent.nativeqa")
        return Json.parseToJsonElement(mathVerifyTool().execute(args).filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
    }
    private suspend fun verified(vararg expressions: String): JsonObject {
        val args = input(*expressions); val result = execute(args)
        assertEquals("VERIFIED", result.getValue("status").jsonPrimitive.content)
        assertTrue(MathVerification.accepts(args, result))
        return result
    }
    private fun domainCheck(result: JsonObject, index: Int = 0) = result.getValue("checks").jsonArray[index].jsonObject.getValue("domain_check").jsonObject

    @Test fun cancellationAndReintroductionKeepTheInitialHole(): Unit = runBlocking {
        val result = verified("(x^2-1)/(x-1)", "x+1", "(x+1)*(x-1)/(x-1)")
        val hole = buildJsonObject { put("0", "-1"); put("1", "1") }
        assertEquals(JsonArray(listOf(hole)), result.getValue("initial_domain").jsonObject.getValue("nonzero_polynomials"))
        assertEquals(2, result.getValue("verified_prefix_transitions").jsonPrimitive.int)
        assertEquals(JsonNull, result.getValue("first_error_transition"))
        verified("x/x", "1", "x/x")
        verified("1/(1/x)", "x", "x+0*(1/x)")
        verified("(1/x)^0", "1", "x^0")
    }
    @Test fun integerAndIrrationalNewHolesAreRealErrorsEvenWhenValuesCancel(): Unit = runBlocking {
        for ((expression, count) in listOf("x/x" to 1, "(x^2-2)/(x^2-2)" to 2, "(x^2-2)^2/(x^2-2)^2" to 2)) {
            val args = input("1", expression); val result = execute(args)
            assertEquals(expression, "DISPROVED", result.getValue("status").jsonPrimitive.content)
            assertEquals(1, result.getValue("first_error_transition").jsonPrimitive.int)
            assertEquals(count, domainCheck(result).getValue("new_excluded_real_root_count").jsonPrimitive.int)
            assertFalse(MathVerification.accepts(args, result))
        }
    }
    @Test fun complexOnlyFactorsAndDifferentCorrectMethodsAreAccepted(): Unit = runBlocking {
        val result = verified("1", "(x^2+1)/(x^2+1)", "(x^4+1)/(x^4+1)")
        assertEquals(0, domainCheck(result).getValue("new_excluded_real_root_count").jsonPrimitive.int)
        verified("1/x+1/x", "(x+x)/(x*x)", "2/x")
        verified("(x^2-2)/(x^2-2)", "1", "(x^2-2)^2/(x^2-2)^2")
    }
    @Test fun everyTransitionUsesInitialDomainRatherThanShrinkingPairwiseDomains(): Unit = runBlocking {
        val result = execute(input("x/x", "1", "(x-1)/(x-1)", "1"))
        assertEquals("DISPROVED", result.getValue("status").jsonPrimitive.content)
        assertEquals(2, result.getValue("first_error_transition").jsonPrimitive.int)
        assertEquals(1, result.getValue("verified_prefix_transitions").jsonPrimitive.int)
        assertEquals(2, result.getValue("checks").jsonArray.size)
        val mixed = execute(input("(x^2-2)/(x^2-2)", "1", "x/x"))
        assertEquals(1, domainCheck(mixed, 1).getValue("new_excluded_real_root_count").jsonPrimitive.int)
    }
    @Test fun exactValueDisproofFollowsVerifiedPrefixAndAvoidsOriginalPoles(): Unit = runBlocking {
        val result = execute(input("x/x", "1", "1+x*(x-1)*(x-2)*(x-3)"))
        assertEquals("DISPROVED", result.getValue("status").jsonPrimitive.content)
        assertEquals(2, result.getValue("first_error_transition").jsonPrimitive.int)
        val witness = result.getValue("checks").jsonArray[1].jsonObject.getValue("value_check").jsonObject.getValue("counterexample").jsonObject
        assertEquals(4, witness.getValue("x").jsonPrimitive.int)
        assertEquals("1", witness.getValue("left").jsonPrimitive.content)
        assertEquals("25", witness.getValue("right").jsonPrimitive.content)
    }
    @Test fun unknownPrefixNeverAccusesLaterStepAndLimitsStayUnknown(): Unit = runBlocking {
        for (expression in listOf("sin(x)", "1/(x-x)", "0^0", "x^33", "x+y")) {
            val result = execute(input("x/x", "1", expression, "2"))
            assertEquals("UNSUPPORTED", result.getValue("status").jsonPrimitive.content)
            assertEquals(JsonNull, result.getValue("first_error_transition"))
            assertEquals(2, result.getValue("unverified_transition").jsonPrimitive.int)
            assertEquals(1, result.getValue("verified_prefix_transitions").jsonPrimitive.int)
        }
        for (args in listOf(input("1"), input(*Array(51) { "1" }), JsonObject(input("1", "1") + ("authorized" to JsonPrimitive("true"))))) {
            assertEquals("UNSUPPORTED", execute(args).getValue("status").jsonPrimitive.content)
        }
        verified(*Array(50) { if (it % 2 == 0) "x/x" else "1" })
    }
    @Test fun durableEvidenceCannotLoseInitialDomainPrefixOrSturmProof(): Unit = runBlocking {
        val args = input("1", "(x^2+1)/(x^2+1)"); val result = execute(args)
        assertTrue(MathVerification.accepts(args, result))
        for ((key, value) in listOf("initial_domain" to JsonNull, "checks" to JsonArray(emptyList()), "verified_prefix_transitions" to JsonPrimitive(0), "scope" to JsonPrimitive("general_proof"), "input_sha256" to JsonPrimitive("0".repeat(64)))) {
            assertFalse(key, MathVerification.accepts(args, JsonObject(result + (key to value))))
        }
        val first = result.getValue("checks").jsonArray[0].jsonObject
        val domain = domainCheck(result)
        val forgedCheck = JsonObject(first + ("domain_check" to JsonObject(domain + ("sturm_sequence" to JsonArray(emptyList())))))
        assertFalse(MathVerification.accepts(args, JsonObject(result + ("checks" to JsonArray(listOf(forgedCheck))))))
        val wrong = input("1", "x/x"); val failure = execute(wrong)
        assertFalse(MathVerification.accepts(wrong, JsonObject(failure + mapOf("status" to JsonPrimitive("VERIFIED"), "verified" to JsonPrimitive(true)))))
        val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "b3e-chain-evidence").apply { mkdirs() }
        val file = File(folder, "fixed-domain-chain.json")
        file.writeText(buildJsonObject { put("input", args); put("result", result) }.toString())
        val durable = Json.parseToJsonElement(file.readText()).jsonObject
        assertTrue(MathVerification.accepts(durable.getValue("input").jsonObject, durable.getValue("result").jsonObject))
        assertFalse(MathVerification.accepts(input("1", "x/x"), durable.getValue("result").jsonObject))
    }
    @Test fun cancellationPropagatesInsteadOfBecomingMathUnsupported(): Unit = runBlocking {
        var cancelled = false
        try { withContext(Job().apply { cancel() }) { execute(input("x/x", "1")) } } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }
}
