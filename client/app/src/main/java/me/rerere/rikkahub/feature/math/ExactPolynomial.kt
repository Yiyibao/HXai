package me.rerere.rikkahub.feature.math

import java.math.BigInteger
import kotlinx.serialization.json.*

internal object QxLimits {
    const val CHARS = 4096
    const val NODES = 256
    const val DEGREE = 32
    const val BITS = 1024
    const val DEPTH = 64
    const val INTERMEDIATE_DEGREE = DEGREE * 2
    const val WITNESS_BITS = (INTERMEDIATE_DEGREE + 1) * BITS + INTERMEDIATE_DEGREE * 14 + 64
}

internal class MathUnsupported(val reason: String) : IllegalArgumentException(reason)

internal class Rational private constructor(val numerator: BigInteger, val denominator: BigInteger) : Comparable<Rational> {
    val isZero get() = numerator.signum() == 0
    val sign get() = numerator.signum()
    override fun compareTo(other: Rational): Int {
        if (this === other) return 0
        if (sign != other.sign) return sign.compareTo(other.sign)
        return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator))
    }
    fun negate() = if (isZero) this else Rational(numerator.negate(), denominator)
    fun add(other: Rational): Rational {
        if (isZero) return other
        if (other.isZero) return this
        val gcd = denominator.gcd(other.denominator)
        val a = other.denominator.divide(gcd)
        val b = denominator.divide(gcd)
        return fraction(numerator.multiply(a).add(other.numerator.multiply(b)), denominator.multiply(a))
    }
    fun multiply(other: Rational): Rational {
        if (isZero || other.isZero) return ZERO
        if (this === ONE) return other
        if (other === ONE) return this
        val a = numerator.abs().gcd(other.denominator)
        val b = other.numerator.abs().gcd(denominator)
        return Rational(numerator.divide(a).multiply(other.numerator.divide(b)), denominator.divide(b).multiply(other.denominator.divide(a)))
    }
    fun timesInteger(value: Int): Rational {
        if (value == 0 || isZero) return ZERO
        if (value == 1) return this
        val n = BigInteger.valueOf(value.toLong()); val gcd = n.abs().gcd(denominator)
        return Rational(numerator.multiply(n.divide(gcd)), denominator.divide(gcd))
    }
    fun reciprocal(): Rational {
        if (isZero) throw MathUnsupported("nonzero_constant_denominator_required")
        return if (numerator.signum() > 0) Rational(denominator, numerator) else Rational(denominator.negate(), numerator.negate())
    }
    override fun toString() = if (denominator == BigInteger.ONE) numerator.toString() else "$numerator/$denominator"
    companion object {
        val ZERO = Rational(BigInteger.ZERO, BigInteger.ONE)
        val ONE = Rational(BigInteger.ONE, BigInteger.ONE)
        fun integer(value: BigInteger) = if (value.signum() == 0) ZERO else if (value == BigInteger.ONE) ONE else Rational(value, BigInteger.ONE)
        fun fraction(numerator: BigInteger, denominator: BigInteger): Rational {
            if (denominator.signum() == 0) throw MathUnsupported("nonzero_constant_denominator_required")
            if (numerator.signum() == 0) return ZERO
            val gcd = numerator.abs().gcd(denominator.abs())
            val sign = if (denominator.signum() < 0) gcd.negate() else gcd
            return Rational(numerator.divide(sign), denominator.divide(sign))
        }
    }
}

