package me.rerere.rikkahub.feature.math

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** A small explicit trusted theorem catalogue, not a truth oracle for hypotheses. */
internal object AnalysisTheorems {
    data class Rule(val parameters: List<String>, val hypotheses: List<String>, val conclusion: String, val reference: String)
    val rules = linkedMapOf(
        "monotone_bounded_sequence" to Rule(listOf("sequence"), listOf("monotone(sequence)", "bounded(sequence)"), "convergent(sequence)", "https://ocw.mit.edu/courses/18-100b-real-analysis-spring-2025/mit18_100b_s25_lec05.pdf"),
        "weierstrass_m_test" to Rule(listOf("series", "majorant", "domain"), listOf("termwise_bound(series,majorant,domain)", "summable_nonnegative(majorant)"), "uniformly_absolutely_convergent(series,domain)", "https://ocw.mit.edu/courses/18-100a-real-analysis-fall-2020/mit18_100af20_lec242.pdf"),
        "uniform_limit_continuity" to Rule(listOf("sequence", "limit", "domain"), listOf("continuous_terms(sequence,domain)", "uniform_limit(sequence,limit,domain)"), "continuous(limit,domain)", "https://ocw.mit.edu/courses/18-100a-real-analysis-fall-2020/mit18_100af20_lec242.pdf"),
        "dominated_convergence" to Rule(listOf("sequence", "limit", "dominator", "measure"), listOf("measurable_terms(sequence,measure)", "measurable(limit,measure)", "pointwise_ae_limit(sequence,limit,measure)", "integrable(dominator,measure)", "absolute_domination_ae(sequence,dominator,measure)"), "integral_limit_commutes(sequence,limit,measure)", "https://ocw.mit.edu/courses/18-102-introduction-to-functional-analysis-spring-2021/e407e57ea631a29148ee94afecef7d33_MIT18_102s21_lec12.pdf"),
        "monotone_convergence" to Rule(listOf("sequence", "limit", "measure"), listOf("measurable_terms(sequence,measure)", "measurable(limit,measure)", "nonnegative_increasing_ae(sequence,measure)", "pointwise_ae_limit(sequence,limit,measure)"), "extended_integral_limit_commutes(sequence,limit,measure)", "https://encyclopediaofmath.org/wiki/Beppo_Levi_theorem"),
        "termwise_integral" to Rule(listOf("sequence", "limit", "interval"), listOf("compact_interval(interval)", "riemann_integrable_terms(sequence,interval)", "uniform_limit(sequence,limit,interval)"), "integral_limit_commutes(sequence,limit,interval)", "https://encyclopediaofmath.org/wiki/Uniform_convergence"),
        "termwise_derivative" to Rule(listOf("sequence", "limit", "derivative", "interval", "point"), listOf("nondegenerate_closed_interval(interval)", "continuously_differentiable_terms(sequence,interval)", "point_in_interval(point,interval)", "converges_at(sequence,point)", "uniform_derivative_limit(sequence,derivative,interval)", "pointwise_limit(sequence,limit,interval)"), "derivative_equals_on_interior(limit,derivative,interval)", "https://encyclopediaofmath.org/wiki/Uniform_convergence"),
        "mean_value" to Rule(listOf("function", "interval"), listOf("nondegenerate_closed_interval(interval)", "continuous_on_closed(function,interval)", "differentiable_on_interior(function,interval)"), "exists_mean_value_point(function,interval)", "https://encyclopediaofmath.org/wiki/Lagrange_theorem"),
        "tonelli" to Rule(listOf("function", "measure_a", "measure_b"), listOf("sigma_finite(measure_a)", "sigma_finite(measure_b)", "product_measurable(function,measure_a,measure_b)", "nonnegative_ae(function,measure_a,measure_b)"), "extended_iterated_integrals_equal(function,measure_a,measure_b)", "https://encyclopediaofmath.org/wiki/Fubini_theorem"),
        "fubini" to Rule(listOf("function", "measure_a", "measure_b"), listOf("sigma_finite(measure_a)", "sigma_finite(measure_b)", "product_measurable(function,measure_a,measure_b)", "absolutely_product_integrable(function,measure_a,measure_b)"), "finite_iterated_integrals_equal(function,measure_a,measure_b)", "https://encyclopediaofmath.org/wiki/Fubini_theorem"),
    )
    fun instantiate(name: String, parameters: JsonObject): Pair<List<JsonElement>, JsonElement> {
        val rule = rules[name] ?: throw MathUnsupported("unregistered_theorem")
        if (parameters.keys != rule.parameters.toSet()) throw MathUnsupported("theorem_parameter_mismatch")
        val names = parameters.mapValues { (_, value) -> value.jsonPrimitive.also {
            if (!it.isString || !it.content.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,31}"))) throw MathUnsupported("theorem_symbol_required")
        }.content }
        fun statement(template: String): JsonObject {
            val rendered = Regex("[A-Za-z][A-Za-z0-9_]*").replace(template) { names[it.value] ?: it.value }
            return buildJsonObject { put("atom", rendered) }
        }
        return rule.hypotheses.map(::statement) to statement(rule.conclusion)
    }
    fun catalogue(): JsonObject = buildJsonObject {
        put("status", "ANALYZED"); put("verified", false); put("scope", "theorem_conditions_not_proofs_of_hypotheses")
        put("rules", JsonArray(rules.map { (id, rule) -> buildJsonObject {
            put("id", id); put("parameters", JsonArray(rule.parameters.map(::JsonPrimitive)))
            put("hypotheses", JsonArray(rule.hypotheses.map(::JsonPrimitive))); put("conclusion", rule.conclusion); put("reference", rule.reference)
        } }))
        put("notice", "注册定理是显式可信规则；列出条件不证明条件。条件须是原题明确假设或已有推导，任意model=true/网页声明不产生证明。实变中几乎处处、可积/绝对可积、有限/扩展积分和σ有限条件不互换。引用URL只说明定理出处，不冒充已检索教材页码。")
    }
}

