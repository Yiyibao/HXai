package me.rerere.rikkahub.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.feature.agent.LocalModelServer
import me.rerere.rikkahub.feature.memory.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

/** Real GenerationLoop + accepted task/lease + localhost request capture. */
@RunWith(AndroidJUnit4::class)
class LegacySourceProviderBoundaryTest {
    @Test fun unmappedLegacySourceMakesZeroProviderRequests() = mainRequest(true)
    @Test fun healthyLegacyProfileStillMakesARealRequest() = mainRequest(false)

    private fun mainRequest(protected: Boolean): Unit = runBlocking {
        val k = GlobalContext.get()
        val settings = withTimeout(20_000) { k.get<SettingsStore>().settingsFlow.first { !it.init } }
        val owner = "qa-orphan-provider-${Uuid.random()}"
        val old = UIMessage.user("LEGACY_ORPHAN_PROVIDER_SENTINEL")
        val messages = listOf(old, UIMessage.assistant("OLD_ANSWER_SENTINEL"), UIMessage.user("new synthetic request"))
        val chat = Conversation(assistantId = settings.assistantId, profileId = owner, messageNodes = messages.map { it.toMessageNode() })
        val db = k.get<AppDatabase>()
        val store = k.get<LearningMemoryStore>()
        val fp = MemoryFingerprints(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext)
        val server = LocalModelServer { LocalModelServer.completion("safe synthetic response") }
        val model = Model(modelId = "qa-orphan-provider")
        val provider = ProviderSetting.OpenAI(baseUrl = server.baseUrl, apiKey = "synthetic", models = listOf(model))
        val assistant = Assistant(chatModelId = model.id, reasoningLevel = ReasoningLevel.OFF, streamOutput = false, localTools = emptyList())
        try {
            // Raw old-format JSON also lets this same boundary test run against the retained old APK.
            val payload = buildJsonObject {
                put("fingerprintVersion", 0)
                put("suppressedSources", JsonArray(if (protected)
                    listOf(JsonPrimitive(fp.legacyOf(owner, "message:${old.id}"))) else emptyList()))
            }.toString()
            db.learningMemoryDao().put(LearningMemoryStateEntity(owner, payload))
            val config = settings.copy(activeProfileId = owner, providers = listOf(provider),
                learnerProfiles = settings.learnerProfiles + me.rerere.rikkahub.feature.studychat.LearnerProfile(owner, "QA orphan", false),
                chatModelId = model.id, compressModelId = model.id,
                networkSetting = settings.networkSetting.copy(proxyUrl = "", enableAutoRetry = false))
            val result = runCatching { LeasedGenerationFixture(config, assistant, chat.id, owner).use { lease ->
                withTimeout(20_000) {lease.run { k.get<GenerationLoop>().generateText(settings = config, model = model, messages = messages,
                    assistant = assistant, profileId = owner, conversationId = chat.id, tools = emptyList()).collect() }}
            } }
            assertFalse("Direct collection must terminate without a response-reader ACK",
                result.exceptionOrNull() is kotlinx.coroutines.TimeoutCancellationException)
            assertEquals(messages, chat.currentMessages)
            if (protected) {
                assertEquals("No transport request may include an unknown deleted source", 0, server.bodies.size)
                assertTrue("Unknown legacy source must block before dispatch", result.isFailure)
                assertTrue(store.read(owner).recoveryBlocked)
            } else {
                assertTrue(result.exceptionOrNull()?.stackTraceToString(), result.isSuccess)
                assertEquals(1, server.bodies.size)
                assertTrue(server.bodies.single().contains(old.toText()))
                assertFalse(store.read(owner).recoveryBlocked)
            }
        } finally {
            server.close()
            db.openHelper.writableDatabase.execSQL("DELETE FROM learning_memory_state WHERE profile_id = ?", arrayOf(owner))
        }
    }
}
