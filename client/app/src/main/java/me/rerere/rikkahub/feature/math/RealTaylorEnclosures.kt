package me.rerere.rikkahub.feature.math

import java.math.BigDecimal
import java.util.IdentityHashMap

/** Coefficient k encloses f^(k)/k! at every real base point in the input interval. */
internal class RealTaylorEnclosures(val real: RealEnclosures, val order: Int) : RealAlgebra<Array<DecimalEnclosure>> {
    private val literals = IdentityHashMap<Rational, Array<DecimalEnclosure>>()
    private val constants = HashMap<String, Array<DecimalEnclosure>>()
    private val integers = Array(2 * order + 2) { real.point(BigDecimal(it)) }
    private val logTen by lazy { real.function("ln", real.point(BigDecimal.TEN)) }
    private fun zero(a: DecimalEnclosure) = a.lower.signum() == 0 && a.upper.signum() == 0
    fun scalar(value: DecimalEnclosure) = Array(order + 1) { if (it == 0) value else real.zero }
    fun variable(value: DecimalEnclosure) = Array(order + 1) { when (it) { 0 -> value; 1 -> real.one; else -> real.zero } }
    override fun rational(value: Rational): Array<DecimalEnclosure> = literals.getOrPut(value) { scalar(real.rational(value)) }
    override fun constant(name: String) = constants.getOrPut(name) { scalar(real.constant(name)) }
    override fun negate(a: Array<DecimalEnclosure>) = Array(order + 1) { real.negate(a[it]) }
    override fun add(a: Array<DecimalEnclosure>, b: Array<DecimalEnclosure>) = Array(order + 1) { real.add(a[it], b[it]) }
    override fun subtract(a: Array<DecimalEnclosure>, b: Array<DecimalEnclosure>) = Array(order + 1) { real.subtract(a[it], b[it]) }
    override fun multiply(a: Array<DecimalEnclosure>, b: Array<DecimalEnclosure>) = Array(order + 1) { n ->
        var sum = real.zero
        for (k in 0..n) if (!zero(a[k]) && !zero(b[n - k])) sum = real.add(sum, real.multiply(a[k], b[n - k]))
        sum
    }
    override fun divide(a: Array<DecimalEnclosure>, b: Array<DecimalEnclosure>): Array<DecimalEnclosure> {
        val result = Array(order + 1) { real.zero }
        result[0] = real.divide(a[0], b[0]) // Preserve the original denominator domain even if numerators cancel.
        for (n in 1..order) {
            var numerator = a[n]
            for (k in 1..n) if (!zero(b[k]) && !zero(result[n - k])) numerator = real.subtract(numerator, real.multiply(b[k], result[n - k]))
            result[n] = real.divide(numerator, b[0])
        }
        return result
    }
    override fun power(a: Array<DecimalEnclosure>, exponent: Int): Array<DecimalEnclosure> {
        if (exponent !in -32..32) throw MathUnsupported("numeric_power_limit")
        if (exponent == 0) return scalar(real.power(a[0], 0))
        if (exponent < 0) return divide(scalar(real.one), power(a, -exponent))
        var result = scalar(real.one); var base = a; var remaining = exponent
        while (remaining > 0) {
            if (remaining and 1 != 0) result = multiply(result, base)
            remaining = remaining ushr 1
            if (remaining > 0) base = multiply(base, base)
        }
        return result
    }
    private fun exp(a: Array<DecimalEnclosure>): Array<DecimalEnclosure> {
        val result = Array(order + 1) { real.zero }; result[0] = real.function("exp", a[0])
        for (n in 1..order) {
            var sum = real.zero
            for (k in 1..n) if (!zero(a[k])) sum = real.add(sum, real.multiply(real.multiply(integers[k], a[k]), result[n - k]))
            result[n] = real.divide(sum, integers[n])
        }
        return result
    }
    private fun sineCosine(a: Array<DecimalEnclosure>): Pair<Array<DecimalEnclosure>, Array<DecimalEnclosure>> {
        val sine = Array(order + 1) { real.zero }; val cosine = Array(order + 1) { real.zero }
        sine[0] = real.function("sin", a[0]); cosine[0] = real.function("cos", a[0])
        for (n in 1..order) {
            var s = real.zero; var c = real.zero
            for (k in 1..n) if (!zero(a[k])) {
                val derivative = real.multiply(integers[k], a[k])
                s = real.add(s, real.multiply(derivative, cosine[n - k]))
                c = real.subtract(c, real.multiply(derivative, sine[n - k]))
            }
            sine[n] = real.divide(s, integers[n]); cosine[n] = real.divide(c, integers[n])
        }
        return sine to cosine
    }
    override fun function(name: String, a: Array<DecimalEnclosure>): Array<DecimalEnclosure> {
        return when (name) {
            "exp" -> exp(a)
            "ln", "log" -> {
                val result = Array(order + 1) { real.zero }
                result[0] = real.function("ln", a[0])
                for (n in 1..order) {
                    var numerator = real.multiply(integers[n], a[n])
                    for (k in 1 until n) if (!zero(result[k]) && !zero(a[n - k])) {
                        numerator = real.subtract(numerator, real.multiply(real.multiply(integers[k], result[k]), a[n - k]))
                    }
                    result[n] = real.divide(numerator, real.multiply(integers[n], a[0]))
                }
                if (name == "log") divide(result, scalar(logTen)) else result
            }
            "sqrt" -> {
                if (a[0].lower.signum() <= 0) throw MathUnsupported("calculus_sqrt_not_smooth_on_closed_interval")
                val result = Array(order + 1) { real.zero }; result[0] = real.function("sqrt", a[0])
                val denominator = real.multiply(integers[2], result[0])
                for (n in 1..order) {
                    var numerator = a[n]
                    for (k in 1 until n) numerator = real.subtract(numerator, real.multiply(result[k], result[n - k]))
                    result[n] = real.divide(numerator, denominator)
                }
                result
            }
            "sin", "cos", "tan" -> {
                val (sine, cosine) = sineCosine(a)
                when (name) { "sin" -> sine; "cos" -> cosine; else -> divide(sine, cosine) }
            }
            "abs" -> when {
                a[0].lower.signum() > 0 -> a
                a[0].upper.signum() < 0 -> negate(a)
                else -> throw MathUnsupported("calculus_abs_not_smooth_or_sign_unresolved")
            }
            else -> throw MathUnsupported("unsupported_numeric_function")
        }
    }
}
