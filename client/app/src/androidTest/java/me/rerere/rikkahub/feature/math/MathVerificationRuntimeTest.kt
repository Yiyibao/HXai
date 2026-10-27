package me.rerere.rikkahub.feature.math

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MathVerificationRuntimeTest {
    private suspend fun execute(input: JsonObject): JsonObject {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "org.hxsi.agent.nativeqa")
        return Json.parseToJsonElement(mathVerifyTool().execute(input).filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
    }
    private fun identity(left: String, right: String) = buildJsonObject { put("operation", "identity"); put("left", left); put("right", right) }
    private fun chain(vararg expressions: String) = buildJsonObject { put("operation", "equal_expression_chain"); put("expressions", JsonArray(expressions.map(::JsonPrimitive))) }
    private fun status(result: JsonObject) = result.getValue("status").jsonPrimitive.content
    private fun save(name: String, input: JsonObject, result: JsonObject) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "b3a-math-evidence").apply { mkdirs() }
        File(directory, "$name.json").writeText(buildJsonObject { put("input", input); put("result", result) }.toString())
    }

    @Test fun exactIdentitiesDerivativesAndLargeIntegersDoNotUseFloatingPoint(): Unit = runBlocking {
        for ((left, right) in listOf(
            "(x+1)^2" to "x*x+2*x+1", "(x-1)*(x+1)" to "x^2-1", "(2*x+1)/3" to "2*x/3+1/3",
            "x-x" to "0", "x^0" to "1", "(x+1)^3" to "1+3*x+3*x*x+x^3", "x/2+x/2" to "x",
            "-x^2" to "-(x*x)", "(-x)^2" to "x*x", "1/2+1/3" to "5/6", "x**(2)" to "x*x",
            "9007199254740993-9007199254740992" to "1", "x^32" to "x**32", "0".repeat(4096) to "0"
        )) {
            val input = identity(left, right); val result = execute(input)
            assertEquals("VERIFIED", status(result)); assertTrue(result.getValue("verified").jsonPrimitive.boolean)
            assertTrue(MathVerification.accepts(input, result))
        }
        for ((expression, derivative) in listOf("x^3-3*x" to "3*x^2-3", "x^2/2" to "x", "9" to "0", "(x+1)^2" to "2*x+2")) {
            val input = buildJsonObject { put("operation", "derivative"); put("expression", expression); put("claimed_derivative", derivative) }
            val result = execute(input); assertEquals("VERIFIED", status(result)); assertTrue(MathVerification.accepts(input, result))
        }
        val input = identity("(2*x+1)/3", "2*x/3+1/3"); val result = execute(input)
        assertEquals(buildJsonObject { put("0", "1/3"); put("1", "2/3") }, result.getValue("canonical_coefficients"))
        save("identity", input, result)
        val derivativeInput = buildJsonObject { put("operation", "derivative"); put("expression", "x^3-3*x"); put("claimed_derivative", "3*x^2-3") }
        save("derivative", derivativeInput, execute(derivativeInput))
        val edge = java.math.BigInteger.ONE.shiftLeft(1023).toString()
        assertEquals("VERIFIED", status(execute(identity(edge, edge))))
        val longest = chain(*Array(50) { "x+0" }); val longestResult = execute(longest)
        assertEquals("VERIFIED", status(longestResult))
        assertEquals(49, longestResult.getValue("verified_prefix_transitions").jsonPrimitive.int)
    }

    @Test fun coincidentSamplesAndWrongDerivativeRemainExactlyDisproved(): Unit = runBlocking {
        val input = identity("x*(x-1)*(x-2)*(x-3)", "0"); val result = execute(input)
        assertEquals("DISPROVED", status(result)); assertFalse(result.getValue("verified").jsonPrimitive.boolean)
        assertEquals(buildJsonObject { put("x", 4); put("left", "24"); put("right", "0") }, result.getValue("counterexample"))
        assertEquals(buildJsonObject { put("1", "-6"); put("2", "11"); put("3", "-6"); put("4", "1") }, result.getValue("difference_coefficients"))
        assertFalse(MathVerification.accepts(input, result))
        val derivativeInput = buildJsonObject { put("operation", "derivative"); put("expression", "x^3"); put("claimed_derivative", "2*x") }
        assertEquals("DISPROVED", status(execute(derivativeInput)))
        assertEquals("DISPROVED", status(execute(identity("-x^2", "(-x)^2"))))
        save("sampling-is-not-proof", input, result)
    }

    @Test fun firstErrorNeedsVerifiedPrefixAndCorrectAlternativeMethodIsAccepted(): Unit = runBlocking {
        val input = chain("(x+1)^2", "x^2+2*x+1", "x^2+1", "x^2"); val result = execute(input)
        assertEquals("DISPROVED", status(result)); assertEquals(2, result.getValue("first_error_transition").jsonPrimitive.int)
        assertEquals(1, result.getValue("verified_prefix_transitions").jsonPrimitive.int)
        assertEquals(buildJsonObject { put("x", 1); put("left", "4"); put("right", "2") }, result.getValue("checks").jsonArray.last().jsonObject.getValue("counterexample"))
        val alternate = chain("(x+1)^2", "1+x+x+x*x", "1+2*x+x^2"); val alternateResult = execute(alternate)
        assertEquals("VERIFIED", status(alternateResult)); assertTrue(MathVerification.accepts(alternate, alternateResult))
        val unknown = chain("x", "x+0", "1/x", "0"); val unknownResult = execute(unknown)
        assertEquals("UNSUPPORTED", status(unknownResult)); assertEquals(JsonNull, unknownResult.getValue("first_error_transition"))
        assertEquals(2, unknownResult.getValue("unverified_transition").jsonPrimitive.int); assertEquals(1, unknownResult.getValue("verified_prefix_transitions").jsonPrimitive.int)
        assertFalse(MathVerification.accepts(unknown, unknownResult))
        save("first-error", input, result); save("unknown-prefix", unknown, unknownResult); save("alternative-chain", alternate, alternateResult)
    }

    @Test fun unsupportedDomainsMalformedRequestsAndResourceLimitsNeverBecomeProof(): Unit = runBlocking {
        for (expression in listOf("1/x", "sqrt(x)", "sin(x)", "__import__(1)", "x**100000000", "x**-1", "x**x", "0.1*x", "x//2", "1/0", "x=1", "x[0]", "x;1", "(x**32)*x", "1".repeat(4000), "x+".repeat(300)+"1", "(x^2-1)/(x-1)", "x+y", "(".repeat(65)+"x"+")".repeat(65))) {
            val input = identity(expression, "0"); val result = execute(input)
            assertEquals("UNSUPPORTED", status(result)); assertFalse(result.getValue("verified").jsonPrimitive.boolean); assertFalse(MathVerification.accepts(input, result))
        }
        for (input in listOf(
            buildJsonObject { put("operation", "identity"); put("left", "x") },
            buildJsonObject { put("operation", "identity"); put("left", 1); put("right", "1") },
            JsonObject(identity("x", "x") + ("scope" to JsonPrimitive("general_proof"))),
            buildJsonObject { put("operation", "equal_expression_chain"); put("expressions", buildJsonArray { add("x") }) }
        )) assertEquals("UNSUPPORTED", status(execute(input)))
        assertEquals("UNSUPPORTED", status(execute(identity(java.math.BigInteger.ONE.shiftLeft(1024).toString(), "0"))))
        assertEquals("UNSUPPORTED", status(execute(identity("0".repeat(4097), "0"))))
        assertEquals("UNSUPPORTED", status(execute(chain(*Array(51) { "x" }))))
        val input = identity("(x^2-1)/(x-1)", "x+1"); save("domain-sensitive-cancellation", input, execute(input))
    }

    @Test fun independentRecomputeRejectsForgedStatusInputScopeAndExactEvidence(): Unit = runBlocking {
        val input = identity("(x+1)^2", "x*x+2*x+1"); val result = execute(input)
        assertTrue(MathVerification.accepts(input, result))
        for ((key, value) in listOf(
            "input_sha256" to JsonPrimitive("0".repeat(64)), "scope" to JsonPrimitive("general_proof"),
            "canonical_coefficients" to buildJsonObject { put("0", "1") }, "verified" to JsonPrimitive(false)
        )) assertFalse(MathVerification.accepts(input, JsonObject(result + (key to value))))
        assertFalse(MathVerification.accepts(input, JsonObject(result - "proof_kind")))
        assertFalse(MathVerification.accepts(identity("x", "x+1"), result))
        val wrong = identity("x", "x+1"); val wrongResult = execute(wrong)
        assertFalse(MathVerification.accepts(wrong, JsonObject(wrongResult + mapOf("status" to JsonPrimitive("VERIFIED"), "verified" to JsonPrimitive(true)))))
        val unsupported = identity("sin(x)", "0"); val unsupportedResult = execute(unsupported)
        assertFalse(MathVerification.accepts(unsupported, JsonObject(unsupportedResult + mapOf("status" to JsonPrimitive("VERIFIED"), "verified" to JsonPrimitive(true)))))
    }
}
