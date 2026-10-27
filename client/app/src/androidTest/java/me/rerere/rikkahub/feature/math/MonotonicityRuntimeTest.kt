package me.rerere.rikkahub.feature.math

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MonotonicityRuntimeTest {
    private fun input(expression: String) = buildJsonObject { put("operation", "rational_monotonicity"); put("expression", expression) }
    private suspend fun execute(args: JsonObject): JsonObject {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "org.hxsi.agent.nativeqa")
        return Json.parseToJsonElement(mathVerifyTool().execute(args).filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
    }
    private suspend fun classify(expression: String): JsonObject {
        val args = input(expression); val result = execute(args)
        assertEquals("VERIFIED", result.getValue("status").jsonPrimitive.content)
        assertTrue(MathVerification.accepts(args, result))
        return result
    }
    private fun endpoint(root: Int?, positive: Boolean = false) = buildJsonObject {
        if (root == null) put("kind", if (positive) "positive_infinity" else "negative_infinity")
        else { put("kind", "root"); put("index", root) }
    }
    private fun interval(lower: Int?, upper: Int?, lowerClosed: Boolean = false, upperClosed: Boolean = false) = buildJsonObject {
        put("lower", endpoint(lower)); put("upper", endpoint(upper, true))
        put("lower_closed", lowerClosed); put("upper_closed", upperClosed)
    }
    private fun point(index: Int, classification: String) = buildJsonObject { put("root_index", index); put("classification", classification) }
    private fun list(result: JsonObject, key: String) = result.getValue(key).jsonArray

    @Test fun cubicFlatStationaryPointDoesNotSplitStrictIncrease(): Unit = runBlocking {
        val result = classify("x^3")
        assertEquals(JsonArray(listOf(interval(null, null))), list(result, "increasing_intervals"))
        assertTrue(list(result, "decreasing_intervals").isEmpty()); assertTrue(list(result, "constant_intervals").isEmpty())
        assertEquals(JsonArray(listOf(point(0, "stationary_non_extremum"))), list(result, "stationary_points"))
        assertFalse(result.getValue("stationary_everywhere").jsonPrimitive.boolean)
    }
    @Test fun turningPointBelongsToBothClosedMonotonicBranches(): Unit = runBlocking {
        val result = classify("x^2")
        assertEquals(JsonArray(listOf(interval(null, 0, upperClosed = true))), list(result, "decreasing_intervals"))
        assertEquals(JsonArray(listOf(interval(0, null, lowerClosed = true))), list(result, "increasing_intervals"))
        assertEquals(JsonArray(listOf(point(0, "local_minimum"))), list(result, "stationary_points"))
    }
    @Test fun polesNeverJoinDisconnectedComponentsOrBecomeStationary(): Unit = runBlocking {
        val result = classify("1/x")
        assertEquals(JsonArray(listOf(interval(null, 0), interval(0, null))), list(result, "decreasing_intervals"))
        assertTrue(list(result, "increasing_intervals").isEmpty()); assertTrue(list(result, "stationary_points").isEmpty())
        assertTrue(result.getValue("derivative_evidence").jsonObject.getValue("roots").jsonArray[0].jsonObject.getValue("excluded").jsonPrimitive.boolean)
    }
    @Test fun constantFunctionsKeepEveryOriginalHoleAndStationaryEverywhere(): Unit = runBlocking {
        for (expression in listOf("x/x", "0*(1/x)", "x^0", "(1/x)/(1/x)")) {
            val result = classify(expression)
            assertEquals(JsonArray(listOf(interval(null, 0), interval(0, null))), list(result, "constant_intervals"))
            assertTrue(result.getValue("stationary_everywhere").jsonPrimitive.boolean)
            assertTrue(list(result, "increasing_intervals").isEmpty()); assertTrue(list(result, "decreasing_intervals").isEmpty())
        }
        assertEquals(JsonArray(listOf(interval(null, null))), list(classify("7"), "constant_intervals"))
    }
    @Test fun cancelledRemovableHoleStillSplitsIncreasingConnectedBranch(): Unit = runBlocking {
        val result = classify("(x^3-x^2)/(x-1)")
        assertEquals(JsonArray(listOf(interval(null, 0, upperClosed = true))), list(result, "decreasing_intervals"))
        assertEquals(JsonArray(listOf(interval(0, 1, lowerClosed = true), interval(1, null))), list(result, "increasing_intervals"))
        assertEquals(JsonArray(listOf(point(0, "local_minimum"))), list(result, "stationary_points"))
    }
    @Test fun algebraicStationaryRootsAndMultipleTurningPointsAreExact(): Unit = runBlocking {
        val result = classify("x^3-2*x")
        assertEquals(JsonArray(listOf(interval(null, 0, upperClosed = true), interval(1, null, lowerClosed = true))), list(result, "increasing_intervals"))
        assertEquals(JsonArray(listOf(interval(0, 1, true, true))), list(result, "decreasing_intervals"))
        assertEquals(JsonArray(listOf(point(0, "local_maximum"), point(1, "local_minimum"))), list(result, "stationary_points"))
        val quartic = classify("x^4-2*x^2")
        assertEquals(JsonArray(listOf(point(0, "local_minimum"), point(1, "local_maximum"), point(2, "local_minimum"))), list(quartic, "stationary_points"))
    }
    @Test fun unsupportedExpressionsAndDerivativeResourcesStayUnknown(): Unit = runBlocking {
        for (expression in listOf("sin(x)", "x+y", "0^0", "1/(x-x)", "x^33", "1/x^32")) {
            val args = input(expression); val result = execute(args)
            assertEquals(expression, "UNSUPPORTED", result.getValue("status").jsonPrimitive.content)
            assertFalse(result.containsKey("increasing_intervals"))
            assertFalse(MathVerification.accepts(args, JsonObject(result + mapOf("status" to JsonPrimitive("VERIFIED"), "verified" to JsonPrimitive(true)))))
        }
        assertEquals("UNSUPPORTED", execute(JsonObject(input("x") + ("authorized" to JsonPrimitive("true")))).getValue("status").jsonPrimitive.content)
    }
    @Test fun durableEvidenceRejectsClosedHolesSplitFlatPointsAndForgedDerivative(): Unit = runBlocking {
        val args = input("x^3"); val result = execute(args)
        assertTrue(MathVerification.accepts(args, result))
        for ((key, value) in listOf("increasing_intervals" to JsonArray(listOf(interval(null, 0), interval(0, null))), "derivative_evidence" to JsonObject(emptyMap()), "stationary_points" to JsonArray(emptyList()), "stationary_everywhere" to JsonPrimitive(true), "scope" to JsonPrimitive("general_proof"), "input_sha256" to JsonPrimitive("0".repeat(64)))) {
            assertFalse(key, MathVerification.accepts(args, JsonObject(result + (key to value))))
        }
        assertFalse(MathVerification.accepts(input("-x^3"), result))
        val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "b3h-monotonicity-evidence").apply { mkdirs() }
        val file = File(folder, "maximal-branches.json")
        file.writeText(buildJsonObject { put("input", args); put("result", result) }.toString())
        val saved = Json.parseToJsonElement(file.readText()).jsonObject
        assertTrue(MathVerification.accepts(saved.getValue("input").jsonObject, saved.getValue("result").jsonObject))
    }
    @Test fun cancellationPropagatesWithoutMonotonicityVerdict(): Unit = runBlocking {
        var cancelled = false
        try { withContext(Job().apply { cancel() }) { execute(input("x^3-2*x")) } } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }
}
