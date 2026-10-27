package me.rerere.rikkahub.feature.math

import java.math.BigInteger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Exact rational limits. A missing original point value does not prevent a punctured-domain limit. */
internal object RationalLimit {
    private val rationalPoint = Regex("[+-]?[0-9]+(?:/[+-]?[0-9]+)?")
    private val positiveInfinity = buildJsonObject { put("kind", "positive_infinity") }
    private val negativeInfinity = buildJsonObject { put("kind", "negative_infinity") }
    private fun infinite(sign: Int) = if (sign > 0) positiveInfinity else negativeInfinity
    private fun finite(value: Rational) = buildJsonObject { put("kind", "finite"); put("value", value.toString()) }

    private fun point(text: String): Rational {
        if (text.length > QxLimits.CHARS || !rationalPoint.matches(text)) throw MathUnsupported("rational_limit_point_required")
        val slash = text.indexOf('/')
        val numerator = BigInteger(if (slash < 0) text else text.substring(0, slash))
        val denominator = if (slash < 0) BigInteger.ONE else BigInteger(text.substring(slash + 1))
        if (denominator.signum() == 0) throw MathUnsupported("nonzero_limit_point_denominator_required")
        if (maxOf(numerator.abs().bitLength(), denominator.abs().bitLength()) > QxLimits.BITS) throw MathUnsupported("limit_point_bits_limit")
        return Rational.fraction(numerator, denominator)
    }

    suspend fun verify(expression: String, at: String, side: String, nodes: MutableList<Int>): JsonObject {
        if (side != "left" && side != "right" && side != "both") throw MathUnsupported("unsupported_limit_side")
        val infinity = at == "+inf" || at == "-inf"
        if (infinity && side != "both") throw MathUnsupported("infinite_limit_requires_both_side")
        val target = if (infinity) null else point(at)
        val policy = RationalFunctionArithmetic()
        val value = ExactExpressionParser(expression, policy).parse().also { nodes += it.astNodes }.value
        currentCoroutineContext().ensureActive()
        return buildJsonObject {
            put("status", "VERIFIED"); put("verified", true)
            put("at", target?.toString() ?: at); put("side", side)
            put("canonical_rational_function", value.json())
            put("original_domain", buildJsonObject {
                put("ambient", "real_numbers"); put("predicate", "all_polynomials_nonzero")
                put("nonzero_polynomials", JsonArray(policy.nonzeroPolynomials.map { it.json() }))
            })
            if (target == null) {
                val numeratorLead = value.numerator.leadingCoefficient
                val denominatorLead = value.denominator.leadingCoefficient
                val ratio = numeratorLead.multiply(denominatorLead.reciprocal())
                val degreeDifference = value.numerator.degree - value.denominator.degree
                val result = when {
                    value.numerator.isZero || degreeDifference < 0 -> finite(Rational.ZERO)
                    degreeDifference == 0 -> finite(ratio)
                    else -> infinite(ratio.sign * if (at == "-inf" && degreeDifference % 2 != 0) -1 else 1)
                }
                put("limit", result)
                put("proof", buildJsonObject {
                    put("kind", "exact_degree_and_leading_coefficients")
                    put("numerator_degree", value.numerator.degree); put("denominator_degree", value.denominator.degree)
                    put("numerator_leading_coefficient", numeratorLead.toString()); put("denominator_leading_coefficient", denominatorLead.toString())
                    put("leading_ratio", ratio.toString()); put("degree_difference", degreeDifference)
                })
            } else {
                val numerator = value.numerator.value(target, RealRootLimits.EVALUATION_BITS)
                val denominator = value.denominator.value(target, RealRootLimits.EVALUATION_BITS)
                val defined = !denominator.isZero && policy.nonzeroPolynomials.all { !it.value(target, RealRootLimits.EVALUATION_BITS).isZero }
                put("point_defined", defined)
                put("point_value", if (defined) JsonPrimitive(numerator.multiply(denominator.reciprocal()).toString()) else JsonNull)
                val left: JsonObject
                val right: JsonObject
                if (!denominator.isZero) {
                    val limit = finite(numerator.multiply(denominator.reciprocal()))
                    left = limit; right = limit
                    put("proof", buildJsonObject {
                        put("kind", "exact_rational_continuity_on_punctured_original_domain")
                        put("numerator_at_point", numerator.toString()); put("denominator_at_point", denominator.toString())
                    })
                } else {
                    check(!numerator.isZero) { "Reduced rational function has a common rational root" }
                    var order = 0
                    var derivative = value.denominator
                    var firstNonzero = denominator
                    while (firstNonzero.isZero) {
                        currentCoroutineContext().ensureActive()
                        order++
                        derivative = derivative.derivative()
                        firstNonzero = derivative.value(target, RealRootLimits.EVALUATION_BITS)
                    }
                    val sign = numerator.sign * firstNonzero.sign
                    right = infinite(sign)
                    left = infinite(if (order % 2 == 0) sign else -sign)
                    put("proof", buildJsonObject {
                        put("kind", "exact_reduced_rational_pole_order")
                        put("numerator_at_point", numerator.toString()); put("denominator_at_point", denominator.toString())
                        put("denominator_zero_order", order); put("first_nonzero_denominator_derivative", firstNonzero.toString())
                    })
                }
                put("one_sided_limits", buildJsonObject { put("left", left); put("right", right) })
                put("limit", when (side) {
                    "left" -> left
                    "right" -> right
                    else -> if (left == right) left else buildJsonObject { put("kind", "does_not_exist"); put("reason", "unequal_one_sided_limits") }
                })
            }
        }
    }
}
