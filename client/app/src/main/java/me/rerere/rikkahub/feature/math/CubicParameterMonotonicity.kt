package me.rerere.rikkahub.feature.math

import kotlinx.serialization.json.*

/** Sign chart for f'(x)=Kx^2+La. Not a general parametric inequality prover. */
internal object CubicParameterMonotonicity {
    const val PROOF_KIND = "cubic_parameter_derivative_sign_chart"

    fun verify(input: JsonObject): JsonObject {
        if (input.keys != setOf("action", "variables", "expression", "variable", "parameter")) {
            throw MathUnsupported("invalid_cubic_monotonicity_fields")
        }
        if (input.getValue("action").jsonPrimitive.content != "cubic_monotonicity") throw MathUnsupported("invalid_cubic_monotonicity_fields")
        val variables = input.getValue("variables").jsonArray.map { it.jsonPrimitive.content }
        if (variables.size !in 2..4 || variables.distinct().size != variables.size) throw MathUnsupported("multivariate_variables_limit")
        val variable = input.getValue("variable").jsonPrimitive.content
        val parameter = input.getValue("parameter").jsonPrimitive.content
        val x = variables.indexOf(variable)
        val a = variables.indexOf(parameter)
        if (x < 0 || a < 0 || x == a) throw MathUnsupported("cubic_parameter_variables_required")
        val polynomial = MultiExpression(input.getValue("expression").jsonPrimitive.content, variables).parse()
        val derivative = polynomial.derivative(x)
        val (leading, linear) = shape(derivative, x, a)
        strictCubicLemma()
        factorizationLemma()
        val scale = linear.negate().multiply(leading.reciprocal())
        val increasing = leading.sign > 0
        val cases = if (scale.isZero) buildJsonArray {
            add(case("all", "strict_isolated_stationary", direction(increasing), null, null))
        } else buildJsonArray {
            val threeOnPositive = scale.sign > 0
            add(case("<0", if (threeOnPositive) "strict" else "three_branches", direction(increasing), if (threeOnPositive) null else direction(!increasing), scale))
            add(case("=0", "strict_isolated_stationary", direction(increasing), null, null))
            add(case(">0", if (threeOnPositive) "three_branches" else "strict", direction(increasing), if (threeOnPositive) direction(!increasing) else null, scale))
        }
        return buildJsonObject {
            put("status", "VERIFIED"); put("verified", true)
            put("proof_kind", PROOF_KIND)
            put("variable", variable); put("parameter", parameter)
            put("derivative_x2", leading.toString()); put("derivative_parameter", linear.toString()); put("scaled_parameter", scale.toString())
            put("cases", cases)
            put("notice", "只认证导数恰好为Kx^2+La的三次多项式在全部实参数上的单调符号表。a=0的严格性来自立方差恒等，两临界点的符号来自x^2-r^2=(x-r)(x+r)的实线序，不是参数采样。含x的一次导数项、参数高次或一般含参不等式仍不支持。极值与图像不是本证书。")
        }
    }

    private fun shape(derivative: MultiPolynomial, x: Int, parameter: Int): Pair<Rational, Rational> {
        var leading = Rational.ZERO
        var linear = Rational.ZERO
        for ((key, value) in derivative.terms) {
            val px = exponent(key, x)
            val pa = exponent(key, parameter)
            if ((0..3).any { it != x && it != parameter && exponent(key, it) != 0 } || pa > 1 || px > 2) {
                throw MathUnsupported("cubic_parameter_shape_unsupported")
            }
            when {
                px == 2 && pa == 0 -> leading = value
                px == 0 && pa == 1 -> linear = value
                px == 0 && pa == 0 && value.isZero -> Unit
                else -> throw MathUnsupported("cubic_parameter_shape_unsupported")
            }
        }
        if (leading.isZero) throw MathUnsupported("cubic_parameter_shape_unsupported")
        return leading to linear
    }

    private fun strictCubicLemma() {
        val u = MultiPolynomial.variable(0)
        val v = MultiPolynomial.variable(1)
        val difference = v.power(3).add(u.power(3), subtract = true)
        val factor = v.add(u, subtract = true).multiply(v.power(2).add(u.multiply(v)).add(u.power(2)))
        if (!difference.add(factor, subtract = true).zero) throw MathUnsupported("strict_cubic_lemma_failed")
    }

    private fun factorizationLemma() {
        val x = MultiPolynomial.variable(0)
        val r = MultiPolynomial.variable(1)
        val difference = x.power(2).add(r.power(2), subtract = true)
            .add(x.add(r, subtract = true).multiply(x.add(r)), subtract = true)
        if (!difference.zero) throw MathUnsupported("quadratic_factorization_lemma_failed")
    }

    private fun exponent(key: Int, index: Int) = (key ushr (index * 4)) and 15
    private fun direction(increasing: Boolean) = if (increasing) "increasing" else "decreasing"

    private fun case(parameter: String, kind: String, outer: String, middle: String?, scale: Rational?) = buildJsonObject {
        put("parameter", parameter); put("kind", kind); put("direction", outer)
        if (kind == "three_branches") {
            put("outer", outer); put("middle", requireNotNull(middle))
            put("radius_squared", "${scale}*parameter")
        }
        if (kind == "strict_isolated_stationary") {
            put("stationary", "0"); put("extremum", false)
        }
    }
}
