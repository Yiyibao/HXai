package me.rerere.rikkahub.feature.math

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.math.BigInteger

class InequalityRuntimeTest {
    private fun input(expression: String, relation: String) = buildJsonObject { put("operation","rational_inequality");put("expression",expression);put("relation",relation) }
    private suspend fun execute(input: JsonObject): JsonObject {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="org.hxsi.agent.nativeqa")
        return Json.parseToJsonElement(mathVerifyTool().execute(input).filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
    }
    private suspend fun solve(expression: String, relation: String): JsonObject {
        val args=input(expression,relation);val result=execute(args)
        assertEquals("VERIFIED",result.getValue("status").jsonPrimitive.content)
        assertTrue(MathVerification.accepts(args,result))
        return result
    }
    private fun endpoint(index: Int?, positive: Boolean=false) = buildJsonObject {
        if(index!=null){put("kind","root");put("index",index)}else put("kind",if(positive)"positive_infinity" else "negative_infinity")
    }
    private fun interval(lower: Int?,upper: Int?,lc: Boolean=false,uc: Boolean=false) = buildJsonObject {
        put("lower",endpoint(lower));put("upper",endpoint(upper,true));put("lower_closed",lc);put("upper_closed",uc)
    }
    private fun solution(result: JsonObject) = result.getValue("solution_intervals").jsonArray
    private fun roots(result: JsonObject) = result.getValue("roots").jsonArray
    private fun number(value: String): Rational = value.split('/').let {Rational.fraction(BigInteger(it[0]),if(it.size==2)BigInteger(it[1]) else BigInteger.ONE)}
    private fun containsExact(root: JsonObject,value: Rational) {
        val isolation=root.getValue("isolation").jsonObject
        if(isolation.getValue("kind").jsonPrimitive.content=="rational_point") assertEquals(0,number(isolation.getValue("value").jsonPrimitive.content).compareTo(value))
        else {assertTrue(number(isolation.getValue("lower").jsonPrimitive.content)<value);assertTrue(value<number(isolation.getValue("upper").jsonPrimitive.content))}
    }
    @Test fun irrationalEndpointsAndAllFourRelationsReturnCertifiedRealSolutions(): Unit=runBlocking {
        val ge=solve("x^2-2",">=");assertEquals(2,ge.getValue("real_root_count").jsonPrimitive.int)
        assertEquals(JsonArray(listOf(interval(null,0,uc=true),interval(1,null,lc=true))),solution(ge))
        assertEquals(JsonArray(listOf(interval(null,0),interval(1,null))),solution(solve("x^2-2",">")))
        assertEquals(JsonArray(listOf(interval(0,1,true,true))),solution(solve("x^2-2","<=")))
        assertEquals(JsonArray(listOf(interval(0,1))),solution(solve("x^2-2","<")))
        assertEquals(buildJsonObject{put("0","-2");put("2","1")},ge.getValue("critical_squarefree_coefficients"))
        assertEquals(listOf(1,-1,1),ge.getValue("cells").jsonArray.map{it.jsonObject.getValue("sign").jsonPrimitive.int})
    }
    @Test fun repeatedRootsDoNotAlwaysFlipSignAndNoRealRootsDoNotBecomeFakeBoundaries(): Unit=runBlocking {
        assertEquals(JsonArray(listOf(interval(null,0),interval(0,null))),solution(solve("(x-1)^2",">")))
        assertEquals(JsonArray(listOf(interval(null,null))),solution(solve("(x-1)^2",">=")))
        assertEquals(JsonArray(listOf(interval(0,0,true,true))),solution(solve("(x-1)^2","<=")))
        assertTrue(solution(solve("(x-1)^2","<")).isEmpty())
        val positive=solve("x^2+1",">");assertEquals(0,positive.getValue("real_root_count").jsonPrimitive.int)
        assertEquals(JsonArray(listOf(interval(null,null))),solution(positive))
        assertTrue(solution(solve("x^2+1","<")).isEmpty());assertTrue(solution(solve("-x^2-1",">=")).isEmpty())
    }
    @Test fun cancelledHolesZeroProductsAndNestedReciprocalsStayExcludedEvenForNonstrictRelations(): Unit=runBlocking {
        val result=solve("(x^2-1)/(x-1)",">=")
        assertEquals(JsonArray(listOf(interval(0,1,true,false),interval(1,null))),solution(result))
        containsExact(roots(result)[0].jsonObject,Rational.integer(BigInteger.valueOf(-1)))
        containsExact(roots(result)[1].jsonObject,Rational.ONE)
        assertFalse(roots(result)[0].jsonObject.getValue("excluded").jsonPrimitive.boolean)
        assertTrue(roots(result)[1].jsonObject.getValue("excluded").jsonPrimitive.boolean)
        for(expression in listOf("x/x","0*(1/x)","(1/x)^0")) assertEquals(JsonArray(listOf(interval(null,0),interval(0,null))),solution(solve(expression,">=")))
        assertEquals(JsonArray(listOf(interval(null,0))),solution(solve("1/(1/x)","<=")))
    }
    @Test fun polesAreNeverClosedWhileValidZerosCanMergePositiveCells(): Unit=runBlocking {
        assertEquals(JsonArray(listOf(interval(0,1,true,false))),solution(solve("(x+1)/(x-1)","<=")))
        assertEquals(JsonArray(listOf(interval(null,0),interval(1,null))),solution(solve("(x+1)/(x-1)",">")))
        assertEquals(JsonArray(listOf(interval(null,1),interval(1,null))),solution(solve("x^2/(x-1)^2",">=")))
        assertEquals(JsonArray(listOf(interval(0,0,true,true))),solution(solve("x^2/(x-1)^2","<=")))
    }
    @Test fun midpointRootsAndVeryCloseRationalRootsAreCountedOnceAndOrderedExactly(): Unit=runBlocking {
        val cubic=solve("x*(x-1)*(x+1)",">=")
        assertEquals(JsonArray(listOf(interval(0,1,true,true),interval(2,null,lc=true))),solution(cubic))
        assertEquals(3,cubic.getValue("real_root_count").jsonPrimitive.int)
        for((index,v) in listOf(-1L,0L,1L).withIndex())containsExact(roots(cubic)[index].jsonObject,Rational.integer(BigInteger.valueOf(v)))
        val divisor=BigInteger.ONE.shiftLeft(80)
        val close=solve("x*(x-1/$divisor)","<=")
        assertEquals(JsonArray(listOf(interval(0,1,true,true))),solution(close))
        containsExact(roots(close)[0].jsonObject,Rational.ZERO)
        containsExact(roots(close)[1].jsonObject,Rational.fraction(BigInteger.ONE,divisor))
    }
    @Test fun constantsEmptySetsUndefinedRequestsAndResourceFailureNeverPublishPartialSolutions(): Unit=runBlocking {
        for(relation in listOf(">=","<="))assertEquals(JsonArray(listOf(interval(null,null))),solution(solve("0",relation)))
        for(relation in listOf(">","<"))assertTrue(solution(solve("0",relation)).isEmpty())
        val denominator=(1..32).joinToString("*"){"(x-$it)"}
        val tiny=BigInteger.ONE.shiftLeft(300)
        for(args in listOf(input("0/(x-x)",">="),input("sin(x)",">"),input("x","=="),input("x^32/($denominator)",">="),input("x*(x-1/$tiny)","<="),JsonObject(input("x",">")+("authorized" to JsonPrimitive(true))))) {
            val result=execute(args);assertEquals("UNSUPPORTED",result.getValue("status").jsonPrimitive.content)
            assertFalse(result.containsKey("solution_intervals"))
            assertFalse(MathVerification.accepts(args,JsonObject(result+mapOf("status" to JsonPrimitive("VERIFIED"),"verified" to JsonPrimitive(true)))))
        }
    }
    @Test fun independentRecomputeRejectsFalseClosedPoleRootCountsSignsAndForgedDomain(): Unit=runBlocking {
        val args=input("(x+1)/(x-1)","<=");val result=execute(args)
        for((key,value) in listOf("solution_intervals" to JsonArray(listOf(interval(0,1,true,true))),"real_root_count" to JsonPrimitive(0),"roots" to JsonArray(emptyList()),"sturm_sequence" to JsonArray(emptyList()),"original_domain" to buildJsonObject{put("ambient","all_real_numbers")},"cells" to JsonArray(emptyList()),"input_sha256" to JsonPrimitive("0".repeat(64)),"scope" to JsonPrimitive("general_proof"),"contractVersion" to JsonPrimitive(2)))assertFalse(key,MathVerification.accepts(args,JsonObject(result+(key to value))))
        assertFalse(MathVerification.accepts(input("(x+1)/(x-1)",">="),result))
        val folder=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"b3c-inequality-evidence").apply{mkdirs()}
        val file=File(folder,"pole-preserving-solution.json");file.writeText(buildJsonObject{put("input",args);put("result",result)}.toString())
        val saved=Json.parseToJsonElement(file.readText()).jsonObject
        assertTrue(MathVerification.accepts(saved.getValue("input").jsonObject,saved.getValue("result").jsonObject))
    }
}
