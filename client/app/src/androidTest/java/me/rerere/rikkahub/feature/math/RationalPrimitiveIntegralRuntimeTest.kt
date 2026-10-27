package me.rerere.rikkahub.feature.math

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RationalPrimitiveIntegralRuntimeTest {
    private fun input(expression: String, primitive: String, lower: String, upper: String, claim: String) = buildJsonObject {
        put("operation", "rational_primitive_integral"); put("expression", expression); put("primitive", primitive)
        put("lower", lower); put("upper", upper); put("claimed_value", claim)
    }
    private suspend fun execute(args: JsonObject): JsonObject {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "org.hxsi.agent.nativeqa")
        return Json.parseToJsonElement(mathVerifyTool().execute(args).filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
    }
    private fun archive(label: String, args: JsonObject, result: JsonObject) {
        val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "b3j-rational-integral-evidence").apply { mkdirs() }
        val file = File(folder, "$label.json"); file.writeText(buildJsonObject { put("input", args); put("result", result) }.toString())
        val saved = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(args, saved.getValue("input")); assertEquals(result, saved.getValue("result"))
    }
    @Test fun suppliedRationalPrimitiveHasExactValueAcrossFractionalBounds(): Unit = runBlocking {
        val args = input("1/(x+1)^2", "7-1/(x+1)", "1/2", "3/2", "4/15"); val result = execute(args)
        assertEquals("VERIFIED", result.getValue("status").jsonPrimitive.content); assertTrue(MathVerification.accepts(args, result))
        assertEquals("4/15", result.getValue("exact_value").jsonPrimitive.content); assertEquals(10, result.getValue("contractVersion").jsonPrimitive.int)
        archive("fractional", args, result)
    }
    @Test fun reversedAndEqualEndpointsPreserveOrientationWithoutSkippingValidation(): Unit = runBlocking {
        for ((label, args) in listOf("reversed" to input("1/(x+1)^2", "-1/(x+1)", "1", "0", "-1/2"), "equal" to input("1/(x+1)^2", "-1/(x+1)", "0", "0", "0"))) {
            val result = execute(args); assertEquals("VERIFIED", result.getValue("status").jsonPrimitive.content); assertTrue(MathVerification.accepts(args, result)); archive(label, args, result)
        }
        val invalid = input("0/x", "0", "0", "0", "0"); val result = execute(invalid)
        assertEquals("UNSUPPORTED", result.getValue("status").jsonPrimitive.content); assertFalse(result.containsKey("exact_value")); archive("equal-hole", invalid, result)
    }
    @Test fun poleAcrossIntervalEndpointPoleAndIrrationalRemovableHoleAreNotProperIntegrals(): Unit = runBlocking {
        for ((label, args) in listOf("cross-pole" to input("1/x^2", "-1/x", "-1", "1", "-2"),
            "endpoint-pole" to input("1/(x-1)^2", "-1/(x-1)", "0", "1", "0"),
            "irrational-hole" to input("(x^2-2)/(x^2-2)", "x", "1", "2", "1"),
            "primitive-hole" to input("1", "x+(x^2-2)/(x^2-2)", "1", "2", "1"))) {
            val result = execute(args); assertEquals("UNSUPPORTED", result.getValue("status").jsonPrimitive.content); assertFalse(result.containsKey("exact_value")); assertFalse(MathVerification.accepts(args, result)); archive(label, args, result)
        }
    }
    @Test fun complexRootsAndOriginalHoleOutsideIntervalDoNotRejectCorrectCertificates(): Unit = runBlocking {
        for ((label, args) in listOf("complex-roots" to input("(1-x^2)/(1+x^2)^2", "x/(1+x^2)", "0", "1", "1/2"),
            "external-hole" to input("x/x", "x", "1", "2", "1"))) {
            val result = execute(args); assertEquals("VERIFIED", result.getValue("status").jsonPrimitive.content); assertTrue(MathVerification.accepts(args, result)); archive(label, args, result)
        }
    }
    @Test fun badPrimitiveIsSeparateFromWrongValueAndForgedCertificatesNeverPass(): Unit = runBlocking {
        val wrongPrimitive = input("2*x", "0", "0", "1", "1"); val result = execute(wrongPrimitive)
        assertEquals("DISPROVED", result.getValue("status").jsonPrimitive.content); assertEquals("NOT_EVALUATED", result.getValue("claim_status").jsonPrimitive.content)
        assertFalse(result.containsKey("exact_value")); archive("bad-primitive-correct-claim", wrongPrimitive, result)
        val wrongValue = input("1/(x+1)^2", "-1/(x+1)", "0", "1", "1"); val disproved = execute(wrongValue)
        assertEquals("DISPROVED", disproved.getValue("status").jsonPrimitive.content); assertEquals("1/2", disproved.getValue("exact_value").jsonPrimitive.content)
        assertFalse(MathVerification.accepts(wrongValue, JsonObject(disproved + mapOf("status" to JsonPrimitive("VERIFIED"), "verified" to JsonPrimitive(true))))); archive("wrong-value", wrongValue, disproved)
        val good = input("1/(x+1)^2", "-1/(x+1)", "0", "1", "1/2"); val verified = execute(good)
        for ((key, value) in listOf("interval_domain_checks" to JsonArray(emptyList()), "closed_interval" to JsonObject(emptyMap()), "endpoint_values" to JsonObject(emptyMap()), "input_sha256" to JsonPrimitive("0".repeat(64)))) {
            assertFalse(key, MathVerification.accepts(good, JsonObject(verified + (key to value))))
        }
        assertFalse(MathVerification.accepts(wrongValue, verified))
    }
    @Test fun malformedAndTranscendentalInputRejectsWithoutFakeValueAndCancellationPropagates(): Unit = runBlocking {
        val base = input("x", "x^2/2", "0", "1", "1/2")
        for (args in listOf(input("sin(x)", "x", "0", "1", "0"), input("1/x", "ln(x)", "1", "2", "0"), input("0^0", "x", "0", "1", "1"),
            input("1", "x", "0", "+inf", "0"), input("1", "x", "0", "1/0", "0"), JsonObject(base + ("owner" to JsonPrimitive("x"))), JsonObject(base + ("lower" to JsonPrimitive(0))))) {
            val result = execute(args); assertEquals("UNSUPPORTED", result.getValue("status").jsonPrimitive.content); assertFalse(result.containsKey("exact_value"))
        }
        var cancelled = false
        try { withContext(Job().apply { cancel() }) { execute(base) } } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }
}
