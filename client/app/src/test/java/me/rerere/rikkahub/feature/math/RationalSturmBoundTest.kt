package me.rerere.rikkahub.feature.math

import java.math.BigInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RationalSturmBoundTest {
    private fun input(expression: String = "1/(x+1)", lower: String = "0", upper: String = "1", bound: String = "1", approximation: String = "0") = buildJsonObject {
        put("operation", "rational_uniform_bound_sturm"); put("expression", expression); put("approximation", approximation)
        put("lower", lower); put("upper", upper); put("claimed_bound", bound)
    }
    @Test fun positiveAndNegativeDenominatorsAndBothErrorSidesAreExact(): Unit = runBlocking {
        for (args in listOf(input(), input("1/(x-2)"), input("1/(x-2)", "-1", "1", "4/3", "1/(x+2)"))) {
            val result = MathVerification.verify(args)
            assertEquals(args.toString(), "VERIFIED", result.getValue("status").jsonPrimitive.content)
            assertTrue(MathVerification.accepts(args, result))
        }
        for (args in listOf(input(bound = "1/2"), input("1/(x-2)", bound = "1/2"))) {
            val result = MathVerification.verify(args)
            assertEquals("DISPROVED", result.getValue("status").jsonPrimitive.content)
            assertTrue(result.containsKey("counterexample")); assertFalse(MathVerification.accepts(args, result))
        }
    }
    @Test fun cancellationAndZeroErrorNeverFillSyntacticHolesOrEndpointPoles(): Unit = runBlocking {
        for (args in listOf(input("x/x", "-1", "1"), input("0/(x-1)", "0", "2", "0"),
            input("1/(x-1)", "0", "2", "0", "1/(x-1)"), input("(x^2-2)/(x^2-2)", "1", "2"),
            input("x^0", "-1", "1"), input("1/x", "0", "1", "100"), input("x/x", "0", "0"),
            input("0", "0", "2", "0", "0/(x-1)"))) {
            val result = MathVerification.verify(args)
            assertEquals("original_domain_not_defined_on_closed_interval", result.getValue("reason").jsonPrimitive.content)
            assertFalse(result.containsKey("interval_domain_checks")); assertFalse(result.containsKey("upper_obligation"))
        }
    }
    @Test fun legalHolesOutsideIntervalComplexOnlyDivisorsAndSingletonsRemainUsable(): Unit = runBlocking {
        for (args in listOf(input("x/x", "1", "2"), input("(x^2+1)/(x^2+1)", "-1", "1"),
            input("x^0", "1", "2"), input("1/(x+1)", "1", "1", "1/2"), input("1/(x+1)", "0", "1", "0", "1/(1+x)"))) {
            val result = MathVerification.verify(args)
            assertEquals("VERIFIED", result.getValue("status").jsonPrimitive.content); assertTrue(MathVerification.accepts(args, result))
        }
    }
    @Test fun algebraicAndNonDyadicTangenciesAreProvedButNarrowViolationIsDisproved(): Unit = runBlocking {
        for (args in listOf(input("1/(1+(x^2-2)^2)", "1", "3/2"), input("1/(1+(3*x-1)^2)"), input("1/(x^16+1)", "-1", "1"))) {
            assertEquals("VERIFIED", MathVerification.verify(args).getValue("status").jsonPrimitive.content)
        }
        val args = input("1/((x-1/3)^2+1/1000003)", bound = "1000000")
        val result = MathVerification.verify(args)
        assertEquals("DISPROVED", result.getValue("status").jsonPrimitive.content)
        assertFalse(MathVerification.accepts(args, JsonObject(result + mapOf("status" to JsonPrimitive("VERIFIED"), "verified" to JsonPrimitive(true)))))
    }
    @Test fun degreeAndIsolationLimitsMalformedFieldsAndCancellationFailClosed(): Unit = runBlocking {
        val degree = MathVerification.verify(input("1/(x^17+2)"))
        assertEquals("bound_obligation_degree_limit", degree.getValue("reason").jsonPrimitive.content)
        val tiny = BigInteger.ONE.shiftLeft(600)
        for (args in listOf(input("sin(x)"), input("0^0"), input(lower = "1", upper = "0"), input(bound = "-1"),
            JsonObject(input() + ("claimed_bound" to JsonPrimitive(1))), JsonObject(input() + ("owner" to JsonPrimitive("other"))),
            input("1-(x-1/3)^2+1/$tiny"))) {
            val result = MathVerification.verify(args)
            assertEquals("UNSUPPORTED", result.getValue("status").jsonPrimitive.content)
            assertFalse(result.containsKey("upper_obligation")); assertFalse(result.containsKey("counterexample"))
        }
        var cancelled = false
        try { withContext(Job().apply { cancel() }) { MathVerification.verify(input()) } } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }
    @Test fun recomputationRejectsOmittedDomainOppositeSideChangedDenominatorAndInput(): Unit = runBlocking {
        val args = input(); val result = MathVerification.verify(args)
        for ((key, value) in listOf("interval_domain_checks" to JsonArray(emptyList()),
            "lower_obligation" to result.getValue("upper_obligation"), "error" to buildJsonObject { put("numerator", JsonObject(emptyMap())); put("denominator", JsonObject(emptyMap())) },
            "input_sha256" to JsonPrimitive("0".repeat(64)))) {
            assertFalse(key, MathVerification.accepts(args, JsonObject(result + (key to value))))
        }
        assertFalse(MathVerification.accepts(args, JsonObject(result - "source_nonzero_polynomials")))
        assertFalse(MathVerification.accepts(input(bound = "1/2"), result))
    }
}
