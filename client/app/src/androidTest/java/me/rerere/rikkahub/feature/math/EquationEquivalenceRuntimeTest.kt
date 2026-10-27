package me.rerere.rikkahub.feature.math

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EquationEquivalenceRuntimeTest {
    private fun input(bl: String, br: String, al: String, ar: String) = buildJsonObject {
        put("operation", "rational_equation_equivalence")
        put("before_left", bl); put("before_right", br); put("after_left", al); put("after_right", ar)
    }
    private suspend fun execute(args: JsonObject): JsonObject {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "org.hxsi.agent.nativeqa")
        return Json.parseToJsonElement(mathVerifyTool().execute(args).filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
    }
    private suspend fun equivalent(bl: String, br: String, al: String, ar: String): JsonObject {
        val args = input(bl, br, al, ar); val result = execute(args)
        assertEquals("VERIFIED", result.getValue("status").jsonPrimitive.content)
        assertTrue(result.getValue("equivalent").jsonPrimitive.boolean)
        assertTrue(MathVerification.accepts(args, result))
        return result
    }
    private fun count(result: JsonObject, key: String) = result.getValue(key).jsonObject.getValue("real_root_count").jsonPrimitive.int

    @Test fun multiplyingOrDividingByVariableCannotAddOrLoseRoots(): Unit = runBlocking {
        val added = execute(input("x", "1", "x*x", "x"))
        assertEquals("DISPROVED", added.getValue("status").jsonPrimitive.content)
        assertEquals(1, count(added, "added_solutions")); assertEquals(0, count(added, "removed_solutions"))
        val removed = execute(input("x^2", "1", "x", "1"))
        assertEquals("DISPROVED", removed.getValue("status").jsonPrimitive.content)
        assertEquals(0, count(removed, "added_solutions")); assertEquals(1, count(removed, "removed_solutions"))
        val mixed = execute(input("x", "1", "0*x", "0"))
        assertEquals("DISPROVED", mixed.getValue("status").jsonPrimitive.content)
        assertEquals("finite_vs_cofinite_real_solution_sets", mixed.getValue("proof_kind").jsonPrimitive.content)
    }
    @Test fun cancelledOriginalDomainsAreNotIntersectedAcrossEquations(): Unit = runBlocking {
        val result = execute(input("x/x", "1", "x", "x"))
        assertEquals("DISPROVED", result.getValue("status").jsonPrimitive.content)
        assertEquals(1, count(result, "added_solutions")); assertEquals(0, count(result, "removed_solutions"))
        val reverse = execute(input("1", "1", "x/x", "1"))
        assertEquals(1, count(reverse, "removed_solutions")); assertEquals(0, count(reverse, "added_solutions"))
        equivalent("(x^2-1)/(x-1)", "0", "x+1", "0")
    }
    @Test fun emptySetsAndComplexOnlyExtraFactorsRemainEquivalent(): Unit = runBlocking {
        equivalent("x^2", "-1", "1", "0")
        equivalent("1/x", "0", "x^2+1", "0")
        equivalent("1", "1", "(x^2+1)/(x^2+1)", "1")
        equivalent("x", "0", "x*(x^2+1)", "0")
        equivalent("(x^2-2)^2", "0", "x^2", "2")
    }
    @Test fun irrationalGainedAndLostSolutionsHaveExactCertificates(): Unit = runBlocking {
        val result = execute(input("1", "1", "(x^2-2)/(x^2-2)", "1"))
        assertEquals("DISPROVED", result.getValue("status").jsonPrimitive.content)
        assertEquals(2, count(result, "removed_solutions")); assertEquals(0, count(result, "added_solutions"))
        val gained = execute(input("1", "0", "x^2", "2"))
        assertEquals(2, count(gained, "added_solutions"))
        val polynomial = buildJsonObject { put("0", "-2"); put("2", "1") }
        assertEquals(polynomial, gained.getValue("added_solutions").jsonObject.getValue("polynomial"))
    }
    @Test fun zeroProductsNestedReciprocalsAndBothSideConditionsSurvive(): Unit = runBlocking {
        equivalent("1/(1/x)", "0", "1", "0")
        equivalent("0*(1/x)", "0", "x/x", "1")
        val result = execute(input("1", "x/x", "1", "1"))
        assertEquals(1, count(result, "added_solutions"))
        assertEquals(JsonArray(listOf(buildJsonObject { put("1", "1") })), result.getValue("before").jsonObject.getValue("right_nonzero_polynomials"))
        equivalent("(1/x)^0", "1", "x^0", "1")
    }
    @Test fun unsupportedFieldsAndResourcesNeverProveEquivalence(): Unit = runBlocking {
        for (expression in listOf("sin(x)", "x+y", "0^0", "x^33", "1/(x-x)", "1.5/x", "0*(1/(x^17-1))+0*(1/(x^17+1))")) {
            val args = input(expression, "0", "x", "0"); val result = execute(args)
            assertEquals(expression, "UNSUPPORTED", result.getValue("status").jsonPrimitive.content)
            assertFalse(MathVerification.accepts(args, JsonObject(result + mapOf("status" to JsonPrimitive("VERIFIED"), "verified" to JsonPrimitive(true)))))
        }
        val args = JsonObject(input("x", "0", "x", "0") + ("authorized" to JsonPrimitive("true")))
        assertEquals("UNSUPPORTED", execute(args).getValue("status").jsonPrimitive.content)
        assertEquals("UNSUPPORTED", execute(JsonObject(input("x", "0", "x", "0") - "after_right")).getValue("status").jsonPrimitive.content)
    }
    @Test fun independentlyReadEvidenceRejectsForgedDomainsAndAddedRoots(): Unit = runBlocking {
        val args = input("x", "0", "x*(x^2+1)", "0"); val result = execute(args)
        assertTrue(MathVerification.accepts(args, result))
        for ((key, value) in listOf("before" to JsonObject(emptyMap()), "added_solutions" to JsonObject(emptyMap()), "equivalent" to JsonPrimitive(false), "input_sha256" to JsonPrimitive("0".repeat(64)), "scope" to JsonPrimitive("general_proof"), "contractVersion" to JsonPrimitive(4))) {
            assertFalse(key, MathVerification.accepts(args, JsonObject(result + (key to value))))
        }
        assertFalse(MathVerification.accepts(input("x", "0", "x-1", "0"), result))
        val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "b3f-equivalence-evidence").apply { mkdirs() }
        val file = File(folder, "original-domain-equivalence.json")
        file.writeText(buildJsonObject { put("input", args); put("result", result) }.toString())
        val saved = Json.parseToJsonElement(file.readText()).jsonObject
        assertTrue(MathVerification.accepts(saved.getValue("input").jsonObject, saved.getValue("result").jsonObject))
    }
    @Test fun cancellationPropagatesWithoutAnEquivalenceVerdict(): Unit = runBlocking {
        var cancelled = false
        try { withContext(Job().apply { cancel() }) { execute(input("x", "1", "x*x", "x")) } } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }
}