/** Natural deduction with dependency tracking. Unknown prose blocks a first-error claim. */
internal object ProofDerivation {
    private data class Line(val formula: JsonElement, val dependencies: Set<String>)
    private fun formula(value: JsonElement, depth: Int = 0): JsonElement {
        if (depth > 20) throw MathUnsupported("proof_formula_depth")
        val obj = value as? JsonObject ?: throw MathUnsupported("proof_formula_object_required")
        if (obj.keys == setOf("atom")) {
            val atom = obj.getValue("atom").jsonPrimitive
            if (!atom.isString || atom.content.length !in 1..1000) throw MathUnsupported("proof_atom_required")
            return obj
        }
        if (obj.keys == setOf("math_fact")) return obj
        if (obj.keys == setOf("false") && obj["false"] == JsonPrimitive(true)) return obj
        val key = obj.keys.singleOrNull() ?: throw MathUnsupported("proof_connective_required")
        if (key == "not") return buildJsonObject { put(key, formula(obj.getValue(key), depth + 1)) }
        if (key !in setOf("and", "or", "implies")) throw MathUnsupported("unsupported_proof_connective")
        val pair = obj.getValue(key) as? JsonArray ?: throw MathUnsupported("proof_binary_connective_required")
        if (pair.size != 2) throw MathUnsupported("proof_binary_connective_required")
        return buildJsonObject { put(key, JsonArray(pair.map { formula(it, depth + 1) })) }
    }
    private fun binary(value: JsonElement, key: String): JsonArray? = (value as? JsonObject)?.takeIf { it.keys == setOf(key) }?.get(key) as? JsonArray
    suspend fun verify(input: JsonObject): JsonObject {
        if (input.keys != setOf("assumptions", "goal", "steps")) throw MathUnsupported("invalid_proof_fields")
        val assumptions = input.getValue("assumptions").jsonArray
        val steps = input.getValue("steps").jsonArray
        if (assumptions.size > 32 || steps.size !in 1..100) throw MathUnsupported("proof_size_limit")
        val hypotheses = linkedMapOf<String, JsonElement>()
        for (entry in assumptions) {
            val a = entry.jsonObject
            if (a.keys != setOf("id", "formula")) throw MathUnsupported("invalid_assumption_fields")
            val id = a.getValue("id").jsonPrimitive.content
            if (!id.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,31}")) || id in hypotheses) throw MathUnsupported("duplicate_or_invalid_assumption")
            hypotheses[id] = formula(a.getValue("formula"))
        }
        val goal = formula(input.getValue("goal"))
        val derived = linkedMapOf<String, Line>()
        val stepIds = HashSet<String>()
        val checks = ArrayList<JsonElement>()
        var blocked = false
        var firstError: Int? = null
        var firstUnknown: Int? = null
        for ((index, item) in steps.withIndex()) {
            currentCoroutineContext().ensureActive()
            val step = item.jsonObject
            val id = step.getValue("id").jsonPrimitive.content
            if (!id.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,31}")) || !stepIds.add(id)) throw MathUnsupported("duplicate_or_invalid_proof_step")
            val rule = step.getValue("rule").jsonPrimitive.content
            val conclusion = formula(step.getValue("conclusion"))
            val refs = (step["premises"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
            if (refs.distinct().size != refs.size) throw MathUnsupported("duplicate_proof_premise")
            val previous = refs.map { derived[it] }
            var actual: JsonElement? = null
            var dependencies: Set<String> = emptySet()
            var unknown = rule == "narrative" || previous.any { it == null }
            var reason = if (unknown) "unverified_reasoning_or_premise" else "invalid_inference"
            if (!unknown) {
                val lines = previous.filterNotNull()
                dependencies = lines.flatMap { it.dependencies }.toSet()
                when (rule) {
                    "assumption" -> {
                        val hypothesis = step["assumption"]?.jsonPrimitive?.content
                        if (refs.isEmpty() && hypothesis in hypotheses) { actual = hypotheses[hypothesis]; dependencies = setOf(checkNotNull(hypothesis)) }
                    }
                    "restate" -> if (lines.size == 1) actual = lines[0].formula
                    "modus_ponens" -> if (lines.size == 2) {
                        val pair = binary(lines[0].formula, "implies")
                        if (pair != null && pair[0] == lines[1].formula) actual = pair[1]
                    }
                    "and_intro" -> if (lines.size == 2) actual = buildJsonObject { put("and", JsonArray(lines.map { it.formula })) }
                    "and_left", "and_right" -> if (lines.size == 1) actual = binary(lines[0].formula, "and")?.get(if (rule == "and_left") 0 else 1)
                    "or_intro" -> if (lines.size == 1) binary(conclusion, "or")?.let { if (lines[0].formula == it[0] || lines[0].formula == it[1]) actual = conclusion }
                    "implies_intro" -> if (lines.size == 1) {
                        val hypothesis = step["assumption"]?.jsonPrimitive?.content
                        if (hypothesis in hypotheses) {
                            actual = buildJsonObject { put("implies", JsonArray(listOf(hypotheses.getValue(checkNotNull(hypothesis)), lines[0].formula))) }
                            dependencies = dependencies - checkNotNull(hypothesis)
                        }
                    }
                    "contradiction" -> if (lines.size == 2) {
                        val negation = (lines[1].formula as? JsonObject)?.get("not")
                        if (negation == lines[0].formula) actual = buildJsonObject { put("false", true) }
                    }
                    "explosion" -> if (lines.size == 1 && lines[0].formula == buildJsonObject { put("false", true) }) actual = conclusion
                    "theorem" -> {
                        val theorem = step.getValue("theorem").jsonPrimitive.content
                        val (required, result) = AnalysisTheorems.instantiate(theorem, step.getValue("parameters").jsonObject)
                        if (required.size == lines.size && required == lines.map { it.formula }) actual = result
                    }
                    "math" -> {
                        val request = step.getValue("input").jsonObject
                        if (request["operation"]?.jsonPrimitive?.content in ProfessionalMath.workflowOperations) throw MathUnsupported("recursive_proof_workflow_forbidden")
                        val proof = MathVerification.verify(request)
                        if (proof["status"] == JsonPrimitive("VERIFIED")) actual = buildJsonObject { put("math_fact", request) }
                        else { unknown = proof["status"] != JsonPrimitive("DISPROVED") || proof["claim_status"] == JsonPrimitive("NOT_EVALUATED"); reason = if (unknown) "mathematical_fact_not_certified" else "mathematical_fact_disproved" }
                    }
                    else -> { unknown = true; reason = "unregistered_proof_rule" }
                }
            }
            val valid = !unknown && actual == conclusion
            val status = if (valid) "VERIFIED" else if (unknown) "UNSUPPORTED" else "DISPROVED"
            if (!valid) {
                if (!blocked && !unknown) firstError = index + 1
                if (unknown && firstUnknown == null) firstUnknown = index + 1
                blocked = true
            } else derived[id] = Line(conclusion, dependencies)
            checks += buildJsonObject { put("step", index + 1); put("id", id); put("status", status); if (!valid) put("reason", reason) }
        }
        val last = derived[steps.last().jsonObject.getValue("id").jsonPrimitive.content]
        val goalReached = !blocked && last?.formula == goal
        return buildJsonObject {
            put("status", if (goalReached) "VERIFIED" else if (firstError != null) "DISPROVED" else "UNSUPPORTED")
            put("verified", goalReached); put("proof_kind", "checked_natural_deduction_under_explicit_assumptions")
            put("goal_reached", goalReached); put("checks", JsonArray(checks))
            put("first_error_step", firstError?.let(::JsonPrimitive) ?: JsonNull)
            put("first_unverified_step", firstUnknown?.let(::JsonPrimitive) ?: JsonNull)
            put("undischarged_assumptions", JsonArray(last?.dependencies.orEmpty().sorted().map(::JsonPrimitive)))
            put("claim_is_conditional", !last?.dependencies.isNullOrEmpty())
            put("notice", "仅认证显式假设下已登记推理规则的推导。未解除假设须保留在结论中；定理条件文字不是条件已被证明。自然语言步骤/未知前缀不自动获证，不把后续错误宣称为首错；正确异法按自身推理而非标准答案字串比较。")
        }
    }
}
