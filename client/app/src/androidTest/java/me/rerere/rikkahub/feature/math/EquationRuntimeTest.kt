package me.rerere.rikkahub.feature.math

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.math.BigInteger

class EquationRuntimeTest {
    private fun input(left: String, right: String) = buildJsonObject { put("operation", "rational_equation"); put("left", left); put("right", right) }
    private suspend fun execute(args: JsonObject): JsonObject {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "org.hxsi.agent.nativeqa")
        return Json.parseToJsonElement(mathVerifyTool().execute(args).filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
    }
    private suspend fun solve(left: String, right: String): JsonObject {
        val args = input(left, right); val result = execute(args)
        assertEquals("VERIFIED", result.getValue("status").jsonPrimitive.content)
        assertTrue(MathVerification.accepts(args, result))
        return result
    }
    private fun endpoint(index: Int?, positive: Boolean = false) = buildJsonObject {
        if (index != null) { put("kind", "root"); put("index", index) }
        else put("kind", if (positive) "positive_infinity" else "negative_infinity")
    }
    private fun interval(lower: Int?, upper: Int?, closed: Boolean = false) = buildJsonObject {
        put("lower", endpoint(lower)); put("upper", endpoint(upper, true))
        put("lower_closed", closed); put("upper_closed", closed)
    }
    private fun points(vararg indices: Int) = JsonArray(indices.map { interval(it, it, true) })
    private fun solution(result: JsonObject) = result.getValue("solution_intervals").jsonArray

    @Test fun irrationalSolutionsAreCompleteExactPointsRatherThanRoundedGuesses(): Unit = runBlocking {
        val result = solve("x^2", "2")
        assertEquals(points(0, 1), solution(result))
        assertEquals(2, result.getValue("real_root_count").jsonPrimitive.int)
        assertEquals(buildJsonObject { put("0", "-2"); put("2", "1") }, result.getValue("critical_squarefree_coefficients"))
        assertTrue(result.getValue("cells").jsonArray.all { !it.jsonObject.getValue("selected").jsonPrimitive.boolean })
        assertEquals(4, result.getValue("contractVersion").jsonPrimitive.int)
    }
    @Test fun crossMultiplicationDoesNotTurnOriginalDenominatorRootsIntoSolutions(): Unit = runBlocking {
        assertEquals(points(0, 2), solution(solve("x", "1/x")))
        val result = solve("(x^2-1)/(x-1)", "0")
        assertEquals(points(0), solution(result))
        assertTrue(result.getValue("roots").jsonArray[1].jsonObject.getValue("excluded").jsonPrimitive.boolean)
        assertEquals(JsonArray(listOf(interval(null, 0), interval(0, null))), solution(solve("(x^2-1)/(x-1)", "x+1")))
    }
    @Test fun identicalValuesKeepBothSidesDifferentHolesAndCorrectAlternativeForms(): Unit = runBlocking {
        val result = solve("x/x", "(x-2)/(x-2)")
        assertEquals(JsonArray(listOf(interval(null, 0), interval(0, 1), interval(1, null))), solution(result))
        assertEquals(buildJsonArray { add(buildJsonObject { put("1", "1") }) }, result.getValue("left_nonzero_polynomials"))
        assertEquals(buildJsonArray { add(buildJsonObject { put("0", "-2"); put("1", "1") }) }, result.getValue("right_nonzero_polynomials"))
        assertEquals(solution(result), solution(solve("(x-2)/(x-2)", "x/x")))
        assertEquals(JsonArray(listOf(interval(null, null))), solution(solve("-(1-x)", "x-1")))
    }
    @Test fun repeatedRootsEmptySetsAndAllRealSolutionsRemainDifferentValidAnswers(): Unit = runBlocking {
        assertEquals(points(0), solution(solve("x^2", "0")))
        assertTrue(solution(solve("x^2+1", "0")).isEmpty())
        assertTrue(solution(solve("1", "0")).isEmpty())
        assertEquals(JsonArray(listOf(interval(null, null))), solution(solve("0", "0")))
    }
    @Test fun nestedReciprocalZeroProductsPowersAndCloseRootsKeepOriginalMeaning(): Unit = runBlocking {
        for (left in listOf("1/(1/x)", "x+0*(1/x)"))
            assertEquals(JsonArray(listOf(interval(null, 0), interval(0, null))), solution(solve(left, "x")))
        assertEquals(JsonArray(listOf(interval(null, 0), interval(0, null))), solution(solve("(1/x)^0", "1")))
        val n = BigInteger.ONE.shiftLeft(80)
        assertEquals(points(0, 1), solution(solve("x*(x-1/$n)", "0")))
    }
    @Test fun malformedUnsupportedAndResourceLimitedRequestsNeverReturnHalfSolutions(): Unit = runBlocking {
        val denominator = (1..32).joinToString("*") { "(x-$it)" }
        val tiny = BigInteger.ONE.shiftLeft(300)
        val requests = listOf(input("0/(x-x)", "0"), input("0^0", "1"), input("sin(x)", "0"), input("x", "y"),
            input("x^32/($denominator)", "0"), input("x*(x-1/$tiny)", "0"),
            JsonObject(input("x", "0") + ("relation" to JsonPrimitive("=="))),
            buildJsonObject { put("operation", "rational_inequality"); put("expression", "x"); put("relation", "==") })
        for (args in requests) {
            val result = execute(args)
            assertEquals("UNSUPPORTED", result.getValue("status").jsonPrimitive.content)
            assertFalse(result.containsKey("solution_intervals"))
            assertFalse(MathVerification.accepts(args, JsonObject(result + mapOf("status" to JsonPrimitive("VERIFIED"), "verified" to JsonPrimitive(true)))))
        }
    }
    @Test fun forgedSideDomainsAndSolutionsFailRecomputeAfterActualDurableReadback(): Unit = runBlocking {
        val args = input("x", "1/x"); val result = execute(args)
        for ((key, value) in listOf("canonical_sides" to buildJsonObject { put("left", "0"); put("right", "0") },
            "left_nonzero_polynomials" to buildJsonArray { add(buildJsonObject { put("1", "1") }) },
            "right_nonzero_polynomials" to JsonArray(emptyList()), "original_nonzero_polynomials" to JsonArray(emptyList()),
            "original_domain" to buildJsonObject { put("ambient", "all_real_numbers") }, "solution_intervals" to points(1),
            "roots" to JsonArray(emptyList()), "scope" to JsonPrimitive("general_proof"), "contractVersion" to JsonPrimitive(3),
            "input_sha256" to JsonPrimitive("0".repeat(64))))
            assertFalse(key, MathVerification.accepts(args, JsonObject(result + (key to value))))
        assertFalse(MathVerification.accepts(input("x", "2/x"), result))
        val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "b3d-equation-evidence").apply { mkdirs() }
        val file = File(folder, "original-domain-solutions.json")
        file.writeText(buildJsonObject { put("input", args); put("result", result) }.toString())
        val saved = Json.parseToJsonElement(file.readText()).jsonObject
        assertTrue(MathVerification.accepts(saved.getValue("input").jsonObject, saved.getValue("result").jsonObject))
    }
}
