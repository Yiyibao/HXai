package me.rerere.rikkahub.feature.math

import java.math.BigInteger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Finite oriented integrals of globally continuous Q[x], not rational/improper integration. */
internal object PolynomialIntegral {
    const val EVALUATION_BITS = 2 * (QxLimits.DEGREE + 2) * QxLimits.BITS + 64
    private val literal = Regex("[+-]?[0-9]+(?:/[+-]?[0-9]+)?")
    private object IntegralArithmetic : ExpressionArithmetic<Polynomial> by PolynomialArithmetic {
        override fun power(value: Polynomial, exponent: Int): Polynomial {
            if (exponent == 0 && value.isZero) throw MathUnsupported("undefined_zero_power")
            return PolynomialArithmetic.power(value, exponent)
        }
    }

    fun number(text: String): Rational {
        if (text.length > QxLimits.CHARS || !literal.matches(text)) throw MathUnsupported("finite_rational_integral_literal_required")
        val slash = text.indexOf('/')
        val numerator = BigInteger(if (slash < 0) text else text.substring(0, slash))
        val denominator = if (slash < 0) BigInteger.ONE else BigInteger(text.substring(slash + 1))
        if (denominator.signum() == 0) throw MathUnsupported("nonzero_integral_literal_denominator_required")
        if (maxOf(numerator.abs().bitLength(), denominator.abs().bitLength()) > QxLimits.BITS) throw MathUnsupported("integral_literal_bits_limit")
        return Rational.fraction(numerator, denominator)
    }

    suspend fun verify(expression: String, lower: String, upper: String, claim: String, nodes: MutableList<Int>): JsonObject {
        val a = number(lower); val b = number(upper); val expected = number(claim)
        val integrand = ExactExpressionParser(expression, IntegralArithmetic).parse().also { nodes += it.astNodes }.value
        currentCoroutineContext().ensureActive()
        val primitive = integrand.antiderivative()
        val atLower = primitive.value(a, EVALUATION_BITS)
        currentCoroutineContext().ensureActive()
        val atUpper = if (a.numerator == b.numerator && a.denominator == b.denominator) atLower else primitive.value(b, EVALUATION_BITS)
        val value = atUpper.add(atLower.negate())
        val difference = value.add(expected.negate())
        if (maxOf(value.numerator.abs().bitLength(), value.denominator.bitLength(),
                difference.numerator.abs().bitLength(), difference.denominator.bitLength()) > EVALUATION_BITS) throw MathUnsupported("integral_evaluation_limit")
        return buildJsonObject {
            put("status", if (difference.isZero) "VERIFIED" else "DISPROVED"); put("verified", difference.isZero)
            put("proof_kind", "exact_polynomial_primitive_and_newton_leibniz")
            put("integrand_coefficients", integrand.json()); put("antiderivative_coefficients", primitive.json())
            put("lower", a.toString()); put("upper", b.toString()); put("claimed_value", expected.toString())
            put("endpoint_values", buildJsonObject { put("lower", atLower.toString()); put("upper", atUpper.toString()) })
            put("exact_value", value.toString()); put("claim_difference", difference.toString())
            put("integration_domain", "oriented_finite_interval_in_real_numbers")
            put("continuity_basis", "polynomial_continuous_on_all_real_numbers")
        }
    }
}
