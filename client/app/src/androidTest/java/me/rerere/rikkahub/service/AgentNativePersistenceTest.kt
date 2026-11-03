package me.rerere.rikkahub.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.feature.agent.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Only synthetic metadata in an isolated noBackup fixture. No provider/network calls. */
@RunWith(AndroidJUnit4::class)
class AgentNativePersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun atomicFileReopenAndOsLeaseProtectUnknown() = runBlocking {
        val directory = File(context.noBackupFilesDir, "qa-agent-${UUID.randomUUID()}")
        val store = AgentTaskStore(AndroidAgentTaskPersistence(directory))
        val model = AgentModelLock("synthetic", "synthetic", "HIGH", "revision", "credential-v1:synthetic")
        store.accept(AgentTask("task-a", "learner-a", "chat-a", "input", model))
        store.accept(AgentTask("task-b", "learner-b", "chat-b", "input", model))
        acquireAgentExecution(directory).use {
            try { acquireAgentExecution(directory).close(); fail("Two executors acquired lease") } catch (_: java.nio.channels.OverlappingFileLockException) {}
            val permit = store.claim("task-a", "learner-a")
            store.beginCall(permit, "file-write", "input")
        }
        val reopened = AgentTaskStore(AndroidAgentTaskPersistence(directory))
        acquireAgentExecution(directory).use { reopened.recoverStoppedProcess() }
        assertEquals("task-a", reopened.snapshot().slot)
        assertEquals(AgentTaskState.UNCERTAIN_SIDE_EFFECT, reopened.get("task-a")!!.state)
        try { reopened.claim("task-b", "learner-b"); fail("Unknown slot released") } catch (_: IllegalStateException) {}
        // Keep the fixture as evidence, including its UNKNOWN record.
        println("qa_agent_fixture=" + directory.name)
    }

    @Test fun actualVaultSnapshotKeepsModelAndReasoningAfterSettingsChange() {
        val snapshots = AgentModelSnapshots(context)
        val modelA = Model(modelId = "synthetic-a")
        val modelB = Model(modelId = "synthetic-b")
        val provider = ProviderSetting.OpenAI(models = listOf(modelA, modelB), apiKey = "SYNTHETIC_AGENT_SECRET", baseUrl = "http://127.0.0.1/never-called")
        val assistant = Assistant(chatModelId = modelA.id, reasoningLevel = ReasoningLevel.HIGH)
        val initial = Settings(providers = listOf(provider), assistants = listOf(assistant), assistantId = assistant.id, chatModelId = modelA.id)
        val lock = snapshots.capture(initial, assistant.id)
        val changed = initial.copy(chatModelId = modelB.id, assistants = listOf(assistant.copy(chatModelId = modelB.id, reasoningLevel = ReasoningLevel.LOW)))
        val resolved = snapshots.resolve(lock, changed, initial.activeProfileId)
        assertEquals(modelA.id, resolved.getCurrentChatModel()!!.id)
        assertEquals(ReasoningLevel.HIGH, resolved.assistants.single().reasoningLevel)
        assertFalse(lock.toString().contains("SYNTHETIC_AGENT_SECRET"))
        assertFalse(resolved.networkSetting.enableAutoRetry)
        try { snapshots.resolve(lock, changed.copy(providers = listOf(provider.copy(enabled = false))), initial.activeProfileId); fail("Disabled provider allowed") } catch (_: IllegalStateException) {}
        try { snapshots.resolve(lock, changed.copy(providers = listOf(provider.copy(apiKey = ""))), initial.activeProfileId); fail("Cleared credential allowed") } catch (_: IllegalStateException) {}
        try { snapshots.resolve(lock, changed.copy(activeProfileId = "other"), initial.activeProfileId); fail("Wrong profile allowed") } catch (_: IllegalStateException) {}
    }

    @Test fun nativeConcurrentClaimsShareSingleSlot() = runBlocking {
        val directory = File(context.noBackupFilesDir, "qa-agent-${UUID.randomUUID()}")
        val model = AgentModelLock("synthetic", "synthetic", "HIGH", "revision", "credential-v1:synthetic")
        val stores = List(8) { AgentTaskStore(AndroidAgentTaskPersistence(directory)) }
        stores.forEachIndexed { i, store -> store.accept(AgentTask("task-$i", "profile-$i", "chat-$i", "input", model)) }
        val claims = stores.mapIndexed { i, store -> async(kotlinx.coroutines.Dispatchers.IO) { runCatching { store.claim("task-$i", "profile-$i") }.isSuccess } }.awaitAll()
        assertEquals(1, claims.count { it })
        assertEquals(1L, stores.first().snapshot().fence)
        println("qa_agent_fixture=" + directory.name)
    }

    @Test fun separateProcessRecovery() {
        val args = InstrumentationRegistry.getArguments()
        val fixtureId = args.getString("agentFixture") ?: return
        require(fixtureId.matches(Regex("[A-Za-z0-9-]{1,80}")))
        val directory = File(context.noBackupFilesDir, "qa-agent-process-$fixtureId")
        val store = AgentTaskStore(AndroidAgentTaskPersistence(directory))
        val phase = args.getString("agentPhase")
        if (phase == "seed") {
            assertTrue(store.snapshot().tasks.isEmpty())
            val model = AgentModelLock("synthetic", "synthetic", "HIGH", "revision", "credential-v1:synthetic")
            store.accept(AgentTask("process-task", "learner", "chat", "input", model))
            acquireAgentExecution(directory).use {
                val permit = store.claim("process-task", "learner")
                store.beginCall(permit, "unknown-write", "input")
                java.io.FileOutputStream(File(directory, "immutable-artifact.txt")).use {
                    it.write("SYNTHETIC_SINGLE_WRITE".toByteArray()); it.fd.sync()
                }
            }
        } else {
            assertEquals("verify", phase)
            acquireAgentExecution(directory).use { store.recoverStoppedProcess() }
            assertEquals("process-task", store.snapshot().slot)
            assertEquals(AgentTaskState.UNCERTAIN_SIDE_EFFECT, store.get("process-task")!!.state)
            assertEquals("IN_FLIGHT", store.get("process-task")!!.calls.single().state)
            assertEquals("SYNTHETIC_SINGLE_WRITE", File(directory, "immutable-artifact.txt").readText())
            assertEquals(1, directory.listFiles()!!.count { it.name.endsWith("artifact.txt") })
        }
        println("qa_agent_pid=" + android.os.Process.myPid())
    }
}
