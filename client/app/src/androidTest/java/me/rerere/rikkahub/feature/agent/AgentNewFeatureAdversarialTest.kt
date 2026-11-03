package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import me.rerere.ai.core.TokenUsage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Pro v1.1 false-success and valid-input/data-preservation counterexamples. */
@RunWith(AndroidJUnit4::class)
class AgentNewFeatureAdversarialTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(block: (AgentTaskStore, String) -> Unit) {
        val name = "qa-review-${UUID.randomUUID()}.db"
        val store = AgentTaskStore(context, name)
        try { block(store, name) }
        finally {
            store.close()
            listOf("", "-journal", "-wal", "-shm").forEach {
                context.noBackupFilesDir.resolve(name + it).delete()
            }
        }
    }
    private fun accepted(store: AgentTaskStore, owner: String = "synthetic-owner") =
        store.accept(owner, "conversation", UUID.randomUUID().toString(),
            "Create a Word document with three verified chapters", "synthetic-reference",
            "synthetic-provider", "synthetic-model", "HIGH", "synthetic-revision")

    @Test fun successfulRequestAndLocalToolReceiptDoNotProveTheUsersGoal() = fixture { store, _ ->
        val task = accepted(store)
        val lease = store.claim(task.owner, task.id)
        val request = store.beginRequest(lease, "main", "synthetic prompt",
            AgentRequestSelection(task.providerId, task.modelId, task.reasoning, task.configurationReference))
        store.finishRequest(lease, request, TokenUsage(17, 8, null, 25))
        // A time query is genuinely successful; the requested document does not exist.
        store.beginCall(lease, "time", "get_time_info", "{}")
        store.finishCall(lease, "time", AgentTaskStore.hash("synthetic-time"), true)
        store.finish(lease, "SUCCEEDED")
        assertEquals("No persisted goal acceptance has been evaluated",
            "COMPLETED_UNVERIFIED", store.get(task.owner, task.id).state)
    }

    @Test fun validZeroToolChatCanFinishAndReleaseTheSlotWithoutInventingUsage() = fixture { store, _ ->
        val task = accepted(store)
        val next = accepted(store, "synthetic-other-owner")
        val lease = store.claim(task.owner, task.id)
        val request = store.beginRequest(lease, "main", "hello",
            AgentRequestSelection(task.providerId, task.modelId, task.reasoning, task.configurationReference))
        store.finishRequest(lease, request, null)
        store.finish(lease, "COMPLETED_UNVERIFIED")
        assertEquals("COMPLETED_UNVERIFIED", store.get(task.owner, task.id).state)
        store.readableDatabase.rawQuery("SELECT provenance,total_tokens FROM usage WHERE request_id=?", arrayOf(request)).use {
            assertTrue(it.moveToFirst()); assertEquals("unknown", it.getString(0)); assertTrue(it.isNull(1))
        }
        val nextLease = store.claim(next.owner, next.id)
        assertTrue(nextLease.fence > lease.fence)
        store.finish(nextLease, "WAITING_INPUT")
    }

    @Test fun futureSchemaIsRefusedWithoutRebuildingOrChangingExistingBytes() = fixture { store, name ->
        val task = accepted(store)
        val lease = store.claim(task.owner, task.id)
        store.beginCall(lease, "unknown", "create_material_file", "synthetic-input")
        store.writableDatabase.version = 12
        store.close()
        val file = context.noBackupFilesDir.resolve(name)
        val before = file.readBytes()
        AgentTaskStore(context, name).use { future ->
            assertTrue(runCatching { future.writableDatabase }.isFailure)
        }
        assertArrayEquals("Future task data must not be cleared or downgraded", before, file.readBytes())
    }
}