internal class Polynomial private constructor(private val coefficients: Array<Rational>, val degree: Int) {
    val isZero get() = degree == 0 && coefficients[0].isZero
    val leadingCoefficient get() = coefficients[degree]
    fun coefficient(power: Int): Rational {
        require(power >= 0)
        return if (power <= degree) coefficients[power] else Rational.ZERO
    }
    fun add(other: Polynomial, subtract: Boolean = false): Polynomial {
        if (other.isZero) return this
        if (isZero) return if (subtract) other.negate() else other
        val values = Array(maxOf(degree, other.degree) + 1) { i ->
            val a = if (i <= degree) coefficients[i] else Rational.ZERO
            val b = if (i <= other.degree) other.coefficients[i] else Rational.ZERO
            a.add(if (subtract) b.negate() else b)
        }
        return checked(values)
    }
    fun negate() = if (isZero) this else checked(Array(degree + 1) { coefficients[it].negate() })
    fun multiply(other: Polynomial): Polynomial {
        if (isZero || other.isZero) return ZERO
        if (this === ONE) return other
        if (other === ONE) return this
        if (degree + other.degree > QxLimits.INTERMEDIATE_DEGREE) throw MathUnsupported("degree_limit")
        val values = Array(degree + other.degree + 1) { Rational.ZERO }
        for (i in 0..degree) if (!coefficients[i].isZero) {
            for (j in 0..other.degree) if (!other.coefficients[j].isZero) {
                values[i + j] = values[i + j].add(coefficients[i].multiply(other.coefficients[j]))
            }
        }
        return checked(values)
    }
    fun divide(other: Polynomial): Polynomial {
        if (other.degree != 0 || other.isZero) throw MathUnsupported("nonzero_constant_denominator_required")
        if (isZero) return ZERO
        if (other === ONE) return this
        val scale = other.coefficients[0].reciprocal()
        return checked(Array(degree + 1) { coefficients[it].multiply(scale) })
    }
    fun power(exponent: Int): Polynomial {
        var remaining = exponent; var base = this; var result = ONE
        while (remaining > 0) {
            if (remaining and 1 != 0) result = result.multiply(base)
            remaining = remaining ushr 1
            if (remaining > 0) base = base.multiply(base)
        }
        return result
    }
    fun derivative() = if (degree == 0) ZERO else checked(Array(degree) { coefficients[it + 1].timesInteger(it + 1) })
    /** Canonical polynomial primitive with integration constant zero. */
    fun antiderivative() = if (isZero) ZERO else checked(Array(degree + 2) { i ->
        if (i == 0 || coefficients[i - 1].isZero) Rational.ZERO
        else Rational.fraction(coefficients[i - 1].numerator,
            coefficients[i - 1].denominator.multiply(BigInteger.valueOf(i.toLong())))
    })
    fun divideByLeadingOf(other: Polynomial): Polynomial {
        if (other.isZero) throw MathUnsupported("identically_zero_denominator")
        val lead = other.coefficients[other.degree]
        if (lead.numerator == BigInteger.ONE && lead.denominator == BigInteger.ONE) return this
        return divide(checked(arrayOf(lead)))
    }
    fun monic(): Polynomial = if (isZero) this else divideByLeadingOf(this)
    fun positiveScaleNormalized(): Polynomial {
        if (isZero) return this
        val lead = coefficients[degree]
        if (lead.denominator == BigInteger.ONE && (lead.numerator == BigInteger.ONE || lead.numerator == BigInteger.valueOf(-1))) return this
        return divide(checked(arrayOf(if (lead.sign < 0) lead.negate() else lead)))
    }
    private fun dividePolynomial(other: Polynomial, exactQuotient: Boolean): Polynomial {
        if (other.isZero) throw MathUnsupported("identically_zero_denominator")
        if (isZero) return ZERO
        if (degree < other.degree) {
            check(!exactQuotient) { "Non-exact internal polynomial division" }
            return this
        }
        if (other.degree == 0) return if (exactQuotient) divide(other) else ZERO
        val values = Array(degree + 1) { coefficients[it] }
        val quotient = if (exactQuotient) Array(degree - other.degree + 1) { Rational.ZERO } else null
        val scale = other.coefficients[other.degree].reciprocal()
        for (i in degree downTo other.degree) {
            if (values[i].isZero) continue
            val factor = values[i].multiply(scale)
            if (quotient != null) quotient[i - other.degree] = factor
            for (j in 0..other.degree) {
                val index = i - other.degree + j
                values[index] = values[index].add(factor.multiply(other.coefficients[j]).negate())
                if (maxOf(values[index].numerator.abs().bitLength(), values[index].denominator.bitLength()) > QxLimits.BITS) {
                    throw MathUnsupported("coefficient_limit")
                }
            }
        }
        if (quotient == null) return checked(values)
        check(values.all { it.isZero }) { "Non-exact internal polynomial division" }
        return checked(quotient)
    }
    fun remainder(other: Polynomial): Polynomial = dividePolynomial(other, exactQuotient = false)
    fun exactQuotient(other: Polynomial): Polynomial = dividePolynomial(other, exactQuotient = true)
    fun gcd(other: Polynomial): Polynomial {
        var a = this
        var b = other
        while (!b.isZero) {
            val remainder = a.remainder(b)
            a = b
            b = remainder
        }
        return a.monic()
    }
    fun value(x: Int): Rational {
        if (x == 0) return coefficients[0]
        var result = coefficients[degree]
        for (i in degree - 1 downTo 0) {
            result = result.timesInteger(x).add(coefficients[i])
            if (maxOf(result.numerator.abs().bitLength(), result.denominator.bitLength()) > QxLimits.WITNESS_BITS) throw MathUnsupported("witness_limit")
        }
        return result
    }
    fun value(x: Rational, maxBits: Int): Rational {
        if (x.isZero) return coefficients[0]
        var result = coefficients[degree]
        for (i in degree - 1 downTo 0) {
            result = result.multiply(x).add(coefficients[i])
            if (maxOf(result.numerator.abs().bitLength(), result.denominator.bitLength()) > maxBits) throw MathUnsupported("root_evaluation_limit")
        }
        return result
    }
    /** Strict Cauchy bound: every complex (therefore real) root has modulus below this integer. */
    fun strictRootBound(): BigInteger {
        check(!isZero)
        val two = BigInteger.valueOf(2)
        var bound = two
        val scale = coefficients[degree].reciprocal()
        for (i in 0 until degree) {
            val value = coefficients[i].multiply(scale)
            val candidate = value.numerator.abs().divide(value.denominator).add(two)
            if (candidate > bound) bound = candidate
        }
        return bound
    }
    fun json(): JsonObject = buildJsonObject {
        for (i in 0..degree) if (!coefficients[i].isZero) put(i.toString(), coefficients[i].toString())
    }
    companion object {
        val ZERO = Polynomial(arrayOf(Rational.ZERO), 0)
        val ONE = Polynomial(arrayOf(Rational.ONE), 0)
        val X = Polynomial(arrayOf(Rational.ZERO, Rational.ONE), 1)
        fun constant(value: BigInteger) = when {
            value.signum() == 0 -> ZERO
            value == BigInteger.ONE -> ONE
            else -> checked(arrayOf(Rational.integer(value)))
        }
        private fun checked(values: Array<Rational>): Polynomial {
            var degree = values.lastIndex
            while (degree > 0 && values[degree].isZero) degree--
            if (degree == 0 && values[0].isZero) return ZERO
            if (degree == 0 && values[0].numerator == BigInteger.ONE && values[0].denominator == BigInteger.ONE) return ONE
            if (degree > QxLimits.INTERMEDIATE_DEGREE) throw MathUnsupported("degree_limit")
            for (i in 0..degree) {
                if (maxOf(values[i].numerator.abs().bitLength(), values[i].denominator.bitLength()) > QxLimits.BITS) throw MathUnsupported("coefficient_limit")
            }
            return Polynomial(values, degree)
        }
    }
}

