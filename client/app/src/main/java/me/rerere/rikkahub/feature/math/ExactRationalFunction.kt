package me.rerere.rikkahub.feature.math

import java.math.BigInteger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Reduced algebraic value only. Original definedness conditions live in the parser policy. */
internal class RationalFunction private constructor(val numerator: Polynomial, val denominator: Polynomial) {
    fun negate() = if (numerator.isZero) this else RationalFunction(numerator.negate(), denominator)
    fun add(other: RationalFunction, subtract: Boolean = false): RationalFunction {
        if (other.numerator.isZero) return this
        if (numerator.isZero) return if (subtract) other.negate() else other
        return of(numerator.multiply(other.denominator).add(other.numerator.multiply(denominator), subtract), denominator.multiply(other.denominator))
    }
    fun multiply(other: RationalFunction): RationalFunction {
        if (numerator.isZero || other.numerator.isZero) return ZERO
        if (this === ONE) return other
        if (other === ONE) return this
        return of(numerator.multiply(other.numerator), denominator.multiply(other.denominator))
    }
    fun divide(other: RationalFunction): RationalFunction {
        if (other.numerator.isZero) throw MathUnsupported("identically_zero_denominator")
        if (numerator.isZero) return ZERO
        if (other === ONE) return this
        return of(numerator.multiply(other.denominator), denominator.multiply(other.numerator))
    }
    fun power(exponent: Int): RationalFunction {
        var remaining = exponent; var base = this; var result = ONE
        while (remaining > 0) {
            if (remaining and 1 != 0) result = result.multiply(base)
            remaining = remaining ushr 1
            if (remaining > 0) base = base.multiply(base)
        }
        return result
    }
    fun derivative(): RationalFunction = of(
        numerator.derivative().multiply(denominator).add(numerator.multiply(denominator.derivative()), subtract = true),
        denominator.multiply(denominator),
    )
    fun value(x: Int): Rational = numerator.value(x).multiply(denominator.value(x).reciprocal())
    fun json(): JsonObject = buildJsonObject { put("numerator", numerator.json()); put("denominator", denominator.json()) }
    companion object {
        val ZERO = RationalFunction(Polynomial.ZERO, Polynomial.ONE)
        val ONE = RationalFunction(Polynomial.ONE, Polynomial.ONE)
        val X = RationalFunction(Polynomial.X, Polynomial.ONE)
        fun constant(value: BigInteger) = when {
            value.signum() == 0 -> ZERO
            value == BigInteger.ONE -> ONE
            else -> RationalFunction(Polynomial.constant(value), Polynomial.ONE)
        }
        private fun of(numerator: Polynomial, denominator: Polynomial): RationalFunction {
            if (denominator.isZero) throw MathUnsupported("identically_zero_denominator")
            if (numerator.isZero) return ZERO
            var n = numerator
            var d = denominator
            if (d.degree == 0) {
                n = n.divide(d)
                d = Polynomial.ONE
            } else {
                val gcd = n.gcd(d)
                if (gcd.degree > 0) { n = n.exactQuotient(gcd); d = d.exactQuotient(gcd) }
                n = n.divideByLeadingOf(d)
                d = d.monic()
            }
            if (n.degree > QxLimits.DEGREE || d.degree > QxLimits.DEGREE) throw MathUnsupported("rational_degree_limit")
            return if (n === Polynomial.ONE && d === Polynomial.ONE) ONE else RationalFunction(n, d)
        }
    }
}

/** Every syntactic divisor records its nonzero numerator before algebraic cancellation. */
internal class RationalFunctionArithmetic : ExpressionArithmetic<RationalFunction> {
    override val variable = RationalFunction.X
    private val conditions = LinkedHashMap<JsonObject, Polynomial>()
    val nonzeroPolynomials: Collection<Polynomial> get() = conditions.values
    private fun requireNonzero(value: Polynomial, reason: String) {
        if (value.isZero) throw MathUnsupported(reason)
        if (value.degree > 0) {
            val condition = value.monic()
            conditions.putIfAbsent(condition.json(), condition)
        }
    }
    override fun constant(value: BigInteger) = RationalFunction.constant(value)
    override fun add(left: RationalFunction, right: RationalFunction, subtract: Boolean) = left.add(right, subtract)
    override fun negate(value: RationalFunction) = value.negate()
    override fun multiply(left: RationalFunction, right: RationalFunction) = left.multiply(right)
    override fun divide(left: RationalFunction, right: RationalFunction): RationalFunction {
        requireNonzero(right.numerator, "identically_zero_denominator")
        return left.divide(right)
    }
    override fun power(value: RationalFunction, exponent: Int): RationalFunction {
        // Unlike the formal polynomial operation, real a^0 requires a defined nonzero base.
        if (exponent == 0) requireNonzero(value.numerator, "zero_to_zero_power")
        return value.power(exponent)
    }
}

internal object RationalFunctionVerification {
    suspend fun compare(left: RationalFunction, right: RationalFunction, leftConditions: Collection<Polynomial>, rightConditions: Collection<Polynomial>): JsonObject {
        val difference = left.numerator.multiply(right.denominator).add(right.numerator.multiply(left.denominator), subtract = true)
        val conditions = LinkedHashMap<JsonObject, Polynomial>()
        for (condition in leftConditions) conditions.putIfAbsent(condition.json(), condition)
        for (condition in rightConditions) conditions.putIfAbsent(condition.json(), condition)
        val witness = if (difference.isZero) null else {
            // A nonzero degree-d polynomial has at most d distinct roots. Including every
            // excluded polynomial's degree guarantees a valid witness without using samples as proof.
            val bound = difference.degree + conditions.values.sumOf { it.degree } + left.denominator.degree + right.denominator.degree
            var point: Int? = null
            for (x in 0..bound) {
                currentCoroutineContext().ensureActive()
                if (conditions.values.any { it.value(x).isZero } || left.denominator.value(x).isZero || right.denominator.value(x).isZero) continue
                if (!difference.value(x).isZero) { point = x; break }
            }
            val x = checkNotNull(point) { "Exact root bound violated" }
            buildJsonObject { put("x", x); put("left", left.value(x).toString()); put("right", right.value(x).toString()) }
        }
        return buildJsonObject {
            put("status", if (difference.isZero) "VERIFIED" else "DISPROVED")
            put("verified", difference.isZero)
            put("proof_kind", "exact_cross_product_coefficients")
            put("computed_left", left.json()); put("claimed_right", right.json())
            put("left_nonzero_polynomials", JsonArray(leftConditions.map { it.json() }))
            put("right_nonzero_polynomials", JsonArray(rightConditions.map { it.json() }))
            put("comparison_domain", buildJsonObject {
                put("ambient", "real_numbers"); put("predicate", "all_polynomials_nonzero")
                put("nonzero_polynomials", JsonArray(conditions.keys.toList()))
            })
            put("cross_difference_coefficients", difference.json())
            if (witness != null) put("counterexample", witness)
        }
    }
}
