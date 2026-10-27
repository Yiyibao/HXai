package me.rerere.rikkahub.feature.math

import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CertifiedCalculusRuntimeTest {
    private suspend fun calculate(action: String, expression: String, lower: String, upper: String,
        digits: String = "12", bindings: JsonObject = buildJsonObject {}) = MathVerification.verify(buildJsonObject {
        put("operation", "professional_calculus")
        put("request", buildJsonObject {
            put("action", action); put("expression", expression); put("variable", "x")
            put("lower", lower); put("upper", upper); put("digits", digits); put("bindings", bindings)
        }.toString())
    })
    private fun encloses(result: JsonObject, value: BigDecimal, tolerance: BigDecimal = BigDecimal("1e-12")) {
        assertEquals(JsonPrimitive("VERIFIED"), result["status"])
        val lower = BigDecimal(result.getValue("lower").jsonPrimitive.content)
        val upper = BigDecimal(result.getValue("upper").jsonPrimitive.content)
        assertTrue("$lower <= $value <= $upper", lower <= value && upper >= value)
        assertTrue(upper - lower <= tolerance)
    }
    private fun enclosesRational(result: JsonObject, value: String) {
        assertEquals(JsonPrimitive("VERIFIED"), result["status"])
        val exact = ProfessionalScalar.parse(value)
        val lower = ProfessionalScalar.parse(result.getValue("lower").jsonPrimitive.content)
        val upper = ProfessionalScalar.parse(result.getValue("upper").jsonPrimitive.content)
        assertTrue(lower <= exact && upper >= exact)
        assertTrue(BigDecimal(result.getValue("upper").jsonPrimitive.content) - BigDecimal(result.getValue("lower").jsonPrimitive.content) <= BigDecimal("1e-12"))
    }
    @Test fun rationalEndpointsAndOrientationCannotChangeAnIntegral(): Unit = runBlocking {
        // Exact rational comparisons do not treat a truncated decimal as the true integral.
        val forward = calculate("integral", "x^6", "-2", "1")
        val reverse = calculate("integral", "x^6", "1", "-2")
        enclosesRational(forward, "129/7")
        enclosesRational(reverse, "-129/7")
        enclosesRational(calculate("integral", "3*x^2", "1/3", "4/3"), "7/3")
    }
    @Test fun analyticRemainderContainsTheTranscendentalIntegral(): Unit = runBlocking {
        val result = calculate("integral", "exp(x)", "0", "1")
        encloses(result, BigDecimal("1.71828182845904523536028747135266249775724709369995"))
        val parameters = buildJsonObject { put("u", buildJsonArray { add("1"); add("2") }) }
        assertEquals(JsonPrimitive("UNSUPPORTED"), calculate("integral", "u*x", "0", "1", bindings = parameters)["status"])
    }
    @Test fun holesAndUnprovedSmoothnessNeverBecomeImproperIntegralSuccess(): Unit = runBlocking {
        for (expression in listOf("x/x", "0*(1/x)", "abs(x)", "sqrt(x^2)")) {
            assertEquals(expression, JsonPrimitive("UNSUPPORTED"), calculate("integral", expression, "-1", "1")["status"])
        }
        // This improper integral exists, but this C8 closed-interval method must not certify it.
        assertEquals(JsonPrimitive("UNSUPPORTED"), calculate("integral", "1/sqrt(x)", "0", "1")["status"])
    }
    @Test fun uniqueRootRequiresExistenceAndNotJustSmallResidual(): Unit = runBlocking {
        val result = calculate("root", "x^2-2", "1", "2")
        encloses(result, BigDecimal("1.41421356237309504880168872420969807856967187537694"))
        assertEquals(JsonPrimitive("UNIQUE_ROOT_IN_REQUESTED_INTERVAL"), result["classification"])
        val noRoot = calculate("root", "x^2+1", "1", "2")
        assertEquals(JsonPrimitive("DISPROVED"), noRoot["status"])
        assertEquals(JsonPrimitive("NO_ROOT_IN_REQUESTED_INTERVAL"), noRoot["classification"])
        assertEquals(JsonPrimitive("UNSUPPORTED"), calculate("root", "x^2-1", "-2", "2")["status"])
        assertEquals(JsonPrimitive("UNSUPPORTED"), calculate("root", "1/x", "-1", "1")["status"])
        encloses(calculate("root", "x-1", "1", "3"), BigDecimal.ONE)
    }
}
