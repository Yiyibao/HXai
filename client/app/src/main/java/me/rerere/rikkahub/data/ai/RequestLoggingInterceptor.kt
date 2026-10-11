package me.rerere.rikkahub.data.ai

import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import okhttp3.Interceptor
import okhttp3.Response

class RequestLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!Logging.isRequestLoggingEnabled()) {
            return chain.proceed(chain.request())
        }

        val request = chain.request()
        val startTime = System.currentTimeMillis()

        // Custom headers, URL paths/queries and bodies can all contain credentials.
        // Keep diagnostics metadata-only, even when request logging is enabled.
        val requestHeaders = emptyMap<String, String>()
        val requestBody: String? = null

        val response: Response
        var error: String? = null

        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            error = "Network request failed"
            Logging.logRequest(
                LogEntry.RequestLog(
                    tag = "HTTP",
                    url = "[redacted]",
                    method = request.method,
                    requestHeaders = requestHeaders,
                    requestBody = requestBody,
                    error = error
                )
            )
            throw e
        }

        val durationMs = System.currentTimeMillis() - startTime
        val responseHeaders = emptyMap<String, String>()

        Logging.logRequest(
            LogEntry.RequestLog(
                tag = "HTTP",
                url = "[redacted]",
                method = request.method,
                requestHeaders = requestHeaders,
                requestBody = requestBody,
                responseCode = response.code,
                responseHeaders = responseHeaders,
                durationMs = durationMs,
                error = error
            )
        )

        return response
    }

}
