package me.rerere.rikkahub.feature.diagnostics

import android.os.SystemClock
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Application interceptor: includes connection/DNS failures, and observes streaming read failures without reading content. */
class DiagnosticsNetworkInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val id = Diagnostics.operationId()
        val start = SystemClock.elapsedRealtime()
        fun log(stage: Diagnostics.Stage, result: Diagnostics.Result, status: Int? = null) =
            Diagnostics.record(stage, result, id, status, SystemClock.elapsedRealtime() - start)
        log(Diagnostics.Stage.NETWORK, Diagnostics.Result.STARTED)
        val response = try { chain.proceed(chain.request()) } catch (error: IOException) {
            log(Diagnostics.Stage.NETWORK, if (chain.call().isCanceled()) Diagnostics.Result.CANCELLED else Diagnostics.Result.FAILED)
            throw error
        }
        log(Diagnostics.Stage.NETWORK, if (response.isSuccessful) Diagnostics.Result.COMPLETED else Diagnostics.Result.HTTP_ERROR, response.code)
        val body = response.body
        val ended = AtomicBoolean(false)
        val source = object : ForwardingSource(body.source()) {
            override fun read(sink: okio.Buffer, byteCount: Long): Long = try {
                super.read(sink, byteCount).also {
                    if (it == -1L && ended.compareAndSet(false, true)) log(Diagnostics.Stage.RESPONSE_BODY, Diagnostics.Result.COMPLETED)
                }
            } catch (error: IOException) {
                if (ended.compareAndSet(false, true)) log(Diagnostics.Stage.RESPONSE_BODY,
                    if (chain.call().isCanceled()) Diagnostics.Result.CANCELLED else Diagnostics.Result.FAILED)
                throw error
            }
            override fun close() {
                try { super.close() } finally {
                    if (ended.compareAndSet(false, true)) log(Diagnostics.Stage.RESPONSE_BODY,
                        if (chain.call().isCanceled()) Diagnostics.Result.CANCELLED else Diagnostics.Result.CLOSED)
                }
            }
        }.buffer()
        return response.newBuilder().body(object : ResponseBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun source(): BufferedSource = source
        }).build()
    }
}
