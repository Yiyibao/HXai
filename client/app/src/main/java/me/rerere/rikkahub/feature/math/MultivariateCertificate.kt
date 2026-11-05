package me.rerere.rikkahub.feature.math

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Sparse exact polynomials in up to four named variables; no variable division. */
internal class MultiPolynomial private constructor(val terms: Map<Int, Rational>) {
    val zero get() = terms.isEmpty()
    fun add(other: MultiPolynomial, subtract: Boolean = false): MultiPolynomial {
        val output = terms.toMutableMap()
        for ((power, value) in other.terms) {
            val sum = ProfessionalScalar.add(output[power] ?: Rational.ZERO, if (subtract) value.negate() else value)
            if (sum.isZero) output.remove(power) else output[power] = sum
        }
        return checked(output)
    }
    fun negate() = checked(terms.mapValues { it.value.negate() })
    fun multiply(other: MultiPolynomial): MultiPolynomial {
        if (zero || other.zero) return ZERO
        val output = HashMap<Int, Rational>()
        for ((a, av) in terms) for ((b, bv) in other.terms) {
            var key = 0; var degree = 0
            for (variable in 0..3) {
                val exponent = ((a ushr (variable * 4)) and 15) + ((b ushr (variable * 4)) and 15)
                if (exponent > 12) throw MathUnsupported("multivariate_degree_limit")
                degree += exponent; key = key or (exponent shl (variable * 4))
            }
            if (degree > 12) throw MathUnsupported("multivariate_degree_limit")
            val sum = ProfessionalScalar.add(output[key] ?: Rational.ZERO, ProfessionalScalar.multiply(av, bv))
            if (sum.isZero) output.remove(key) else output[key] = sum
            if (output.size > 256) throw MathUnsupported("multivariate_term_limit")
        }
        return checked(output)
    }
    fun divide(other: MultiPolynomial): MultiPolynomial {
        if (other.terms.size != 1 || other.terms[0] == null) throw MathUnsupported("multivariate_nonzero_constant_divisor_required")
        val divisor = other.terms.getValue(0)
        return checked(terms.mapValues { ProfessionalScalar.divide(it.value, divisor) })
    }
    fun power(exponent: Int): MultiPolynomial {
        if (exponent !in 0..12 || exponent == 0 && zero) throw MathUnsupported("multivariate_power_limit_or_zero_power_zero")
        var n = exponent; var base = this; var answer = ONE
        while (n > 0) {
            if (n and 1 != 0) answer = answer.multiply(base)
            n = n ushr 1
            if (n != 0) base = base.multiply(base)
        }
        return answer
    }
    fun derivative(index: Int): MultiPolynomial {
        val output = HashMap<Int, Rational>()
        for ((key, value) in terms) {
            val power = (key ushr (index * 4)) and 15
            if (power > 0) output[key - (1 shl (index * 4))] = ProfessionalScalar.checked(value.timesInteger(power))
        }
        return checked(output)
    }
    fun substitute(values: Map<Int, MultiPolynomial>): MultiPolynomial {
        var answer = ZERO
        for ((key, value) in terms) {
            var term = constant(value)
            for (v in 0..3) {
                val power = (key ushr (v * 4)) and 15
                if (power != 0) term = term.multiply((values[v] ?: variable(v)).power(power))
            }
            answer = answer.add(term)
        }
        return answer
    }
    fun json(names: List<String>) = JsonArray(terms.toSortedMap().map { (key, value) -> buildJsonObject {
        put("coefficient", value.toString())
        put("powers", buildJsonObject { names.forEachIndexed { i, name -> val e = (key ushr (i * 4)) and 15; if (e > 0) put(name, e) } })
    } })
    companion object {
        val ZERO = MultiPolynomial(emptyMap())
        val ONE = MultiPolynomial(mapOf(0 to Rational.ONE))
        fun constant(value: Rational) = if (value.isZero) ZERO else MultiPolynomial(mapOf(0 to ProfessionalScalar.checked(value)))
        fun variable(index: Int) = MultiPolynomial(mapOf((1 shl (index * 4)) to Rational.ONE))
        private fun checked(terms: Map<Int, Rational>): MultiPolynomial {
            if (terms.size > 256) throw MathUnsupported("multivariate_term_limit")
            terms.values.forEach(ProfessionalScalar::checked)
            return if (terms.isEmpty()) ZERO else MultiPolynomial(terms)
        }
    }
}

internal class MultiExpression(private val source: String, private val variables: List<String>) {
    private var position = 0; private var depth = 0; private var nodes = 0
    init { if (source.length !in 1..4096) throw MathUnsupported("multivariate_expression_length") }
    private fun count() { if (++nodes > 256) throw MathUnsupported("multivariate_node_limit") }
    private fun token(c: Char): Boolean {
        while (position < source.length && source[position].isWhitespace()) position++
        if (position < source.length && source[position] == c) { position++; return true }
        return false
    }
    fun parse(): MultiPolynomial { val answer = sum(); token(' '); if (position != source.length) throw MathUnsupported("invalid_multivariate_expression"); return answer }
    private fun sum(): MultiPolynomial {
        var value = product()
        while (true) { val subtract = if (token('+')) false else if (token('-')) true else return value; count(); value = value.add(product(), subtract) }
    }
    private fun product(): MultiPolynomial {
        var value = unary()
        while (true) {
            val divide = if (token('*')) false else if (token('/')) true else return value
            count(); val other = unary(); value = if (divide) value.divide(other) else value.multiply(other)
        }
    }
    private fun unary(): MultiPolynomial {
        if (++depth > 32) throw MathUnsupported("multivariate_depth_limit")
        try {
            if (token('+')) return unary()
            if (token('-')) { count(); return unary().negate() }
            var value = atom()
            if (token('^')) {
                token(' '); val start = position
                while (position < source.length && source[position].isDigit()) position++
                val exponent = source.substring(start, position).toIntOrNull() ?: throw MathUnsupported("nonnegative_integer_exponent_required")
                count(); value = value.power(exponent)
            }
            return value
        } finally { depth-- }
    }
    private fun atom(): MultiPolynomial {
        count()
        if (token('(')) { val value = sum(); if (!token(')')) throw MathUnsupported("missing_parenthesis"); return value }
        token(' '); val start = position
        if (position < source.length && (source[position].isDigit() || source[position] == '.')) {
            while (position < source.length && (source[position].isDigit() || source[position] == '.')) position++
            return MultiPolynomial.constant(ProfessionalScalar.parse(source.substring(start, position)))
        }
        while (position < source.length && (source[position].isLetterOrDigit() || source[position] == '_')) position++
        val index = variables.indexOf(source.substring(start, position))
        if (index < 0) throw MathUnsupported("unknown_multivariate_symbol")
        return MultiPolynomial.variable(index)
    }
}

