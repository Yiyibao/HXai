package me.rerere.rikkahub.feature.math

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Exact finite closed-interval bounds only where BOTH original expressions are defined everywhere. */
internal object RationalSturmBound {
    private fun absolute(value: Rational) = if (value.sign < 0) value.negate() else value
    private fun at(value: RationalFunction, x: Rational): Rational {
        val result = value.numerator.value(x, RealRootLimits.EVALUATION_BITS)
            .multiply(value.denominator.value(x, RealRootLimits.EVALUATION_BITS).reciprocal())
        if (maxOf(result.numerator.abs().bitLength(), result.denominator.bitLength()) > RealRootLimits.EVALUATION_BITS) {
            throw MathUnsupported("rational_bound_evaluation_limit")
        }
        return result
    }
    private fun obligation(factor: Polynomial, denominator: Polynomial): Polynomial {
        if (!factor.isZero && factor.degree + denominator.degree > RealRootLimits.DEGREE) {
            throw MathUnsupported("bound_obligation_degree_limit")
        }
        return factor.multiply(denominator)
    }

    suspend fun verify(expression: String, approximation: String, lower: String, upper: String,
        claimedBound: String, nodes: MutableList<Int>): JsonObject {
        val a = PolynomialIntegral.number(lower); val b = PolynomialIntegral.number(upper); val claim = PolynomialIntegral.number(claimedBound)
        if (a > b) throw MathUnsupported("ordered_finite_interval_required")
        if (claim.sign < 0) throw MathUnsupported("nonnegative_uniform_bound_required")
        val sourcePolicy = RationalFunctionArithmetic(); val approximatePolicy = RationalFunctionArithmetic()
        val source = ExactExpressionParser(expression, sourcePolicy).parse().also { nodes += it.astNodes }.value
        currentCoroutineContext().ensureActive()
        val approximate = ExactExpressionParser(approximation, approximatePolicy).parse().also { nodes += it.astNodes }.value
        val error = source.add(approximate, subtract = true)
        val checks = ClosedIntervalDomain.certify(sourcePolicy.nonzeroPolynomials + approximatePolicy.nonzeroPolynomials +
            listOf(source.denominator, approximate.denominator, error.denominator), a, b)
        val constant = Polynomial.constant(claim.numerator).divide(Polynomial.constant(claim.denominator))
        val scaledDenominator = constant.multiply(error.denominator)
        // D is nonzero on the whole interval. Multiplication by D^2 preserves BOTH inequality directions.
        val upperPolynomial = obligation(scaledDenominator.add(error.numerator, subtract = true), error.denominator)
        val lowerPolynomial = obligation(scaledDenominator.add(error.numerator), error.denominator)
        val upperProof = PolynomialSturmBound.nonnegative(upperPolynomial, a, b)
        val lowerProof = PolynomialSturmBound.nonnegative(lowerPolynomial, a, b)
        val witness = upperProof.negativeAt ?: lowerProof.negativeAt
        val certificate = buildJsonObject {
            put("status", if (witness == null) "VERIFIED" else "DISPROVED"); put("verified", witness == null)
            put("claim_status", if (witness == null) "VERIFIED" else "DISPROVED")
            put("proof_kind", "exact_original_domain_and_denominator_squared_sturm_bound")
            put("source", source.json()); put("approximation", approximate.json()); put("error", error.json())
            put("source_nonzero_polynomials", JsonArray(sourcePolicy.nonzeroPolynomials.map { it.json() }))
            put("approximation_nonzero_polynomials", JsonArray(approximatePolicy.nonzeroPolynomials.map { it.json() }))
            put("interval_domain_checks", checks)
            put("closed_interval", buildJsonObject { put("lower", a.toString()); put("upper", b.toString()); put("every_point_defined", true) })
            put("lower", a.toString()); put("upper", b.toString()); put("claimed_bound", claim.toString())
            put("polynomial_reduction", "upper=(claim*D-N)*D;lower=(claim*D+N)*D;D_squared_positive_on_closed_interval")
            put("upper_obligation", upperProof.certificate); put("lower_obligation", lowerProof.certificate)
            if (witness != null) {
                val value = at(error, witness)
                check(absolute(value) > claim)
                put("counterexample", buildJsonObject {
                    put("x", witness.toString()); put("source_value", at(source, witness).toString()); put("approximation_value", at(approximate, witness).toString())
                    put("error", value.toString()); put("absolute_error", absolute(value).toString())
                })
            }
        }
        if (PolynomialSturmBound.size(certificate) + 8192 > PolynomialSturmBound.MAX_CERTIFICATE_CHARS) {
            throw MathUnsupported("rational_bound_certificate_size_limit")
        }
        return certificate
    }
}
