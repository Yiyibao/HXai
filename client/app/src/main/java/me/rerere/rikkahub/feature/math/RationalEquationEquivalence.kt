package me.rerere.rikkahub.feature.math

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Equality of complete real solution sets, not equality after intersecting the equations' domains. */
internal object RationalEquationEquivalence {
    private class Equation(
        val left: RationalFunction,
        val right: RationalFunction,
        val leftConditions: Collection<Polynomial>,
        val rightConditions: Collection<Polynomial>,
        val difference: RationalFunction,
        val excluded: Polynomial,
        val rootSet: Polynomial,
    ) {
        val cofinite get() = difference.numerator.isZero
        fun json(): JsonObject = buildJsonObject {
            put("canonical_sides", buildJsonObject { put("left", left.json()); put("right", right.json()) })
            put("left_nonzero_polynomials", JsonArray(leftConditions.map { it.json() }))
            put("right_nonzero_polynomials", JsonArray(rightConditions.map { it.json() }))
            put("canonical_difference", difference.json()); put("excluded_squarefree_coefficients", excluded.json())
            put("solution_kind", if (cofinite) "complement_of_finite_real_roots" else "finite_real_roots")
            put("root_set_polynomial", rootSet.json())
        }
    }

    private suspend fun include(excluded: Polynomial, conditions: Collection<Polynomial>): Polynomial {
        var result = excluded
        for (condition in conditions) {
            currentCoroutineContext().ensureActive()
            result = CriticalPolynomials.union(result, CriticalPolynomials.squareFree(condition))
        }
        return result
    }

    private suspend fun equation(leftText: String, rightText: String, nodes: MutableList<Int>): Equation {
        val leftPolicy = RationalFunctionArithmetic()
        val left = ExactExpressionParser(leftText, leftPolicy).parse().also { nodes += it.astNodes }.value
        currentCoroutineContext().ensureActive()
        val rightPolicy = RationalFunctionArithmetic()
        val right = ExactExpressionParser(rightText, rightPolicy).parse().also { nodes += it.astNodes }.value
        val difference = left.add(right, subtract = true)
        var excluded = include(Polynomial.ONE, leftPolicy.nonzeroPolynomials)
        excluded = include(excluded, rightPolicy.nonzeroPolynomials)
        excluded = CriticalPolynomials.union(excluded, CriticalPolynomials.squareFree(difference.denominator))
        val numeratorRoots = if (difference.numerator.isZero) null else CriticalPolynomials.squareFree(difference.numerator)
        val roots = numeratorRoots?.exactQuotient(numeratorRoots.gcd(excluded)) ?: excluded
        return Equation(left, right, leftPolicy.nonzeroPolynomials, rightPolicy.nonzeroPolynomials, difference, excluded, roots)
    }

    private suspend fun rootCertificate(polynomial: Polynomial): JsonObject {
        val sturm = SturmSequence.build(polynomial)
        val isolation = ExactRealRootIsolation.isolate(polynomial, sturm)
        return buildJsonObject {
            put("polynomial", polynomial.json()); put("sturm_sequence", sturm.json())
            put("root_bound", isolation.bound.toString())
            put("bound_variations", buildJsonObject { put("negative", isolation.lowerVariations); put("positive", isolation.upperVariations) })
            put("real_root_count", isolation.roots.size)
            put("roots", JsonArray(isolation.roots.mapIndexed { index, root -> root.json(index) }))
            put("isolation_work_nodes", isolation.workNodes)
        }
    }

    suspend fun verify(beforeLeft: String, beforeRight: String, afterLeft: String, afterRight: String, nodes: MutableList<Int>): JsonObject {
        val before = equation(beforeLeft, beforeRight, nodes)
        currentCoroutineContext().ensureActive()
        val after = equation(afterLeft, afterRight, nodes)
        if (before.cofinite != after.cofinite) return buildJsonObject {
            put("status", "DISPROVED"); put("verified", false); put("equivalent", false)
            put("proof_kind", "finite_vs_cofinite_real_solution_sets")
            put("before", before.json()); put("after", after.json())
        }
        val common = before.rootSet.gcd(after.rootSet)
        val beforeOnly = before.rootSet.exactQuotient(common)
        val afterOnly = after.rootSet.exactQuotient(common)
        // For complements, a new excluded root removes a solution, not adds one.
        val removed = rootCertificate(if (before.cofinite) afterOnly else beforeOnly)
        val added = rootCertificate(if (before.cofinite) beforeOnly else afterOnly)
        val equivalent = removed.getValue("real_root_count").jsonPrimitive.int == 0 && added.getValue("real_root_count").jsonPrimitive.int == 0
        return buildJsonObject {
            put("status", if (equivalent) "VERIFIED" else "DISPROVED"); put("verified", equivalent); put("equivalent", equivalent)
            put("proof_kind", "exact_real_solution_set_symmetric_difference")
            put("before", before.json()); put("after", after.json())
            put("common_root_factor", common.json())
            put("removed_solutions", removed); put("added_solutions", added)
        }
    }
}
