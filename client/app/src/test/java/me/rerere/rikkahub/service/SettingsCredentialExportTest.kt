package me.rerere.rikkahub.service

import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.datastore.NetworkSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.withoutCredentials
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class SettingsCredentialExportTest {
    @Test fun sharedProviderTemplateExcludesNestedConnectionSecrets() {
        val provider = ProviderSetting.OpenAI(apiKey = "SHARE_KEY_CANARY", baseUrl = "https://SHARE_URL_CANARY.invalid",
            customHeaders = listOf(CustomHeader("Authorization", "SHARE_HEADER_CANARY")))
        val exported = provider.withoutCredentials()
        val raw = JsonInstant.encodeToString(exported)
        assertFalse(raw.contains("CANARY"))
        assertEquals(provider.id, exported.id)
        assertEquals("SHARE_KEY_CANARY", provider.apiKey)
    }
    @Test fun portableSettingsExcludeSecretsWithoutMutatingRuntime() {
        val provider = ProviderSetting.Google(apiKey = "TEST_KEY_CANARY", privateKey = "TEST_PRIVATE_CANARY",
            baseUrl = "https://example.invalid/TEST_URL_CANARY",
            customHeaders = listOf(CustomHeader("X-Custom", "TEST_HEADER_CANARY")),
            models = listOf(Model(customHeaders = listOf(CustomHeader("Authorization", "TEST_MODEL_CANARY")))))
        val runtime = Settings(providers = listOf(provider),
            assistants = listOf(Assistant(customHeaders = listOf(CustomHeader("X-Key", "TEST_ASSISTANT_CANARY")))),
            networkSetting = NetworkSetting(proxyPassword = "TEST_PROXY_CANARY"),
            webServerAccessPassword = "TEST_WEB_CANARY", webServerEnabled = true)
        val portable = runtime.withoutCredentials()
        val serialized = JsonInstant.encodeToString(portable)
        listOf("TEST_KEY_CANARY", "TEST_PRIVATE_CANARY", "TEST_URL_CANARY", "TEST_PROXY_CANARY", "TEST_WEB_CANARY",
            "TEST_HEADER_CANARY", "TEST_MODEL_CANARY", "TEST_ASSISTANT_CANARY")
            .forEach { assertFalse(serialized.contains(it)) }
        assertEquals(provider.id, portable.providers.single().id)
        assertEquals("TEST_KEY_CANARY", provider.apiKey)
        assertEquals("TEST_PROXY_CANARY", runtime.networkSetting.proxyPassword)
        assertFalse(portable.webServerEnabled)
        assertEquals(serialized, JsonInstant.encodeToString(portable.withoutCredentials()))
    }
}