internal interface ExpressionArithmetic<T> {
    val variable: T
    fun constant(value: BigInteger): T
    fun add(left: T, right: T, subtract: Boolean = false): T
    fun negate(value: T): T
    fun multiply(left: T, right: T): T
    fun divide(left: T, right: T): T
    fun power(value: T, exponent: Int): T
}

internal object PolynomialArithmetic : ExpressionArithmetic<Polynomial> {
    override val variable = Polynomial.X
    override fun constant(value: BigInteger) = Polynomial.constant(value)
    override fun add(left: Polynomial, right: Polynomial, subtract: Boolean) = left.add(right, subtract)
    override fun negate(value: Polynomial) = value.negate()
    override fun multiply(left: Polynomial, right: Polynomial): Polynomial {
        if (!left.isZero && !right.isZero && left.degree + right.degree > QxLimits.DEGREE) throw MathUnsupported("degree_limit")
        return left.multiply(right)
    }
    override fun divide(left: Polynomial, right: Polynomial) = left.divide(right)
    override fun power(value: Polynomial, exponent: Int): Polynomial {
        if (value.degree * exponent > QxLimits.DEGREE) throw MathUnsupported("degree_limit")
        return value.power(exponent)
    }
}

internal data class ParsedExpression<T>(val value: T, val astNodes: Int)

