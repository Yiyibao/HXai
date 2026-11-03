package me.rerere.rikkahub.feature.math

import java.math.BigInteger
import kotlinx.serialization.json.*

internal object QxLimits {
    const val CHARS = 4096
    const val NODES = 256
    const val DEGREE = 32
    const val BITS = 1024
    const val DEPTH = 64
    const val WITNESS_BITS = (DEGREE + 1) * BITS + DEGREE * 6 + 64
}

internal class MathUnsupported(val reason: String) : IllegalArgumentException(reason)

internal class Rational private constructor(val numerator: BigInteger, val denominator: BigInteger) {
    val isZero get() = numerator.signum() == 0
    fun negate() = if (isZero) this else Rational(numerator.negate(), denominator)
    fun add(other: Rational): Rational {
        if (isZero) return other
        if (other.isZero) return this
        val gcd = denominator.gcd(other.denominator)
        val a = other.denominator.divide(gcd)
        val b = denominator.divide(gcd)
        return of(numerator.multiply(a).add(other.numerator.multiply(b)), denominator.multiply(a))
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
        private fun of(numerator: BigInteger, denominator: BigInteger): Rational {
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
        if (degree + other.degree > QxLimits.DEGREE) throw MathUnsupported("degree_limit")
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
    fun value(x: Int): Rational {
        if (x == 0) return coefficients[0]
        var result = coefficients[degree]
        for (i in degree - 1 downTo 0) {
            result = result.timesInteger(x).add(coefficients[i])
            if (maxOf(result.numerator.abs().bitLength(), result.denominator.bitLength()) > QxLimits.WITNESS_BITS) throw MathUnsupported("witness_limit")
        }
        return result
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
            if (degree > QxLimits.DEGREE) throw MathUnsupported("degree_limit")
            for (i in 0..degree) {
                if (maxOf(values[i].numerator.abs().bitLength(), values[i].denominator.bitLength()) > QxLimits.BITS) throw MathUnsupported("coefficient_limit")
            }
            return Polynomial(values, degree)
        }
    }
}

internal data class ParsedPolynomial(val polynomial: Polynomial, val astNodes: Int)

/** Recursive descent over a closed grammar, not execution of supplied expressions. */
internal class PolynomialParser(private val expression: String) {
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
    fun parse(): ParsedPolynomial {
        if (expression.length !in 1..QxLimits.CHARS) throw MathUnsupported("expression_length")
        for (ch in expression) if (!(ch in '0'..'9' || ch == 'x' || ch.isWhitespace() || ch in "+-*/()^")) throw MathUnsupported("only_rational_polynomials_in_x")
        val result = sum(); whitespace()
        if (position != expression.length) throw MathUnsupported("invalid_expression")
        return ParsedPolynomial(result, nodes)
    }
    private fun sum(): Polynomial {
        var result = product()
        while (true) result = when {
            consume('+') -> { count(2); result.add(product()) }
            consume('-') -> { count(2); result.add(product(), subtract = true) }
            else -> return result
        }
    }
    private fun product(): Polynomial {
        var result = unary()
        while (true) {
            whitespace()
            if (position < expression.length && expression[position] == '*' && (position + 1 >= expression.length || expression[position + 1] != '*')) {
                position++; count(2); result = result.multiply(unary())
            } else if (consume('/')) { count(2); result = result.divide(unary()) }
            else return result
        }
    }
    private fun unary(): Polynomial {
        enter()
        try {
            if (consume('+')) { count(2); return unary() }
            if (consume('-')) { count(2); return unary().negate() }
            return power()
        } finally { depth-- }
    }
    private fun power(): Polynomial {
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
        return base.power(exponent.toInt())
    }
    private fun primary(): Polynomial {
        if (consume('(')) {
            val result = sum()
            if (!consume(')')) throw MathUnsupported("invalid_expression")
            return result
        }
        if (consume('x')) { count(2); return Polynomial.X }
        return Polynomial.constant(number())
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
