package me.rerere.rikkahub.feature.math

import java.math.BigInteger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Affine recurrence u(n+1)=A(n)*u(n)+B(n). Denominators are polynomials in n, never in u. */
internal object RationalLinearRecurrence {
    const val PROOF_KIND = "rational_linear_recurrence_exact_base_and_step"

    suspend fun verify(start: Int, initial: Rational, nextSource: String, claimedSource: String): JsonObject {
        val recurrence = AffineExpression(nextSource).parse()
        val claimed = AffineExpression(claimedSource).parse()
        if (!claimed.value.slope.numerator.isZero) throw MathUnsupported("closed_formula_cannot_depend_on_sequence_value")
        val conditions = recurrence.conditions + claimed.conditions + listOf(
            recurrence.value.slope.denominator,
            recurrence.value.intercept.denominator,
            claimed.value.intercept.denominator,
        )
        for (condition in conditions) {
            currentCoroutineContext().ensureActive()
            definedOnIntegers(condition, start)
        }
        val closed = claimed.value.intercept
        val baseHolds = closed.value(start).compareTo(initial) == 0
        val shiftedNumerator = shiftOne(closed.numerator)
        val shiftedDenominator = shiftOne(closed.denominator)
        val slope = recurrence.value.slope
        val intercept = recurrence.value.intercept
        val left = shiftedNumerator.multiply(slope.denominator).multiply(closed.denominator).multiply(intercept.denominator)
        val rightInner = slope.numerator.multiply(closed.numerator).multiply(intercept.denominator)
            .add(intercept.numerator.multiply(slope.denominator).multiply(closed.denominator))
        val difference = left.add(shiftedDenominator.multiply(rightInner), subtract = true)
        val stepHolds = difference.isZero
        val verified = baseHolds && stepHolds
        return buildJsonObject {
            put("status", if (verified) "VERIFIED" else "DISPROVED")
            put("verified", verified)
            put("proof_kind", PROOF_KIND)
            put("integer_domain", "n >= $start")
            put("recurrence", "u(n+1) = A(n)*u(n) + B(n)")
            put("base_holds", baseHolds)
            put("step_holds", stepHolds)
            if (!verified) {
                val witness = if (difference.isZero) start else (start..start + difference.degree).first { !difference.value(it).isZero }
                put("counterexample", buildJsonObject {
                    put("n", witness)
                    put("claimed", closed.value(witness).toString())
                    put("recurrence_value", slope.value(witness).multiply(closed.value(witness)).add(intercept.value(witness)).toString())
                })
            }
            put("notice", "只认证所给线性有理递推在全部整数 n>=start 上的初值与清分母后的归纳步。每个语法分母在约分前都要在该整数域上非零；有零点则方法不支持，不把未定义递推判为假。u 的非线性项或分母中的序列值不支持。有限项相符不是证明，原题措辞仍需核对。")
        }
    }

    private suspend fun definedOnIntegers(polynomial: Polynomial, start: Int) {
        if (polynomial.isZero) throw MathUnsupported("identically_zero_denominator")
        if (polynomial.degree == 0) return
        val bound = polynomial.strictRootBound()
        if (bound.bitLength() > 16) throw MathUnsupported("integer_domain_check_limit")
        val limit = bound.toInt()
        if (start >= limit) return
        if (limit - start > 8192) throw MathUnsupported("integer_domain_check_limit")
        for (n in start until limit) {
            if (n and 63 == 0) currentCoroutineContext().ensureActive()
            if (polynomial.value(n).isZero) throw MathUnsupported("denominator_vanishes_on_integer_domain")
        }
    }

    private fun shiftOne(polynomial: Polynomial): Polynomial {
        var result = Polynomial.ZERO
        val step = Polynomial.X.add(Polynomial.constant(BigInteger.ONE))
        var power = Polynomial.ONE
        for (k in 0..polynomial.degree) {
            val coefficient = polynomial.coefficient(k)
            if (!coefficient.isZero) {
                val scale = Polynomial.constant(coefficient.numerator).divide(Polynomial.constant(coefficient.denominator))
                result = result.add(power.multiply(scale))
            }
            if (k < polynomial.degree) power = power.multiply(step)
        }
        return result
    }
}

private class Affine(val slope: RationalFunction, val intercept: RationalFunction)

private class ParsedAffine(val value: Affine, val conditions: List<Polynomial>)

private class AffineExpression(private val source: String) {
    private var position = 0
    private var depth = 0
    private var nodes = 0
    private val conditions = ArrayList<Polynomial>()

    init {
        if (source.length !in 1..4096) throw MathUnsupported("rational_recurrence_length")
    }

