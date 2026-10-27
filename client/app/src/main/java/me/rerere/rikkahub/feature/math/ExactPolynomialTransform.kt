package me.rerere.rikkahub.feature.math

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Exact coefficients of p(offset+scale*t); descending updates preserve each previous Horner row. */
internal object ExactPolynomialTransform {
    suspend fun affine(polynomial: Polynomial, offset: Rational, scale: Rational, maxBits: Int,
        limitReason: String): Array<Rational> {
        fun checked(value: Rational): Rational {
            if (maxOf(value.numerator.abs().bitLength(), value.denominator.bitLength()) > maxBits) throw MathUnsupported(limitReason)
            return value
        }
        val values = Array(polynomial.degree + 1) { Rational.ZERO }
        for (power in polynomial.degree downTo 0) {
            currentCoroutineContext().ensureActive()
            for (k in polynomial.degree - power downTo 1) values[k] = checked(values[k].multiply(offset).add(values[k - 1].multiply(scale)))
            values[0] = checked(values[0].multiply(offset).add(polynomial.coefficient(power)))
        }
        return values
    }
}
