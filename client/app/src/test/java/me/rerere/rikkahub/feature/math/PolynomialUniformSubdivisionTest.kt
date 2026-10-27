package me.rerere.rikkahub.feature.math

import java.math.BigInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PolynomialUniformSubdivisionTest {
    private fun input(expression: String = "x-x^2", lower: String = "0", upper: String = "1", bound: String = "1/4", depth: String = "1", approximation: String = "0") = buildJsonObject {
        put("operation", "polynomial_uniform_bound_subdivision"); put("expression", expression); put("approximation", approximation)
        put("lower", lower); put("upper", upper); put("claimed_bound", bound); put("max_depth", depth)
    }
    @Test fun midpointSubdivisionProvesSharpBoundButZeroDepthPreservesUnknown(): Unit = runBlocking {
        val unknown = MathVerification.verify(input(depth = "0"))
        assertEquals("NOT_CERTIFIED", unknown.getValue("claim_status").jsonPrimitive.content)
        assertEquals("1/2", unknown.getValue("certified_absolute_bound").jsonPrimitive.content)
        val args = input(); val result = MathVerification.verify(args)
        assertEquals("VERIFIED", result.getValue("status").jsonPrimitive.content)
        assertEquals("1/4", result.getValue("certified_absolute_bound").jsonPrimitive.content)
        val leaves = result.getValue("leaves").jsonArray
        assertEquals(listOf("0", "1/2"), leaves.map { it.jsonObject.getValue("lower").jsonPrimitive.content })
        assertEquals(listOf("1/2", "1"), leaves.map { it.jsonObject.getValue("upper").jsonPrimitive.content })
        assertTrue(MathVerification.accepts(args, result))
    }
    @Test fun rationalNegativeZeroAndPointIntervalsTerminateWithExactBounds(): Unit = runBlocking {
        for ((args, expected) in listOf(input("-x^2", "-1/2", "1/2", "1/4", "8") to "1/4",
            input("(x+1)^2", "-3/7", "11/7", "0", "8", "x*x+2*x+1") to "0",
            input("x^2", "2", "2", "4", "8") to "4", input("-3", "-9", "7", "4", "8", "1") to "4",
            input("x^32", "0", "1", "1", "8") to "1")) {
            val result = MathVerification.verify(args)
            assertEquals("VERIFIED", result.getValue("status").jsonPrimitive.content)
            assertEquals(expected, result.getValue("certified_absolute_bound").jsonPrimitive.content)
            assertTrue(MathVerification.accepts(args, result))
        }
        val point = MathVerification.verify(input("x^2", "2", "2", "3", "8"))
        assertEquals("point_interval", point.getValue("leaves").jsonArray.single().jsonObject.getValue("stop_reason").jsonPrimitive.content)
        val constant = MathVerification.verify(input("1", bound = "0", depth = "8"))
        assertEquals("constant_error", constant.getValue("leaves").jsonArray.single().jsonObject.getValue("stop_reason").jsonPrimitive.content)
    }
    @Test fun correctNonDyadicExtremumRemainsUnknownAtDepthLimit(): Unit = runBlocking {
        val args = input("x*(1-x)^2", "0", "1", "4/27", "8"); val result = MathVerification.verify(args)
        assertEquals("UNSUPPORTED", result.getValue("status").jsonPrimitive.content)
        assertEquals("NOT_CERTIFIED", result.getValue("claim_status").jsonPrimitive.content)
        assertTrue(result.getValue("depth_limit_reached").jsonPrimitive.boolean)
        assertEquals(8, result.getValue("deepest_leaf").jsonPrimitive.int)
        assertFalse(MathVerification.accepts(args, result))
    }
    @Test fun everyFailingBranchIsRetainedIncludingMaximumLeafBoundary(): Unit = runBlocking {
        val args = input("x^2+1", bound = "0", depth = "8"); val result = MathVerification.verify(args)
        assertEquals("NOT_CERTIFIED", result.getValue("claim_status").jsonPrimitive.content)
        assertEquals("2", result.getValue("certified_absolute_bound").jsonPrimitive.content)
        val leaves = result.getValue("leaves").jsonArray
        assertEquals(256, leaves.size); assertEquals(255, result.getValue("split_count").jsonPrimitive.int)
        assertEquals("0", leaves.first().jsonObject.getValue("lower").jsonPrimitive.content)
        assertEquals("1", leaves.last().jsonObject.getValue("upper").jsonPrimitive.content)
        for (i in 1 until leaves.size) assertEquals(leaves[i - 1].jsonObject.getValue("upper"), leaves[i].jsonObject.getValue("lower"))
        val forged = JsonObject(result + mapOf("leaves" to JsonArray(listOf(leaves.first())), "verified" to JsonPrimitive(true), "status" to JsonPrimitive("VERIFIED")))
        assertFalse(MathVerification.accepts(args, forged))
    }
    @Test fun malformedDepthAndOversizedCompleteCertificateFailClosedAndCancellationPropagates(): Unit = runBlocking {
        for (depth in listOf("-1", "9", "01", "+1", "1.0", " 1", "")) {
            val result = MathVerification.verify(input(depth = depth))
            assertEquals("subdivision_depth_0_to_8_required", result.getValue("reason").jsonPrimitive.content)
            assertFalse(result.containsKey("leaves"))
        }
        for (args in listOf(JsonObject(input() + ("max_depth" to JsonPrimitive(1))), JsonObject(input() + ("owner" to JsonPrimitive("other"))), input("sin(x)"), input("0^0"), input(lower = "1", upper = "0"), input(bound = "-1"))) {
            val result = MathVerification.verify(args); assertEquals("UNSUPPORTED", result.getValue("status").jsonPrimitive.content); assertFalse(result.containsKey("leaves"))
        }
        val denominator = BigInteger.ONE.shiftLeft(1000).add(BigInteger.ONE)
        val oversized = MathVerification.verify(input("x^8", "1/$denominator", "2/$denominator", "0", "8"))
        assertEquals("subdivision_certificate_size_limit", oversized.getValue("reason").jsonPrimitive.content)
        assertFalse(oversized.containsKey("leaves"))
        var cancelled = false
        try { withContext(Job().apply { cancel() }) { MathVerification.verify(input()) } } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }
    @Test fun recomputationRejectsChangedPartitionEndpointsBoundsDepthAndInput(): Unit = runBlocking {
        val args = input(); val result = MathVerification.verify(args); val leaves = result.getValue("leaves").jsonArray
        for ((key, value) in listOf("certified_absolute_bound" to JsonPrimitive("0"), "max_depth" to JsonPrimitive(8), "input_sha256" to JsonPrimitive("0".repeat(64)),
            "leaves" to JsonArray(leaves.reversed()), "leaves" to JsonArray(listOf(leaves[0])),
            "leaves" to JsonArray(listOf(JsonObject(leaves[0].jsonObject + ("upper" to JsonPrimitive("1/3"))), leaves[1])),
            "leaves" to JsonArray(listOf(JsonObject(leaves[0].jsonObject + ("bernstein_coefficients" to JsonArray(listOf(JsonPrimitive("0"))))), leaves[1])))) {
            assertFalse(key, MathVerification.accepts(args, JsonObject(result + (key to value))))
        }
        assertFalse(MathVerification.accepts(input(bound = "1/8"), result))
    }
}
