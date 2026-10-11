package me.rerere.rikkahub.feature.interactive

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class MathPlotTest {
    private fun plot(curve: String, points: String = "[]") = Json.parseToJsonElement("""{"id":"plot","type":"math_plot","xMin":-5,"xMax":5,"yMin":-5,"yMax":5,"curves":[$curve],"points":$points}""").jsonObject
    @Test fun precedenceConstantsFunctionsAndParameters() {
        val samples = mapOf("-x^2" to -4.0, "2^3^2" to 512.0, "2^-2" to .25, "sqrt(9)+ln(e)+log(100)" to 6.0, "sin(pi/2)+abs(-3)" to 4.0, "a*x^2" to 12.0)
        samples.forEach { (expr,expected) -> assertEquals(expr,expected,MathExpression.parse(expr,setOf("x","a")).value(mapOf("x" to 2.0,"a" to 3.0)),1e-9) }
    }
    @Test fun rejectsCodeUnknownVariablesAndUnboundedGrammar() {
        listOf("2x", "system(x)", "x;exit()", "x+y", "NaN", "1e999", "(".repeat(30)+"x"+")".repeat(30), "x+".repeat(100)+"x").forEach {
            assertTrue(it,runCatching { MathExpression.parse(it,setOf("x")) }.isFailure)
        }
    }
    @Test fun ellipseSamplesSatisfyEquation() {
        val curve=plot("""{"kind":"parametric","label":"椭圆","x":"3*cos(t)","y":"2*sin(t)","min":0,"max":6.283185307179586}""")
        val points=MathPlot.sample(curve,emptyMap()).single().segments.flatten()
        assertTrue(points.size>1000)
        points.forEach { assertEquals(1.0,it.x*it.x/9+it.y*it.y/4,1e-9) }
    }
    @Test fun parameterChangesRecalculateFunctionAndDomain() {
        val curve=plot("""{"kind":"function","label":"函数","expression":"a*x^2","min":0,"max":1}""")
        val first=MathPlot.sample(curve,mapOf("a" to 1.0)).single().segments.flatten()
        val second=MathPlot.sample(curve,mapOf("a" to 2.0)).single().segments.flatten()
        assertEquals(first.last().y*2,second.last().y,1e-9)
        assertTrue(first.all { it.x in 0.0..1.0 })
    }
    @Test fun hyperbolaParabolaAndRotatedEllipseKeepTheirEquations() {
        val hyperbola=MathPlot.sample(plot("""{"kind":"parametric","label":"双曲线右支","x":"3*sqrt(1+t^2)","y":"2*t","min":-2,"max":2}"""),emptyMap()).single().segments.flatten()
        assertTrue(hyperbola.isNotEmpty())
        hyperbola.forEach { assertEquals(1.0,it.x*it.x/9-it.y*it.y/4,1e-9) }
        val parabola=MathPlot.sample(plot("""{"kind":"function","label":"抛物线","expression":"x^2-2*x-3"}"""),emptyMap()).single().segments.flatten()
        parabola.forEach { assertEquals(it.x*it.x-2*it.x-3,it.y,1e-9) }
        val rotated=MathPlot.sample(plot("""{"kind":"parametric","label":"旋转椭圆","x":"3*cos(t)*cos(pi/4)-2*sin(t)*sin(pi/4)","y":"3*cos(t)*sin(pi/4)+2*sin(t)*cos(pi/4)","min":0,"max":6.283185307179586}"""),emptyMap()).single().segments.flatten()
        rotated.forEach { val x=(it.x+it.y)/sqrt(2.0);val y=(-it.x+it.y)/sqrt(2.0);assertEquals(1.0,x*x/9+y*y/4,1e-9) }
    }
    @Test fun rationalPoleDoesNotJoinBranchesEvenInLargeViewport() {
        val curve=plot("""{"kind":"function","label":"倒数","expression":"1/(x-0.0123)"}""")
        val segments=MathPlot.sample(curve,emptyMap()).single().segments
        assertTrue(segments.size>=2)
        assertTrue(segments.none { line -> line.any { it.x<0.0123 } && line.any { it.x>0.0123 } })
    }
    @Test fun squareRootRespectsRealDomain() {
        val points=MathPlot.sample(plot("""{"kind":"function","label":"平方根","expression":"sqrt(x)"}"""),emptyMap()).single().segments.flatten()
        assertTrue(points.isNotEmpty()); assertTrue(points.all { it.x>=0 && it.y>=0 })
    }
    @Test fun implicitCircleHasSmallResidualAndPoleHasNoZeroContour() {
        val circle=MathPlot.sample(plot("""{"kind":"implicit","label":"圆","expression":"x^2+y^2-4"}"""),emptyMap()).single().segments.flatten()
        assertTrue(circle.size>100)
        circle.forEach { assertTrue(abs(it.x*it.x+it.y*it.y-4)<.01) }
        val pole=MathPlot.sample(plot("""{"kind":"implicit","label":"无零点","expression":"1/(x-0.0123)"}"""),emptyMap()).single().segments
        assertTrue(pole.isEmpty())
    }
    @Test fun validationRejectsMissingParameterBoundsAndUnknownFields() {
        val good=plot("""{"kind":"function","label":"函数","expression":"a*x"}""")
        assertTrue(runCatching { MathPlot.validate(good,emptySet()) }.isFailure)
        MathPlot.validate(good,setOf("a"))
        assertTrue(runCatching { MathPlot.validate(good,setOf("x")) }.isFailure)
        assertTrue(runCatching { MathPlot.validate(JsonObject(good+ ("xMax" to JsonPrimitive(-5))),setOf("a")) }.isFailure)
        assertTrue(runCatching { MathPlot.validate(JsonObject(good+ ("code" to JsonPrimitive("evil"))),setOf("a")) }.isFailure)
        assertTrue(runCatching { MathPlot.validate(JsonObject(good+ ("equalAspect" to JsonPrimitive("true"))),setOf("a")) }.isFailure)
    }
    @Test fun mathToolUsesExistingSurfaceIdentityAndStateValidation() {
        val raw="""{"version":3,"title":"函数","components":[{"id":"a","type":"slider","label":"a","min":1,"max":3,"value":1},${plot("""{"kind":"function","label":"函数","expression":"a*x"}""")}]}"""
        val surface=InteractiveSurface.validate(raw)
        InteractiveSurface.validateState(surface,buildJsonObject { put("a",2) })
        assertTrue(InteractiveSurface.isSurfaceTool("render_math_plot"))
        val tool=me.rerere.ai.ui.UIMessagePart.Tool("math",InteractiveSurface.MATH_TOOL,buildJsonObject { put("definition",raw) }.toString())
        assertEquals(raw,InteractiveSurface.definition(tool))
    }
}
