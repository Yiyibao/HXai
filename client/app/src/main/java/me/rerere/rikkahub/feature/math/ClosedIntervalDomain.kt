package me.rerere.rikkahub.feature.math

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Preserve syntactic exclusions: a reduced value does not define a removable hole. */
internal object ClosedIntervalDomain {
    suspend fun certify(polynomials: Collection<Polynomial>, lower: Rational, upper: Rational): JsonArray {
        check(lower <= upper)
        val conditions = LinkedHashMap<JsonObject, Polynomial>()
        for (polynomial in polynomials) {
            currentCoroutineContext().ensureActive()
            if (polynomial.isZero) throw MathUnsupported("original_domain_not_defined_on_closed_interval")
            if (polynomial.degree > 0) {
                val normalized = polynomial.monic()
                conditions.putIfAbsent(normalized.json(), normalized)
            }
        }
        return buildJsonArray {
            for (condition in conditions.values) {
                currentCoroutineContext().ensureActive()
                val squareFree = CriticalPolynomials.squareFree(condition)
                val sturm = SturmSequence.build(squareFree)
                val left = sturm.at(lower); val right = if (lower.compareTo(upper) == 0) left else sturm.at(upper)
                val interior = if (lower.compareTo(upper) == 0) 0 else left.variations - right.variations - if (right.isRoot) 1 else 0
                if (left.isRoot || right.isRoot || interior != 0) throw MathUnsupported("original_domain_not_defined_on_closed_interval")
                add(buildJsonObject {
                    put("nonzero_polynomial", condition.json()); put("square_free_polynomial", squareFree.json())
                    put("sturm_sequence", sturm.json()); put("lower_variations", left.variations); put("upper_variations", right.variations)
                    put("lower_is_root", false); put("upper_is_root", false); put("open_interval_root_count", interior)
                })
            }
        }
    }
}
