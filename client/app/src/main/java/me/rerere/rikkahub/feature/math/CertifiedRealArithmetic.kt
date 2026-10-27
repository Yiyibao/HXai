package me.rerere.rikkahub.feature.math

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

internal data class DecimalEnclosure(val lower: BigDecimal, val upper: BigDecimal) {
    init { if (lower > upper) throw MathUnsupported("reversed_interval") }
}

/** Directed decimal arithmetic and analytic series remainders, never Double certification. */
internal class RealEnclosures(val digits: Int) : RealAlgebra<DecimalEnclosure> {
    private val precision = digits + 80
    private val down = MathContext(precision, RoundingMode.FLOOR)
    private val up = MathContext(precision, RoundingMode.CEILING)
    private val epsilon = BigDecimal.ONE.scaleByPowerOfTen(-precision + 4)
    private val two = BigDecimal(2)
    private val half = BigDecimal("0.5")
    val zero = point(BigDecimal.ZERO)
    val one = point(BigDecimal.ONE)
    fun point(value: BigDecimal) = DecimalEnclosure(value, value)
    override fun rational(value: Rational) = DecimalEnclosure(BigDecimal(value.numerator).divide(BigDecimal(value.denominator), down), BigDecimal(value.numerator).divide(BigDecimal(value.denominator), up))
    override fun negate(a: DecimalEnclosure) = DecimalEnclosure(a.upper.negate(), a.lower.negate())
    override fun add(a: DecimalEnclosure, b: DecimalEnclosure) = bounded(DecimalEnclosure(a.lower.add(b.lower, down), a.upper.add(b.upper, up)))
    override fun subtract(a: DecimalEnclosure, b: DecimalEnclosure) = add(a, negate(b))
    override fun multiply(a: DecimalEnclosure, b: DecimalEnclosure): DecimalEnclosure {
        val low = arrayOf(a.lower.multiply(b.lower, down), a.lower.multiply(b.upper, down), a.upper.multiply(b.lower, down), a.upper.multiply(b.upper, down)).minOrNull()!!
        val high = arrayOf(a.lower.multiply(b.lower, up), a.lower.multiply(b.upper, up), a.upper.multiply(b.lower, up), a.upper.multiply(b.upper, up)).maxOrNull()!!
        return bounded(DecimalEnclosure(low, high))
    }
    override fun divide(a: DecimalEnclosure, b: DecimalEnclosure): DecimalEnclosure {
        if (b.lower.signum() <= 0 && b.upper.signum() >= 0) throw MathUnsupported("division_interval_contains_zero")
        return multiply(a, DecimalEnclosure(BigDecimal.ONE.divide(b.upper, down), BigDecimal.ONE.divide(b.lower, up)))
    }
    override fun power(a: DecimalEnclosure, exponent: Int): DecimalEnclosure {
        if (exponent !in -32..32) throw MathUnsupported("numeric_power_limit")
        if (exponent == 0 && a.lower.signum() <= 0 && a.upper.signum() >= 0) throw MathUnsupported("zero_power_zero_not_defined")
        if (exponent < 0) return divide(one, power(a, -exponent))
        var answer = one; var base = a; var n = exponent
        while (n > 0) { if (n and 1 != 0) answer = multiply(answer, base); n = n ushr 1; if (n > 0) base = multiply(base, base) }
        return answer
    }
    private fun bounded(a: DecimalEnclosure): DecimalEnclosure {
        if (listOf(a.lower, a.upper).any { kotlin.math.abs(it.scale()) > 2000 || it.precision() > 4000 || it.abs() > BigDecimal.ONE.scaleByPowerOfTen(500) }) throw MathUnsupported("numeric_magnitude_limit")
        return a
    }
    private fun absolute(a: DecimalEnclosure): BigDecimal = maxOf(a.lower.abs(), a.upper.abs())
    private fun widen(a: DecimalEnclosure, error: BigDecimal) = DecimalEnclosure(a.lower.subtract(error, down), a.upper.add(error, up))
    private fun integerRoot(n: BigInteger): BigInteger {
        if (n.signum() == 0) return BigInteger.ZERO
        var x = BigInteger.ONE.shiftLeft((n.bitLength() + 1) / 2)
        while (true) { val next = x.add(n.divide(x)).shiftRight(1); if (next >= x) return x; x = next }
    }
    private fun sqrtPoint(x: BigDecimal): DecimalEnclosure {
        if (x.signum() < 0) throw MathUnsupported("sqrt_negative_domain")
        val q = ProfessionalScalar.parse(x.toPlainString())
        val scale = BigInteger.TEN.pow(2 * precision)
        val numerator = q.numerator.multiply(scale)
        val root = integerRoot(numerator.divide(q.denominator))
        val exact = root.multiply(root).multiply(q.denominator) == numerator
        return DecimalEnclosure(BigDecimal(root, precision), BigDecimal(if (exact) root else root.add(BigInteger.ONE), precision))
    }
    private fun expPoint(x: BigDecimal): DecimalEnclosure {
        if (x.abs() > BigDecimal(100)) throw MathUnsupported("exp_argument_limit_100")
        if (x.signum() < 0) return divide(one, expPoint(x.negate()))
        var reduced = x; var squares = 0
        while (reduced > half) { reduced = reduced.divide(two); squares++ }
        val argument = point(reduced)
        var sum = one; var term = one
        for (n in 1..1000) {
            term = divide(multiply(term, argument), point(BigDecimal(n)))
            sum = add(sum, term)
            val next = divide(multiply(term, argument), point(BigDecimal(n + 1)))
            val tail = next.upper.multiply(two, up)
            if (tail <= epsilon) {
                var enclosed = DecimalEnclosure(sum.lower, sum.upper.add(tail, up))
                repeat(squares) { enclosed = multiply(enclosed, enclosed) }
                return enclosed
            }
        }
        throw MathUnsupported("exp_series_limit")
    }
    private fun logUnit(x: BigDecimal): DecimalEnclosure {
        // 1 <= x <= 2, so 0 <= z=(x-1)/(x+1) <= 1/3.
        val z = divide(subtract(point(x), one), add(point(x), one))
        val square = multiply(z, z)
        var power = z; var sum = zero
        for (n in 0..1000) {
            sum = add(sum, divide(power, point(BigDecimal(2 * n + 1))))
            power = multiply(power, square)
            val tail = divide(multiply(point(two), power), multiply(point(BigDecimal(2 * n + 3)), subtract(one, square))).upper
            if (tail <= epsilon) return DecimalEnclosure(sum.lower.multiply(two, down), sum.upper.multiply(two, up).add(tail, up))
        }
        throw MathUnsupported("log_series_limit")
    }
    private val logTwo by lazy { logUnit(two) }
    private fun logPoint(x: BigDecimal): DecimalEnclosure {
        if (x.signum() <= 0) throw MathUnsupported("log_nonpositive_domain")
        if (x < BigDecimal.ONE.scaleByPowerOfTen(-100) || x > BigDecimal.ONE.scaleByPowerOfTen(100)) throw MathUnsupported("log_argument_limit")
        var reduced = x; var exponent = 0
        while (reduced < BigDecimal.ONE) { reduced = reduced.multiply(two); exponent-- }
        while (reduced > two) { reduced = reduced.divide(two); exponent++ }
        return add(logUnit(reduced), multiply(point(BigDecimal(exponent)), logTwo))
    }
    private fun trigPoint(x: BigDecimal, cosine: Boolean): DecimalEnclosure {
        if (x.abs() > BigDecimal(100)) throw MathUnsupported("trigonometric_argument_limit_100")
        val square = point(x.multiply(x).negate())
        var term = if (cosine) one else point(x)
        var sum = term
        val parity = if (cosine) 0 else 1
        for (n in 0..1000) {
            val k = 2 * n + parity
            val next = divide(multiply(term, square), point(BigDecimal((k + 1).toLong() * (k + 2))))
            // The remaining alternating terms decrease once k exceeds |x|.
            val error = absolute(next)
            if (BigDecimal(k + 1) > x.abs() && error <= epsilon) return widen(sum, error)
            sum = add(sum, next); term = next
        }
        throw MathUnsupported("trigonometric_series_limit")
    }
    private fun atanReciprocal(denominator: Int): DecimalEnclosure {
        val x = divide(one, point(BigDecimal(denominator))); val square = multiply(x, x)
        var power = x; var sum = zero
        for (n in 0..1000) {
            val term = divide(power, point(BigDecimal(2 * n + 1)))
            sum = if (n and 1 == 0) add(sum, term) else subtract(sum, term)
            power = multiply(power, square)
            val next = divide(power, point(BigDecimal(2 * n + 3)))
            if (next.upper <= epsilon) return widen(sum, next.upper)
        }
        throw MathUnsupported("pi_series_limit")
    }
    val pi by lazy { subtract(multiply(point(BigDecimal(16)), atanReciprocal(5)), multiply(point(BigDecimal(4)), atanReciprocal(239))) }
    private val e by lazy { expPoint(BigDecimal.ONE) }
    override fun constant(name: String) = when (name) { "pi" -> pi; "e" -> e; else -> throw MathUnsupported("unknown_numeric_constant") }
    override fun function(name: String, a: DecimalEnclosure): DecimalEnclosure = when (name) {
        "sqrt" -> { val low = sqrtPoint(a.lower); val high = sqrtPoint(a.upper); DecimalEnclosure(low.lower, high.upper) }
        "exp" -> DecimalEnclosure(expPoint(a.lower).lower, expPoint(a.upper).upper)
        "ln", "log" -> {
            val logarithm = DecimalEnclosure(logPoint(a.lower).lower, logPoint(a.upper).upper)
            if (name == "log") divide(logarithm, logPoint(BigDecimal.TEN)) else logarithm
        }
        "sin", "cos" -> {
            val middle = a.lower.add(a.upper).divide(two)
            val radius = a.upper.subtract(a.lower).divide(two, up)
            widen(trigPoint(middle, name == "cos"), radius) // |sin'|, |cos'| <= 1 on R.
        }
        "tan" -> divide(function("sin", a), function("cos", a))
        "abs" -> when { a.lower.signum() >= 0 -> a; a.upper.signum() <= 0 -> negate(a); else -> DecimalEnclosure(BigDecimal.ZERO, absolute(a)) }
        else -> throw MathUnsupported("unsupported_numeric_function")
    }
    fun bindings(input: JsonObject): Map<String, DecimalEnclosure> {
        if (input.size > 8) throw MathUnsupported("numeric_input_limit")
        return input.mapValues { (name, raw) ->
            RealExpression.checkVariable(name)
            val pair = raw as? JsonArray ?: throw MathUnsupported("variable_interval_pair_required")
            if (pair.size != 2) throw MathUnsupported("variable_interval_pair_required")
            bounded(DecimalEnclosure(rational(ProfessionalScalar.read(pair[0])).lower, rational(ProfessionalScalar.read(pair[1])).upper))
        }
    }
    fun evaluate(source: String, bindings: JsonObject): DecimalEnclosure =
        bounded(RealExpression.compile(source).evaluate(this, this.bindings(bindings)))
}

