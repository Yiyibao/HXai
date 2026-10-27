package me.rerere.ai.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.StreamChunk
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Admission and receipt for each physical inference request, including server-tool continuations. */
abstract class ProviderRequestAccounting : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ProviderRequestAccounting>

    abstract fun begin(
        provider: ProviderSetting,
        params: TextGenerationParams,
        body: JsonObject,
        encodedBody: String,
    ): ProviderRequestReceipt
}

abstract class ProviderRequestReceipt {
    abstract fun complete(usage: TokenUsage?)
    /** A known HTTP rejection acknowledges the request; it never proves the goal or zero usage. */
    open fun authorizationRejected(status: Int) { require(status==401 || status==403); complete(null) }
}

/** Optional local source check immediately before every physical request admission. */
class ProviderRequestGuard(val validate: suspend () -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ProviderRequestGuard>
}

suspend fun beginProviderRequest(
    provider: ProviderSetting,
    params: TextGenerationParams,
    body: JsonObject,
    encodedBody: String,
): ProviderRequestReceipt? {
    val context = currentCoroutineContext()
    context[ProviderRequestGuard]?.validate?.invoke()
    return context[ProviderRequestAccounting]?.begin(provider, params, body, encodedBody)
}


/** Image and Responses dialects report different counters; absent counters remain unknown. */
fun imageTokenUsage(body: JsonObject): TokenUsage? {
    val usage = body["usage"] as? JsonObject ?: return null
    fun counter(key: String) = (usage[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
    return TokenUsage(promptTokens=counter("input_tokens") ?: counter("prompt_tokens"),
        completionTokens=counter("output_tokens") ?: counter("completion_tokens"),
        totalTokens=counter("total_tokens"),
        cachedTokens=((usage["input_tokens_details"] ?: usage["prompt_tokens_details"]) as? JsonObject)
            ?.get("cached_tokens")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() })
}
/** Holds only counters, never request/response bodies. Committed before the terminal chunk is published. */
internal class StreamingRequestReceipt(private val receipt: ProviderRequestReceipt) {
    private var usage: TokenUsage? = null
    private var completed = false

    @Synchronized
    fun observe(chunk: StreamChunk) {
        if (completed) return
        if (chunk is StreamChunk.Usage) usage = usage.merge(chunk.usage)
        if (chunk is StreamChunk.Finish && chunk.finishReason != null) complete()
    }

    @Synchronized
    fun complete() {
        if (completed) return
        receipt.complete(usage)
        completed = true
    }

    @Synchronized
    fun authorizationRejected(status: Int) {
        if (completed) return
        receipt.authorizationRejected(status)
        completed = true
    }
}
