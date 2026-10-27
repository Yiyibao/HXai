package me.rerere.rikkahub.feature.math

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Proper integrals certified by a supplied rational primitive, never principal values or hole filling. */
internal object RationalPrimitiveIntegral {
    const val EVALUATION_BITS = 4 * (QxLimits.DEGREE + 1) * QxLimits.BITS + 128
    private fun bounded(value: Rational): Rational {
        if (maxOf(value.numerator.abs().bitLength(), value.denominator.bitLength()) > EVALUATION_BITS) throw MathUnsupported("rational_integral_evaluation_limit")
        return value
    }
    private fun at(value: RationalFunction, point: Rational): Rational = bounded(
        value.numerator.value(point, EVALUATION_BITS).multiply(value.denominator.value(point, EVALUATION_BITS).reciprocal()))

    suspend fun verify(expression: String, primitive: String, lower: String, upper: String,
        claim: String, nodes: MutableList<Int>): JsonObject {
        val a = PolynomialIntegral.number(lower); val b = PolynomialIntegral.number(upper); val expected = PolynomialIntegral.number(claim)
        val integrandPolicy = RationalFunctionArithmetic(); val primitivePolicy = RationalFunctionArithmetic()
        val integrand = ExactExpressionParser(expression, integrandPolicy).parse().also { nodes += it.astNodes }.value
        currentCoroutineContext().ensureActive()
        val candidate = ExactExpressionParser(primitive, primitivePolicy).parse().also { nodes += it.astNodes }.value
        val lo = minOf(a, b); val hi = maxOf(a, b)
        val checks = ClosedIntervalDomain.certify(
            integrandPolicy.nonzeroPolynomials + primitivePolicy.nonzeroPolynomials +
                listOf(integrand.denominator, candidate.denominator), lo, hi)
        currentCoroutineContext().ensureActive()
        val derivative = candidate.derivative()
        val difference = derivative.numerator.multiply(integrand.denominator).add(integrand.numerator.multiply(derivative.denominator), subtract = true)
        val validPrimitive = difference.isZero
        val atLower = if (validPrimitive) at(candidate, a) else null
        val atUpper = if (!validPrimitive) null else if (a.compareTo(b) == 0) atLower else at(candidate, b)
        val value = if (validPrimitive) bounded(checkNotNull(atUpper).add(checkNotNull(atLower).negate())) else null
        val claimDifference = value?.let { bounded(it.add(expected.negate())) }
        return buildJsonObject {
            put("status", if (validPrimitive && checkNotNull(claimDifference).isZero) "VERIFIED" else "DISPROVED")
            put("verified", validPrimitive && checkNotNull(claimDifference).isZero)
            put("proof_kind", "exact_rational_primitive_sturm_domain_and_newton_leibniz")
            put("certificate_status", if (validPrimitive) "VERIFIED" else "DISPROVED")
            put("claim_status", if (!validPrimitive) "NOT_EVALUATED" else if (checkNotNull(claimDifference).isZero) "VERIFIED" else "DISPROVED")
            put("integrand", integrand.json()); put("primitive", candidate.json()); put("primitive_derivative", derivative.json())
            put("derivative_cross_difference", difference.json())
            put("integrand_nonzero_polynomials", JsonArray(integrandPolicy.nonzeroPolynomials.map { it.json() }))
            put("primitive_nonzero_polynomials", JsonArray(primitivePolicy.nonzeroPolynomials.map { it.json() }))
            put("interval_domain_checks", checks)
            put("closed_interval", buildJsonObject { put("lower", lo.toString()); put("upper", hi.toString()); put("every_point_defined", true) })
            put("lower", a.toString()); put("upper", b.toString()); put("claimed_value", expected.toString())
            if (validPrimitive) {
                put("endpoint_values", buildJsonObject { put("lower", checkNotNull(atLower).toString()); put("upper", checkNotNull(atUpper).toString()) })
                put("exact_value", checkNotNull(value).toString()); put("claim_difference", checkNotNull(claimDifference).toString())
            } else put("reason", "supplied_primitive_derivative_mismatch_claim_not_evaluated")
            put("integration_domain", "oriented_finite_closed_interval_in_both_original_real_domains")
            put("continuity_basis", "rational_functions_defined_at_every_point_of_closed_interval")
        }
    }
}
