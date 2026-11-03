package me.rerere.rikkahub.feature.agent

import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Loopback fixture only: synthetic payloads/keys, never real credentials or private material. */
internal class LocalModelServer(
    private val status: (Map<String,String>,String) -> Int = { _,_ -> 200 },
    private val disconnect: (Map<String,String>,String) -> Boolean = { _,_ -> false },
    private val allowClientDisconnect: Boolean = false,
    private val response: (String) -> String,
) : AutoCloseable {
    private val server = ServerSocket(0)
    val baseUrl = "http://127.0.0.1:${server.localPort}/v1"
    val bodies = CopyOnWriteArrayList<String>()
    val headers = CopyOnWriteArrayList<Map<String,String>>()
    val entered = CountDownLatch(1)
    @Volatile var barrier: CountDownLatch? = null
    private val active = AtomicInteger()
    val maxActive = AtomicInteger()
    private val workers = CopyOnWriteArrayList<Thread>()
    private val errors = CopyOnWriteArrayList<Throwable>()
    private val acceptor = thread(name="qa-local-model") {
        try { while (!server.isClosed) {
            val socket = server.accept()
            workers += thread(name="qa-local-model-request") {
                try { socket.use {
                    socket.soTimeout=15_000
                    val input=socket.getInputStream().buffered()
                    fun line(): String {
                        val result=StringBuilder()
                        while (true) { val byte=input.read(); check(byte>=0)
                            if (byte==10) return result.toString().trimEnd('\r')
                            require(result.length<16384); result.append(byte.toChar()) }
                    }
                    var length=0
                    val requestHeaders=mutableMapOf<String,String>()
                    while (true) { val header=line(); if (header.isEmpty()) break
                        if(':' in header) {
                            val name=header.substringBefore(':').lowercase(java.util.Locale.ROOT)
                            val value=header.substringAfter(':').trim()
                            requestHeaders[name]=value
                            if(name=="content-length") length=value.toInt()
                        }
                    }
                    require(length in 0..8_000_000)
                    val bytes=ByteArray(length); var offset=0
                    while(offset<length) { val n=input.read(bytes,offset,length-offset); check(n>0); offset+=n }
                    val body=bytes.toString(Charsets.UTF_8); bodies+=body;headers+=requestHeaders
                    val running=active.incrementAndGet(); maxActive.updateAndGet { maxOf(it,running) }; entered.countDown()
                    try {
                        barrier?.let { check(it.await(15,TimeUnit.SECONDS)) }
                        if(disconnect(requestHeaders,body)) return@use
                        val code=status(requestHeaders,body)
                        val output=response(body).toByteArray()
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 $code QA\r\nContent-Type: application/json\r\nContent-Length: ${output.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(output); flush()
                        }
                    } finally { active.decrementAndGet() }
                } } catch (error: Throwable) {
                    if (!server.isClosed && !(allowClientDisconnect && error is SocketException)) errors+=error
                }
            }
        } } catch (_: SocketException) { }
    }
    override fun close() {
        barrier?.countDown(); server.close(); acceptor.join(3000)
        workers.forEach { it.join(3000) }
        check(errors.isEmpty()) { "Synthetic server failure: ${errors.firstOrNull()}" }
    }
    companion object {
        fun completion(text: String, model: String="qa-model") = kotlinx.serialization.json.buildJsonObject {
            put("id",kotlinx.serialization.json.JsonPrimitive("synthetic"))
            put("model",kotlinx.serialization.json.JsonPrimitive(model))
            put("choices",kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.buildJsonObject {
                put("index",kotlinx.serialization.json.JsonPrimitive(0))
                put("message",kotlinx.serialization.json.buildJsonObject {
                    put("role",kotlinx.serialization.json.JsonPrimitive("assistant")); put("content",kotlinx.serialization.json.JsonPrimitive(text)) })
                put("finish_reason",kotlinx.serialization.json.JsonPrimitive("stop")) }) })
        }.toString()
    }
}
