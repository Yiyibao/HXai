package me.rerere.rikkahub.feature.math

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class ProfessionalMathRuntimeTest {
    private suspend fun verify(operation: String, request: JsonObject) = MathVerification.verify(buildJsonObject { put("operation", operation); put("request", request.toString()) })
    private fun obj(raw: String) = Json.parseToJsonElement(raw).jsonObject
    @Test fun compositeCannotReplaceOrOmitDeclaredGoal(): Unit = runBlocking {
        val fact = obj("""{"operation":"derivative","expression":"x^3-3*x","claimed_derivative":"3*x^2-3"}""")
        val request = buildJsonObject {
            put("problem", "求导并核验两个明确子目标")
            put("goals", buildJsonArray { for (id in listOf("first", "second")) add(buildJsonObject { put("id", id); put("statement", "导数"); put("predicate", fact) }) })
            put("checks", buildJsonArray { add(buildJsonObject { put("goal", "first"); put("input", fact) }) })
        }
        val partial = verify("professional_solution", request)
        assertEquals(JsonPrimitive("UNSUPPORTED"), partial["status"])
        assertEquals(buildJsonArray { add("second") }, partial["unverified_goals"])
        val complete = JsonObject(request + ("checks" to buildJsonArray { for (id in listOf("first", "second")) add(buildJsonObject { put("goal",id);put("input",fact) }) }))
        assertEquals(JsonPrimitive("VERIFIED"),verify("professional_solution",complete)["status"])
        val wrong = JsonObject(request + ("checks" to buildJsonArray { add(buildJsonObject { put("goal","first");put("input",obj("""{"operation":"identity","left":"1","right":"1"}""")) }) }))
        assertEquals(JsonPrimitive("subproblem_predicate_mismatch"),verify("professional_solution",wrong)["reason"])
    }
    @Test fun dominatedConvergenceRequiresAllHypothesesAndRemainsConditional(): Unit = runBlocking {
        val conditions = listOf("measurable_terms(s,mu)","measurable(f,mu)","pointwise_ae_limit(s,f,mu)","integrable(g,mu)","absolute_domination_ae(s,g,mu)")
        val goal = buildJsonObject { put("atom","integral_limit_commutes(s,f,mu)") }
        fun request(includeDomination: Boolean) = buildJsonObject {
            put("assumptions",JsonArray(conditions.mapIndexed { i,text -> buildJsonObject { put("id","h$i");put("formula",buildJsonObject {put("atom",text)}) } }))
            put("goal",goal)
            put("steps",buildJsonArray {
                conditions.forEachIndexed { i,text -> add(buildJsonObject {put("id","p$i");put("rule","assumption");put("assumption","h$i");put("conclusion",buildJsonObject {put("atom",text)})}) }
                add(buildJsonObject {put("id","finish");put("rule","theorem");put("theorem","dominated_convergence");put("parameters",obj("""{"sequence":"s","limit":"f","dominator":"g","measure":"mu"}"""));put("premises",buildJsonArray {for(i in 0 until if(includeDomination)5 else 4) add("p$i")});put("conclusion",goal)})
            })
        }
        val valid=verify("professional_proof",request(true));assertEquals(JsonPrimitive("VERIFIED"),valid["status"])
        assertEquals(JsonPrimitive(true),valid["claim_is_conditional"])
        assertEquals((0..4).map {JsonPrimitive("h$it")},valid.getValue("undischarged_assumptions").jsonArray.toList())
        assertEquals(JsonPrimitive("DISPROVED"),verify("professional_proof",request(false))["status"])
    }
    @Test fun exactSingularityAndFreeVariablesAreNotInventedUniqueSolutions(): Unit = runBlocking {
        val result=verify("professional_linear_algebra",obj("""{"action":"solve","matrix":[["1","2","3"],["2","4","6"]],"rhs":["1","2"]}"""))
        assertEquals(JsonPrimitive("AFFINE_FAMILY"),result["classification"])
        assertEquals(JsonPrimitive(1),result["rank"])
        assertEquals(JsonPrimitive("0"),result["exact_residual"])
        val inverse=verify("professional_linear_algebra",obj("""{"action":"inverse","matrix":[["1","2"],["2","4"]]}"""))
        assertEquals(JsonPrimitive("SINGULAR_NO_INVERSE"),inverse["classification"]);assertEquals(JsonNull,inverse["inverse"])
    }
    @Test fun strict100DigitIntervalsDoNotConfusePrecisionWithInputCertainty(): Unit = runBlocking {
        // Independent known decimal prefix: this enclosing reference interval is wider than its truncation.
        val reference=BigDecimal("1.414213562373095048801688724209698078569671875376948073176679737990732478462107038850387534327641572735013846230912297024924836055850737212644121497099935831413222665927505592755799950501152782060571470109559")
        val input=buildJsonObject {put("operation","professional_numeric");put("request","""{"expression":"sqrt(2)","digits":"100","bindings":{}}""")}
        val result=MathVerification.verify(input)
        assertTrue(MathVerification.accepts(input,result))
        val lower=BigDecimal(result.getValue("lower").jsonPrimitive.content);val upper=BigDecimal(result.getValue("upper").jsonPrimitive.content)
        assertTrue(lower<=reference+BigDecimal("1e-198") && upper>=reference)
        assertTrue(upper-lower<=BigDecimal("1e-100"))
        assertFalse(MathVerification.accepts(input,JsonObject(result+("lower" to JsonPrimitive("0")))))
        val uncertain=verify("professional_numeric",obj("""{"expression":"u+1","digits":"100","bindings":{"u":["1","1.00001"]}}"""))
        assertEquals(JsonPrimitive("UNSUPPORTED"),uncertain["status"])
        val pole=verify("professional_numeric",obj("""{"expression":"tan(pi/2)","digits":"80","bindings":{}}"""))
        assertEquals(JsonPrimitive("UNSUPPORTED"),pole["status"])
    }
    @Test fun inductionCannotInferAllIntegersFromSomeCorrectSamples(): Unit = runBlocking {
        val valid=verify("professional_induction",obj("""{"start":"0","initial":"0","next":"u+2*n+1","claimed":"n^2"}"""))
        assertEquals(JsonPrimitive("VERIFIED"),valid["status"])
        val falseBase=verify("professional_induction",obj("""{"start":"0","initial":"1","next":"u+2*n+1","claimed":"n^2"}"""))
        assertEquals(JsonPrimitive("DISPROVED"),falseBase["status"])
        val fakeStep=verify("professional_induction",obj("""{"start":"0","initial":"0","next":"u+2*n+1+n*(n-1)*(n-2)","claimed":"n^2"}"""))
        assertEquals(JsonPrimitive("DISPROVED"),fakeStep["status"])
    }
}
