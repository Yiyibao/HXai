package me.rerere.rikkahub.feature.math

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Finite polynomial translation and exact remainder, not a theorem about general analytic functions. */
internal object PolynomialTaylor {
    const val SHIFT_BITS = 2 * (QxLimits.DEGREE + 2) * QxLimits.BITS + 128
    private object TaylorArithmetic : ExpressionArithmetic<Polynomial> by PolynomialArithmetic {
        override fun power(value: Polynomial, exponent: Int): Polynomial {
            if (exponent == 0 && value.isZero) throw MathUnsupported("undefined_zero_power")
            return PolynomialArithmetic.power(value, exponent)
        }
    }
    private fun bounded(value: Rational): Rational {
        if (maxOf(value.numerator.abs().bitLength(), value.denominator.bitLength()) > SHIFT_BITS) throw MathUnsupported("taylor_shift_coefficient_limit")
        return value
    }

    suspend fun verify(expression: String, center: String, orderText: String, claims: JsonArray,
        nodes: MutableList<Int>): JsonObject {
        if (orderText.length !in 1..2) throw MathUnsupported("taylor_order_0_to_32_required")
        val order = orderText.toIntOrNull()?.takeIf { it in 0..QxLimits.DEGREE && it.toString() == orderText }
            ?: throw MathUnsupported("taylor_order_0_to_32_required")
        if (claims.size != order + 1 || claims.any { it !is JsonPrimitive || !it.isString }) throw MathUnsupported("taylor_provide_all_order_plus_one_coefficient_strings")
        val a = PolynomialIntegral.number(center)
        val expected = Array(claims.size) { PolynomialIntegral.number(claims[it].jsonPrimitive.content) }
        val polynomial = ExactExpressionParser(expression, TaylorArithmetic).parse().also { nodes += it.astNodes }.value
        val shifted = ExactPolynomialTransform.affine(polynomial, a, Rational.ONE, SHIFT_BITS, "taylor_shift_coefficient_limit")
        fun actual(k: Int) = if (k < shifted.size) shifted[k] else Rational.ZERO
        var firstMismatch: Int? = null
        val differences = Array(order + 1) { k ->
            bounded(actual(k).add(expected[k].negate())).also { if (!it.isZero && firstMismatch == null) firstMismatch = k }
        }
        currentCoroutineContext().ensureActive()
        return buildJsonObject {
            put("status", if (firstMismatch == null) "VERIFIED" else "DISPROVED"); put("verified", firstMismatch == null)
            put("proof_kind", "exact_polynomial_translation_and_remainder_factorization")
            put("center", a.toString()); put("order", order); put("input_coefficients", polynomial.json())
            put("shifted_coefficients", buildJsonObject { for (k in shifted.indices) if (!shifted[k].isZero) put(k.toString(), shifted[k].toString()) })
            put("taylor_coefficients", buildJsonArray { for (k in 0..order) add(actual(k).toString()) })
            put("claimed_coefficients", JsonArray(expected.map { JsonPrimitive(it.toString()) }))
            put("coefficient_differences", JsonArray(differences.map { JsonPrimitive(it.toString()) }))
            put("first_mismatching_power", firstMismatch?.let(::JsonPrimitive) ?: JsonNull)
            put("remainder", buildJsonObject {
                put("kind", "exact_polynomial_factorization"); put("factor_power", order + 1)
                put("quotient_coefficients", buildJsonObject { for (k in order + 1 until shifted.size) if (!shifted[k].isZero) put((k - order - 1).toString(), shifted[k].toString()) })
                put("identically_zero", order >= polynomial.degree)
            })
            put("coordinate", "h=x-center")
            put("identity", "p(center+h)=sum(taylor_coefficients[k]*h^k,k=0..order)+h^(order+1)*remainder.quotient(h)")
            put("identity_uses", "computed_taylor_coefficients_not_unverified_claim")
            put("truncation_equals_input_globally", order >= polynomial.degree)
        }
    }
}
