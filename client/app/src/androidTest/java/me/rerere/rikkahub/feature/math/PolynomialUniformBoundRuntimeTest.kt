package me.rerere.rikkahub.feature.math

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PolynomialUniformBoundRuntimeTest {
    private fun input(expression: String, approximation: String, lower: String, upper: String, bound: String) = buildJsonObject {
        put("operation", "polynomial_uniform_bound"); put("expression", expression); put("approximation", approximation)
        put("lower", lower); put("upper", upper); put("claimed_bound", bound)
    }
    private suspend fun execute(args: JsonObject): JsonObject {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "org.hxsi.agent.nativeqa")
        return Json.parseToJsonElement(mathVerifyTool().execute(args).filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
    }
    private suspend fun archive(label: String, args: JsonObject, result: JsonObject) {
        val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "b3l-bound-evidence").apply { mkdirs() }
        val file = File(folder, "$label.json"); file.writeText(buildJsonObject { put("input", args); put("result", result) }.toString())
        val saved = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(result.getValue("status").jsonPrimitive.content == "VERIFIED", MathVerification.accepts(saved.getValue("input").jsonObject, saved.getValue("result").jsonObject))
    }
    @Test fun realTaylorApproximationHasFullIntervalCertificateNotSampling(): Unit = runBlocking {
        val args = input("(x+1)^3", "1+3*x", "0", "1", "4"); val result = execute(args)
        assertEquals("VERIFIED", result.getValue("status").jsonPrimitive.content); assertEquals(12, result.getValue("contractVersion").jsonPrimitive.int)
        assertEquals(JsonArray(listOf("0", "0", "1", "4").map(::JsonPrimitive)), result.getValue("bernstein_coefficients")); archive("taylor-error", args, result)
    }
    @Test fun rationalIntervalAndNegativeErrorsUseExactAbsoluteEnclosure(): Unit = runBlocking {
        for ((label, args, bound) in listOf(Triple("rational", input("x^2", "0", "-1/2", "1/2", "1/4"), "1/4"), Triple("negative", input("-3", "1", "-9", "7", "4"), "4"))) {
            val result = execute(args); assertEquals("VERIFIED", result.getValue("status").jsonPrimitive.content)
            assertEquals(bound, result.getValue("certified_absolute_bound").jsonPrimitive.content); archive(label, args, result)
        }
    }
    @Test fun mathematicallyCorrectTighterBoundIsUnknownRatherThanWrongAndLargerBoundCanBeProved(): Unit = runBlocking {
        val tight = input("x-x^2", "0", "0", "1", "1/4"); val result = execute(tight)
        assertEquals("UNSUPPORTED", result.getValue("status").jsonPrimitive.content); assertEquals("NOT_CERTIFIED", result.getValue("claim_status").jsonPrimitive.content)
        assertEquals("1/2", result.getValue("certified_absolute_bound").jsonPrimitive.content)
        assertFalse(MathVerification.accepts(tight, JsonObject(result + mapOf("status" to JsonPrimitive("VERIFIED"), "verified" to JsonPrimitive(true))))); archive("correct-tight-not-certified", tight, result)
        val loose = input("x-x^2", "0", "0", "1", "1/2"); val accepted = execute(loose)
        assertEquals("VERIFIED", accepted.getValue("status").jsonPrimitive.content); archive("sufficient-bound", loose, accepted)
    }
    @Test fun zeroDifferencePointIntervalAndDegreeThirtyTwoHaveExactBoundaryBehavior(): Unit = runBlocking {
        for ((label, args, bound) in listOf(Triple("zero-error", input("(x+1)^2", "x*x+2*x+1", "-3/7", "11/7", "0"), "0"),
            Triple("point", input("x^2", "0", "2", "2", "4"), "4"), Triple("degree32", input("x^32", "0", "0", "1", "1"), "1"))) {
            val result = execute(args); assertEquals("VERIFIED", result.getValue("status").jsonPrimitive.content)
            assertEquals(bound, result.getValue("certified_absolute_bound").jsonPrimitive.content); archive(label, args, result)
        }
    }
    @Test fun invalidInputCannotIssueConvexHullAndCancellationPropagates(): Unit = runBlocking {
        val base = input("x", "0", "0", "1", "1")
        for (args in listOf(input("x", "0", "1", "0", "1"), input("x", "0", "0", "1", "-1"), input("x", "0", "0", "+inf", "1"),
            input("sin(x)", "0", "0", "1", "1"), input("x", "1/x", "1", "2", "1"), input("0^0", "0", "0", "1", "1"),
            JsonObject(base + ("claimed_bound" to JsonPrimitive(1))), JsonObject(base + ("owner" to JsonPrimitive("other"))))) {
            val result = execute(args); assertEquals("UNSUPPORTED", result.getValue("status").jsonPrimitive.content); assertFalse(result.containsKey("bernstein_coefficients"))
        }
        var cancelled = false
        try { withContext(Job().apply { cancel() }) { execute(base) } } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }
    @Test fun recomputationRejectsForgedEnclosureCoefficientsClaimAndHash(): Unit = runBlocking {
        val args = input("x^2", "0", "-1", "1", "1"); val result = execute(args)
        for ((key, value) in listOf("certified_absolute_bound" to JsonPrimitive("0"), "bernstein_coefficients" to JsonArray(listOf(JsonPrimitive("0"))),
            "error_enclosure" to JsonObject(emptyMap()), "claimed_bound" to JsonPrimitive("0"), "input_sha256" to JsonPrimitive("0".repeat(64)))) {
            assertFalse(key, MathVerification.accepts(args, JsonObject(result + (key to value))))
        }
        assertFalse(MathVerification.accepts(input("x^2", "0", "-1", "1", "0"), result)); archive("fresh-enclosure", args, result)
    }
}
