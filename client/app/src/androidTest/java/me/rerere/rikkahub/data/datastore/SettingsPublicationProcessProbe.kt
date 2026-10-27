package me.rerere.rikkahub.data.datastore

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.io.FileOutputStream
import kotlin.uuid.Uuid

/** Named prepare/verify methods only; the host observes and terminates the live QA process. */
@RunWith(AndroidJUnit4::class)
class SettingsPublicationProcessProbe {
    private fun ready(): File {
        val fixture = requireNotNull(InstrumentationRegistry.getArguments().getString("fixture"))
        java.util.UUID.fromString(fixture)
        return File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "settings-publication-$fixture.json")
    }

    @Test fun prepare(): Unit = runBlocking {
        val store = GlobalContext.get().get<SettingsStore>()
        val original = withTimeout(20_000) { store.settingsFlow.first { !it.init } }
        val model = Model(modelId = "qa-publication-restart")
        val assistant = Assistant(name = "Synthetic restart", chatModelId = model.id)
        store.update(original.copy(chatModelId = model.id, assistantId = assistant.id,
            assistants = original.assistants + assistant,
            providers = listOf(ProviderSetting.OpenAI(name = "Synthetic restart", models = listOf(model)))))
        val actual = store.settingsFlow.value
        assertTrue(actual.publicationRevision > 0)
        val record = buildJsonObject {
            put("revision", actual.publicationRevision); put("model", model.id.toString())
            put("assistant", assistant.id.toString()); put("owner", actual.activeProfileId)
        }.toString().toByteArray()
        FileOutputStream(ready()).use { it.write(record); it.fd.sync() }
        awaitCancellation()
    }

    @Test fun verify(): Unit = runBlocking {
        val expected = Json.parseToJsonElement(ready().readText()).jsonObject
        val store = GlobalContext.get().get<SettingsStore>()
        val current = withTimeout(20_000) { store.settingsFlow.first { !it.init } }
        assertEquals(expected.getValue("revision").jsonPrimitive.long, current.publicationRevision)
        assertEquals(expected.getValue("model").jsonPrimitive.content, current.chatModelId.toString())
        assertEquals(expected.getValue("assistant").jsonPrimitive.content, current.assistantId.toString())
        assertEquals(expected.getValue("owner").jsonPrimitive.content, current.activeProfileId)
        store.update { it.copy(launchCount = it.launchCount + 1) }
        assertTrue(store.settingsFlow.value.publicationRevision > current.publicationRevision)
        assertEquals(current.chatModelId, store.settingsFlowRaw.first().chatModelId)
    }
}
