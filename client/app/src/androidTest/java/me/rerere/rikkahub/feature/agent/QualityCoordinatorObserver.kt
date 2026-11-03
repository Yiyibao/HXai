package me.rerere.rikkahub.feature.agent

import android.content.Context
import kotlinx.serialization.json.*
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import okhttp3.OkHttpClient
import okio.Buffer
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** QA only: observe message text, never headers, reasoning blocks or another executor. */
internal class QualityCoordinatorObserver(manager: ProviderManager,context: Context,private val secret: String): AutoCloseable {
    private val manager=manager
    private val original=manager.getProvider("openai")
    private val folder=AtomicReference<File?>(null)
    private val sequence=AtomicInteger()
    init {
        check(context.packageName=="org.hxsi.agent.nativeqa" && original is OpenAIProvider)
        val field=OpenAIProvider::class.java.getDeclaredField("client").apply {isAccessible=true}
        val originalClient=field.get(original) as OkHttpClient
        val builder=originalClient.newBuilder()
        builder.interceptors().add(0,okhttp3.Interceptor {chain ->
            val target=folder.get();var phase: String?=null;var request: JsonObject?=null;var inputHash: String?=null
            if(target!=null)try {
                val body=chain.request().body
                if(body!=null && !body.isOneShot() && !body.isDuplex() && body.contentLength() in 1..2097152) {
                    val buffer=Buffer();body.writeTo(buffer);val text=buffer.readUtf8();val parsed=Json.parseToJsonElement(text).jsonObject
                    val instruction=(parsed["instructions"] as? JsonPrimitive)?.content.orEmpty()
                    phase=when {instruction.startsWith("HXAI_TASK_PLAN_V6")->"planning";instruction.startsWith("HXAI_FINAL_PLAN_V1")->"final_planning";instruction.startsWith("HXAI_GOAL_VERIFY_V6") || instruction.startsWith("HXAI_RESPONSE_DISPOSITION_V1")->"semantic_acceptance";else->null}
                    if(phase!=null) {request=parsed;inputHash=AgentTaskStore.hash(text)}
                }
            } catch(_: Exception) { /* Observation cannot alter the actual provider request. */ }
            val response=chain.proceed(chain.request())
            if(target!=null && phase!=null)try {
                val output=if(response.isSuccessful) {
                    val json=Json.parseToJsonElement(response.peekBody(262144).string()).jsonObject
                    val text=(json["output"] as? JsonArray).orEmpty().filter {it.jsonObject["type"]==JsonPrimitive("message")}
                        .flatMap {(it.jsonObject["content"] as? JsonArray).orEmpty()}
                        .filter {it.jsonObject["type"]==JsonPrimitive("output_text")}
                        .joinToString("\n") {it.jsonObject.getValue("text").jsonPrimitive.content}
                    text.replace(secret,"[REDACTED]").replace(Regex("sk-[A-Za-z0-9_-]{12,}"),"[REDACTED]").take(16384)
                } else ""
                val value=buildJsonObject {
                    put("scope","QA_PUBLIC_COORDINATOR_MESSAGE_TEXT_ONLY_NOT_AUTHENTICATION");put("phase",phase)
                    put("requestSha256",inputHash);put("httpStatus",response.code)
                    put("requestedModel",request?.get("model") ?: JsonNull);put("reasoning",request?.get("reasoning") ?: JsonNull)
                    put("store",request?.get("store") ?: JsonNull);put("responseText",output);put("responseTextSha256",AgentTaskStore.hash(output))
                    put("responseTextLimit",16384);put("responseTextMayBeTruncated",output.length==16384)
                    put("hiddenReasoningExported",false)
                }
                val path=File(target,"COORDINATOR_${sequence.incrementAndGet().toString().padStart(3,'0')}.json")
                path.outputStream().use {it.write(value.toString().toByteArray())}
            } catch(_: Exception) { /* Preserve original response/exception semantics, no retry. */ }
            response
        })
        manager.registerProvider("openai",OpenAIProvider(builder.build(),context))
    }
    fun observeCase(value: File?) {folder.set(value)}
    override fun close() {folder.set(null);manager.registerProvider("openai",original)}
}
