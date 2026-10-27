package me.rerere.rikkahub.feature.agent

import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Proxy
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit

/** One bounded session per invocation/readback. Never reconnects or replays an uncertain POST. */
internal class ExtensionMcpTransport(private val endpoint: String, private val protocol: String,
    private val token: String, private val profileHash: String? = null, private val guard: () -> Unit) {
    private var sequence = 0
    private var session: String? = null
    private var initialized = false
    fun initialize(): JsonObject {
        val result=requireNotNull(rpc("initialize",buildJsonObject {
            put("protocolVersion",protocol);put("capabilities",buildJsonObject {})
            put("clientInfo",buildJsonObject { put("name","HXai");put("version","a04-v1") })
        }))
        require(result["protocolVersion"] == JsonPrimitive(protocol) && result["capabilities"]?.jsonObject?.get("tools") is JsonObject) { "MCP 协议或工具能力不匹配" }
        initialized=true
        rpc("notifications/initialized",null,notification=true)
        return result
    }
    fun tools(): List<JsonObject> {
        val all=mutableListOf<JsonObject>();val cursors=mutableSetOf<String>();var cursor: String?=null
        repeat(8) {
            val result=requireNotNull(rpc("tools/list",buildJsonObject { cursor?.let { put("cursor",it) } }))
            all += result.getValue("tools").jsonArray.map { it.jsonObject }
            require(all.size <= 128 && all.map { it.getValue("name").jsonPrimitive.content }.distinct().size == all.size)
            cursor=result["nextCursor"]?.jsonPrimitive?.also { require(it.isString) }?.content
            if(cursor == null) return all
            require(cursor!!.length <= 2048 && cursors.add(cursor!!))
        }
        error("MCP 工具分页超出限制")
    }
    fun call(name: String, args: JsonObject): JsonObject {
        val result=requireNotNull(rpc("tools/call",buildJsonObject { put("name",name);put("arguments",args) }))
        require(result["isError"] == null || result["isError"] == JsonPrimitive(false)) { "远端工具失败；结果未核验" }
        require(result["content"] is JsonArray)
        // No remote URL/resource/image is downloaded or treated as executable instructions.
        require(result.getValue("content").jsonArray.all {
            it.jsonObject["type"] == JsonPrimitive("text") && it.jsonObject["text"]?.jsonPrimitive?.isString == true
        }) { "仅支持文字/结构化结果；未加载远端资源" }
        guard();return result
    }
    private fun rpc(method: String, params: JsonObject?, notification: Boolean = false): JsonObject? {
        return try { rpcChecked(method,params,notification) }
        catch(e: kotlinx.coroutines.CancellationException) { throw e }
        catch(_: Exception) { error("MCP 请求未获可验证结果；请核对连接、凭据或契约，未自动重试。") }
    }
    private fun rpcChecked(method: String, params: JsonObject?, notification: Boolean): JsonObject? {
        guard()
        val id=++sequence
        val payload=buildJsonObject { put("jsonrpc","2.0");if(!notification) put("id",id);put("method",method);params?.let { put("params",it) } }
        val request=Request.Builder().url(endpoint).header("Accept","application/json, text/event-stream")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
        if(initialized) request.header("MCP-Protocol-Version",protocol)
        session?.let { request.header("Mcp-Session-Id",it) }
        if(token.isNotEmpty()) request.header("Authorization","Bearer $token")
        profileHash?.let { request.header("X-HXai-Profile",it) }
        val value=client.newCall(request.build()).execute().use { response ->
            guard()
            require(response.code != 401 && response.code != 403) { "NEEDS_AUTH：请在前台重新配置此服务的凭据；未自动恢复授权" }
            require(response.code == if(notification) 202 else 200) { "MCP 请求未获有效响应；未自动重试" }
            val received=response.header("Mcp-Session-Id")
            if(received != null) {
                require(received.length in 1..200 && received.all { it.code in 33..126 })
                require(!initialized || received == session) { "MCP 会话被替换" };session=received
            }
            val stream=response.body.byteStream()
            val buffer=java.io.ByteArrayOutputStream();val bytes=ByteArray(4096)
            while(true) { val n=stream.read(bytes,0,minOf(bytes.size,262145-buffer.size()));if(n<0) break
                buffer.write(bytes,0,n);require(buffer.size() <= 262144) { "MCP 结果超限" };guard() }
            val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(buffer.toByteArray())).toString()
            if(notification) { require(text.isEmpty());null } else {
                val mime=response.body.contentType()?.let { "${it.type}/${it.subtype}" }
                val envelopes=when(mime) {
                    "application/json" -> listOf(Json.parseToJsonElement(text).jsonObject)
                    "text/event-stream" -> parseSse(text)
                    else -> error("MCP 响应类型不支持")
                }
                val replies=envelopes.filter { it["id"] != null }
                require(envelopes.all { it["jsonrpc"] == JsonPrimitive("2.0") && !(it["id"] != null && it["method"] != null) }) { "服务器反向请求未授权" }
                val envelope=replies.single()
                require(envelope["id"] == JsonPrimitive(id) && envelope["error"] == null && envelope["result"] is JsonObject) { "MCP 返回身份或结果无效" }
                envelope.getValue("result").jsonObject
            }
        }
        guard();return value
    }
    companion object {
        private val client=OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).connectTimeout(10,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS)
            .callTimeout(45,TimeUnit.SECONDS).build()
        internal fun parseSse(text: String): List<JsonObject> {
            val result=mutableListOf<JsonObject>();val data=mutableListOf<String>()
            fun flush() { if(data.isNotEmpty()) { result+=Json.parseToJsonElement(data.joinToString("\n")).jsonObject;data.clear() } }
            text.replace("\r\n","\n").lineSequence().forEach { line ->
                if(line.isEmpty()) flush() else if(line.startsWith("data:")) data+=line.removePrefix("data:").removePrefix(" ")
                require(result.size <= 128)
            }
            flush();require(result.isNotEmpty());return result
        }
    }
}
