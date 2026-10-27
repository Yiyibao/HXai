package me.rerere.rikkahub.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import me.rerere.ai.core.*
import me.rerere.ai.provider.*
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.feature.agent.LocalModelServer
import me.rerere.rikkahub.feature.memory.LearningMemoryStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** The parent hook mutates only at physical admission, after provider encoding. */
@RunWith(AndroidJUnit4::class)
class PhysicalHistoryBoundaryTest {
    @Test fun deletedSourceAtPhysicalMainAdmissionNeverLeavesDevice() = mainBoundary(true)
    @Test fun healthyMainAdmissionStillWorks() = mainBoundary(false)
    @Test fun deletedSourceAtPhysicalStreamAdmissionNeverLeavesDevice() = mainBoundary(true, stream = true)
    @Test fun healthyStreamingAdmissionStillWorks() = mainBoundary(false, stream = true)
    @Test fun sourceDeletedWhileMainResponseIsInFlightCannotPublish() = mainBoundary(false, late = true)

    private fun mainBoundary(delete: Boolean, stream: Boolean = false, late: Boolean = false): Unit = runBlocking {
        val k = GlobalContext.get()
        val settings = k.get<SettingsStore>()
        val original = withTimeout(20_000) { settings.settingsFlow.first { !it.init } }
        val memory = k.get<LearningMemoryStore>()
        val owner = original.activeProfileId
        val previous = memory.read(owner)
        val message = UIMessage.user("synthetic physical source sentinel")
        val server = LocalModelServer(responseContentType = if (stream) "text/event-stream" else "application/json") {
            if (late) runBlocking { memory.change(owner) { state -> state.copy(suppressedSources = setOf(memory.fingerprint(owner, "message:${message.id}"))) to Unit } }
            if (stream) "data: {\"id\":\"synthetic\",\"model\":\"qa-physical-history\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"synthetic answer\"},\"finish_reason\":null}]}\n\ndata: {\"id\":\"synthetic\",\"model\":\"qa-physical-history\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
            else LocalModelServer.completion("synthetic answer")
        }
        val model = Model(modelId = "qa-physical-history")
        val provider = ProviderSetting.OpenAI(baseUrl = server.baseUrl, apiKey = "synthetic", models = listOf(model))
        val assistant = Assistant(chatModelId = model.id, reasoningLevel = ReasoningLevel.OFF,
            streamOutput = stream, localTools = emptyList(), enableWebSearch = false)
        val config = original.copy(providers = listOf(provider), assistants = original.assistants + assistant,
            assistantId = assistant.id, chatModelId = model.id)
        var physical = 0
        try {
            settings.update(config); settings.settingsFlow.first { it.assistantId == assistant.id }
            memory.change(owner) { me.rerere.rikkahub.feature.memory.MemoryState(fingerprintVersion = 1) to Unit }
            LeasedGenerationFixture(config, assistant).use { fixture ->
                val result = runCatching { fixture.run {
                    withContext(ProviderRequestGuard {
                        if (currentCoroutineContext()[ProviderRequestAccounting] != null) {
                            physical++
                            if (delete) memory.change(owner) { it.copy(suppressedSources = setOf(
                                memory.fingerprint(owner, "message:${message.id}"))) to Unit }
                        }
                    }) {
                        k.get<GenerationLoop>().generateText(config, model, listOf(message), assistant = assistant).collect()
                    }
                } }
                assertEquals("The source guard must reach physical admission", 1, physical)
                assertEquals(!delete && !late, result.isSuccess)
                assertEquals(if (delete) 0 else 1, server.bodies.size)
            }
        } finally {
            memory.change(owner) { previous to Unit }
            settings.update(original); settings.settingsFlow.first { it.assistantId == original.assistantId }
            server.close()
        }
    }
}
