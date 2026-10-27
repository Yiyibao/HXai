package me.rerere.ai.core

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import org.junit.Assert.*
import org.junit.Test

class ProviderRequestGuardTest {
    private val provider = ProviderSetting.OpenAI(apiKey = "synthetic")
    private val params = TextGenerationParams(model = Model(modelId = "qa"))

    @Test fun rejectedSourceCannotCreatePhysicalAccountingReceipt() = runBlocking {
        var admitted = 0
        val accounting = object : ProviderRequestAccounting() {
            override fun begin(provider: ProviderSetting, params: TextGenerationParams,
                               body: kotlinx.serialization.json.JsonObject, encodedBody: String): ProviderRequestReceipt {
                admitted++
                error("A suppressed source must never reach physical admission")
            }
        }
        val failure = runCatching {
            withContext(accounting + ProviderRequestGuard { error("synthetic source revoked") }) {
                beginProviderRequest(provider, params, buildJsonObject {}, "{}")
            }
        }
        assertEquals("synthetic source revoked", failure.exceptionOrNull()?.message)
        assertEquals(0, admitted)
    }

    @Test fun sourceRecheckedForEveryPhysicalAdmissionAndUnguardedRequestsRemainCompatible() = runBlocking {
        var checked = 0
        var admitted = 0
        val receipt = object : ProviderRequestReceipt() { override fun complete(usage: TokenUsage?) {} }
        val accounting = object : ProviderRequestAccounting() {
            override fun begin(provider: ProviderSetting, params: TextGenerationParams,
                               body: kotlinx.serialization.json.JsonObject, encodedBody: String): ProviderRequestReceipt {
                admitted++
                return receipt
            }
        }
        withContext(accounting + ProviderRequestGuard { checked++; check(checked == 1) { "source deleted" } }) {
            assertSame(receipt, beginProviderRequest(provider, params, buildJsonObject {}, "{}"))
            assertTrue(runCatching { beginProviderRequest(provider, params, buildJsonObject {}, "{}") }.isFailure)
        }
        withContext(accounting) { assertSame(receipt, beginProviderRequest(provider, params, buildJsonObject {}, "{}")) }
        assertEquals(2, checked)
        assertEquals(2, admitted)
    }
}
