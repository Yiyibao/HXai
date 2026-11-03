package me.rerere.rikkahub.feature.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private class MemoryPersistence(initial: AgentTaskSnapshot = AgentTaskSnapshot()) : AgentTaskPersistence {
    var state = initial
    @Synchronized override fun transaction(change: (AgentTaskSnapshot) -> AgentTaskSnapshot): AgentTaskSnapshot {
        val next = change(state)
        state = next
        return next
    }
}

class AgentTaskStoreTest {
    @Test fun `explicit edit of unexecuted queue preserves identity and new input`() {
        val store = AgentTaskStore(MemoryPersistence())
        val initial = task("edited")
        store.accept(initial)
        val edited = initial.copy(inputHash = "revised")
        val revised = store.reviseQueuedInput(initial, edited)
        assertEquals("revised", revised.inputHash)
        assertEquals(listOf(initial.inputHash), revised.inputHistory)
        assertEquals(revised, store.accept(edited))
        rejects { store.reviseQueuedInput(initial, initial.copy(inputHash = "stale")) }
        rejects { store.reviseQueuedInput(revised, edited.copy(profileId = "other")) }
        rejects { store.reviseQueuedInput(revised, edited.copy(model = model.copy(reasoning = "LOW"))) }
        val permit = store.claim("edited", initial.profileId)
        store.beginCall(permit, "provider:main", "request")
        store.recordReturn(permit, "provider:main", "request", "response")
        store.finish(permit, AgentTaskState.PAUSED)
        rejects { store.reviseQueuedInput(store.get("edited")!!, edited.copy(inputHash = "after-dispatch")) }
        assertEquals(revised.inputHash, store.get("edited")!!.inputHash)
    }
    private val model = AgentModelLock("provider-a", "model-a", "HIGH", "config-v1", "credential-v1:synthetic")
    private fun task(id: String, profile: String = "learner-a") = AgentTask(id, profile, "conversation-$id", agentHash(id), model)
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalStateException) {} catch (_: IllegalArgumentException) {} }

    @Test fun `two stores and twelve profiles share one atomic slot`() {
        val persistence = MemoryPersistence()
        val stores = List(12) { AgentTaskStore(persistence) }
        stores.forEachIndexed { i, store -> store.accept(task("$i", "profile-$i")) }
        val pool = Executors.newFixedThreadPool(12)
        try {
            val claimed = stores.mapIndexed { i, store -> pool.submit<Boolean> { runCatching { store.claim("$i", "profile-$i") }.isSuccess } }.count { it.get() }
            assertEquals(1, claimed)
            assertEquals(1L, persistence.state.fence)
        } finally { pool.shutdownNow() }
    }

    @Test fun `old fence and wrong owner cannot dispatch or publish`() {
        val store = AgentTaskStore(MemoryPersistence())
        store.accept(task("a")); store.accept(task("b"))
        rejects { store.claim("a", "other") }
        val a = store.claim("a", "learner-a")
        store.finish(a, AgentTaskState.RESPONSE_READY)
        val b = store.claim("b", "learner-a")
        assertTrue(b.fence > a.fence)
        rejects { store.beginCall(a, "late", "input") }
        rejects { store.finish(a, AgentTaskState.RESPONSE_READY) }
    }

    @Test fun `process restart after provider or tool dispatch retains unknown and slot`() {
        for (id in listOf("provider:main:req", "file-write")) {
            val persistence = MemoryPersistence()
            val store = AgentTaskStore(persistence)
            store.accept(task("a")); store.accept(task("b"))
            val permit = store.claim("a", "learner-a")
            store.beginCall(permit, id, "input")
            val reopened = AgentTaskStore(persistence)
            reopened.recoverStoppedProcess()
            assertEquals(AgentTaskState.UNCERTAIN_SIDE_EFFECT, reopened.get("a")!!.state)
            assertEquals("a", reopened.snapshot().slot)
            rejects { reopened.claim("b", "learner-a") }
            rejects { reopened.resumePaused("a", "learner-a") }
        }
    }

    @Test fun `failed request or stop cannot release unknown effects`() {
        val store = AgentTaskStore(MemoryPersistence())
        store.accept(task("a"))
        val permit = store.claim("a", "learner-a")
        store.beginCall(permit, "request", "input")
        store.finish(permit, AgentTaskState.PAUSED)
        assertEquals("a", store.snapshot().slot)
        assertTrue(store.get("a")!!.manualPaused)
        assertEquals(AgentTaskState.UNCERTAIN_SIDE_EFFECT, store.get("a")!!.state)
    }

    @Test fun `durable returned result is idempotent and cannot replay after lost ack`() {
        val store = AgentTaskStore(MemoryPersistence())
        store.accept(task("a"))
        val permit = store.claim("a", "learner-a")
        store.beginCall(permit, "call", "input")
        store.recordReturn(permit, "call", "input", "result")
        store.recordReturn(permit, "call", "input", "result")
        rejects { store.recordReturn(permit, "call", "input", "changed") }
        rejects { store.beginCall(permit, "call", "input") }
        store.finish(permit, AgentTaskState.NEEDS_VERIFICATION)
        assertNull(store.snapshot().slot)
        assertEquals(AgentTaskState.NEEDS_VERIFICATION, store.get("a")!!.state)
    }

    @Test fun `accepted model cannot be replaced even with identical request key`() {
        val store = AgentTaskStore(MemoryPersistence())
        val first = task("a")
        store.accept(first)
        assertEquals(first, store.accept(first))
        rejects { store.accept(first.copy(model = model.copy(reasoning = "LOW"))) }
        rejects { store.accept(first.copy(inputHash = "different")) }
        rejects { store.accept(first.copy(profileId = "other")) }
        assertEquals(model, store.get("a")!!.model)
    }

    @Test fun `usage is unknown before receipt and idempotent after reported receipt`() {
        val store = AgentTaskStore(MemoryPersistence())
        store.accept(task("a"))
        val permit = store.claim("a", "learner-a")
        store.beginCall(permit, "provider:main:req", "input")
        assertEquals("unknown", store.get("a")!!.calls.single().usage.provenance)
        assertNull(store.get("a")!!.calls.single().usage.input)
        val usage = AgentUsage("reported", 12, 4)
        store.recordReturn(permit, "provider:main:req", "input", "result")
        store.recordUsage(permit, "provider:main:req", usage)
        store.recordUsage(permit, "provider:main:req", usage)
        assertEquals(usage, store.get("a")!!.calls.single().usage)
        assertEquals(1, store.get("a")!!.calls.size)
        rejects { store.recordUsage(permit, "provider:main:req", usage.copy(output = 5)) }
    }

    @Test fun `future schema is rejected without overwriting records`() {
        val initial = AgentTaskSnapshot(schema = 2, tasks = listOf(task("a")))
        val persistence = MemoryPersistence(initial)
        rejects { AgentTaskStore(persistence).accept(task("b")) }
        assertEquals(initial, persistence.state)
    }

    @Test fun `pause is sticky and resume requires matching owner`() {
        val store = AgentTaskStore(MemoryPersistence())
        store.accept(task("a"))
        val permit = store.claim("a", "learner-a")
        store.finish(permit, AgentTaskState.PAUSED)
        rejects { store.claim("a", "learner-a") }
        rejects { store.resumePaused("a", "other") }
        store.resumePaused("a", "learner-a")
        assertTrue(store.claim("a", "learner-a").fence > permit.fence)
    }

    @Test fun `serialized old queue and locked queue both preserve content`() {
        val json = Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString<me.rerere.rikkahub.data.model.ConversationQueue>("""{"messages":[],"paused":true}""")
        assertTrue(old.paused)
        val input = me.rerere.rikkahub.data.model.PendingInput(kotlin.uuid.Uuid.random(), listOf(me.rerere.ai.ui.UIMessagePart.Text("valid input")), modelLock = model)
        val queue = me.rerere.rikkahub.data.model.ConversationQueue(listOf(input), true)
        assertEquals(queue, json.decodeFromString<me.rerere.rikkahub.data.model.ConversationQueue>(json.encodeToString(queue)))
        val runtime = me.rerere.rikkahub.service.MessageQueue()
        runtime.restore(queue)
        assertEquals(model, runtime.state.value.messages.single().modelLock)
        assertEquals(queue, runtime.persisted())
    }

    @Test fun `scheduler queues different conversations without parallel executor`() = runBlocking {
        val store = AgentTaskStore(MemoryPersistence())
        val scheduler = AgentScheduler(store)
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val first = async { scheduler.execute(task("a"), { AgentTaskState.RESPONSE_READY }) {
            maximum.updateAndGet { maxOf(it, active.incrementAndGet()) }
            firstEntered.complete(Unit); releaseFirst.await(); active.decrementAndGet()
        } }
        firstEntered.await()
        val second = async { scheduler.execute(task("b", "learner-b"), { AgentTaskState.RESPONSE_READY }) {
            maximum.updateAndGet { maxOf(it, active.incrementAndGet()) }
            secondEntered.complete(Unit); active.decrementAndGet()
        } }
        kotlinx.coroutines.yield()
        assertFalse(secondEntered.isCompleted)
        releaseFirst.complete(Unit)
        listOf(first, second).awaitAll()
        assertEquals(1, maximum.get())
        assertEquals(2L, store.snapshot().fence)
        assertNull(store.snapshot().slot)
    }
}
