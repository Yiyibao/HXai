package me.rerere.rikkahub.feature.math

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** An equality chain on the first expression's domain, never on a shrinking pairwise intersection. */
internal object RationalExpressionChain {
    private class Expression(val value: RationalFunction, val conditions: Collection<Polynomial>)

    private fun parse(text: String, nodes: MutableList<Int>): Expression {
        val policy = RationalFunctionArithmetic()
        val parsed = ExactExpressionParser(text, policy).parse()
        nodes += parsed.astNodes
        return Expression(parsed.value, policy.nonzeroPolynomials)
    }

    private suspend fun excluded(conditions: Collection<Polynomial>): Polynomial {
        var result = Polynomial.ONE
        for (condition in conditions) {
            currentCoroutineContext().ensureActive()
            result = CriticalPolynomials.union(result, CriticalPolynomials.squareFree(condition))
        }
        return result
    }

    private suspend fun domainCheck(initialExcluded: Polynomial, next: Expression): JsonObject {
        val nextExcluded = excluded(next.conditions)
        // Both operands are square-free. Removing the common factors removes exactly
        // the already forbidden points, including irrational roots and multiplicities.
        val extra = nextExcluded.exactQuotient(nextExcluded.gcd(initialExcluded))
        val sturm = SturmSequence.build(extra)
        val isolation = ExactRealRootIsolation.isolate(extra, sturm)
        return buildJsonObject {
            put("status", if (isolation.roots.isEmpty()) "VERIFIED" else "DISPROVED")
            put("proof_kind", "exact_sturm_domain_inclusion")
            put("next_nonzero_polynomials", JsonArray(next.conditions.map { it.json() }))
            put("next_excluded_squarefree_coefficients", nextExcluded.json())
            put("new_exclusion_coefficients", extra.json())
            put("sturm_sequence", sturm.json()); put("root_bound", isolation.bound.toString())
            put("bound_variations", buildJsonObject { put("negative", isolation.lowerVariations); put("positive", isolation.upperVariations) })
            put("new_excluded_real_root_count", isolation.roots.size)
            put("new_excluded_roots", JsonArray(isolation.roots.mapIndexed { index, root -> root.json(index) }))
            put("isolation_work_nodes", isolation.workNodes)
        }
    }

    suspend fun verify(expressions: JsonArray, nodes: MutableList<Int>): JsonObject {
        if (expressions.size !in 2..50 || expressions.any { it !is JsonPrimitive || !it.isString }) {
            throw MathUnsupported("provide_2_to_50_expression_strings")
        }
        val checks = ArrayList<JsonElement>(expressions.size - 1)
        var initial: Expression? = null
        var initialExcluded: Polynomial? = null
        var previous: Expression? = null
        var status = "VERIFIED"
        var stoppedAt: Int? = null
        for (index in 0 until expressions.size - 1) {
            currentCoroutineContext().ensureActive()
            val check = try {
                val left = previous ?: parse(expressions[index].jsonPrimitive.content, nodes).also {
                    initial = it
                    initialExcluded = excluded(it.conditions)
                }
                val right = parse(expressions[index + 1].jsonPrimitive.content, nodes)
                val domain = domainCheck(checkNotNull(initialExcluded), right)
                if (domain.getValue("status").jsonPrimitive.content == "DISPROVED") {
                    buildJsonObject {
                        put("status", "DISPROVED"); put("verified", false)
                        put("reason", "expression_undefined_on_initial_domain"); put("domain_check", domain)
                    }
                } else {
                    val value = RationalFunctionVerification.compare(left.value, right.value,
                        checkNotNull(initial).conditions + left.conditions, right.conditions)
                    previous = right
                    buildJsonObject {
                        put("status", value.getValue("status")); put("verified", value.getValue("verified"))
                        put("domain_check", domain); put("value_check", value)
                    }
                }
            } catch (e: MathUnsupported) {
                buildJsonObject { put("status", "UNSUPPORTED"); put("verified", false); put("reason", e.reason) }
            }
            checks.add(check)
            status = check.getValue("status").jsonPrimitive.content
            if (status != "VERIFIED") { stoppedAt = index + 1; break }
        }
        return buildJsonObject {
            put("status", status); put("verified", status == "VERIFIED")
            put("proof_kind", "exact_fixed_domain_rational_expression_chain")
            put("first_error_transition", if (status == "DISPROVED") JsonPrimitive(checkNotNull(stoppedAt)) else JsonNull)
            put("unverified_transition", if (status == "UNSUPPORTED") JsonPrimitive(checkNotNull(stoppedAt)) else JsonNull)
            put("verified_prefix_transitions", if (stoppedAt == null) expressions.size - 1 else stoppedAt - 1)
            put("checks", JsonArray(checks))
            put("initial_domain", initial?.let { expression -> buildJsonObject {
                put("ambient", "real_numbers"); put("predicate", "all_polynomials_nonzero")
                put("nonzero_polynomials", JsonArray(expression.conditions.map { it.json() }))
            } } ?: JsonNull)
            put("initial_excluded_squarefree_coefficients", initialExcluded?.json() ?: JsonNull)
            if (status == "VERIFIED") put("canonical_final_expression", checkNotNull(previous).value.json())
        }
    }
}
