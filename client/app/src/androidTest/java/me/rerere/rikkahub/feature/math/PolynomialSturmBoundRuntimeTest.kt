package me.rerere.rikkahub.feature.math

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.math.BigInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class PolynomialSturmBoundRuntimeTest {
    private fun input(expression: String = "x*(1-x)^2", lower: String = "0", upper: String = "1", bound: String = "4/27", approximation: String = "0") = buildJsonObject {
        put("operation", "polynomial_uniform_bound_sturm"); put("expression", expression); put("approximation", approximation)
        put("lower", lower); put("upper", upper); put("claimed_bound", bound)
    }
    private suspend fun execute(label: String, args: JsonObject): JsonObject {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "org.hxsi.agent.nativeqa")
        val result = Json.parseToJsonElement(mathVerifyTool().execute(args).filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
        val file = File(File(context.cacheDir, "b3n-sturm-bound-evidence").apply { mkdirs() }, "$label.json")
        file.writeText(buildJsonObject { put("input", args); put("result", result) }.toString())
        val saved = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(result.getValue("status").jsonPrimitive.content == "VERIFIED", MathVerification.accepts(saved.getValue("input").jsonObject, saved.getValue("result").jsonObject))
        return result
    }
    @Test fun sharpNonDyadicAndAlgebraicTangenciesAreActuallyProved(): Unit = runBlocking {
        for ((label, args) in listOf("sharp" to input(), "algebraic" to input("1-(x^2-2)^2/2", "1", "3/2", "1"),
            "endpoints" to input("x", "-1", "1", "1"), "zero" to input("(x+1)^2", "-3/7", "11/7", "0", "x*x+2*x+1"),
            "degree32" to input("x^32", bound = "1"), "point" to input("x^2", "2", "2", "4"))) {
            assertEquals("VERIFIED", execute(label, args).getValue("status").jsonPrimitive.content)
        }
    }
    @Test fun narrowInteriorAndNegativeSideFalseClaimsNeverBecomeSuccess(): Unit = runBlocking {
        for ((label, args) in listOf("false-tight" to input(bound = "147/1000"), "false-negative" to input("-x*(1-x)^2", bound = "147/1000"),
            "narrow" to input("1-(x-1/3)^2+1/1000003", bound = "1"), "false-point" to input("x^2", "2", "2", "3"))) {
            val result = execute(label, args)
            assertEquals("DISPROVED", result.getValue("status").jsonPrimitive.content)
            assertFalse(MathVerification.accepts(args, JsonObject(result + mapOf("status" to JsonPrimitive("VERIFIED"), "verified" to JsonPrimitive(true)))))
        }
    }
    @Test fun isolationLimitAndMalformedRequestsFailClosedAndCancellationPropagates(): Unit = runBlocking {
        val tiny = BigInteger.ONE.shiftLeft(600)
        val limited = execute("depth-limit", input("1-(x-1/3)^2+1/$tiny", bound = "1"))
        assertEquals("root_isolation_depth_limit", limited.getValue("reason").jsonPrimitive.content)
        assertFalse(limited.containsKey("upper_obligation"))
        for (args in listOf(input("1/x"), input("0^0"), input(bound = "-1"), input(lower = "1", upper = "0"), JsonObject(input() + ("owner" to JsonPrimitive("other"))))) {
            assertEquals("UNSUPPORTED", Json.parseToJsonElement(mathVerifyTool().execute(args).filterIsInstance<UIMessagePart.Text>().single().text).jsonObject.getValue("status").jsonPrimitive.content)
        }
        var cancelled = false
        try { withContext(Job().apply { cancel() }) { MathVerification.verify(input()) } } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }
    @Test fun persistedProofCannotBeForgedByOmittingCellsOrOppositeObligation(): Unit = runBlocking {
        val args = input(); val result = execute("durable", args); val proof = result.getValue("upper_obligation").jsonObject
        assertFalse(MathVerification.accepts(args, JsonObject(result + ("upper_obligation" to JsonObject(proof + ("cells" to JsonArray(emptyList())))))))
        assertFalse(MathVerification.accepts(args, JsonObject(result - "lower_obligation")))
        assertFalse(MathVerification.accepts(input(bound = "1/10"), result))
    }
}
