package me.rerere.rikkahub.feature.agent

import kotlinx.serialization.json.*
import me.rerere.ai.core.TokenUsage
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class TrustedCostTest {
    private val now=Instant.parse("2026-10-22T12:00:00Z").toEpochMilli()
    private fun basis(endpoint: String="https://api.openai.com/v1",model: String="gpt-4.1",extra: JsonObject=buildJsonObject {})=
        TrustedCost.basis(endpoint,model,extra,now)
    @Test fun cacheIsIncludedInInputAndDecimalIsExact() {
        val c=TrustedCost.calculate(basis(),TokenUsage(1000,100,200,1100))
        assertEquals("0.0025",c["amount"]?.jsonPrimitive?.content);assertEquals(JsonPrimitive("estimated"),c["status"])
    }
    @Test fun missingCacheCountAndUnknownOrInvalidUsageNeverBecomeZero() {
        for(u in listOf(null,TokenUsage(0,0),TokenUsage(1,2,3,3),TokenUsage(-1,2,0,1),TokenUsage(1,2,0,999)))
            assertEquals(JsonPrimitive("unknown"),TrustedCost.calculate(basis(),u)["status"])
        assertEquals(JsonPrimitive("0"),TrustedCost.calculate(basis(),TokenUsage(0,0,0,0))["amount"])
    }
    @Test fun endpointModelTierExpiryAndMultimodalAreNotGuessed() {
        for(p in listOf(basis("https://proxy.example/v1"),basis(model="unknown"),basis(extra=buildJsonObject { put("service_tier","priority") }),
            basis(extra=buildJsonObject { put("messages",buildJsonArray { add(buildJsonObject { put("content",buildJsonArray { add(buildJsonObject { put("type","image_url") }) }) }) }) }),
            TrustedCost.basis("https://api.openai.com/v1","gpt-4.1",buildJsonObject {},now+60L*86400000)))
            assertEquals(JsonPrimitive("unknown"),p["status"])
    }
    @Test fun supplierRequiresActualOfficialResponseOriginAndValidDecimal() {
        val p=basis("https://openrouter.ai/api/v1","any")
        val source="https://openrouter.ai/api/v1/chat/completions"
        assertEquals(JsonPrimitive("reported"),TrustedCost.calculate(p,TokenUsage(costUsd="0.00012345",costSource=source))["status"])
        for(u in listOf(TokenUsage(costUsd="1"),TokenUsage(costUsd="-1",costSource=source),TokenUsage(costUsd="NaN",costSource=source),TokenUsage(costUsd="1e999999",costSource=source)))
            assertEquals(JsonPrimitive("unknown"),TrustedCost.calculate(p,u)["status"])
    }
    @Test fun totalsSeparateProvenanceCurrencyAndUnknown() {
        val estimate=TrustedCost.calculate(basis(),TokenUsage(1000,100,200,1100))
        val report=TrustedCost.calculate(basis("https://openrouter.ai/api/v1"),TokenUsage(costUsd="0.1",costSource="https://openrouter.ai/api/v1/chat/completions"))
        val c=TrustedCost.aggregate(listOf(estimate,report,TrustedCost.unknown()))
        assertEquals(JsonPrimitive("1"),c["unknownRequests"]);assertEquals(JsonPrimitive("partial"),c["status"])
        assertEquals(2,c.getValue("totals").jsonArray.size)
    }
}
