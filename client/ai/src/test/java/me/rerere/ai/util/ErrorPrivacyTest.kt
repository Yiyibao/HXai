package me.rerere.ai.util

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ErrorPrivacyTest {
    @Test fun wrappedResponseBodyCanBeReadForSafeErrorClassification() {
        val delegate = """{"error":{"code":"invalid_function_parameters","message":"SECRET_CANARY"}}"""
            .toByteArray().let { bytes ->
                object : okhttp3.ResponseBody() {
                    override fun contentType() = null
                    override fun contentLength() = bytes.size.toLong()
                    override fun source(): okio.BufferedSource = okio.Buffer().write(bytes)
                }
            }
        val error = parseModelHttpError(400, delegate.readModelErrorBody())
        assertEquals(ModelRejectionReason.TOOL_SCHEMA, error.rejectionReason)
        assertFalse(error.stackTraceToString().contains("SECRET_CANARY"))
    }
    @Test fun httpRejectionsRetainOnlyKnownReasonsAndStatus() {
        val cases = mapOf(
            "invalid_function_parameters" to ModelRejectionReason.TOOL_SCHEMA,
            "context_length_exceeded" to ModelRejectionReason.CONTEXT_LIMIT,
            "unsupported_parameter" to ModelRejectionReason.UNSUPPORTED_PARAMETER,
            "content_policy_violation" to ModelRejectionReason.CONTENT_POLICY,
            "prompt_blocked" to ModelRejectionReason.CONTENT_POLICY,
            "SECRET_CANARY" to null,
        )
        cases.forEach { (code, expected) ->
            val error = parseModelHttpError(400, """{"error":{"code":"$code","message":"Bearer SECRET_CANARY","param":"SECRET_CANARY"}}""")
            assertEquals(400, error.statusCode)
            assertEquals(expected, error.rejectionReason)
            assertFalse(error.stackTraceToString().contains("SECRET_CANARY"))
            assertNull(error.cause)
        }
        assertEquals(ModelRejectionReason.TOOL_SCHEMA, parseModelHttpError(400,
            """{"error":{"message":"Invalid schema for function SECRET_CANARY: additionalProperties must be false"}}""").rejectionReason)
        assertEquals(ModelRejectionReason.TOOL_HISTORY, parseModelHttpError(400,
            """{"error":{"message":"No tool call found for function_call_output SECRET_CANARY"}}""").rejectionReason)
        listOf(null, "", "SECRET_CANARY", "[]", "\"SECRET_CANARY\"").forEach { body ->
            val error = parseModelHttpError(400, body)
            assertEquals(400, error.statusCode)
            assertNull(error.rejectionReason)
            assertEquals("Model service HTTP 400", error.message)
        }
    }
    @Test fun remoteErrorBodiesNeverBecomeExceptionMessages() {
        listOf(
            "{\"error\":{\"message\":\"Bearer SECRET_CANARY\"}}",
            "{\"unexpected\":\"SECRET_CANARY\"}",
            "[\"SECRET_CANARY\"]",
            "\"SECRET_CANARY\"",
        ).forEach { raw ->
            val exception = Json.parseToJsonElement(raw).parseErrorDetail()
            assertFalse(exception.stackTraceToString().contains("SECRET_CANARY"))
            assertNull(exception.cause)
        }
    }
}
