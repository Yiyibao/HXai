package me.rerere.rikkahub.service

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.feature.material.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File

class P0EnvironmentTest {
    @Test fun recoverInterruptedSyntheticSetupOnlyWhenOriginalAssistantIsUnambiguous() = runBlocking {
        val store = GlobalContext.get().get<SettingsStore>(); val current = store.settingsFlow.first { !it.init }
        val selected = current.getCurrentAssistant()
        if (!selected.name.startsWith("P0-live-") && selected.name != "P0 routing") return@runBlocking
        val candidates = current.assistants.filter { candidate -> candidate.id != selected.id && !candidate.name.startsWith("P0-live-") && candidate.name != "P0 routing" && selected.copy(id = candidate.id, name = candidate.name, systemPrompt = candidate.systemPrompt, localTools = candidate.localTools, enabledSkills = candidate.enabledSkills, enableMemory = candidate.enableMemory) == candidate }
        org.junit.Assert.assertEquals("Interrupted synthetic assistant must have exactly one source", 1, candidates.size)
        store.update(current.copy(assistantId = candidates.single().id, assistants = current.assistants.filter { it.id != selected.id }))
    }
    @Test fun inspectCurrentExternalPrerequisitesWithoutSecrets() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = GlobalContext.get().get<SettingsStore>().settingsFlow.first { !it.init }
        val docs = DocumentServiceRepository(context, GlobalContext.get().get<MaterialFiles>())
        val config = docs.config(settings.activeProfileId)
        var ready = false; var failure = ""
        if (config.enabled && config.baseUrl == "https://hxnf.top/documents" && config.credentialReference.isNotBlank()) {
            try { ready = docs.capabilities(settings.activeProfileId)["ready"]?.jsonPrimitive?.boolean == true }
            catch (e: Exception) { failure = e.message?.take(160).orEmpty() }
        }
        File(context.cacheDir, "p0-environment.json").writeText(buildJsonObject {
            put("model_configured", settings.getCurrentChatModel() != null)
            put("document_service_enabled", config.enabled); put("document_credential_configured", config.credentialReference.isNotBlank())
            put("document_service_ready_verified", ready); put("document_probe_failure", failure)
            put("synthetic_test_credential_file_present", File(context.cacheDir, "document-service-test-credentials.json").exists())
            put("device", android.os.Build.MODEL)
        }.toString())
    }
}
