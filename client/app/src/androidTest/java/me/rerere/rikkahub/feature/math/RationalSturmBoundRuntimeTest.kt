package me.rerere.rikkahub.feature.math

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class RationalSturmBoundRuntimeTest {
    private fun input(expression: String = "1/(x+1)", lower: String = "0", upper: String = "1", bound: String = "1", approximation: String = "0") = buildJsonObject {
        put("operation", "rational_uniform_bound_sturm"); put("expression", expression); put("approximation", approximation)
        put("lower", lower); put("upper", upper); put("claimed_bound", bound)
    }
    private suspend fun execute(label: String, args: JsonObject): JsonObject {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "org.hxsi.agent.nativeqa")
        val result = Json.parseToJsonElement(mathVerifyTool().execute(args).filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
        val file = File(File(context.cacheDir, "b3o-rational-bound-evidence").apply { mkdirs() }, "$label.json")
        file.writeText(buildJsonObject { put("input", args); put("result", result) }.toString())
        val saved = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(result.getValue("status").jsonPrimitive.content == "VERIFIED", MathVerification.accepts(saved.getValue("input").jsonObject, saved.getValue("result").jsonObject))
        return result
    }
    @Test fun originalDomainAndBothSignsAreActuallyProvedWithAlgebraicContacts(): Unit = runBlocking {
        for ((label, args) in listOf("positive" to input(), "negative" to input("1/(x-2)"),
            "two-denominators" to input("1/(x-2)", "-1", "1", "4/3", "1/(x+2)"),
            "algebraic" to input("1/(1+(x^2-2)^2)", "1", "3/2"), "non-dyadic" to input("1/(1+(3*x-1)^2)"),
            "degree32" to input("1/(x^16+1)", "-1", "1"), "singleton" to input("1/(x+1)", "1", "1", "1/2"),
            "zero-error" to input("1/(x+1)", bound = "0", approximation = "1/(1+x)"),
            "outside-hole" to input("x/x", "1", "2"), "complex-divisor" to input("(x^2+1)/(x^2+1)", "-1", "1"))) {
            assertEquals("VERIFIED", execute(label, args).getValue("status").jsonPrimitive.content)
        }
    }
    @Test fun narrowInteriorAndNegativeDenominatorFalseClaimsNeverBecomeSuccess(): Unit = runBlocking {
        for ((label, args) in listOf("false-positive" to input(bound = "1/2"), "false-negative" to input("1/(x-2)", bound = "1/2"),
            "narrow" to input("1/((x-1/3)^2+1/1000003)", bound = "1000000"))) {
            val result = execute(label, args)
            assertEquals("DISPROVED", result.getValue("status").jsonPrimitive.content)
            assertFalse(MathVerification.accepts(args, JsonObject(result + mapOf("status" to JsonPrimitive("VERIFIED"), "verified" to JsonPrimitive(true)))))
        }
    }
    @Test fun syntacticHolesPolesZeroErrorsAndDegreeLimitsRemainUnknown(): Unit = runBlocking {
        for ((label, args) in listOf("rational-hole" to input("x/x", "-1", "1"), "zero-numerator-hole" to input("0/(x-1)", "0", "2", "0"),
            "same-source-pole" to input("1/(x-1)", "0", "2", "0", "1/(x-1)"), "algebraic-hole" to input("(x^2-2)/(x^2-2)", "1", "2"),
            "zero-power" to input("x^0", "-1", "1"), "endpoint-pole" to input("1/x", "0", "1", "100"),
            "singleton-hole" to input("x/x", "0", "0"), "approximation-hole" to input("0", "0", "2", "0", "0/(x-1)"))) {
            val result = execute(label, args)
            assertEquals("original_domain_not_defined_on_closed_interval", result.getValue("reason").jsonPrimitive.content)
            assertFalse(result.containsKey("interval_domain_checks")); assertFalse(result.containsKey("upper_obligation"))
        }
        val degree = execute("degree-limit", input("1/(x^17+2)"))
        assertEquals("bound_obligation_degree_limit", degree.getValue("reason").jsonPrimitive.content)
        var cancelled = false
        try { withContext(Job().apply { cancel() }) { MathVerification.verify(input()) } } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }
    @Test fun persistedEvidenceRejectsOmittedDomainOneSidedProofOrChangedInput(): Unit = runBlocking {
        val args = input(); val result = execute("durable", args)
        assertFalse(MathVerification.accepts(args, JsonObject(result + ("interval_domain_checks" to JsonArray(emptyList())))))
        assertFalse(MathVerification.accepts(args, JsonObject(result - "lower_obligation")))
        assertFalse(MathVerification.accepts(args, JsonObject(result - "source_nonzero_polynomials")))
        assertFalse(MathVerification.accepts(input(bound = "1/2"), result))
    }
}