    fun parse(): ParsedAffine {
        val value = sum()
        skip()
        if (position != source.length) throw MathUnsupported("invalid_rational_recurrence")
        return ParsedAffine(value, conditions.toList())
    }

    private fun count() {
        if (++nodes > 256) throw MathUnsupported("rational_recurrence_node_limit")
    }

    private fun skip() {
        while (position < source.length && source[position].isWhitespace()) position++
    }

    private fun token(c: Char): Boolean {
        skip()
        if (position < source.length && source[position] == c) {
            position++
            return true
        }
        return false
    }

    private fun sum(): Affine {
        var value = product()
        while (true) {
            val subtract = if (token('+')) false else if (token('-')) true else return value
            count()
            value = add(value, product(), subtract)
        }
    }

    private fun product(): Affine {
        var value = unary()
        while (true) {
            val divide = if (token('*')) false else if (token('/')) true else return value
            count()
            val other = unary()
            value = if (divide) divide(value, other) else multiply(value, other)
        }
    }

    private fun unary(): Affine {
        if (++depth > 32) throw MathUnsupported("rational_recurrence_depth_limit")
        try {
            if (token('+')) return unary()
            if (token('-')) {
                count()
                return negate(unary())
            }
            var value = atom()
            if (token('^')) {
                skip()
                val start = position
                while (position < source.length && source[position].isDigit()) position++
                val exponent = source.substring(start, position).toIntOrNull()
                    ?: throw MathUnsupported("nonnegative_integer_exponent_required")
                count()
                value = power(value, exponent)
            }
            return value
        } finally {
            depth--
        }
    }

    private fun atom(): Affine {
        count()
        if (token('(')) {
            val value = sum()
            if (!token(')')) throw MathUnsupported("missing_parenthesis")
            return value
        }
        skip()
        val start = position
        if (position < source.length && (source[position].isDigit() || source[position] == '.')) {
            while (position < source.length && (source[position].isDigit() || source[position] == '.' || source[position] == 'e' || source[position] == 'E' || source[position] == '+' || source[position] == '-')) {
                if ((source[position] == '+' || source[position] == '-') && source[position - 1] != 'e' && source[position - 1] != 'E') break
                position++
            }
            return constant(ProfessionalScalar.parse(source.substring(start, position)))
        }
        while (position < source.length && (source[position].isLetterOrDigit() || source[position] == '_')) position++
        return when (source.substring(start, position)) {
            "n" -> Affine(RationalFunction.ZERO, RationalFunction.X)
            "u" -> Affine(RationalFunction.ONE, RationalFunction.ZERO)
            else -> throw MathUnsupported("unknown_rational_recurrence_symbol")
        }
    }

    private fun constant(value: Rational): Affine {
        val function = RationalFunction.constant(value.numerator).divide(RationalFunction.constant(value.denominator))
        return Affine(RationalFunction.ZERO, function)
    }

    private fun negate(value: Affine) = Affine(value.slope.negate(), value.intercept.negate())

    private fun add(left: Affine, right: Affine, subtract: Boolean) = Affine(
        left.slope.add(right.slope, subtract),
        left.intercept.add(right.intercept, subtract),
    )

    private fun multiply(left: Affine, right: Affine): Affine {
        if (!left.slope.numerator.isZero && !right.slope.numerator.isZero) throw MathUnsupported("rational_recurrence_nonlinear_in_u")
        return Affine(
            left.slope.multiply(right.intercept).add(left.intercept.multiply(right.slope)),
            left.intercept.multiply(right.intercept),
        )
    }

    private fun divide(left: Affine, right: Affine): Affine {
        if (!right.slope.numerator.isZero) throw MathUnsupported("sequence_value_in_denominator")
        if (right.intercept.numerator.isZero) throw MathUnsupported("identically_zero_denominator")
        conditions += right.intercept.numerator
        return Affine(left.slope.divide(right.intercept), left.intercept.divide(right.intercept))
    }

    private fun power(value: Affine, exponent: Int): Affine {
        if (exponent !in 0..12) throw MathUnsupported("rational_recurrence_power_limit")
        if (exponent == 0) {
            if (value.slope.numerator.isZero && value.intercept.numerator.isZero) throw MathUnsupported("zero_to_zero_power")
            return Affine(RationalFunction.ZERO, RationalFunction.ONE)
        }
        if (exponent == 1) return value
        if (!value.slope.numerator.isZero) throw MathUnsupported("rational_recurrence_nonlinear_in_u")
        return Affine(RationalFunction.ZERO, value.intercept.power(exponent))
    }
}
