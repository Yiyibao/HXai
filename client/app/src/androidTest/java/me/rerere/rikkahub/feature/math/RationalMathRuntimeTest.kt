package me.rerere.rikkahub.feature.math

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RationalMathRuntimeTest {
    private fun identity(left: String, right: String) = buildJsonObject { put("operation", "rational_identity"); put("left", left); put("right", right) }
    private fun derivative(expression: String, claim: String) = buildJsonObject { put("operation", "rational_derivative"); put("expression", expression); put("claimed_derivative", claim) }
    private fun coefficients(vararg pairs: Pair<String,String>) = buildJsonObject { for ((degree,value) in pairs) put(degree,value) }
    private fun conditions(result: JsonObject, side: String) = result.getValue("${side}_nonzero_polynomials").jsonArray
    private suspend fun execute(input: JsonObject): JsonObject {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "org.hxsi.agent.nativeqa")
        return Json.parseToJsonElement(mathVerifyTool().execute(input).filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
    }
    private suspend fun verified(input: JsonObject): JsonObject = execute(input).also {
        assertEquals("VERIFIED",it.getValue("status").jsonPrimitive.content)
        assertEquals("Q(x)",it.getValue("domain").jsonPrimitive.content)
        assertTrue(MathVerification.accepts(input,it))
    }

    @Test fun cancelledFactorsKeepOriginalHoleAndDoNotClaimEqualTotalDomains(): Unit = runBlocking {
        val result=verified(identity("(x^2-1)/(x-1)","x+1"))
        val hole=coefficients("0" to "-1","1" to "1")
        assertEquals(JsonArray(listOf(hole)),conditions(result,"left"));assertTrue(conditions(result,"right").isEmpty())
        assertEquals(coefficients("0" to "1","1" to "1"),result.getValue("computed_left").jsonObject.getValue("numerator"))
        assertEquals(coefficients("0" to "1"),result.getValue("computed_left").jsonObject.getValue("denominator"))
        assertEquals(JsonArray(listOf(hole)),result.getValue("comparison_domain").jsonObject.getValue("nonzero_polynomials"))
        assertEquals("rational_identity_on_common_defined_real_domain",result.getValue("scope").jsonPrimitive.content)
    }
    @Test fun zeroProductsZeroPowersAndNestedReciprocalsCannotEraseDefinedness(): Unit = runBlocking {
        val x=JsonArray(listOf(coefficients("1" to "1")))
        for ((left,right) in listOf("x/x" to "1","0*(1/x)" to "0","(1/x)^0" to "1","1/(1/x)" to "x","x^0" to "1","(1/x)/(1/x)" to "1")) {
            assertEquals(left,x,conditions(verified(identity(left,right)),"left"))
        }
        assertEquals("UNSUPPORTED",execute(identity("0^0","1")).getValue("status").jsonPrimitive.content)
        assertEquals("VERIFIED",execute(buildJsonObject {put("operation","identity");put("left","0^0");put("right","1")}).getValue("status").jsonPrimitive.content)
        val nested=verified(identity("(1/(x-1))/(1/(x+1))","(x+1)/(x-1)"))
        assertEquals(JsonArray(listOf(coefficients("0" to "-1","1" to "1"),coefficients("0" to "1","1" to "1"))),conditions(nested,"left"))
    }
    @Test fun exactQuotientRuleAcceptsAlternativeClaimsAndPreservesOriginalDomain(): Unit = runBlocking {
        for ((expression,claim) in listOf("1/x" to "-1/x^2","(x+1)/(x-1)" to "-2/(x-1)^2","1/(x^2+1)" to "-2*x/(x^2+1)^2","(x^2-1)/(x-1)" to "1","(1/x)/(1/x)" to "0","1/x^31" to "-31/x^32","x/2+x/3" to "5/6")) {
            verified(derivative(expression,claim))
        }
        val result=verified(derivative("(x^2-1)/(x-1)","1"))
        assertEquals(JsonArray(listOf(coefficients("0" to "-1","1" to "1"))),conditions(result,"left"))
        assertEquals("rational_derivative_on_common_defined_real_domain",result.getValue("scope").jsonPrimitive.content)
    }
    @Test fun exactDisproofWitnessAvoidsPolesAndCancelledHolesInsteadOfSamplingProof(): Unit = runBlocking {
        val input=identity("x*(x-1)*(x-2)*(x-3)/(x-4)","0");val result=execute(input)
        assertEquals("DISPROVED",result.getValue("status").jsonPrimitive.content)
        assertEquals(buildJsonObject {put("x",5);put("left","120");put("right","0")},result.getValue("counterexample"))
        assertEquals(coefficients("1" to "-6","2" to "11","3" to "-6","4" to "1"),result.getValue("cross_difference_coefficients"))
        assertFalse(MathVerification.accepts(input,result))
        val hidden=execute(identity("(1/x)/(1/x)","2"))
        assertEquals(1,hidden.getValue("counterexample").jsonObject.getValue("x").jsonPrimitive.int)
        assertEquals("DISPROVED",execute(derivative("1/x","1/x^2")).getValue("status").jsonPrimitive.content)
    }
    @Test fun reducedFractionsMonicDenominatorsAndLargeIntegersRemainExact(): Unit = runBlocking {
        for ((left,right) in listOf("1/x+1/x" to "2/x","1/(2*x)" to "(1/2)/x","(x^2-1)/(2*x-2)" to "(x+1)/2","(2*x+2)/(2*x)" to "1+1/x","(x^32-x^31)/(x-1)" to "x^31","9007199254740993/x-9007199254740992/x" to "1/x")) verified(identity(left,right))
        val result=verified(identity("1/(2*x)","1/(x*2)"))
        assertEquals(coefficients("0" to "1/2"),result.getValue("computed_left").jsonObject.getValue("numerator"))
        assertEquals(coefficients("1" to "1"),result.getValue("computed_left").jsonObject.getValue("denominator"))
    }
    @Test fun undefinedExpressionsUnsupportedDomainsAndLimitsNeverBecomeSuccess(): Unit = runBlocking {
        for (left in listOf("x/(x-x)","0/(x-x)","1/(0*(1/x))","sin(x)","1/(x+y)","x^-1","x^33","1.0/x","x=1","x[0]","__import__(1)","1/"+"(".repeat(65)+"x"+")".repeat(65),"x/".repeat(300)+"1")) {
            val input=identity(left,"1");val result=execute(input)
            assertEquals(left,"UNSUPPORTED",result.getValue("status").jsonPrimitive.content)
            assertFalse(MathVerification.accepts(input,JsonObject(result+mapOf("status" to JsonPrimitive("VERIFIED"),"verified" to JsonPrimitive(true)))))
        }
        for (input in listOf(JsonObject(identity("x","x")+("authorized" to JsonPrimitive("true"))),buildJsonObject {put("operation","rational_identity");put("left",1);put("right","1")})) assertEquals("UNSUPPORTED",execute(input).getValue("status").jsonPrimitive.content)
        val old=execute(buildJsonObject {put("operation","identity");put("left","1/x");put("right","1/x")})
        assertEquals("UNSUPPORTED",old.getValue("status").jsonPrimitive.content)
    }
    @Test fun originalDomainAndAllExactEvidenceMustSurviveIndependentReadback(): Unit = runBlocking {
        val input=identity("x/x","1");val result=verified(input)
        for ((key,value) in listOf("comparison_domain" to buildJsonObject {put("ambient","real_numbers");put("predicate","all_polynomials_nonzero");put("nonzero_polynomials",JsonArray(emptyList()))},"left_nonzero_polynomials" to JsonArray(emptyList()),"domain" to JsonPrimitive("all_real_functions"),"scope" to JsonPrimitive("general_proof"),"input_sha256" to JsonPrimitive("0".repeat(64)),"cross_difference_coefficients" to coefficients("0" to "1"),"contractVersion" to JsonPrimitive(1))) assertFalse(key,MathVerification.accepts(input,JsonObject(result+(key to value))))
        val folder=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"b3b-rational-evidence").apply{mkdirs()}
        val file=File(folder,"domain-preserving-identity.json");file.writeText(buildJsonObject {put("input",input);put("result",result)}.toString())
        val durable=Json.parseToJsonElement(file.readText()).jsonObject
        assertTrue(MathVerification.accepts(durable.getValue("input").jsonObject,durable.getValue("result").jsonObject))
        assertFalse(MathVerification.accepts(identity("x/x","2"),result))
    }
    @Test fun cancellationIsNotConvertedIntoUnsupportedMath(): Unit = runBlocking {
        var cancelled=false
        try { withContext(Job().apply {cancel()}) { execute(identity("x/x","1")) } } catch (_: CancellationException) {cancelled=true}
        assertTrue(cancelled)
    }
}