internal object MultivariateCertificate {
    suspend fun verify(input: JsonObject): JsonObject {
        currentCoroutineContext().ensureActive()
        val action = input.getValue("action").jsonPrimitive.content
        if (action == "cubic_monotonicity") return CubicParameterMonotonicity.verify(input)
        val required = if (action == "derivative") setOf("action", "variables", "expression", "variable", "claimed") else setOf("action", "variables", "left", "right")
        if (input.keys != required || action !in setOf("identity", "derivative")) throw MathUnsupported("invalid_multivariate_fields")
        val variables = input.getValue("variables").jsonArray.map { it.jsonPrimitive.also { p -> if (!p.isString) throw MathUnsupported("variable_names_required") }.content }
        if (variables.size !in 1..4 || variables.distinct().size != variables.size || variables.any { !it.matches(Regex("[a-zA-Z][a-zA-Z0-9_]{0,15}")) }) throw MathUnsupported("multivariate_variables_limit")
        fun expression(key: String) = MultiExpression(input.getValue(key).jsonPrimitive.also { if (!it.isString) throw MathUnsupported("expression_string_required") }.content, variables).parse()
        val left = if (action == "identity") expression("left") else {
            val variable = variables.indexOf(input.getValue("variable").jsonPrimitive.content)
            if (variable < 0) throw MathUnsupported("unknown_derivative_variable")
            expression("expression").derivative(variable)
        }
        val right = expression(if (action == "identity") "right" else "claimed")
        val difference = left.add(right, subtract = true)
        currentCoroutineContext().ensureActive()
        return buildJsonObject {
            put("status", if (difference.zero) "VERIFIED" else "DISPROVED"); put("verified", difference.zero)
            put("proof_kind", "exact_multivariate_rational_coefficients")
            put("variables", JsonArray(variables.map(::JsonPrimitive))); put("difference_terms", difference.json(variables))
            put("notice", "只认证所给多元多项式恒等/偏导，参数视为独立实变量。除数必须是非零常量，不约去变量分母或填定义域孔洞；不认证未经分支处理的参数不等式或几何建模。")
        }
    }
    suspend fun induction(input: JsonObject): JsonObject {
        if (input.keys != setOf("start", "initial", "next", "claimed")) throw MathUnsupported("invalid_induction_fields")
        val start = input.getValue("start").jsonPrimitive.content.toIntOrNull() ?: throw MathUnsupported("integer_induction_start_required")
        if (start !in 0..1000000) throw MathUnsupported("induction_start_limit")
        val initial = ProfessionalScalar.read(input.getValue("initial"))
        val nextText = input.getValue("next").jsonPrimitive.content
        val claimedText = input.getValue("claimed").jsonPrimitive.content
        return try {
            polynomialInduction(start, initial, nextText, claimedText)
        } catch (error: MathUnsupported) {
            if (error.reason != "multivariate_nonzero_constant_divisor_required") throw error
            RationalLinearRecurrence.verify(start, initial, nextText, claimedText)
        }
    }
    private suspend fun polynomialInduction(start: Int, initial: Rational, nextText: String, claimedText: String): JsonObject {
        val names = listOf("n", "u")
        val next = MultiExpression(nextText, names).parse()
        val claimed = MultiExpression(claimedText, names).parse()
        if (claimed.terms.keys.any { ((it ushr 4) and 15) != 0 }) throw MathUnsupported("closed_formula_cannot_depend_on_sequence_value")
        val base = claimed.substitute(mapOf(0 to MultiPolynomial.constant(ProfessionalScalar.parse(start.toString()))))
            .add(MultiPolynomial.constant(initial), subtract = true)
        currentCoroutineContext().ensureActive()
        val step = next.substitute(mapOf(1 to claimed)).add(claimed.substitute(mapOf(0 to MultiPolynomial.variable(0).add(MultiPolynomial.ONE))), subtract = true)
        val verified = base.zero && step.zero
        return buildJsonObject {
            put("status", if (verified) "VERIFIED" else "DISPROVED"); put("verified", verified)
            put("proof_kind", "mathematical_induction_exact_polynomial_base_and_step")
            put("integer_domain", "n >= $start"); put("recurrence", "u(n+1) = next(n,u(n))")
            put("base_difference", base.json(names)); put("induction_difference", step.json(names))
            put("notice", "数学归纳法认证所给初值与多项式递推的闭式，不将有限项相符当证明；原题是否符合此递推仍需题意和出处核对。")
        }
    }
}
