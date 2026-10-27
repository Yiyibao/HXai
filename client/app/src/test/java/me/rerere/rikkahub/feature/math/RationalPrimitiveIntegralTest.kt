package me.rerere.rikkahub.feature.math

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RationalPrimitiveIntegralTest {
    private fun input(expression: String, primitive: String, lower: String = "0", upper: String = "1", claim: String = "1/2") = buildJsonObject {
        put("operation", "rational_primitive_integral"); put("expression", expression); put("primitive", primitive)
        put("lower", lower); put("upper", upper); put("claimed_value", claim)
    }
    private fun status(result: JsonObject) = result.getValue("status").jsonPrimitive.content
    @Test fun validRationalPrimitiveWithConstantHasExactOrientedEndpointDifference(): Unit = runBlocking {
        for ((args, expected) in listOf(input("1/(x+1)^2", "7-1/(x+1)") to "1/2", input("1/(x+1)^2", "-1/(x+1)", "1", "0", "-1/2") to "-1/2",
            input("1/(x+1)^2", "-1/(x+1)", "1/2", "3/2", "4/15") to "4/15")) {
            val result = MathVerification.verify(args); assertEquals("VERIFIED", status(result)); assertEquals(expected, result.getValue("exact_value").jsonPrimitive.content)
            assertTrue(MathVerification.accepts(args, result)); assertEquals(10, result.getValue("contractVersion").jsonPrimitive.int)
        }
    }
    @Test fun intervalPoleAndIrrationalOrRemovableHolesAreNotFilledByEndpointArithmetic(): Unit = runBlocking {
        for (args in listOf(input("1/x^2", "-1/x", "-1", "1", "-2"), input("1/(x-1)^2", "-1/(x-1)"),
            input("(x^2-2)/(x^2-2)", "x", "1", "2", "1"), input("1", "x+(x^2-2)/(x^2-2)", "1", "2", "1"),
            input("0*(1/x)", "0", "0", "0", "0"), input("x^0", "x", "-1", "1", "2"))) {
            val result = MathVerification.verify(args); assertEquals("UNSUPPORTED", status(result)); assertFalse(result.containsKey("exact_value"))
        }
    }
    @Test fun complexOnlyDenominatorAndExternalHoleAreValidAcrossClosedInterval(): Unit = runBlocking {
        for (args in listOf(input("(1-x^2)/(1+x^2)^2", "x/(1+x^2)"), input("x/x", "x", "1", "2", "1"))) {
            val result = MathVerification.verify(args); assertEquals("VERIFIED", status(result)); assertTrue(MathVerification.accepts(args, result))
            for (check in result.getValue("interval_domain_checks").jsonArray) assertEquals(0, check.jsonObject.getValue("open_interval_root_count").jsonPrimitive.int)
        }
    }
    @Test fun wrongPrimitiveDoesNotDisproveAnActuallyCorrectIntegralClaim(): Unit = runBlocking {
        val args = input("2*x", "0"); val result = MathVerification.verify(JsonObject(args + ("claimed_value" to JsonPrimitive("1"))))
        assertEquals("DISPROVED", status(result)); assertEquals("NOT_EVALUATED", result.getValue("claim_status").jsonPrimitive.content)
        assertEquals("DISPROVED", result.getValue("certificate_status").jsonPrimitive.content); assertFalse(result.containsKey("exact_value"))
    }
    @Test fun wrongClaimAndForgedDomainOrEndpointCertificatesFailRecomputation(): Unit = runBlocking {
        val args = input("1/(x+1)^2", "-1/(x+1)"); val correct = MathVerification.verify(args)
        for ((key, value) in listOf("interval_domain_checks" to JsonArray(emptyList()), "exact_value" to JsonPrimitive("9"), "primitive_derivative" to JsonObject(emptyMap()), "input_sha256" to JsonPrimitive("0".repeat(64)))) {
            assertFalse(key, MathVerification.accepts(args, JsonObject(correct + (key to value))))
        }
        val wrong = JsonObject(args + ("claimed_value" to JsonPrimitive("1"))); val result = MathVerification.verify(wrong)
        assertEquals("DISPROVED", status(result)); assertEquals("-1/2", result.getValue("claim_difference").jsonPrimitive.content)
        assertFalse(MathVerification.accepts(wrong, JsonObject(result + mapOf("status" to JsonPrimitive("VERIFIED"), "verified" to JsonPrimitive(true)))))
    }
    @Test fun equalEndpointStillRequiresDefinedValidPrimitiveAndStrictFields(): Unit = runBlocking {
        val good = input("1/(x+1)^2", "-1/(x+1)", "0", "0", "0")
        assertEquals("VERIFIED", status(MathVerification.verify(good)))
        assertEquals("DISPROVED", status(MathVerification.verify(input("1", "0", "0", "0", "0"))))
        for (args in listOf(input("0^0", "x"), input("sin(x)", "x"), input("1/x", "ln(x)", "1", "2", "0"),
            input("1", "x", "0", "+inf", "0"), JsonObject(good + ("authorized" to JsonPrimitive("true"))), JsonObject(good + ("lower" to JsonPrimitive(0))))) {
            assertEquals("UNSUPPORTED", status(MathVerification.verify(args)))
        }
    }
}
