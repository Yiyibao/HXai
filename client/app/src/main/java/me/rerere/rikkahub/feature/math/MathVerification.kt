package me.rerere.rikkahub.feature.math

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Exact Q[x] facts only. Completion authority remains with the host task verifier. */
object MathVerification {
    private val operations = setOf("identity", "derivative", "equal_expression_chain")
    private fun digest(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val digits = "0123456789abcdef"
        return buildString(bytes.size * 2) { for (b in bytes) { val n = b.toInt() and 255; append(digits[n ushr 4]); append(digits[n and 15]) } }
    }
    private fun boundedInputHash(input: JsonObject): String? {
        if (input.size > 4 || input.keys.any { it.length > 64 }) return null
        for (value in input.values) when (value) {
            is JsonPrimitive -> if (!value.isString || value.content.length > QxLimits.CHARS) return null
            is JsonArray -> if (value.size > 50 || value.any { it !is JsonPrimitive || !it.isString || it.content.length > QxLimits.CHARS }) return null
            else -> return null
        }
        return digest(buildJsonObject { for (key in input.keys.sorted()) put(key, input.getValue(key)) }.toString())
    }
    private fun scope(operation: String) = when (operation) {
        "identity" -> "formal_univariate_rational_polynomial_identity"
        "derivative" -> "formal_polynomial_derivative"
        "equal_expression_chain" -> "equal_expression_chain_only"
        else -> "unsupported_request"
    }
    private fun string(input: JsonObject, key: String): String {
        val value = input[key] as? JsonPrimitive ?: throw MathUnsupported("invalid_request_fields")
        if (!value.isString) throw MathUnsupported("invalid_request_fields")
        return value.content
    }
    private fun unsupported(reason: String): JsonObject = buildJsonObject {
        put("status", "UNSUPPORTED"); put("verified", false); put("reason", reason)
    }
    private fun compare(left: Polynomial, right: Polynomial, includeCanonical: Boolean = true): JsonObject {
        val difference = left.add(right, subtract = true)
        return buildJsonObject {
            put("status", if (difference.isZero) "VERIFIED" else "DISPROVED")
            put("verified", difference.isZero)
            put("proof_kind", if (difference.isZero) "exact_rational_coefficients" else "exact_nonzero_rational_coefficients")
            if (includeCanonical && difference.isZero) put("canonical_coefficients", left.json())
            put("difference_coefficients", difference.json())
            if (!difference.isZero) {
                // Nonzero exact coefficients are the disproof; these points only produce a witness.
                val x = (0..difference.degree).first { !difference.value(it).isZero }
                put("counterexample", buildJsonObject { put("x", x); put("left", left.value(x).toString()); put("right", right.value(x).toString()) })
            }
        }
    }
    private suspend fun chain(expressions: JsonArray, nodes: MutableList<Int>): JsonObject {
        if (expressions.size !in 2..50 || expressions.any { it !is JsonPrimitive || !it.isString }) throw MathUnsupported("provide_2_to_50_expression_strings")
        val checks = mutableListOf<JsonElement>()
        var previous: Polynomial? = null
        for (index in 0 until expressions.size - 1) {
            currentCoroutineContext().ensureActive()
            val check = try {
                val left = previous ?: PolynomialParser(expressions[index].jsonPrimitive.content).parse().also { nodes += it.astNodes }.polynomial
                val right = PolynomialParser(expressions[index + 1].jsonPrimitive.content).parse().also { nodes += it.astNodes }.polynomial
                previous = right
                compare(left, right, includeCanonical = false)
            } catch (e: MathUnsupported) { unsupported(e.reason) }
            checks += check
            val status = check.getValue("status").jsonPrimitive.content
            if (status != "VERIFIED") return buildJsonObject {
                put("status", status); put("verified", false)
                put("first_error_transition", if (status == "DISPROVED") JsonPrimitive(index + 1) else JsonNull)
                put("unverified_transition", if (status == "UNSUPPORTED") JsonPrimitive(index + 1) else JsonNull)
                put("verified_prefix_transitions", index); put("checks", JsonArray(checks))
            }
        }
        return buildJsonObject {
            put("status", "VERIFIED"); put("verified", true)
            put("first_error_transition", JsonNull); put("unverified_transition", JsonNull)
            put("verified_prefix_transitions", expressions.size - 1); put("checks", JsonArray(checks))
            put("proof_kind", "exact_rational_coefficients"); put("canonical_coefficients", checkNotNull(previous).json())
        }
    }

    suspend fun verify(input: JsonObject): JsonObject = withContext(Dispatchers.Default) {
        currentCoroutineContext().ensureActive()
        val requested = (input["operation"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val operation = requested?.takeIf { it in operations } ?: "unsupported_request"
        val nodes = mutableListOf<Int>()
        val result = try {
            val fields = when (operation) {
                "identity" -> setOf("operation", "left", "right")
                "derivative" -> setOf("operation", "expression", "claimed_derivative")
                "equal_expression_chain" -> setOf("operation", "expressions")
                else -> throw MathUnsupported("unsupported_operation")
            }
            if (input.keys != fields) throw MathUnsupported("invalid_request_fields")
            when (operation) {
                "identity", "derivative" -> {
                    val left = PolynomialParser(string(input, if (operation == "identity") "left" else "expression")).parse().also { nodes += it.astNodes }
                    currentCoroutineContext().ensureActive()
                    val right = PolynomialParser(string(input, if (operation == "identity") "right" else "claimed_derivative")).parse().also { nodes += it.astNodes }
                    compare(if (operation == "derivative") left.polynomial.derivative() else left.polynomial, right.polynomial)
                }
                else -> chain(input["expressions"] as? JsonArray ?: throw MathUnsupported("provide_2_to_50_expression_strings"), nodes)
            }
        } catch (e: MathUnsupported) { unsupported(e.reason) }
        currentCoroutineContext().ensureActive()
        buildJsonObject {
            for ((key, value) in result) put(key, value)
            put("contractVersion", 1); put("operation", operation); put("domain", "Q[x]"); put("scope", scope(operation))
            put("input_sha256", boundedInputHash(input)?.let(::JsonPrimitive) ?: JsonNull)
            put("ast_nodes", JsonArray(nodes.map(::JsonPrimitive)))
            put("limits", buildJsonObject {
                put("expression_chars", QxLimits.CHARS); put("ast_nodes", QxLimits.NODES); put("degree", QxLimits.DEGREE)
                put("coefficient_bits", QxLimits.BITS); put("parser_depth", QxLimits.DEPTH); put("chain_expressions", 50)
            })
            put("notice", "仅核验Q[x]多项式恒等、形式导数或相等表达式链；不是一般证明、方程等价、OCR或评分。精确系数是依据，整数点只为反例见证；UNSUPPORTED/DISPROVED不自动成为用户目标成功。")
        }
    }

    /** Recompute from durable input, not the model's status or claimed proof. */
    suspend fun accepts(input: JsonObject, candidate: JsonObject): Boolean {
        val actual = verify(input)
        if (actual.getValue("status").jsonPrimitive.content != "VERIFIED" || candidate.keys != actual.keys) return false
        if ((candidate["notice"] as? JsonPrimitive)?.isString != true) return false
        return actual.all { (key, value) -> key == "notice" || candidate[key] == value }
    }
}
