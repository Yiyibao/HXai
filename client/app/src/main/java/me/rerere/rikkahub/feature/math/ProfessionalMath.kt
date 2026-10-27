package me.rerere.rikkahub.feature.math

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Professional workflows reuse the existing verifier and never create task authority. */
internal object ProfessionalMath {
    val operations = setOf("professional_linear_algebra", "professional_multivariate", "professional_induction", "professional_proof", "professional_numeric", "professional_solution", "professional_review", "professional_theorems", "professional_calculus")
    val workflowOperations = setOf("professional_proof", "professional_solution", "professional_review")
    const val REQUEST_CHARS = 65536
    fun request(raw: String): JsonObject {
        if (raw.length !in 2..REQUEST_CHARS) throw MathUnsupported("professional_request_length_limit")
        // Reject adversarial nesting before the JSON parser allocates a recursive tree.
        var depth = 0; var quoted = false; var escaped = false
        for (c in raw) {
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
            else when (c) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > 32) throw MathUnsupported("professional_json_depth_limit")
                '}', ']' -> if (--depth < 0) throw MathUnsupported("invalid_professional_json")
            }
        }
        if (quoted || depth != 0) throw MathUnsupported("invalid_professional_json")
        return Json.parseToJsonElement(raw) as? JsonObject ?: throw MathUnsupported("professional_request_object_required")
    }
    suspend fun verify(operation: String, input: JsonObject): JsonObject = when (operation) {
        "professional_linear_algebra" -> ExactLinearAlgebra.verify(input)
        "professional_multivariate" -> MultivariateCertificate.verify(input)
        "professional_induction" -> MultivariateCertificate.induction(input)
        "professional_numeric" -> CertifiedRealArithmetic.verify(input)
        "professional_calculus" -> CertifiedCalculus.verify(input)
        "professional_proof" -> ProofDerivation.verify(input)
        "professional_theorems" -> {
            if (input.isNotEmpty()) throw MathUnsupported("theorem_catalogue_takes_empty_request")
            JsonObject(AnalysisTheorems.catalogue() + mapOf("status" to JsonPrimitive("VERIFIED"), "verified" to JsonPrimitive(true), "proof_kind" to JsonPrimitive("trusted_rule_catalogue_readback_only")))
        }
        "professional_solution" -> solution(input)
        "professional_review" -> review(input)
        else -> throw MathUnsupported("unsupported_professional_operation")
    }
    private suspend fun fact(input: JsonObject): JsonObject {
        val operation = input["operation"]?.jsonPrimitive?.content ?: throw MathUnsupported("subproblem_operation_required")
        if (operation in setOf("professional_solution", "professional_review", "professional_theorems")) throw MathUnsupported("recursive_or_catalogue_fact_forbidden")
        return MathVerification.verify(input)
    }
    private suspend fun solution(input: JsonObject): JsonObject {
        if (input.keys != setOf("problem", "goals", "checks")) throw MathUnsupported("invalid_solution_fields")
        val problem = input.getValue("problem").jsonPrimitive.also { if (!it.isString || it.content.length !in 1..8000) throw MathUnsupported("problem_statement_required") }.content
        val goals = input.getValue("goals").jsonArray
        val checks = input.getValue("checks").jsonArray
        if (goals.size !in 1..20 || checks.size !in 1..40) throw MathUnsupported("solution_size_limit")
        val predicates = linkedMapOf<String, JsonObject>()
        val goalIds = goals.map { value ->
            val goal = value.jsonObject
            if (goal.keys != setOf("id", "statement", "predicate")) throw MathUnsupported("invalid_goal_fields")
            if (goal.getValue("statement").jsonPrimitive.let { !it.isString || it.content.length !in 1..2000 }) throw MathUnsupported("goal_statement_required")
            goal.getValue("id").jsonPrimitive.also { if (!it.isString) throw MathUnsupported("goal_id_required") }.content.also {
                predicates[it] = goal.getValue("predicate").jsonObject
            }
        }
        if (goalIds.distinct().size != goalIds.size || goalIds.any { !it.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,31}")) }) throw MathUnsupported("invalid_goal_identity")
        val covered = HashSet<String>()
        var allVerified = true
        val results = ArrayList<JsonElement>()
        for (item in checks) {
            currentCoroutineContext().ensureActive()
            val check = item.jsonObject
            if (check.keys != setOf("goal", "input")) throw MathUnsupported("invalid_subproblem_check_fields")
            val goal = check.getValue("goal").jsonPrimitive.content
            if (goal !in goalIds) throw MathUnsupported("unknown_subproblem_goal")
            val request = check.getValue("input").jsonObject
            if (request != predicates[goal]) throw MathUnsupported("subproblem_predicate_mismatch")
            val result = fact(request)
            val verified = result["status"] == JsonPrimitive("VERIFIED")
            if (verified) covered += goal else allVerified = false
            results += buildJsonObject { put("goal", goal); put("input", request); put("result", result) }
        }
        val complete = allVerified && covered == goalIds.toSet()
        return buildJsonObject {
            put("status", if (complete) "VERIFIED" else "UNSUPPORTED"); put("verified", complete)
            put("proof_kind", "declared_subproblem_certificate_set")
            put("problem", problem); put("goals", goals); put("checks", JsonArray(results))
            put("unverified_goals", JsonArray(goalIds.filter { it !in covered }.map(::JsonPrimitive)))
            put("coverage", "EXPLICIT_DECLARED_PREDICATES_ONLY"); put("natural_language_solution_certified", false)
            put("notice", "综合题须保留完整题设、参数分支、定义域、方法与推导；本工具核对所声明子目标证书，不能从部分计算推断原题所有目标均已覆盖。原题覆盖及最终解释仍由同一锁定模型与宿主目标验收核对，图形/数值不得替代证明。")
        }
    }
    private suspend fun review(input: JsonObject): JsonObject {
        if (input.keys != setOf("steps", "reference_note")) throw MathUnsupported("invalid_review_fields")
        val steps = input.getValue("steps").jsonArray
        if (steps.size !in 1..100) throw MathUnsupported("review_step_limit")
        if (input.getValue("reference_note").jsonPrimitive.content.length > 8000) throw MathUnsupported("reference_note_limit")
        val results = ArrayList<JsonElement>()
        var firstError: Int? = null; var firstUnknown: Int? = null; var prefix = 0; var blocked = false
        for ((index, item) in steps.withIndex()) {
            currentCoroutineContext().ensureActive()
            val step = item.jsonObject
            if (step.keys != setOf("original", "locator", "check")) throw MathUnsupported("invalid_review_step_fields")
            val original = step.getValue("original").jsonPrimitive.also { if (!it.isString || it.content.length !in 1..2000) throw MathUnsupported("original_step_required") }.content
            val locator = step.getValue("locator").jsonPrimitive.also { if (!it.isString || it.content.length !in 1..300) throw MathUnsupported("step_locator_required") }.content
            val request = step["check"] as? JsonObject
            val result = request?.let { fact(it) }
            val rawStatus = result?.get("status")?.jsonPrimitive?.content
            val status = if (rawStatus == "DISPROVED" && result["claim_status"] == JsonPrimitive("NOT_EVALUATED")) "UNSUPPORTED" else rawStatus ?: "UNSUPPORTED"
            if (status == "VERIFIED" && !blocked) prefix++
            if (status != "VERIFIED") {
                if (!blocked && status == "DISPROVED") firstError = index + 1
                if (status != "DISPROVED" && firstUnknown == null) firstUnknown = index + 1
                blocked = true
            }
            results += buildJsonObject {
                put("step", index + 1); put("original", original); put("locator", locator)
                put("status", status); put("checked_claim", request ?: JsonNull); put("evidence", result ?: JsonNull)
            }
        }
        return buildJsonObject {
            put("status", if (!blocked) "VERIFIED" else if (firstError != null) "DISPROVED" else "UNSUPPORTED")
            put("verified", !blocked); put("proof_kind", "ordered_explicit_claim_review_not_ocr_or_grading")
            put("verified_prefix_steps", prefix); put("first_error_step", firstError?.let(::JsonPrimitive) ?: JsonNull)
            put("first_unverified_step", firstUnknown?.let(::JsonPrimitive) ?: JsonNull); put("checks", JsonArray(results))
            put("transcription_source_status", "MUST_BE_CHECKED_SEPARATELY"); put("score", JsonNull)
            put("notice", "保留原步与定位，仅判断映射出的明确数学声明，不按参考解法字串评分。前缀未知则不宣称后续是首错；错误证书不等于结论错。图片转录/步骤映射须对照固定原页，未确认符号先澄清；该纯计算结果不是机器OCR质量或整份手写已认证。")
        }
    }
}
