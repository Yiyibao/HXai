package me.rerere.rikkahub.feature.math

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.math.BigInteger

class PolynomialTaylorRuntimeTest {
    private fun input(expression: String, center: String, order: String, vararg coefficients: String) = buildJsonObject {
        put("operation", "polynomial_taylor"); put("expression", expression); put("center", center); put("order", order)
        put("claimed_coefficients", JsonArray(coefficients.map(::JsonPrimitive)))
    }
    private suspend fun execute(args: JsonObject): JsonObject {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "org.hxsi.agent.nativeqa")
        return Json.parseToJsonElement(mathVerifyTool().execute(args).filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
    }
    private suspend fun archive(label: String, args: JsonObject, result: JsonObject) {
        val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "b3k-taylor-evidence").apply { mkdirs() }
        val file = File(folder, "$label.json"); file.writeText(buildJsonObject { put("input", args); put("result", result) }.toString())
        val saved = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(result.getValue("status").jsonPrimitive.content == "VERIFIED", MathVerification.accepts(saved.getValue("input").jsonObject, saved.getValue("result").jsonObject))
    }
    @Test fun rationalCenteredTruncationHasRealNonzeroRemainderRatherThanFakeGlobalEquality(): Unit = runBlocking {
        val args = input("(x+1)^3", "1/2", "1", "27/8", "27/4"); val result = execute(args)
        assertEquals("VERIFIED", result.getValue("status").jsonPrimitive.content); assertEquals(11, result.getValue("contractVersion").jsonPrimitive.int)
        assertFalse(result.getValue("truncation_equals_input_globally").jsonPrimitive.boolean)
        val tail = result.getValue("remainder").jsonObject
        assertEquals(2, tail.getValue("factor_power").jsonPrimitive.int); assertFalse(tail.getValue("identically_zero").jsonPrimitive.boolean)
        assertEquals(buildJsonObject { put("0", "9/2"); put("1", "1") }, tail.getValue("quotient_coefficients"))
        archive("fractional-truncated", args, result)
    }
    @Test fun correctDifferentExpressionNormalizedFractionsZeroConstantAndPaddedOrderRemainUsable(): Unit = runBlocking {
        for ((label, args) in listOf("expanded" to input("1+3*x+3*x*x+x*x*x", "1/2", "3", "27/8", "27/4", "9/2", "1"),
            "zero" to input("0", "-3/7", "2", "0", "0", "0"), "constant" to input("9", "2/3", "0", "9"),
            "padded" to input("x", "1/-2", "3", "-2/4", "2/2", "0", "0"))) {
            val result = execute(args); assertEquals("VERIFIED", result.getValue("status").jsonPrimitive.content)
            assertTrue(result.getValue("remainder").jsonObject.getValue("identically_zero").jsonPrimitive.boolean); archive(label, args, result)
        }
    }
    @Test fun falseExpansionMatchingSeveralSamplesStillDisprovedByExactCoefficients(): Unit = runBlocking {
        val args = input("x*(x-1)*(x-2)*(x-3)", "0", "4", "0", "0", "0", "0", "0"); val result = execute(args)
        assertEquals("DISPROVED", result.getValue("status").jsonPrimitive.content); assertEquals(1, result.getValue("first_mismatching_power").jsonPrimitive.int)
        assertEquals(JsonArray(listOf("0", "-6", "11", "-6", "1").map(::JsonPrimitive)), result.getValue("taylor_coefficients"))
        assertFalse(MathVerification.accepts(args, JsonObject(result + mapOf("status" to JsonPrimitive("VERIFIED"), "verified" to JsonPrimitive(true)))))
        archive("false-sampling", args, result)
    }
    @Test fun maximumDegreeAndLegalLargeCenterAreBoundedExactlyWhileOverlargeCenterIsUnsupported(): Unit = runBlocking {
        val maximum = input("x^32", "0", "32", *Array(33) { if (it == 32) "1" else "0" })
        val result = execute(maximum); assertEquals("VERIFIED", result.getValue("status").jsonPrimitive.content); archive("degree32", maximum, result)
        val big = BigInteger.ONE.shiftLeft(1023); val args = input("x^32", big.toString(), "0", "0"); val actual = execute(args)
        assertEquals("DISPROVED", actual.getValue("status").jsonPrimitive.content)
        assertEquals(big.pow(32).toString(), actual.getValue("taylor_coefficients").jsonArray.single().jsonPrimitive.content); archive("large-center", args, actual)
        val unsupported = input("x", BigInteger.ONE.shiftLeft(1024).toString(), "0", "0"); val rejected = execute(unsupported)
        assertEquals("UNSUPPORTED", rejected.getValue("status").jsonPrimitive.content); assertFalse(rejected.containsKey("taylor_coefficients")); archive("oversized-center", unsupported, rejected)
    }
    @Test fun malformedOrderIncompleteArrayExtraOwnerAndUnsupportedExpressionCannotIssueExpansion(): Unit = runBlocking {
        val base = input("x", "0", "1", "0", "1")
        for (args in listOf(input("x", "0", "2", "0", "1"), input("x", "0", "01", "0", "1"), input("x", "0", "33", "0"), input("x", "0", "-1", "0"),
            input("x", "0", "2147483648", "0"), input("x", "0", "0", "1/0"), input("x", "+inf", "0", "0"), input("1/x", "1", "0", "1"),
            input("sin(x)", "0", "0", "0"), input("0^0", "0", "0", "1"), input("(x-x)^0", "0", "0", "1"),
            JsonObject(base + ("order" to JsonPrimitive(1))), JsonObject(base + ("claimed_coefficients" to JsonArray(listOf(JsonPrimitive(0), JsonPrimitive("1"))))),
            JsonObject(base + ("claimed_coefficients" to JsonPrimitive("0,1"))), JsonObject(base + ("owner" to JsonPrimitive("other"))))) {
            val result = execute(args); assertEquals("UNSUPPORTED", result.getValue("status").jsonPrimitive.content); assertFalse(result.containsKey("remainder"))
        }
    }
    @Test fun independentRecomputationRejectsForgedRemainderShiftAndStatusAndPropagatesCancellation(): Unit = runBlocking {
        val args = input("x^3", "1", "1", "1", "3"); val result = execute(args)
        assertTrue(MathVerification.accepts(args, result))
        for ((key, value) in listOf("remainder" to JsonObject(emptyMap()), "shifted_coefficients" to JsonObject(emptyMap()), "center" to JsonPrimitive("0"),
            "order" to JsonPrimitive(3), "truncation_equals_input_globally" to JsonPrimitive(true), "input_sha256" to JsonPrimitive("0".repeat(64)))) {
            assertFalse(key, MathVerification.accepts(args, JsonObject(result + (key to value))))
        }
        assertFalse(MathVerification.accepts(input("x^3", "0", "1", "0", "0"), result)); archive("fresh-recompute", args, result)
        var cancelled = false
        try { withContext(Job().apply { cancel() }) { execute(args) } } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }
}
