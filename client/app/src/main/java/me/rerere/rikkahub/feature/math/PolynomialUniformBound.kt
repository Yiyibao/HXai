package me.rerere.rikkahub.feature.math

import java.math.BigInteger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** A sufficient exact convex-hull certificate; a tighter unproved bound is not a disproof. */
internal object PolynomialUniformBound {
    const val EVALUATION_BITS = 4 * (QxLimits.DEGREE + 2) * QxLimits.BITS + 128
    private object BoundArithmetic : ExpressionArithmetic<Polynomial> by PolynomialArithmetic {
        override fun power(value: Polynomial, exponent: Int): Polynomial {
            if (exponent == 0 && value.isZero) throw MathUnsupported("undefined_zero_power")
            return PolynomialArithmetic.power(value, exponent)
        }
    }
    private fun bounded(value: Rational): Rational {
        if (maxOf(value.numerator.abs().bitLength(), value.denominator.bitLength()) > EVALUATION_BITS) throw MathUnsupported("bernstein_coefficient_limit")
        return value
    }
    private fun absolute(value: Rational) = if (value.sign < 0) value.negate() else value

    suspend fun verify(expression: String, approximation: String, lower: String, upper: String,
        claimedBound: String, nodes: MutableList<Int>, maxDepth: Int? = null): JsonObject {
        val a = PolynomialIntegral.number(lower); val b = PolynomialIntegral.number(upper); val claim = PolynomialIntegral.number(claimedBound)
        if (a > b) throw MathUnsupported("ordered_finite_interval_required")
        if (claim.sign < 0) throw MathUnsupported("nonnegative_uniform_bound_required")
        val source = ExactExpressionParser(expression, BoundArithmetic).parse().also { nodes += it.astNodes }.value
        currentCoroutineContext().ensureActive()
        val approximate = ExactExpressionParser(approximation, BoundArithmetic).parse().also { nodes += it.astNodes }.value
        val error = source.add(approximate, subtract = true)
        val width = b.add(a.negate())
        val power = ExactPolynomialTransform.affine(error, a, width, EVALUATION_BITS, "bernstein_coefficient_limit")
        val n = error.degree
        val denominators = IntArray(n + 1)
        var choose = 1L
        for (i in 0..n) { denominators[i] = choose.toInt(); choose = choose * (n - i) / (i + 1) }
        val bernstein = Array(n + 1) { Rational.ZERO }
        var hullLower = Rational.ZERO; var hullUpper = Rational.ZERO
        for (k in 0..n) {
            currentCoroutineContext().ensureActive()
            var numerator = 1L; var sum = Rational.ZERO
            for (i in 0..k) {
                if (!power[i].isZero) {
                    val ratio = Rational.fraction(BigInteger.valueOf(numerator), BigInteger.valueOf(denominators[i].toLong()))
                    sum = bounded(sum.add(power[i].multiply(ratio)))
                }
                numerator = numerator * (k - i) / (i + 1)
            }
            bernstein[k] = sum
            if (k == 0 || sum < hullLower) hullLower = sum
            if (k == 0 || sum > hullUpper) hullUpper = sum
        }
        val certified = maxOf(absolute(hullLower), absolute(hullUpper))
        val accepted = claim >= certified
        val certificate = buildJsonObject {
            put("status", if (accepted) "VERIFIED" else "UNSUPPORTED"); put("verified", accepted)
            put("claim_status", if (accepted) "VERIFIED" else "NOT_CERTIFIED")
            if (!accepted) put("reason", "claimed_bound_not_certified_by_bernstein_hull")
            put("proof_kind", "exact_bernstein_nonnegative_partition_of_unity_convex_hull")
            put("bound_kind", "sufficient_not_necessarily_sharp")
            put("source_coefficients", source.json()); put("approximation_coefficients", approximate.json()); put("error_coefficients", error.json())
            put("lower", a.toString()); put("upper", b.toString()); put("interval_width", width.toString()); put("claimed_bound", claim.toString())
            put("coordinate", "x=lower+interval_width*t,t_in_closed_unit_interval")
            put("unit_interval_power_coefficients", buildJsonObject { for (i in power.indices) if (!power[i].isZero) put(i.toString(), power[i].toString()) })
            put("bernstein_degree", n); put("bernstein_coefficients", JsonArray(bernstein.map { JsonPrimitive(it.toString()) }))
            put("bernstein_basis", "binomial(n,k)*t^k*(1-t)^(n-k)")
            put("error_enclosure", buildJsonObject { put("lower", hullLower.toString()); put("upper", hullUpper.toString()) })
            put("certified_absolute_bound", certified.toString())
            put("claim_comparison", "claimed_bound_greater_than_or_equal_to_certified_absolute_bound")
        }
        return if (maxDepth == null) certificate
        else PolynomialUniformSubdivision.certify(certificate, a, b, claim, bernstein, maxDepth)
    }
}
