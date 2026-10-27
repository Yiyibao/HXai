package me.rerere.rikkahub.data.datastore

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.feature.agent.AgentScheduler
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

/** Real DataStore/vault and hot settings publication; synthetic data, no model requests. */
@RunWith(AndroidJUnit4::class)
class SettingsPublicationRuntimeTest {
    @Test fun updateAcknowledgementWaitsForDurableCollectorPublication(): Unit = runBlocking {
        val store = GlobalContext.get().get<SettingsStore>()
        val original = withTimeout(20_000) { store.settingsFlow.first { !it.init } }
        val model = Model(modelId = "qa-publication-${Uuid.random()}")
        val provider = ProviderSetting.OpenAI(name = "Synthetic publication", models = listOf(model))
        val assistant = Assistant(name = "Synthetic publication", chatModelId = model.id)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        // AppScope publishes on Main. Hold it while the DataStore writer commits on IO.
        Handler(Looper.getMainLooper()).post {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val pending = async(Dispatchers.Default) {
            store.update(original.copy(providers = listOf(provider), assistants = original.assistants + assistant,
                assistantId = assistant.id, chatModelId = model.id))
        }
        try {
            withTimeout(5_000) { store.settingsFlowRaw.first { it.chatModelId == model.id } }
            assertNull("update acknowledged before durable collector could publish; optimistic state is unsafe",
                withTimeoutOrNull(250) { pending.await(); true })
            assertEquals(original.chatModelId, store.settingsFlow.value.chatModelId)
            release.countDown()
            withTimeout(5_000) { pending.await() }
            assertEquals(model.id, store.settingsFlow.value.chatModelId)
            // The normalization performed by the durable reader is visible on acknowledgement.
            assertTrue(store.settingsFlow.value.providers.size > 1)
            delay(100)
            assertEquals(model.id, store.settingsFlow.value.chatModelId)
            val current = store.settingsFlow.value
            val scheduler = AgentScheduler.shared(GlobalContext.get().get())
            val task = scheduler.accept(Conversation(assistantId = assistant.id, profileId = current.activeProfileId, messageNodes = emptyList()),
                Uuid.random().toString(), "Synthetic publication admission", current)
            assertEquals(model.id.toString(), task.modelId)
            scheduler.command(task.owner, task.id, Uuid.random().toString(), task.revision, task.fence, "cancel", "{}")
        } finally {
            release.countDown()
            withTimeout(5_000) { pending.await() }
            store.update(original)
        }
    }

    @Test fun concurrentFunctionalUpdatesRetainEveryCommittedIncrement(): Unit = runBlocking {
        val store = GlobalContext.get().get<SettingsStore>()
        val original = withTimeout(20_000) { store.settingsFlow.first { !it.init } }
        try {
            coroutineScope {
                val start = CompletableDeferred<Unit>()
                val writers = List(12) { async(Dispatchers.Default) {
                    start.await()
                    store.update { it.copy(launchCount = it.launchCount + 1) }
                } }
                start.complete(Unit)
                withTimeout(20_000) { writers.awaitAll() }
            }
            assertEquals(original.launchCount + 12, store.settingsFlow.value.launchCount)
            assertEquals(original.launchCount + 12, store.settingsFlowRaw.first().launchCount)
        } finally { store.update(original) }
    }

    @Test fun noSelectedModelStillRejectsAdmissionAndDoesNotSubstitute(): Unit = runBlocking {
        val store = GlobalContext.get().get<SettingsStore>()
        val original = withTimeout(20_000) { store.settingsFlow.first { !it.init } }
        val missing = Uuid.random()
        val assistant = Assistant(name = "Synthetic missing model", chatModelId = missing)
        try {
            store.update(original.copy(assistantId = assistant.id, assistants = original.assistants + assistant,
                chatModelId = missing))
            val actual = store.settingsFlow.value
            val error = runCatching {
                AgentScheduler.shared(GlobalContext.get().get()).accept(
                    Conversation(assistantId = assistant.id, profileId = actual.activeProfileId, messageNodes = emptyList()),
                    Uuid.random().toString(), "Must reject missing model", actual)
            }.exceptionOrNull()
            assertNotNull(error)
            assertEquals("No chat model selected", error?.message)
        } finally { store.update(original) }
    }

    @Test fun equalValueCommitIsPublishedAndMetadataStaysOutOfSerializedSettings(): Unit = runBlocking {
        val store = GlobalContext.get().get<SettingsStore>()
        val original = withTimeout(20_000) { store.settingsFlow.first { !it.init } }
        store.update(original)
        val current = store.settingsFlow.value
        assertTrue(current.publicationRevision > original.publicationRevision)
        val encoded = kotlinx.serialization.json.Json.encodeToString(Settings.serializer(), current)
        assertFalse(encoded.contains("publicationRevision"))
        assertFalse(encoded.contains("settings_publication_revision"))
        val legacy = kotlinx.serialization.json.Json.decodeFromString(Settings.serializer(), encoded)
        assertEquals(0L, legacy.publicationRevision)
        assertEquals(current.chatModelId, legacy.chatModelId)
        assertEquals(current.activeProfileId, legacy.activeProfileId)
    }

    @Test fun staleFullSettingsCannotUndoDurableMemorySelection(): Unit = runBlocking {
        val store = GlobalContext.get().get<SettingsStore>()
        val original = withTimeout(20_000) { store.settingsFlow.first { !it.init } }
        val owner = original.activeProfileId
        val enabled = original.learnerProfiles.single { it.id == owner }.memoryEnabled
        try {
            store.setLearnerMemoryEnabled(owner, !enabled)
            store.update(original)
            assertEquals(owner, store.settingsFlow.value.activeProfileId)
            assertEquals(!enabled, store.settingsFlow.value.learnerProfiles.single { it.id == owner }.memoryEnabled)
            assertEquals(!enabled, store.settingsFlowRaw.first().learnerProfiles.single { it.id == owner }.memoryEnabled)
        } finally {
            store.setLearnerMemoryEnabled(owner, enabled)
            store.update(original)
        }
    }
}
