package me.rerere.rikkahub.feature.math

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PolynomialIntegralRuntimeTest {
    private fun input(expression: String, lower: String, upper: String, claim: String) = buildJsonObject {
        put("operation", "polynomial_definite_integral"); put("expression", expression)
        put("lower", lower); put("upper", upper); put("claimed_value", claim)
    }
    private suspend fun execute(args: JsonObject): JsonObject {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "org.hxsi.agent.nativeqa")
        return Json.parseToJsonElement(mathVerifyTool().execute(args).filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
    }
    private fun status(result: JsonObject) = result.getValue("status").jsonPrimitive.content
    private fun archive(label: String, args: JsonObject, result: JsonObject) {
        val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "b3i-integral-evidence").apply { mkdirs() }
        File(folder, "$label.json").writeText(buildJsonObject { put("input", args); put("result", result) }.toString())
    }

    @Test fun fractionalBoundsAndCoefficientsHaveExactPrimitiveAndEndpointEvidence(): Unit = runBlocking {
        val args = input("x^2-1/2", "-1/2", "3/2", "1/6"); val result = execute(args)
        assertEquals("VERIFIED", status(result)); assertTrue(MathVerification.accepts(args, result))
        assertEquals(9, result.getValue("contractVersion").jsonPrimitive.int)
        assertEquals("1/6", result.getValue("exact_value").jsonPrimitive.content)
        assertEquals(buildJsonObject { put("1", "-1/2"); put("3", "1/3") }, result.getValue("antiderivative_coefficients"))
        assertEquals(buildJsonObject { put("lower", "5/24"); put("upper", "3/8") }, result.getValue("endpoint_values"))
        archive("fractional", args, result)
    }
    @Test fun reversedEqualAndZeroIntegralsRetainOrientationAndValidateIntegrand(): Unit = runBlocking {
        for ((label, args) in listOf("reversed" to input("2*x", "2", "-1", "-3"), "equal" to input("x^32", "-9/7", "-9/7", "0"), "zero" to input("0", "-3", "4", "0"))) {
            val result = execute(args); assertEquals("VERIFIED", status(result)); assertTrue(MathVerification.accepts(args, result)); archive(label, args, result)
        }
        assertEquals("UNSUPPORTED", status(execute(input("1/x", "0", "0", "0"))))
    }
    @Test fun maximumInputDegreeCanProduceDegreeThirtyThreePrimitive(): Unit = runBlocking {
        val args = input("x^32", "0", "1", "1/33"); val result = execute(args)
        assertEquals("VERIFIED", status(result)); assertEquals(buildJsonObject { put("33", "1/33") }, result.getValue("antiderivative_coefficients"))
        archive("degree32", args, result)
    }
    @Test fun wrongClaimIsDisprovedAndCannotBePromotedByForgedStatusOrCertificate(): Unit = runBlocking {
        val args = input("x^2", "0", "1", "1/2"); val result = execute(args)
        assertEquals("DISPROVED", status(result)); assertFalse(result.getValue("verified").jsonPrimitive.boolean)
        assertEquals("1/3", result.getValue("exact_value").jsonPrimitive.content)
        assertEquals("-1/6", result.getValue("claim_difference").jsonPrimitive.content)
        assertFalse(MathVerification.accepts(args, JsonObject(result + mapOf("status" to JsonPrimitive("VERIFIED"), "verified" to JsonPrimitive(true)))))
        val correctArgs = input("x^2", "0", "1", "1/3"); val correct = execute(correctArgs)
        for ((key, value) in listOf("antiderivative_coefficients" to buildJsonObject { put("3", "1/2") }, "endpoint_values" to buildJsonObject { put("lower", "0"); put("upper", "1/2") }, "exact_value" to JsonPrimitive("1/2"), "input_sha256" to JsonPrimitive("0".repeat(64)))) {
            assertFalse(MathVerification.accepts(correctArgs, JsonObject(correct + (key to value))))
        }
        assertFalse(MathVerification.accepts(input("x^2", "1", "0", "-1/3"), correct))
        archive("disproved", args, result)
    }
    @Test fun unsupportedExpressionsEndpointsAndFieldsNeverBecomeIntegralEvidence(): Unit = runBlocking {
        val bad = listOf(input("sin(x)", "0", "1", "0"), input("x^33", "0", "1", "0"), input("x+y", "0", "1", "0"), input("x/x", "0", "1", "1"), input("0^0", "0", "1", "1"),
            input("x", "-inf", "1", "0"), input("x", "0", "1/0", "0"), input("x", "0", "1", "1/0"), input("x", "(0)", "1", "1/2"), input("x", "0", "1", "1".repeat(4000)),
            JsonObject(input("x", "0", "1", "1/2") + ("authorized" to JsonPrimitive("true"))), JsonObject(input("x", "0", "1", "1/2") + ("lower" to JsonPrimitive(0))))
        for (args in bad) {
            val result = execute(args); assertEquals("UNSUPPORTED", status(result)); assertFalse(result.containsKey("exact_value"))
            assertFalse(MathVerification.accepts(args, JsonObject(result + mapOf("status" to JsonPrimitive("VERIFIED"), "verified" to JsonPrimitive(true)))))
        }
    }
    @Test fun cancellationDoesNotReturnAFakeIntegralVerdict(): Unit = runBlocking {
        var cancelled = false
        try { withContext(Job().apply { cancel() }) { execute(input("x^2", "0", "1", "1/3")) } } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }
}
