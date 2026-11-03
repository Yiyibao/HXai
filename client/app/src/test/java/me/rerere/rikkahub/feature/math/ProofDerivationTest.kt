package me.rerere.rikkahub.feature.math

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProofDerivationTest {
    private fun obj(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private val p = obj("""{"atom":"P"}""")
    private val pp = buildJsonObject { put("and", JsonArray(listOf(p, p))) }
    private fun assumption() = obj("""{"id":"s1","rule":"assumption","assumption":"h","conclusion":{"atom":"P"}}""")
    private fun conjunction(refs: List<String> = listOf("s1", "s1"), conclusion: JsonObject = pp) = buildJsonObject {
        put("id", "s2"); put("rule", "and_intro")
        put("premises", JsonArray(refs.map(::JsonPrimitive))); put("conclusion", conclusion)
    }
    private fun proof(goal: JsonObject, steps: List<JsonObject>) = buildJsonObject {
        put("operation", "professional_proof")
        put("request", buildJsonObject {
            put("assumptions", buildJsonArray { add(buildJsonObject { put("id", "h"); put("formula", p) }) })
            put("goal", goal); put("steps", JsonArray(steps))
        }.toString())
    }
    private fun status(result: JsonObject) = result.getValue("status").jsonPrimitive.content

    @Test fun repeatedPremisePreservesItsConditionalDependency(): Unit = runBlocking {
        val input = proof(pp, listOf(assumption(), conjunction()))
        val result = MathVerification.verify(input)
        assertEquals("VERIFIED", status(result))
        assertEquals(JsonPrimitive(true), result["claim_is_conditional"])
        assertEquals(buildJsonArray { add("h") }, result["undischarged_assumptions"])
        assertTrue(MathVerification.accepts(input, result))
        assertFalse(MathVerification.accepts(input, JsonObject(result + mapOf(
            "claim_is_conditional" to JsonPrimitive(false), "undischarged_assumptions" to JsonArray(emptyList())
        ))))
    }

    @Test fun implicationDischargesTheReusedAssumption(): Unit = runBlocking {
        val goal = buildJsonObject { put("implies", JsonArray(listOf(p, pp))) }
        val input = proof(goal, listOf(assumption(), conjunction(), buildJsonObject {
            put("id", "s3"); put("rule", "implies_intro"); put("assumption", "h")
            put("premises", buildJsonArray { add("s2") }); put("conclusion", goal)
        }))
        val result = MathVerification.verify(input)
        assertEquals("VERIFIED", status(result))
        assertEquals(JsonPrimitive(false), result["claim_is_conditional"])
        assertEquals(JsonArray(emptyList()), result["undischarged_assumptions"])
    }

    private fun fubini(includeIntegrability: Boolean): JsonObject {
        val conditions = listOf("sigma_finite(mu)", "product_measurable(f,mu,mu)", "absolutely_product_integrable(f,mu,mu)")
        val goal = obj("""{"atom":"finite_iterated_integrals_equal(f,mu,mu)"}""")
        return buildJsonObject {
            put("operation", "professional_proof")
            put("request", buildJsonObject {
                put("assumptions", JsonArray(conditions.mapIndexed { i, text -> buildJsonObject {
                    put("id", "h$i"); put("formula", buildJsonObject { put("atom", text) })
                } }))
                put("goal", goal)
                put("steps", buildJsonArray {
                    conditions.forEachIndexed { i, text -> add(buildJsonObject {
                        put("id", "p$i"); put("rule", "assumption"); put("assumption", "h$i")
                        put("conclusion", buildJsonObject { put("atom", text) })
                    }) }
                    add(buildJsonObject {
                        put("id", "finish"); put("rule", "theorem"); put("theorem", "fubini")
                        put("parameters", obj("""{"function":"f","measure_a":"mu","measure_b":"mu"}"""))
                        put("premises", buildJsonArray {
                            add("p0"); add("p0"); add("p1"); if (includeIntegrability) add("p2")
                        })
                        put("conclusion", goal)
                    })
                })
            }.toString())
        }
    }

    @Test fun sameMeasureTheoremCanReuseSigmaFinitenessButNotOmitIntegrability(): Unit = runBlocking {
        val valid = MathVerification.verify(fubini(true))
        assertEquals("VERIFIED", status(valid))
        assertEquals(JsonPrimitive(true), valid["claim_is_conditional"])
        assertEquals(buildJsonArray { add("h0"); add("h1"); add("h2") }, valid["undischarged_assumptions"])
        val missing = MathVerification.verify(fubini(false))
        assertEquals("DISPROVED", status(missing))
        assertEquals(JsonPrimitive(4), missing["first_error_step"])
    }

    @Test fun repetitionCannotChangeConclusionOrPremiseArity(): Unit = runBlocking {
        val wrong = obj("""{"and":[{"atom":"P"},{"atom":"Q"}]}""")
        for (step in listOf(conjunction(conclusion = wrong), conjunction(listOf("s1")), conjunction(listOf("s1", "s1", "s1")))) {
            val result = MathVerification.verify(proof(step.getValue("conclusion").jsonObject, listOf(assumption(), step)))
            assertEquals("DISPROVED", status(result))
            assertEquals(JsonPrimitive(2), result["first_error_step"])
        }
    }

    @Test fun unknownRepeatedPremiseCannotInventFactOrFirstError(): Unit = runBlocking {
        val unknown = buildJsonObject { put("id", "s1"); put("rule", "narrative"); put("conclusion", p) }
        val wrong = obj("""{"atom":"Q"}""")
        val result = MathVerification.verify(proof(wrong, listOf(unknown, conjunction(), buildJsonObject {
            put("id", "s3"); put("rule", "restate"); put("premises", buildJsonArray { add("s2") })
            put("conclusion", wrong)
        })))
        assertEquals("UNSUPPORTED", status(result))
        assertEquals(JsonNull, result["first_error_step"])
        assertEquals(JsonPrimitive(1), result["first_unverified_step"])
    }

    @Test fun stepIdentityRemainsUniqueEvenWhenPremiseOccurrencesRepeat(): Unit = runBlocking {
        val duplicate = JsonObject(conjunction() + ("id" to JsonPrimitive("s1")))
        assertEquals("UNSUPPORTED", status(MathVerification.verify(proof(pp, listOf(assumption(), duplicate)))))
        val result = MathVerification.verify(proof(pp, listOf(conjunction(listOf("missing", "missing")))))
        assertEquals("UNSUPPORTED", status(result))
        assertEquals(JsonNull, result["first_error_step"])
    }
}
