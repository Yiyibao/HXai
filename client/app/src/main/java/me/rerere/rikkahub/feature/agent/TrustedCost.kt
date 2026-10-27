package me.rerere.rikkahub.feature.agent

import kotlinx.serialization.json.*
import me.rerere.ai.core.TokenUsage
import java.math.BigDecimal
import java.time.Instant

/** Money is decimal text, never floating point or a missing counter coerced to zero. */
internal object TrustedCost {
    private const val SOURCE="https://developers.openai.com/api/docs/models/gpt-4.1"
    private const val VERSION="openai-gpt41-text-standard-20261022"
    private val since=Instant.parse("2026-10-22T00:00:00Z").toEpochMilli()
    private val until=Instant.parse("2026-11-21T00:00:00Z").toEpochMilli()
    fun unknown(reason: String="not_reported"): JsonObject = buildJsonObject {
        put("version",1);put("status","unknown");put("amount",JsonNull);put("currency",JsonNull);put("reason",reason)
    }
    fun basis(endpoint: String?, model: String?, body: JsonObject, now: Long=System.currentTimeMillis()): JsonObject {
        if(endpoint?.trimEnd('/')=="https://openrouter.ai/api/v1") return buildJsonObject {
            put("version",1);put("status","supplier");put("currency","USD");put("priceVersion","openrouter-usage-cost-v1")
            put("source","https://openrouter.ai/docs/cookbook/administration/usage-accounting")
            put("endpoint","https://openrouter.ai/api/v1/chat/completions")
        }
        // Exact official origin and path, explicit model ids, default text pricing only.
        if(endpoint?.trimEnd('/')!="https://api.openai.com/v1" || model !in setOf("gpt-4.1","gpt-4.1-2025-04-14") ||
            now !in since until until || body["service_tier"]?.jsonPrimitive?.content.let { it!=null && it!="default" } ||
            body["tools"]?.jsonArray.orEmpty().any { it.jsonObject["type"]?.jsonPrimitive?.content!="function" } ||
            body.keys.any { it in setOf("audio","modalities","prediction","batch","web_search_options") } ||
            body["n"]?.jsonPrimitive?.intOrNull.let { it!=null && it!=1 }) return unknown("unsupported_price_basis")
        // Image/audio/files and opaque remote input have distinct billing rules.
        fun textOnly(v: JsonElement): Boolean = when(v) {
            is JsonObject -> v.keys.none { it in setOf("image_url","file_id","file_data","input_audio","input_image","input_file") } &&
                v["type"]?.jsonPrimitive?.content.let { it==null || it in setOf("text","input_text","output_text","function","function_call","function_call_output","message") } && v.values.all(::textOnly)
            is JsonArray -> v.all(::textOnly)
            else -> true
        }
        if(!textOnly(body["messages"] ?: body["input"] ?: JsonNull)) return unknown("non_text_or_opaque_input")
        return buildJsonObject {
            put("version",1);put("status","tariff");put("currency","USD");put("source",SOURCE);put("priceVersion",VERSION)
            put("model",model);put("endpoint","https://api.openai.com/v1");put("verifiedAtMs",since.toString());put("expiresAtMs",until.toString())
            put("inputPerMillion","2");put("cachedPerMillion","0.5");put("outputPerMillion","8")
        }
    }
    fun calculate(basis: JsonObject?, usage: TokenUsage?): JsonObject {
        if(basis?.get("status")==JsonPrimitive("supplier")) {
            if(usage?.costSource!=basis["endpoint"]?.jsonPrimitive?.content) return unknown("untrusted_or_missing_supplier_cost")
            val amount=usage?.costUsd?.takeIf { it.length<=64 }?.toBigDecimalOrNull()
            if(amount==null || amount<BigDecimal.ZERO || amount.scale() !in -18..18 || amount.precision()>36 || amount.precision()-amount.scale()>18) return unknown("invalid_supplier_cost")
            return buildJsonObject {
                put("version",1);put("status","reported");put("amount",amount.stripTrailingZeros().toPlainString());put("currency","USD")
                put("basis",basis);put("basisSha256",AgentTaskStore.hash(basis.toString()))
            }
        }
        if(basis?.get("status")!=JsonPrimitive("tariff") || usage==null) return unknown()
        val input=usage.promptTokens;val output=usage.completionTokens;val cached=usage.cachedTokens
        if(input==null || output==null || cached==null || input<0 || output<0 || cached !in 0..input ||
            usage.totalTokens?.let { it.toLong()!=input.toLong()+output } == true) return unknown("incomplete_or_inconsistent_usage")
        val amount=(BigDecimal(input-cached)*basis.getValue("inputPerMillion").jsonPrimitive.content.toBigDecimal()+
            BigDecimal(cached)*basis.getValue("cachedPerMillion").jsonPrimitive.content.toBigDecimal()+
            BigDecimal(output)*basis.getValue("outputPerMillion").jsonPrimitive.content.toBigDecimal()).movePointLeft(6)
        return buildJsonObject {
            put("version",1);put("status","estimated");put("amount",amount.stripTrailingZeros().toPlainString());put("currency","USD")
            put("basis",basis);put("basisSha256",AgentTaskStore.hash(basis.toString()))
        }
    }
    fun aggregate(receipts: Iterable<JsonObject>): JsonObject {
        var count=0L
        var unknown=0L
        val amounts=sortedMapOf<String,BigDecimal>()
        receipts.forEach { row ->
            count++
            val status=row["status"]?.jsonPrimitive?.content
            if(status !in setOf("estimated","reported")) unknown++ else {
                val currency=row.getValue("currency").jsonPrimitive.content
                require(currency.matches(Regex("[A-Z]{3}")))
                val amount=row.getValue("amount").jsonPrimitive.content.toBigDecimal()
                require(amount>=BigDecimal.ZERO && amount.scale()<=18 && amount.precision()<=36)
                val key="$currency:$status";amounts[key]=(amounts[key]?:BigDecimal.ZERO)+amount
            }
        }
        return buildJsonObject {
            put("version",1);put("status",if(unknown==count) "unknown" else if(unknown>0) "partial" else "complete")
            put("unknownRequests",unknown.toString());put("requestCount",count.toString())
            put("amount",JsonNull);put("currency",JsonNull) // Totals are separated by currency and provenance below.
            put("totals",JsonArray(amounts.map { (key,amount) -> buildJsonObject {
                put("currency",key.substringBefore(':'));put("provenance",key.substringAfter(':'));put("amount",amount.stripTrailingZeros().toPlainString())
            } }))
        }
    }
}
