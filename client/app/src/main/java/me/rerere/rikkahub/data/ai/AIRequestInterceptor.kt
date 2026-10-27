package me.rerere.rikkahub.data.ai

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.RequestBody
import okio.BufferedSink

class AIRequestInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = request.body
        if (body != null && !body.isOneShot()) {
            // A provider POST may already have consumed quota even when its response is lost.
            // RetryAndFollowUpInterceptor must not replay it (including Retry-After: 0).
            val singleDispatch = object : RequestBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun isDuplex() = body.isDuplex()
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
            }
            return chain.proceed(request.newBuilder().method(request.method,singleDispatch).build())
        }
        return chain.proceed(request)
    }
}
