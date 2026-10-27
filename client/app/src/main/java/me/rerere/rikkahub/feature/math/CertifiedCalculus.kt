package me.rerere.rikkahub.feature.math

import java.math.BigDecimal
import java.util.PriorityQueue
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Sufficient certificates, not heuristic quadrature or an unrestricted root solver. */
internal object CertifiedCalculus {
    const val ORDER = 8
    const val MAX_CELLS = 512
    private val two = Rational.ONE.timesInteger(2)
    private fun midpoint(a: Rational, b: Rational) = a.add(b).multiply(two.reciprocal())
    private fun width(a: DecimalEnclosure) = a.upper.subtract(a.lower)
    private fun absolute(a: DecimalEnclosure) = maxOf(a.lower.abs(), a.upper.abs())
    private fun sign(a: DecimalEnclosure): Int? = when {
        a.lower.signum() > 0 -> 1
        a.upper.signum() < 0 -> -1
        a.lower.signum() == 0 && a.upper.signum() == 0 -> 0
        else -> null
    }
    private fun interval(a: DecimalEnclosure) = buildJsonObject { put("lower", a.lower.toPlainString()); put("upper", a.upper.toPlainString()) }
    suspend fun verify(input: JsonObject): JsonObject {
        if (input.keys != setOf("action", "expression", "variable", "lower", "upper", "digits", "bindings")) throw MathUnsupported("invalid_calculus_fields")
        fun text(key: String) = input.getValue(key).jsonPrimitive.also { if (!it.isString) throw MathUnsupported("calculus_string_required") }.content
        val digits = text("digits").toIntOrNull() ?: throw MathUnsupported("calculus_digits_required")
        if (digits !in 2..24) throw MathUnsupported("calculus_digits_2_to_24")
        val variable = text("variable"); RealExpression.checkVariable(variable)
        val expression = RealExpression.compile(text("expression"))
        val real = RealEnclosures(digits)
        val bindings = real.bindings(input.getValue("bindings").jsonObject)
        if (variable in bindings) throw MathUnsupported("calculus_variable_cannot_be_parameter_binding")
        val lower = ProfessionalScalar.parse(text("lower")); val upper = ProfessionalScalar.parse(text("upper"))
        val tolerance = BigDecimal.ONE.scaleByPowerOfTen(-digits)
        val result = when (text("action")) {
            "integral" -> integral(expression, variable, lower, upper, real, bindings, tolerance)
            "root" -> root(expression, variable, lower, upper, real, bindings, tolerance)
            else -> throw MathUnsupported("unsupported_calculus_action")
        }
        return JsonObject(result + mapOf("requested_absolute_digits" to JsonPrimitive(digits), "working_digits" to JsonPrimitive(digits + 80),
            "notice" to JsonPrimitive("仅认证所给有限闭区间与参数区间的局部计算声明。积分依赖全区间平滑域、区间自动微分和Taylor解析余项；求根依赖全区间导数严格单号及端点存在性证书，不声称枚举所有实根。奇点、反常积分、不可证平滑/单调或精度资源不足返回UNSUPPORTED，不作发散/无根判断。数值证书不是恒等式、一般定理或自然语言原题的完整证明。")))
    }
    private data class Cell(val left: Rational, val right: Rational, val value: DecimalEnclosure) {
        val uncertainty: BigDecimal = width(value)
    }
    private suspend fun integral(expression: RealExpression, variable: String, a: Rational, b: Rational,
        real: RealEnclosures, parameters: Map<String, DecimalEnclosure>, tolerance: BigDecimal): JsonObject {
        val reverse = a > b; val left = minOf(a, b); val right = maxOf(a, b)
        val jets = RealTaylorEnclosures(real, ORDER)
        val variables = parameters.mapValues { jets.scalar(it.value) }.toMutableMap()
        fun evaluate(low: Rational, high: Rational): Array<DecimalEnclosure> {
            variables[variable] = jets.variable(DecimalEnclosure(real.rational(low).lower, real.rational(high).upper))
            return expression.evaluate(jets, variables)
        }
        var evaluations = 0
        suspend fun cell(low: Rational, high: Rational): Cell {
            currentCoroutineContext().ensureActive()
            val whole = evaluate(low, high); evaluations++ // Every original subexpression must have a valid C8 domain.
            if (low.compareTo(high) == 0) return Cell(low, high, real.zero)
            val middle = midpoint(low, high); val radius = high.add(low.negate()).multiply(two.reciprocal())
            val center = evaluate(middle, middle); evaluations++
            var integral = real.zero; var radiusPower = radius
            for (k in 0 until ORDER) {
                if (k and 1 == 0) {
                    val moment = radiusPower.timesInteger(2).multiply(Rational.ONE.timesInteger(k + 1).reciprocal())
                    integral = real.add(integral, real.multiply(center[k], real.rational(moment)))
                }
                radiusPower = radiusPower.multiply(radius)
            }
            // f^(8)(xi)/8! * t^8, integrated with |t|^8 on [-h,h].
            val remainderMoment = radiusPower.timesInteger(2).multiply(Rational.ONE.timesInteger(ORDER + 1).reciprocal())
            val error = real.multiply(real.point(absolute(whole[ORDER])), real.rational(remainderMoment)).upper
            return Cell(low, high, real.add(integral, DecimalEnclosure(error.negate(), error)))
        }
        val cells = PriorityQueue<Cell> { x, y -> val byWidth = y.uncertainty.compareTo(x.uncertainty); if (byWidth != 0) byWidth else x.left.compareTo(y.left) }
        val initial = cell(left, right); cells.add(initial)
        var lower = initial.value.lower; var upper = initial.value.upper
        var stagnant = 0
        while (upper.subtract(lower) > tolerance && cells.size < MAX_CELLS && stagnant < 24) {
            currentCoroutineContext().ensureActive()
            val parent = cells.remove(); val middle = midpoint(parent.left, parent.right)
            val first = cell(parent.left, middle); val second = cell(middle, parent.right)
            val childrenWidth = first.uncertainty.add(second.uncertainty)
            stagnant = if (childrenWidth >= parent.uncertainty.multiply(BigDecimal("0.999"))) stagnant + 1 else 0
            // Exact finite-decimal bookkeeping removes the old endpoints, not an interval subtraction.
            lower = lower.subtract(parent.value.lower).add(first.value.lower).add(second.value.lower)
            upper = upper.subtract(parent.value.upper).add(first.value.upper).add(second.value.upper)
            cells.add(first); cells.add(second)
        }
        val enclosed = if (reverse) DecimalEnclosure(upper.negate(), lower.negate()) else DecimalEnclosure(lower, upper)
        val certified = width(enclosed) <= tolerance
        return buildJsonObject {
            put("status", if (certified) "VERIFIED" else "UNSUPPORTED"); put("verified", certified)
            put("proof_kind", "outward_interval_taylor_integral_with_c8_remainder")
            put("action", "integral"); put("lower", enclosed.lower.toPlainString()); put("upper", enclosed.upper.toPlainString())
            put("estimate", enclosed.lower.add(enclosed.upper).divide(BigDecimal(2)).toPlainString())
            put("absolute_error_bound", width(enclosed).divide(BigDecimal(2)).toPlainString())
            put("taylor_order", ORDER); put("subintervals", cells.size); put("max_subintervals", MAX_CELLS); put("jet_evaluations", evaluations)
            put("original_domain_checked", true); put("scope_detail", "finite_oriented_integral_on_certified_C8_closed_subinterval_cover")
            if (!certified) put("reason", "calculus_precision_or_subdivision_limit")
        }
    }
    private suspend fun root(expression: RealExpression, variable: String, a: Rational, b: Rational,
        real: RealEnclosures, parameters: Map<String, DecimalEnclosure>, tolerance: BigDecimal): JsonObject {
        if (a >= b) throw MathUnsupported("root_strictly_ordered_interval_required")
        currentCoroutineContext().ensureActive()
        val jets = RealTaylorEnclosures(real, 1)
        val variables = parameters.mapValues { jets.scalar(it.value) }.toMutableMap()
        variables[variable] = jets.variable(DecimalEnclosure(real.rational(a).lower, real.rational(b).upper))
        val derivative = expression.evaluate(jets, variables)[1]
        val direction = sign(derivative)
        if (direction == null || direction == 0) throw MathUnsupported("root_strict_monotonicity_not_certified")
        val pointVariables = parameters.toMutableMap()
        fun value(x: Rational): DecimalEnclosure { pointVariables[variable] = real.rational(x); return expression.evaluate(real, pointVariables) }
        var left = a; var right = b; var lowValue = value(left); var highValue = value(right)
        var lowSign = sign(lowValue); val highSign = sign(highValue)
        if (lowSign == null || highSign == null) throw MathUnsupported("root_endpoint_sign_not_certified")
        if (lowSign != 0 && lowSign == highSign) return buildJsonObject {
            put("status", "DISPROVED"); put("verified", false); put("action", "root")
            put("classification", "NO_ROOT_IN_REQUESTED_INTERVAL"); put("proof_kind", "strict_monotonicity_and_same_nonzero_endpoint_sign")
            put("derivative_enclosure", interval(derivative)); put("left_value", interval(lowValue)); put("right_value", interval(highValue))
        }
        if (lowSign == 0) { right = left; highValue = lowValue }
        else if (highSign == 0) { left = right; lowValue = highValue }
        var iterations = 0
        while (real.rational(right.add(left.negate())).upper > tolerance) {
            currentCoroutineContext().ensureActive()
            if (++iterations > 2048) throw MathUnsupported("root_bisection_limit")
            val middle = midpoint(left, right); val middleValue = value(middle)
            val middleSign = sign(middleValue) ?: throw MathUnsupported("root_midpoint_sign_uncertainty")
            if (middleSign == 0) { left = middle; right = middle; lowValue = middleValue; highValue = middleValue }
            else if (middleSign == lowSign) { left = middle; lowValue = middleValue; lowSign = middleSign }
            else { right = middle; highValue = middleValue }
        }
        val enclosed = DecimalEnclosure(real.rational(left).lower, real.rational(right).upper)
        if (width(enclosed) > tolerance) throw MathUnsupported("root_decimal_output_precision_limit")
        return buildJsonObject {
            put("status", "VERIFIED"); put("verified", true); put("action", "root"); put("classification", "UNIQUE_ROOT_IN_REQUESTED_INTERVAL")
            put("proof_kind", "continuous_strict_monotonicity_and_intermediate_value_bracket")
            put("lower", enclosed.lower.toPlainString()); put("upper", enclosed.upper.toPlainString())
            put("estimate", enclosed.lower.add(enclosed.upper).divide(BigDecimal(2)).toPlainString())
            put("absolute_error_bound", width(enclosed).divide(BigDecimal(2)).toPlainString())
            put("exact_left", left.toString()); put("exact_right", right.toString()); put("iterations", iterations)
            put("derivative_enclosure", interval(derivative)); put("left_value", interval(lowValue)); put("right_value", interval(highValue))
            put("original_domain_checked", true); put("scope_detail", "one_unique_root_in_the_requested_closed_interval_only")
        }
    }
}
