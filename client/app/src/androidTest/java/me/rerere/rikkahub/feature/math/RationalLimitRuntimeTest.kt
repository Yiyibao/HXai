package me.rerere.rikkahub.feature.math

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RationalLimitRuntimeTest {
    private fun input(expression: String, at: String, side: String = "both") = buildJsonObject {
        put("operation", "rational_limit"); put("expression", expression); put("at", at); put("side", side)
    }
    private suspend fun execute(args: JsonObject): JsonObject {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "org.hxsi.agent.nativeqa")
        return Json.parseToJsonElement(mathVerifyTool().execute(args).filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
    }
    private suspend fun classify(expression: String, at: String, side: String = "both"): JsonObject {
        val args = input(expression, at, side); val result = execute(args)
        assertEquals("VERIFIED", result.getValue("status").jsonPrimitive.content)
        assertTrue(MathVerification.accepts(args, result))
        return result
    }
    private fun limit(result: JsonObject) = result.getValue("limit").jsonObject
    private fun finite(value: String) = buildJsonObject { put("kind", "finite"); put("value", value) }

    @Test fun removableHolesAndZeroProductsHaveLimitsWithoutPointValues(): Unit = runBlocking {
        for ((expression, value) in listOf("(x^2-1)/(x-1)" to "2", "0*(1/(x-1))" to "0", "(1/(x-1))^0" to "1")) {
            val result = classify(expression, "1")
            assertEquals(finite(value), limit(result)); assertFalse(result.getValue("point_defined").jsonPrimitive.boolean)
            assertEquals(JsonNull, result.getValue("point_value"))
            assertFalse(result.getValue("original_domain").jsonObject.getValue("nonzero_polynomials").jsonArray.isEmpty())
        }
        val regular = classify("x+1", "1")
        assertTrue(regular.getValue("point_defined").jsonPrimitive.boolean)
        assertEquals(JsonPrimitive("2"), regular.getValue("point_value"))
    }
    @Test fun oddAndEvenPolesClassifyEachSideWithoutClaimingFiniteExistence(): Unit = runBlocking {
        val both = classify("1/x", "0")
        assertEquals("does_not_exist", limit(both).getValue("kind").jsonPrimitive.content)
        assertEquals("negative_infinity", limit(classify("1/x", "0", "left")).getValue("kind").jsonPrimitive.content)
        assertEquals("positive_infinity", limit(classify("1/x", "0", "right")).getValue("kind").jsonPrimitive.content)
        assertEquals("positive_infinity", limit(classify("1/x^2", "0")).getValue("kind").jsonPrimitive.content)
        assertEquals("negative_infinity", limit(classify("-1/x^32", "0")).getValue("kind").jsonPrimitive.content)
        assertEquals("does_not_exist", limit(classify("1/x^31", "0")).getValue("kind").jsonPrimitive.content)
        assertEquals(31, classify("1/x^31", "0").getValue("proof").jsonObject.getValue("denominator_zero_order").jsonPrimitive.int)
    }
    @Test fun infinityUsesDegreeLeadingCoefficientAndNegativeTailParity(): Unit = runBlocking {
        for (at in listOf("+inf", "-inf")) {
            assertEquals(finite("2/3"), limit(classify("(2*x^2+1)/(3*x^2-1)", at)))
            assertEquals(finite("0"), limit(classify("1/x", at)))
            assertEquals(finite("0"), limit(classify("0*(1/x)", at)))
        }
        assertEquals("positive_infinity", limit(classify("x^3/x^2", "+inf")).getValue("kind").jsonPrimitive.content)
        assertEquals("negative_infinity", limit(classify("x^3/x^2", "-inf")).getValue("kind").jsonPrimitive.content)
        assertEquals("negative_infinity", limit(classify("-2*x^2", "-inf")).getValue("kind").jsonPrimitive.content)
    }
    @Test fun signedRationalPointsAndLargeCoefficientsStayExact(): Unit = runBlocking {
        val result = classify("x/3+1/7", "1/-2")
        assertEquals("-1/2", result.getValue("at").jsonPrimitive.content)
        assertEquals(finite("-1/42"), limit(result))
        assertEquals(finite("9007199254740993"), limit(classify("9007199254740993*x/x", "0")))
        val pole = classify("1/(2*x+1)", "-1/2")
        assertEquals("does_not_exist", limit(pole).getValue("kind").jsonPrimitive.content)
        assertEquals("1/2", pole.getValue("proof").jsonObject.getValue("numerator_at_point").jsonPrimitive.content)
    }
    @Test fun originalNestedAndZeroPowerDomainsAreNotFilledByFiniteLimits(): Unit = runBlocking {
        for ((expression, expected) in listOf("1/(1/x)" to "0", "x^0" to "1", "(1/x)/(1/x)" to "1")) {
            val result = classify(expression, "0")
            assertEquals(finite(expected), limit(result)); assertFalse(result.getValue("point_defined").jsonPrimitive.boolean)
        }
    }
    @Test fun unsupportedPointsSidesExpressionsAndFieldsStayUnknown(): Unit = runBlocking {
        for (at in listOf("1.0", "x-x", "sqrt(2)", "1/0", "inf", "1e3", "9".repeat(400))) {
            assertEquals(at, "UNSUPPORTED", execute(input("x", at)).getValue("status").jsonPrimitive.content)
        }
        for (args in listOf(input("sin(x)", "0"), input("x+y", "0"), input("0^0", "0"), input("1/(x-x)", "0"), input("x^33", "0"), input("x", "0", "all"), input("x", "+inf", "left"), JsonObject(input("x", "0") + ("authorized" to JsonPrimitive("true"))))) {
            val result = execute(args); assertEquals("UNSUPPORTED", result.getValue("status").jsonPrimitive.content)
            assertFalse(MathVerification.accepts(args, JsonObject(result + mapOf("status" to JsonPrimitive("VERIFIED"), "verified" to JsonPrimitive(true)))))
        }
    }
    @Test fun durableEvidenceCannotForgePointValuePoleSignOrLimitExistence(): Unit = runBlocking {
        val args = input("1/x", "0"); val result = execute(args)
        assertTrue(MathVerification.accepts(args, result))
        for ((key, value) in listOf("limit" to finite("0"), "point_defined" to JsonPrimitive(true), "point_value" to JsonPrimitive("0"), "proof" to JsonObject(emptyMap()), "one_sided_limits" to JsonObject(emptyMap()), "original_domain" to JsonObject(emptyMap()), "input_sha256" to JsonPrimitive("0".repeat(64)), "scope" to JsonPrimitive("general_limit_proof"))) {
            assertFalse(key, MathVerification.accepts(args, JsonObject(result + (key to value))))
        }
        assertFalse(MathVerification.accepts(input("1/x", "0", "right"), result))
        val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "b3g-limit-evidence").apply { mkdirs() }
        val file = File(folder, "point-and-limit.json")
        file.writeText(buildJsonObject { put("input", args); put("result", result) }.toString())
        val saved = Json.parseToJsonElement(file.readText()).jsonObject
        assertTrue(MathVerification.accepts(saved.getValue("input").jsonObject, saved.getValue("result").jsonObject))
    }
    @Test fun cancellationPropagatesWithoutALimitClassification(): Unit = runBlocking {
        var cancelled = false
        try { withContext(Job().apply { cancel() }) { execute(input("1/x^32", "0")) } } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }
}
