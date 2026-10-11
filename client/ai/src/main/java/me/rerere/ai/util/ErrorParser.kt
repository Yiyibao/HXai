package me.rerere.ai.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class HttpException(
    message: String,
    val statusCode: Int? = null,
    val rejectionReason: ModelRejectionReason? = null,
) : RuntimeException(message)

enum class ModelRejectionReason(val description: String) {
    TOOL_SCHEMA("invalid tool schema"),
    TOOL_HISTORY("invalid tool call history"),
    CONTEXT_LIMIT("context limit exceeded"),
    UNSUPPORTED_PARAMETER("unsupported parameter"),
    CONTENT_POLICY("content policy rejection"),
    SERVICE_ERROR("upstream service error"),
    RATE_LIMIT("rate limit exceeded"),
    MODEL_UNAVAILABLE("model unavailable"),
}

/** Keep an allowlisted reason while discarding all remotely supplied text and identifiers. */
fun parseModelHttpError(statusCode: Int, body: String?): HttpException =
    modelRejection(statusCode, body)

private fun modelRejection(statusCode: Int?, body: String?): HttpException {
    val error = runCatching {
        val root = Json.parseToJsonElement(body.orEmpty()) as? JsonObject
        (root?.get("error") as? JsonObject) ?: root
    }.getOrNull()
    fun field(name: String) = (error?.get(name) as? JsonPrimitive)?.content.orEmpty().lowercase()
    val code = field("code")
    val message = field("message")
    val reason = when {
        code == "invalid_function_parameters" || code == "invalid_tool_schema" ||
            message.contains("invalid schema") || message.contains("additionalproperties") -> ModelRejectionReason.TOOL_SCHEMA
        code == "context_length_exceeded" -> ModelRejectionReason.CONTEXT_LIMIT
        code in setOf("content_filter", "content_policy_violation", "prompt_blocked", "completion_blocked") ||
            message.contains("blocked by prompt filter") -> ModelRejectionReason.CONTENT_POLICY
        code in setOf("unsupported_parameter", "unsupported_value") -> ModelRejectionReason.UNSUPPORTED_PARAMETER
        code in setOf("server_error", "internal_server_error") -> ModelRejectionReason.SERVICE_ERROR
        code in setOf("rate_limit_exceeded", "insufficient_quota") -> ModelRejectionReason.RATE_LIMIT
        code == "model_not_found" -> ModelRejectionReason.MODEL_UNAVAILABLE
        message.contains("tool_call_id") || message.contains("function_call_output") ||
            message.contains("no tool call found") -> ModelRejectionReason.TOOL_HISTORY
        else -> null
    }
    val title = statusCode?.let { "Model service HTTP $it" } ?: "Model service rejected the request"
    val detail = if (statusCode == null) "" else reason?.let { " (${it.description})" }.orEmpty()
    return HttpException(title + detail, statusCode, reason)
}

fun JsonElement.parseErrorDetail(): HttpException {
    // Remote error bodies may echo Authorization, request JSON, or private URLs.
    // Callers retain the HTTP status separately; never promote the body to an exception.
    return modelRejection(null, toString())
}
