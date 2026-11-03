package me.rerere.rikkahub.feature.math

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProofBranchRulesTest {
    private fun atom(s: String) = buildJsonObject { put("atom", s) }
    private fun binary(s: String, a: JsonElement, b: JsonElement) = buildJsonObject { put(s, JsonArray(listOf(a,b))) }
    private fun not(a: JsonElement) = buildJsonObject { put("not",a) }
    private val p = atom("P"); private val q = atom("Q"); private val r = atom("R")
    private fun run(hypotheses: List<JsonObject>, goal: JsonObject, rule: String, refs: List<String>, discharge: String? = null) = runBlocking {
        ProofDerivation.verify(buildJsonObject {
            put("assumptions", JsonArray(hypotheses.mapIndexed { i,f -> buildJsonObject { put("id","h$i");put("formula",f) } }))
            put("goal",goal)
            put("steps", buildJsonArray {
                hypotheses.forEachIndexed { i,f -> add(buildJsonObject { put("id","s$i");put("rule","assumption");put("assumption","h$i");put("conclusion",f) }) }
                add(buildJsonObject { put("id","last");put("rule",rule);put("conclusion",goal)
                    put("premises",JsonArray(refs.map(::JsonPrimitive)));if(discharge!=null)put("assumption",discharge) })
            })
        })
    }
    @Test fun bothAlternativeBranchesMustHaveTheSameConclusion() {
        val hypotheses = listOf(binary("or",p,q),binary("implies",p,r),binary("implies",q,r))
        val valid=run(hypotheses,r,"or_elim",listOf("s0","s1","s2"))
        assertEquals(JsonPrimitive("VERIFIED"),valid["status"])
        assertEquals(buildJsonArray { add("h0");add("h1");add("h2") },valid["undischarged_assumptions"])
        for (wrong in listOf(hypotheses.dropLast(1)+binary("implies",q,p),
            hypotheses.dropLast(1)+binary("implies",p,r))) {
            val bad=run(wrong,r,"or_elim",listOf("s0","s1","s2"))
            assertEquals(JsonPrimitive("DISPROVED"),bad["status"])
            assertEquals(JsonPrimitive("NOT_EVALUATED"),bad["claim_status"])
        }
    }
    @Test fun modusTollensPreservesConditionsAndCannotUseAffirmingConsequent() {
        assertEquals(JsonPrimitive("VERIFIED"),run(listOf(binary("implies",p,q),not(q)),not(p),"modus_tollens",listOf("s0","s1"))["status"])
        assertEquals(JsonPrimitive("DISPROVED"),run(listOf(binary("implies",p,q),q),p,"modus_tollens",listOf("s0","s1"))["status"])
        assertEquals(JsonPrimitive("DISPROVED"),run(listOf(binary("implies",p,q),not(r)),not(p),"modus_tollens",listOf("s0","s1"))["status"])
    }
    @Test fun negationIntroductionRemovesOnlyTheSpecifiedAssumption() {
        val bottom=buildJsonObject { put("false",true) }
        val result=run(listOf(p,bottom),not(p),"not_intro",listOf("s1"),"h0")
        assertEquals(JsonPrimitive("VERIFIED"),result["status"])
        assertEquals(buildJsonArray { add("h1") },result["undischarged_assumptions"])
        assertEquals(JsonPrimitive(true),result["claim_is_conditional"])
        assertEquals(JsonPrimitive("DISPROVED"),run(listOf(p),not(p),"not_intro",listOf("s0"),"h0")["status"])
    }
    @Test fun missingBranchCannotBeInventedAndExtraPremisesAreRejected() {
        val hypotheses=listOf(binary("or",p,q),binary("implies",p,r),binary("implies",q,r))
        val missing=run(hypotheses,r,"or_elim",listOf("s0","s1","unknown"))
        assertEquals(JsonPrimitive("UNSUPPORTED"),missing["status"])
        assertEquals(JsonNull,missing["first_error_step"])
        assertEquals(JsonPrimitive("DISPROVED"),run(hypotheses,r,"or_elim",listOf("s0","s1","s2","s2"))["status"])
    }
}
