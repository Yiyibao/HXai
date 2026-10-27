package me.rerere.rikkahub.feature.math

import java.math.BigInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PolynomialSturmBoundTest {
    private fun input(expression: String = "x*(1-x)^2", lower: String = "0", upper: String = "1", bound: String = "4/27", approximation: String = "0") = buildJsonObject {
        put("operation", "polynomial_uniform_bound_sturm"); put("expression", expression); put("approximation", approximation)
        put("lower", lower); put("upper", upper); put("claimed_bound", bound)
    }
    @Test fun tightNonDyadicBoundIsProvedWithoutChangingFiniteSubdivisionUnknown(): Unit = runBlocking {
        val args = input(); val result = MathVerification.verify(args)
        assertEquals("VERIFIED", result.getValue("status").jsonPrimitive.content)
        assertTrue(MathVerification.accepts(args, result))
        val old = MathVerification.verify(JsonObject(args + mapOf("operation" to JsonPrimitive("polynomial_uniform_bound_subdivision"), "max_depth" to JsonPrimitive("8"))))
        assertEquals("NOT_CERTIFIED", old.getValue("claim_status").jsonPrimitive.content)
    }
    @Test fun algebraicTangenciesAndClosedEndpointRootsDoNotRejectCorrectBounds(): Unit = runBlocking {
        for (args in listOf(input("1-(x^2-2)^2/2", "1", "3/2", "1"), input("x", "-1", "1", "1"),
            input("(x+1)^2", "-3/7", "11/7", "0", "x*x+2*x+1"), input("-3", "-9", "7", "4", "1"),
            input("x^32", "0", "1", "1"), input("x^2", "2", "2", "4"))) {
            val result = MathVerification.verify(args)
            assertEquals(args.toString(), "VERIFIED", result.getValue("status").jsonPrimitive.content)
            assertTrue(MathVerification.accepts(args, result))
        }
        val algebraic = MathVerification.verify(input("1-(x^2-2)^2/2", "1", "3/2", "1"))
        val roots = algebraic.getValue("upper_obligation").jsonObject.getValue("roots").jsonArray
        assertEquals(1, roots.size)
        assertEquals("open_rational_interval", roots.single().jsonObject.getValue("isolation").jsonObject.getValue("kind").jsonPrimitive.content)
    }
    @Test fun bothErrorSignsAndNarrowInteriorViolationsYieldExactCounterexamples(): Unit = runBlocking {
        for (args in listOf(input(bound = "147/1000"), input("-x*(1-x)^2", bound = "147/1000"),
            input("1-(x-1/3)^2+1/1000003", bound = "1"), input("x^2", "2", "2", "3"))) {
            val result = MathVerification.verify(args)
            assertEquals("DISPROVED", result.getValue("status").jsonPrimitive.content)
            assertTrue(result.containsKey("counterexample")); assertFalse(MathVerification.accepts(args, result))
            assertFalse(MathVerification.accepts(args, JsonObject(result + mapOf("status" to JsonPrimitive("VERIFIED"), "verified" to JsonPrimitive(true)))))
        }
    }
    @Test fun malformedAndResourceLimitedInputsNeverReturnPartialProofAndCancellationPropagates(): Unit = runBlocking {
        val tiny = BigInteger.ONE.shiftLeft(600)
        for (args in listOf(input("sin(x)"), input("1/x"), input("0^0"), input(lower = "2", upper = "1"), input(bound = "-1"),
            JsonObject(input() + ("owner" to JsonPrimitive("other"))), JsonObject(input() + ("claimed_bound" to JsonPrimitive(1))),
            input("1-(x-1/3)^2+1/$tiny", bound = "1"))) {
            val result = MathVerification.verify(args)
            assertEquals("UNSUPPORTED", result.getValue("status").jsonPrimitive.content)
            assertFalse(result.containsKey("upper_obligation")); assertFalse(result.containsKey("counterexample"))
        }
        val limited = MathVerification.verify(input("1-(x-1/3)^2+1/$tiny", bound = "1"))
        assertEquals("root_isolation_depth_limit", limited.getValue("reason").jsonPrimitive.content)
        var cancelled = false
        try { withContext(Job().apply { cancel() }) { MathVerification.verify(input()) } } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }
    @Test fun durableRecomputationRejectsOmittedCellsChangedSturmAndWrongInput(): Unit = runBlocking {
        val args = input(); val result = MathVerification.verify(args)
        val proof = result.getValue("upper_obligation").jsonObject
        for ((key, value) in listOf("claimed_bound" to JsonPrimitive("0"), "input_sha256" to JsonPrimitive("0".repeat(64)),
            "upper_obligation" to JsonObject(proof + ("cells" to JsonArray(emptyList()))),
            "upper_obligation" to JsonObject(proof + ("sturm_sequence" to JsonArray(emptyList()))),
            "lower_obligation" to JsonObject(proof))) {
            assertFalse(key, MathVerification.accepts(args, JsonObject(result + (key to value))))
        }
        assertFalse(MathVerification.accepts(input(bound = "1/10"), result))
    }
}
