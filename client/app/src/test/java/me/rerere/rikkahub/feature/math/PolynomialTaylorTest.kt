package me.rerere.rikkahub.feature.math

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

class PolynomialTaylorTest {
    private fun input(expression: String, center: String, order: String, vararg coefficients: String) = buildJsonObject {
        put("operation", "polynomial_taylor"); put("expression", expression); put("center", center); put("order", order)
        put("claimed_coefficients", JsonArray(coefficients.map(::JsonPrimitive)))
    }
    @Test fun rationalCenterHasExactTruncationAndNonzeroRemainder(): Unit = runBlocking {
        val args = input("(x+1)^3", "1/2", "1", "27/8", "27/4"); val result = MathVerification.verify(args)
        assertEquals("VERIFIED", result.getValue("status").jsonPrimitive.content); assertTrue(MathVerification.accepts(args, result))
        assertEquals(buildJsonObject { put("0", "27/8"); put("1", "27/4"); put("2", "9/2"); put("3", "1") }, result.getValue("shifted_coefficients"))
        val tail = result.getValue("remainder").jsonObject
        assertEquals(2, tail.getValue("factor_power").jsonPrimitive.int)
        assertEquals(buildJsonObject { put("0", "9/2"); put("1", "1") }, tail.getValue("quotient_coefficients"))
        assertFalse(tail.getValue("identically_zero").jsonPrimitive.boolean); assertFalse(result.getValue("truncation_equals_input_globally").jsonPrimitive.boolean)
    }
    @Test fun constantZeroAndOrdersAboveDegreeKeepExplicitZeroCoefficients(): Unit = runBlocking {
        for (args in listOf(input("0", "-3/7", "2", "0", "0", "0"), input("9", "2/3", "0", "9"),
            input("(x+1)^3", "1/2", "4", "27/8", "27/4", "9/2", "1", "0"), input("x", "1/-2", "1", "-2/4", "2/2"))) {
            val result = MathVerification.verify(args); assertEquals("VERIFIED", result.getValue("status").jsonPrimitive.content)
            assertTrue(MathVerification.accepts(args, result)); assertTrue(result.getValue("remainder").jsonObject.getValue("identically_zero").jsonPrimitive.boolean)
        }
    }
    @Test fun matchingSeveralSamplePointsDoesNotProveFalseAllZeroExpansion(): Unit = runBlocking {
        val args = input("x*(x-1)*(x-2)*(x-3)", "0", "4", "0", "0", "0", "0", "0")
        val result = MathVerification.verify(args); assertEquals("DISPROVED", result.getValue("status").jsonPrimitive.content)
        assertEquals(1, result.getValue("first_mismatching_power").jsonPrimitive.int)
        assertEquals(JsonArray(listOf("0", "-6", "11", "-6", "1").map(::JsonPrimitive)), result.getValue("coefficient_differences"))
        assertFalse(MathVerification.accepts(args, JsonObject(result + mapOf("status" to JsonPrimitive("VERIFIED"), "verified" to JsonPrimitive(true)))))
    }
    @Test fun inputDegreeThirtyTwoAndLargeButLegalCenterDoNotUseOldCoefficientBudgetForShift(): Unit = runBlocking {
        val coefficients = Array(33) { if (it == 32) "1" else "0" }
        val args = input("x^32", "0", "32", *coefficients); assertEquals("VERIFIED", MathVerification.verify(args).getValue("status").jsonPrimitive.content)
        val big = BigInteger.ONE.shiftLeft(1023)
        val result = MathVerification.verify(input("x^32", big.toString(), "0", "0"))
        assertEquals("DISPROVED", result.getValue("status").jsonPrimitive.content)
        assertEquals(big.pow(32).toString(), result.getValue("taylor_coefficients").jsonArray.single().jsonPrimitive.content)
        val tooBig = MathVerification.verify(input("x", BigInteger.ONE.shiftLeft(1024).toString(), "0", "0"))
        assertEquals("UNSUPPORTED", tooBig.getValue("status").jsonPrimitive.content)
    }
    @Test fun missingZeroTermsMalformedCoefficientsUnsupportedExpressionsAndOrdersAreRejected(): Unit = runBlocking {
        val good = input("x", "0", "1", "0", "1")
        val bad = listOf(input("x", "0", "2", "0", "1"), input("x", "0", "01", "0", "1"), input("x", "0", "33", "0"), input("x", "0", "-1", "0"),
            input("x", "0", "2147483648", "0"), input("x", "0", "0", "1/0"), input("x", "+inf", "0", "0"), input("1/x", "1", "0", "1"),
            input("sin(x)", "0", "0", "0"), input("0^0", "0", "0", "1"), input("(x-x)^0", "0", "0", "1"), input("x^33", "0", "0", "0"),
            JsonObject(good + ("center" to JsonPrimitive(0))), JsonObject(good + ("claimed_coefficients" to JsonArray(listOf(JsonPrimitive(0), JsonPrimitive("1"))))),
            JsonObject(good + ("owner" to JsonPrimitive("other"))))
        for (args in bad) {
            val result = MathVerification.verify(args); assertEquals(args.toString(), "UNSUPPORTED", result.getValue("status").jsonPrimitive.content)
            assertFalse(result.containsKey("remainder")); assertFalse(result.containsKey("taylor_coefficients"))
        }
    }
    @Test fun forgedRemainderCenterOrderAndComputedCoefficientsCannotPassRecompute(): Unit = runBlocking {
        val args = input("x^3", "1", "1", "1", "3"); val result = MathVerification.verify(args)
        for ((key, value) in listOf("remainder" to JsonObject(emptyMap()), "center" to JsonPrimitive("0"), "order" to JsonPrimitive(3),
            "taylor_coefficients" to JsonArray(listOf(JsonPrimitive("0"))), "truncation_equals_input_globally" to JsonPrimitive(true), "input_sha256" to JsonPrimitive("0".repeat(64)))) {
            assertFalse(key, MathVerification.accepts(args, JsonObject(result + (key to value))))
        }
        assertFalse(MathVerification.accepts(input("x^3", "0", "1", "0", "0"), result))
    }
}
