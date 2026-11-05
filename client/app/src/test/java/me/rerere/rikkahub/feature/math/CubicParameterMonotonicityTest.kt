package me.rerere.rikkahub.feature.math

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.feature.agent.CubicParameterGoal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CubicParameterMonotonicityTest {
    private val goal = "对所有实参数a，完整讨论f_a(x)=x^3-3ax在实数域的单调性。必须分别处理退化边界，不能只给导数或代入若干参数值。"
    private val reply = "a<0时在R上严格递增。a=0时x=0孤立驻点仍严格递增。a>0时临界点满足x^2=a，|x|>sqrt(a)严格递增，|x|<sqrt(a)严格递减。"

    @Test fun sealedFamilyComputesTheThreeCells() = runBlocking {
        val result = verify("x^3-3*a*x")
        assertEquals("VERIFIED", result.getValue("status").jsonPrimitive.content)
        assertEquals("3", result.getValue("derivative_x2").jsonPrimitive.content)
        assertEquals("-3", result.getValue("derivative_parameter").jsonPrimitive.content)
        assertEquals("1", result.getValue("scaled_parameter").jsonPrimitive.content)
        val cases = result.getValue("cases").jsonArray
        assertEquals("strict", cases[0].jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals("strict_isolated_stationary", cases[1].jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals("decreasing", cases[2].jsonObject.getValue("middle").jsonPrimitive.content)
        assertEquals("false", cases[1].jsonObject.getValue("extremum").jsonPrimitive.content)
    }

    @Test fun constantShiftDoesNotChangeTheChartAndOppositeLeadingFlipsIt() = runBlocking {
        val shifted = verify("x^3-3*a*x+5")
        assertEquals("1", shifted.getValue("scaled_parameter").jsonPrimitive.content)
        val flipped = verify("-x^3+3*a*x")
        assertEquals("-3", flipped.getValue("derivative_x2").jsonPrimitive.content)
        assertEquals("decreasing", flipped.getValue("cases").jsonArray[0].jsonObject.getValue("direction").jsonPrimitive.content)
    }

    @Test fun generalCubicAndSamplingShapeAreUnsupported() = runBlocking {
        val expanded = verify("(x-a)^3")
        assertEquals("UNSUPPORTED", expanded.getValue("status").jsonPrimitive.content)
        assertEquals("cubic_parameter_shape_unsupported", expanded.getValue("reason").jsonPrimitive.content)
        val numeric = MathVerification.verify(buildJsonObject {
            put("operation", "rational_monotonicity"); put("expression", "x^3-3*x")
        })
        assertEquals("exact_derivative_sturm_monotonic_branches", numeric.getValue("proof_kind").jsonPrimitive.content)
    }

    @Test fun hostAcceptsTheSealedDiscussionAndRejectsDerivativeOnlyOrMissingZero() = runBlocking {
        val spec = requireNotNull(CubicParameterGoal.parse(goal))
        assertEquals("x^3-3*a*x", spec.expression)
        val certificate = MathVerification.verify(spec.request())
        assertTrue(CubicParameterGoal.quoted(reply, certificate))
        assertFalse(CubicParameterGoal.quoted("导数是3(x^2-a)，代入a=1和a=-1看起来递增。", certificate))
        assertFalse(CubicParameterGoal.quoted("a<0严格递增。a=0孤立驻点仍严格递增。a>0外侧严格递增，中间严格递减。", certificate))
        assertTrue(CubicParameterGoal.quoted("a<0时在R上严格递增。a=0时x=0为驻点仍严格递增。a>0时临界点x=±\\sqrt a。严格递增：(-\\infty,-\\sqrt a]和[\\sqrt a,+\\infty)。严格递减：[-\\sqrt a,\\sqrt a]。", certificate))
        assertFalse(CubicParameterGoal.quoted("a<0时在R上严格递增。a=0时仍严格递增。a>0时临界点x=±\\sqrt a。严格递减：(-\\infty,-\\sqrt a]和[\\sqrt a,+\\infty)。严格递增：[-\\sqrt a,\\sqrt a]。", certificate))
        assertFalse(CubicParameterGoal.quoted("a<0时在R上严格递增。a=0时仍严格递增。a>0时临界点x=±\\sqrt a。严格递减：(-\\infty,-\\sqrt a]和[\\sqrt a,+\\infty)。严格递增：[-\\sqrt a,\\sqrt a]。", certificate))
        assertFalse(CubicParameterGoal.quoted("a<0时在R上严格递增。a=0时x=0为驻点仍严格递增。a>0时外侧严格递减，中间严格递增，临界点sqrt(a)。", certificate))
        assertFalse(CubicParameterGoal.quoted("a<0时在R上严格递增。a=0时仍严格递增。a>0时临界点满足x^2=4a，不是x^2=a。|x|>2*sqrt(a)严格递增，|x|<2*sqrt(a)严格递减。", certificate))
        assertTrue(CubicParameterGoal.quoted("a<0时在R上严格递增。a=0时x=0为驻点仍严格递增。a>0时临界点x=±\\sqrt a。严格递增：(-\\infty,-\\sqrt a]和[\\sqrt a,+\\infty)；严格递减：[-\\sqrt a,\\sqrt a]。", certificate))
    }

    private fun verify(expression: String): JsonObject = runBlocking {
        MathVerification.verify(buildJsonObject {
            put("operation", "professional_multivariate")
            put("request", """{"action":"cubic_monotonicity","variables":["x","a"],"expression":"$expression","variable":"x","parameter":"a"}""")
        })
    }
}
