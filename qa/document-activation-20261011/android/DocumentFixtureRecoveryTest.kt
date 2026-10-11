package me.rerere.rikkahub.service

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.junit.Test
import org.koin.core.context.GlobalContext

/** Remove only synthetic fixtures left by the explicitly interrupted regression. */
class DocumentFixtureRecoveryTest {
    @Test fun removeInterruptedQaBridgeConfiguration() = runBlocking {
        val settings = GlobalContext.get().get<SettingsStore>()
        val before = settings.settingsFlow.first { !it.init }
        val stale = before.providers.filterIsInstance<ProviderSetting.OpenAI>().filter { it.name == "QA bridge" && it.apiKey == "synthetic" && it.baseUrl.startsWith("http://127.0.0.1:") }
        val modelIds = stale.flatMap { it.models }.map { it.id }.toSet()
        val assistants = before.assistants.filterNot { it.name == "QA bridge" && it.chatModelId in modelIds }
        check(assistants.isNotEmpty())
        settings.update(before.copy(providers = before.providers.filterNot { it.id in stale.map { p -> p.id } }, assistants = assistants,
            assistantId = assistants.firstOrNull { it.id == before.assistantId }?.id ?: assistants.first().id,
            chatModelId = if (before.chatModelId in modelIds) me.rerere.rikkahub.data.datastore.Settings().chatModelId else before.chatModelId,
            fastModelId = if (before.fastModelId in modelIds) me.rerere.rikkahub.data.datastore.Settings().fastModelId else before.fastModelId))
    }
}