internal object CertifiedRealArithmetic {
    suspend fun verify(input: JsonObject): JsonObject {
        if (input.keys != setOf("expression", "digits", "bindings")) throw MathUnsupported("invalid_certified_numeric_fields")
        val digits = input.getValue("digits").jsonPrimitive.content.toIntOrNull() ?: throw MathUnsupported("numeric_digits_required")
        if (digits !in 10..100) throw MathUnsupported("numeric_digits_10_to_100")
        val expression = input.getValue("expression").jsonPrimitive.also { if (!it.isString) throw MathUnsupported("numeric_expression_string_required") }.content
        val bindings = input.getValue("bindings").jsonObject
        currentCoroutineContext().ensureActive()
        val first = RealEnclosures(digits).evaluate(expression, bindings)
        currentCoroutineContext().ensureActive()
        val second = RealEnclosures(digits + 16).evaluate(expression, bindings)
        val lower = maxOf(first.lower, second.lower); val upper = minOf(first.upper, second.upper)
        check(lower <= upper) { "Independent precision enclosures disagree" }
        val width = upper.subtract(lower)
        val certified = width <= BigDecimal.ONE.scaleByPowerOfTen(-digits)
        val midpoint = lower.add(upper).divide(BigDecimal(2))
        return buildJsonObject {
            put("status", if (certified) "VERIFIED" else "UNSUPPORTED"); put("verified", certified)
            put("proof_kind", "outward_decimal_rounding_and_analytic_remainder_enclosure")
            put("lower", lower.toPlainString()); put("upper", upper.toPlainString()); put("estimate", midpoint.toPlainString())
            put("absolute_error_bound", width.divide(BigDecimal(2)).toPlainString()); put("requested_absolute_digits", digits)
            put("higher_precision_checked", true); put("working_digits", digits + 80)
            if (!certified) put("reason", "input_interval_or_rounding_width_exceeds_requested_accuracy")
            put("notice", "有向舍入与解析级数余项给出真值包围区间；高低两精度一致不是证明依据本身。仅认证所给表达式/输入区间，不将数值结果或图像当恒等、一般定理或原题完整证明。输入不确定性不会被更多位数消除。")
        }
    }
}
