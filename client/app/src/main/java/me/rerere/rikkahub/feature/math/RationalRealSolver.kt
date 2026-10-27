package me.rerere.rikkahub.feature.math

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

internal object RationalRealSolver {
    val inequalityRelations = setOf(">", ">=", "<", "<=")
    private fun selected(sign: Int, relation: String): Boolean = when (relation) {
        ">" -> sign > 0
        ">=" -> sign >= 0
        "<" -> sign < 0
        "<=" -> sign <= 0
        "==" -> sign == 0
        else -> throw MathUnsupported("unsupported_real_relation")
    }
    private fun endpoint(index: Int, rootCount: Int): JsonObject = buildJsonObject {
        when (index) {
            -1 -> put("kind", "negative_infinity")
            rootCount -> put("kind", "positive_infinity")
            else -> { put("kind", "root"); put("index", index) }
        }
    }
    private fun interval(lower: Int, upper: Int, lowerClosed: Boolean, upperClosed: Boolean, roots: Int): JsonObject = buildJsonObject {
        put("lower", endpoint(lower, roots)); put("upper", endpoint(upper, roots))
        put("lower_closed", lowerClosed); put("upper_closed", upperClosed)
    }
    /** The same ordered real root is referenced everywhere, not an unverified decimal approximation. */
    fun intervals(regions: BooleanArray, rootCount: Int): JsonArray {
        val intervals = ArrayList<JsonElement>()
        var start = 0
        while (start < regions.size) {
            if (!regions[start]) { start++; continue }
            var end = start
            while (end + 1 < regions.size && regions[end + 1]) end++
            val lower = if (start % 2 == 0) start / 2 - 1 else start / 2
            val upper = end / 2
            intervals.add(interval(lower, upper, start % 2 == 1, end % 2 == 1, rootCount))
            start = end + 1
        }
        return JsonArray(intervals)
    }
    suspend fun solve(value: RationalFunction, originalConditions: Collection<Polynomial>, relation: String): JsonObject {
        if (relation !in inequalityRelations && relation != "==") throw MathUnsupported("unsupported_real_relation")
        val domain = LinkedHashMap<JsonObject, Polynomial>()
        for (condition in originalConditions) domain.putIfAbsent(condition.json(), condition)
        if (value.denominator.degree > 0) {
            val denominator = value.denominator.monic()
            domain.putIfAbsent(denominator.json(), denominator)
        }
        var excluded = Polynomial.ONE
        for (condition in domain.values) {
            currentCoroutineContext().ensureActive()
            excluded = CriticalPolynomials.union(excluded, CriticalPolynomials.squareFree(condition))
        }
        val critical = CriticalPolynomials.union(excluded, CriticalPolynomials.squareFree(value.numerator))
        val sturm = SturmSequence.build(critical)
        val isolation = ExactRealRootIsolation.isolate(critical, sturm)
        val roots = isolation.roots
        val excludedSturm = when {
            roots.isEmpty() || excluded.degree == 0 -> null
            // Both are monic and excluded divides critical, so equal degrees mean the same polynomial.
            excluded.degree == critical.degree -> sturm
            else -> SturmSequence.build(excluded)
        }
        val regions = BooleanArray(roots.size * 2 + 1)
        val rootEvidence = ArrayList<JsonElement>(roots.size)
        for ((index, root) in roots.withIndex()) {
            currentCoroutineContext().ensureActive()
            val forbidden = when {
                excludedSturm == null -> false
                root.isExact -> excluded.value(root.lower, RealRootLimits.EVALUATION_BITS).isZero
                else -> {
                    val count = excludedSturm.countOpen(root.lower, root.upper)
                    check(count in 0..1)
                    count == 1
                }
            }
            val included = !forbidden && selected(0, relation)
            regions[index * 2 + 1] = included
            rootEvidence.add(JsonObject(root.json(index) + mapOf("excluded" to JsonPrimitive(forbidden), "selected" to JsonPrimitive(included))))
        }
        val cells = ArrayList<JsonElement>(roots.size + 1)
        for (index in 0..roots.size) {
            currentCoroutineContext().ensureActive()
            val sample = RealRootLimits.coordinate(when {
                roots.isEmpty() -> Rational.ZERO
                index == 0 -> if (roots.first().isExact) roots.first().lower.add(Rational.ONE.negate()) else roots.first().lower
                index == roots.size -> if (roots.last().isExact) roots.last().upper.add(Rational.ONE) else roots.last().upper
                else -> roots[index - 1].upper.add(roots[index].lower).multiply(RealRootLimits.HALF)
            })
            check(domain.values.all { !it.value(sample, RealRootLimits.EVALUATION_BITS).isZero }) { "Cell sample lies outside original domain" }
            val denominator = value.denominator.value(sample, RealRootLimits.EVALUATION_BITS)
            check(!denominator.isZero)
            val actual = value.numerator.value(sample, RealRootLimits.EVALUATION_BITS).multiply(denominator.reciprocal())
            val chosen = selected(actual.sign, relation)
            regions[index * 2] = chosen
            cells.add(buildJsonObject {
                put("index", index); put("lower", endpoint(index - 1, roots.size)); put("upper", endpoint(index, roots.size))
                put("sample", sample.toString()); put("value", actual.toString()); put("sign", actual.sign); put("selected", chosen)
            })
        }
        return buildJsonObject {
            put("status", "VERIFIED"); put("verified", true)
            put("proof_kind", "exact_sturm_sign_decomposition")
            put("relation", relation); put("compared_to", "0"); put("canonical_rational_function", value.json())
            put("original_nonzero_polynomials", JsonArray(originalConditions.map { it.json() }))
            put("original_domain", buildJsonObject {
                put("ambient", "real_numbers"); put("predicate", "all_polynomials_nonzero"); put("nonzero_polynomials", JsonArray(domain.keys.toList()))
            })
            put("excluded_squarefree_coefficients", excluded.json()); put("critical_squarefree_coefficients", critical.json())
            put("sturm_sequence", sturm.json()); put("root_bound", isolation.bound.toString())
            put("bound_variations", buildJsonObject { put("negative", isolation.lowerVariations); put("positive", isolation.upperVariations) })
            put("real_root_count", roots.size); put("isolation_work_nodes", isolation.workNodes)
            put("roots", JsonArray(rootEvidence)); put("cells", JsonArray(cells)); put("solution_intervals", intervals(regions, roots.size))
        }
    }
}
