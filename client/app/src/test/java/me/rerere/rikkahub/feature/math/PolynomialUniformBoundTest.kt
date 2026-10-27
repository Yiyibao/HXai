package me.rerere.rikkahub.feature.math

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PolynomialUniformBoundTest {
    private fun input(expression: String, approximation: String, lower: String, upper: String, bound: String) = buildJsonObject {
        put("operation", "polynomial_uniform_bound"); put("expression", expression); put("approximation", approximation)
        put("lower", lower); put("upper", upper); put("claimed_bound", bound)
    }
    @Test fun taylorTruncationDifferenceHasExactBernsteinErrorCertificate(): Unit = runBlocking {
        val args = input("(x+1)^3", "1+3*x", "0", "1", "4"); val result = MathVerification.verify(args)
        assertEquals("VERIFIED", result.getValue("status").jsonPrimitive.content); assertTrue(MathVerification.accepts(args, result))
        assertEquals(JsonArray(listOf("0", "0", "1", "4").map(::JsonPrimitive)), result.getValue("bernstein_coefficients"))
        assertEquals("4", result.getValue("certified_absolute_bound").jsonPrimitive.content)
    }
    @Test fun rationalAndNegativeIntervalsUseAffineScaleAndAbsoluteHull(): Unit = runBlocking {
        for ((args, bound) in listOf(input("x^2", "0", "-1", "1", "1") to "1", input("x^2", "0", "-1/2", "1/2", "1/4") to "1/4", input("-3", "1", "-9", "7", "4") to "4")) {
            val result = MathVerification.verify(args); assertEquals("VERIFIED", result.getValue("status").jsonPrimitive.content)
            assertEquals(bound, result.getValue("certified_absolute_bound").jsonPrimitive.content); assertTrue(MathVerification.accepts(args, result))
        }
    }
    @Test fun correctTighterBoundIsUncertifiedNotMathematicallyDisproved(): Unit = runBlocking {
        val args = input("x-x^2", "0", "0", "1", "1/4"); val result = MathVerification.verify(args)
        assertEquals("UNSUPPORTED", result.getValue("status").jsonPrimitive.content); assertEquals("NOT_CERTIFIED", result.getValue("claim_status").jsonPrimitive.content)
        assertEquals("1/2", result.getValue("certified_absolute_bound").jsonPrimitive.content)
        assertFalse(MathVerification.accepts(args, JsonObject(result + mapOf("status" to JsonPrimitive("VERIFIED"), "verified" to JsonPrimitive(true)))))
        assertEquals("VERIFIED", MathVerification.verify(input("x-x^2", "0", "0", "1", "1/2")).getValue("status").jsonPrimitive.content)
    }
    @Test fun identicalPolynomialPointIntervalsAndHighestDegreeRemainExact(): Unit = runBlocking {
        for ((args, bound) in listOf(input("(x+1)^2", "x*x+2*x+1", "-3/7", "11/7", "0") to "0", input("x^2", "0", "2", "2", "4") to "4", input("x^32", "0", "0", "1", "1") to "1")) {
            val result = MathVerification.verify(args); assertEquals("VERIFIED", result.getValue("status").jsonPrimitive.content)
            assertEquals(bound, result.getValue("certified_absolute_bound").jsonPrimitive.content); assertTrue(MathVerification.accepts(args, result))
        }
    }
    @Test fun malformedBoundsEndpointsTypesAndUnsupportedFunctionsNeverGrantBound(): Unit = runBlocking {
        val base = input("x", "0", "0", "1", "1")
        for (args in listOf(input("x", "0", "1", "0", "1"), input("x", "0", "0", "1", "-1"), input("x", "0", "0", "+inf", "1"), input("x", "0", "0", "1", "1/0"),
            input("sin(x)", "0", "0", "1", "1"), input("x", "1/x", "1", "2", "1"), input("0^0", "0", "0", "1", "1"), input("x^33", "0", "0", "1", "1"),
            JsonObject(base + ("claimed_bound" to JsonPrimitive(1))), JsonObject(base + ("owner" to JsonPrimitive("other"))))) {
            val result = MathVerification.verify(args); assertEquals("UNSUPPORTED", result.getValue("status").jsonPrimitive.content)
            assertFalse(result.containsKey("bernstein_coefficients")); assertFalse(MathVerification.accepts(args, result))
        }
    }
    @Test fun forgedHullCoefficientsAndInputCannotBeAcceptedByStatusAlone(): Unit = runBlocking {
        val args = input("x^2", "0", "-1", "1", "1"); val result = MathVerification.verify(args)
        for ((key, value) in listOf("certified_absolute_bound" to JsonPrimitive("0"), "bernstein_coefficients" to JsonArray(listOf(JsonPrimitive("0"))),
            "error_enclosure" to JsonObject(emptyMap()), "unit_interval_power_coefficients" to JsonObject(emptyMap()), "input_sha256" to JsonPrimitive("0".repeat(64)))) {
            assertFalse(key, MathVerification.accepts(args, JsonObject(result + (key to value))))
        }
        assertFalse(MathVerification.accepts(input("x^2", "0", "-1", "1", "0"), result))
    }
}