/** One closed grammar for both domains; arithmetic policy never executes input. */
internal class ExactExpressionParser<T>(private val expression: String, private val arithmetic: ExpressionArithmetic<T>) {
    private var position = 0
    private var nodes = 1 // Expression wrapper; operators and variable loads count as AST nodes.
    private var depth = 0
    private fun count(amount: Int) {
        nodes += amount
        if (nodes > QxLimits.NODES) throw MathUnsupported("node_limit")
    }
    private fun enter() {
        if (++depth > QxLimits.DEPTH) throw MathUnsupported("depth_limit")
    }
    private fun whitespace() { while (position < expression.length && expression[position].isWhitespace()) position++ }
    private fun consume(ch: Char): Boolean {
        whitespace()
        if (position < expression.length && expression[position] == ch) { position++; return true }
        return false
    }
    fun parse(): ParsedExpression<T> {
        if (expression.length !in 1..QxLimits.CHARS) throw MathUnsupported("expression_length")
        for (ch in expression) if (!(ch in '0'..'9' || ch == 'x' || ch.isWhitespace() || ch in "+-*/()^")) throw MathUnsupported("only_rational_polynomials_in_x")
        val result = sum(); whitespace()
        if (position != expression.length) throw MathUnsupported("invalid_expression")
        return ParsedExpression(result, nodes)
    }
    private fun sum(): T {
        var result = product()
        while (true) result = when {
            consume('+') -> { count(2); arithmetic.add(result, product()) }
            consume('-') -> { count(2); arithmetic.add(result, product(), subtract = true) }
            else -> return result
        }
    }
    private fun product(): T {
        var result = unary()
        while (true) {
            whitespace()
            if (position < expression.length && expression[position] == '*' && (position + 1 >= expression.length || expression[position + 1] != '*')) {
                position++; count(2); result = arithmetic.multiply(result, unary())
            } else if (consume('/')) { count(2); result = arithmetic.divide(result, unary()) }
            else return result
        }
    }
    private fun unary(): T {
        enter()
        try {
            if (consume('+')) { count(2); return unary() }
            if (consume('-')) { count(2); return arithmetic.negate(unary()) }
            return power()
        } finally { depth-- }
    }
    private fun power(): T {
        val base = primary(); whitespace()
        val caret = consume('^')
        val stars = !caret && position + 1 < expression.length && expression[position] == '*' && expression[position + 1] == '*'
        if (!caret && !stars) return base
        if (stars) position += 2
        count(2)
        var parentheses = 0
        while (consume('(')) {
            if (++parentheses + depth > QxLimits.DEPTH) throw MathUnsupported("depth_limit")
        }
        val exponent = number()
        repeat(parentheses) { if (!consume(')')) throw MathUnsupported("literal_nonnegative_exponent_required") }
        if (exponent > BigInteger.valueOf(QxLimits.DEGREE.toLong())) throw MathUnsupported("exponent_limit")
        return arithmetic.power(base, exponent.toInt())
    }
    private fun primary(): T {
        if (consume('(')) {
            val result = sum()
            if (!consume(')')) throw MathUnsupported("invalid_expression")
            return result
        }
        if (consume('x')) { count(2); return arithmetic.variable }
        return arithmetic.constant(number())
    }
    private fun number(): BigInteger {
        whitespace(); val start = position
        while (position < expression.length && expression[position] in '0'..'9') position++
        if (start == position) throw MathUnsupported("invalid_expression")
        count(1)
        if (expression[start] == '0') {
            for (i in start until position) if (expression[i] != '0') throw MathUnsupported("invalid_expression")
            return BigInteger.ZERO
        }
        if (position - start > 310) throw MathUnsupported("coefficient_limit")
        val value = BigInteger(expression.substring(start, position))
        if (value.bitLength() > QxLimits.BITS) throw MathUnsupported("coefficient_limit")
        return value
    }
}
