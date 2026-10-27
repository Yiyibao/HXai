package me.rerere.rikkahub.feature.math

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.math.BigInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class PolynomialUniformSubdivisionRuntimeTest {
    private fun input(expression: String = "x-x^2", lower: String = "0", upper: String = "1", bound: String = "1/4", depth: String = "1", approximation: String = "0") = buildJsonObject {
        put("operation", "polynomial_uniform_bound_subdivision"); put("expression", expression); put("approximation", approximation)
        put("lower", lower); put("upper", upper); put("claimed_bound", bound); put("max_depth", depth)
    }
    private suspend fun execute(args: JsonObject): JsonObject {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "org.hxsi.agent.nativeqa")
        return Json.parseToJsonElement(mathVerifyTool().execute(args).filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
    }
    private suspend fun archive(label: String, args: JsonObject, result: JsonObject) {
        val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "b3m-subdivision-evidence").apply { mkdirs() }
        val file = File(folder, "$label.json"); file.writeText(buildJsonObject { put("input", args); put("result", result) }.toString())
        val saved = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(result.getValue("status").jsonPrimitive.content == "VERIFIED", MathVerification.accepts(saved.getValue("input").jsonObject, saved.getValue("result").jsonObject))
    }
    @Test fun realMidpointPartitionProvesPreviouslyUncertifiedSharpBound(): Unit = runBlocking {
        val args = input(); val result = execute(args)
        assertEquals("VERIFIED", result.getValue("status").jsonPrimitive.content); assertEquals(13, result.getValue("contractVersion").jsonPrimitive.int)
        assertEquals("1/4", result.getValue("certified_absolute_bound").jsonPrimitive.content)
        val leaves = result.getValue("leaves").jsonArray
        assertEquals(listOf("0", "1/2"), leaves.map { it.jsonObject.getValue("lower").jsonPrimitive.content })
        assertEquals(listOf("1/2", "1"), leaves.map { it.jsonObject.getValue("upper").jsonPrimitive.content })
        archive("sharp-midpoint", args, result)
    }
    @Test fun exhaustedDepthAndWrongOrCorrectUncertifiedClaimsNeverTurnIntoSuccess(): Unit = runBlocking {
        for ((label, args) in listOf("depth-zero" to input(depth = "0"), "correct-nondyadic" to input("x*(1-x)^2", "0", "1", "4/27", "8"), "false-bound" to input("x^2", bound = "1/2", depth = "3"))) {
            val result = execute(args); assertEquals("NOT_CERTIFIED", result.getValue("claim_status").jsonPrimitive.content)
            assertTrue(result.getValue("depth_limit_reached").jsonPrimitive.boolean)
            assertFalse(MathVerification.accepts(args, JsonObject(result + mapOf("verified" to JsonPrimitive(true), "status" to JsonPrimitive("VERIFIED")))))
            archive(label, args, result)
        }
    }
    @Test fun rationalNegativeZeroPointAndDegreeThirtyTwoRemainExact(): Unit = runBlocking {
        for ((label, args, bound) in listOf(Triple("rational-negative", input("-x^2", "-1/2", "1/2", "1/4", "8"), "1/4"),
            Triple("zero-error", input("(x+1)^2", "-3/7", "11/7", "0", "8", "x*x+2*x+1"), "0"),
            Triple("point", input("x^2", "2", "2", "4", "8"), "4"), Triple("degree32", input("x^32", bound = "1", depth = "8"), "1"),
            Triple("constant", input("-3", "-9", "7", "4", "8", "1"), "4"))) {
            val result = execute(args); assertEquals("VERIFIED", result.getValue("status").jsonPrimitive.content)
            assertEquals(bound, result.getValue("certified_absolute_bound").jsonPrimitive.content); archive(label, args, result)
        }
    }
    @Test fun fullDepthRetainsAllTwoHundredFiftySixUnprovedLeaves(): Unit = runBlocking {
        val args = input("x^2+1", bound = "0", depth = "8"); val result = execute(args)
        assertEquals("NOT_CERTIFIED", result.getValue("claim_status").jsonPrimitive.content)
        assertEquals("2", result.getValue("certified_absolute_bound").jsonPrimitive.content)
        val leaves = result.getValue("leaves").jsonArray
        assertEquals(256, leaves.size); assertEquals(255, result.getValue("split_count").jsonPrimitive.int)
        assertEquals("0", leaves.first().jsonObject.getValue("lower").jsonPrimitive.content); assertEquals("1", leaves.last().jsonObject.getValue("upper").jsonPrimitive.content)
        for (i in 1 until leaves.size) assertEquals(leaves[i - 1].jsonObject.getValue("upper"), leaves[i].jsonObject.getValue("lower"))
        archive("full-depth-coverage", args, result)
    }
    @Test fun invalidDepthTypesAndOutputLimitNeverIssuePartialCertificateAndCancellationPropagates(): Unit = runBlocking {
        for (args in listOf(input(depth = "9"), input(depth = "01"), JsonObject(input() + ("max_depth" to JsonPrimitive(1))), JsonObject(input() + ("owner" to JsonPrimitive("other"))), input("sin(x)"), input("0^0"), input(lower = "1", upper = "0"), input(bound = "-1"))) {
            val result = execute(args); assertEquals("UNSUPPORTED", result.getValue("status").jsonPrimitive.content); assertFalse(result.containsKey("leaves"))
        }
        val denominator = BigInteger.ONE.shiftLeft(1000).add(BigInteger.ONE)
        val args = input("x^8", "1/$denominator", "2/$denominator", "0", "8"); val oversized = execute(args)
        assertEquals("subdivision_certificate_size_limit", oversized.getValue("reason").jsonPrimitive.content); assertFalse(oversized.containsKey("leaves")); archive("complete-output-limit", args, oversized)
        var cancelled = false
        try { withContext(Job().apply { cancel() }) { execute(input()) } } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }
    @Test fun independentRecomputationRejectsMissingLeavesWrongEndpointsCoefficientsAndInput(): Unit = runBlocking {
        val args = input(); val result = execute(args); val leaves = result.getValue("leaves").jsonArray
        for ((key, value) in listOf("certified_absolute_bound" to JsonPrimitive("0"), "max_depth" to JsonPrimitive(8), "input_sha256" to JsonPrimitive("0".repeat(64)),
            "leaves" to JsonArray(listOf(leaves[0])), "leaves" to JsonArray(leaves.reversed()),
            "leaves" to JsonArray(listOf(JsonObject(leaves[0].jsonObject + ("upper" to JsonPrimitive("1/3"))), leaves[1])),
            "leaves" to JsonArray(listOf(JsonObject(leaves[0].jsonObject + ("bernstein_coefficients" to JsonArray(listOf(JsonPrimitive("0"))))), leaves[1])))) {
            assertFalse(key, MathVerification.accepts(args, JsonObject(result + (key to value))))
        }
        assertFalse(MathVerification.accepts(input(bound = "1/8"), result))
        archive("fresh-full-certificate", args, result)
    }
}
