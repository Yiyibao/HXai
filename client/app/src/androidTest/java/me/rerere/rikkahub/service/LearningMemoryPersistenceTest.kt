package me.rerere.rikkahub.service

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.feature.memory.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class LearningMemoryPersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun database(name: String) = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
    private fun conversation(profile: String, text: String = "请记住我喜欢先看反例") = Conversation(id = Uuid.random(), assistantId = Uuid.random(), profileId = profile,
        messageNodes = listOf(MessageNode(messages = listOf(UIMessage.user(text))), MessageNode(messages = listOf(UIMessage.assistant("可以继续学习").copy(generationState = "COMPLETED")))))
    private fun candidate(id: String, content: String = "喜欢先看反例") = MemoryCandidate(MemoryType.PREFERENCE, "教学", "顺序", content, EvidenceType.SELF_REPORTED, listOf(id))

    @Test fun memorySurvivesProcessRestart() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val phase = arguments.getString("memoryPhase")
        org.junit.Assume.assumeTrue(phase in setOf("seed", "verify"))
        val fixture = arguments.getString("memoryFixture")!!
        require(Regex("[a-f0-9-]{36}").matches(fixture))
        val name = "qa-memory-process-$fixture.db"; val db = database(name)
        try {
            val store = LearningMemoryStore(db, MemoryFingerprints(context))
            if (phase == "seed") {
                val a = conversation("process-a"); store.reconcile(a); store.enqueue(a, true)
                val job = store.claim("process-a", true, System.currentTimeMillis())!!
                store.commit("process-a", job, listOf(candidate(job.sourceIds.single())), true)
                val b = conversation("process-b"); store.reconcile(b)
                store.saveExplicit("process-b", b.id.toString(), b.currentMessages.first().id.toString(), b.currentMessages.first().toText(), MemoryType.PREFERENCE, "教学", "顺序", "喜欢先看反例")
                store.delete("process-b", store.read("process-b").items.single().id, 1)
            } else {
                assertEquals("喜欢先看反例", store.relevant("process-a", "反例").single().content)
                assertTrue(store.read("process-b").items.isEmpty())
                assertEquals(1, store.hiddenMessageIds("process-b").size)
                assertTrue(store.read("process-a").suppressedSources.isEmpty())
            }
        } finally { db.close(); if (phase == "verify") context.deleteDatabase(name) }
    }

    @Test fun restartDurabilityAndTwoLearners() = runBlocking {
        val name = "qa-memory-${Uuid.random()}.db"
        var db = database(name)
        try {
            var store = LearningMemoryStore(db, MemoryFingerprints(context))
            val a = conversation("a"); val b = conversation("b", "我喜欢先看定义")
            store.reconcile(a); store.reconcile(b)
            assertEquals(1, store.enqueue(a, true)); assertEquals(0, store.enqueue(a, true))
            val job = store.claim("a", true, System.currentTimeMillis())!!
            assertEquals(1, store.commit("a", job, listOf(candidate(job.sourceIds.single())), true))
            assertTrue(store.read("b").items.isEmpty())
            assertNotEquals(MemoryValidation.hash(a.currentMessages.first().toText()), store.read("a").sources.single().hash)
            assertNotEquals(store.fingerprint("a", "same fact"), store.fingerprint("b", "same fact"))
            val id = store.read("a").items.single().id
            db.close(); db = database(name); store = LearningMemoryStore(db, MemoryFingerprints(context))
            assertEquals(id, store.relevant("a", "反例").single().id)
            assertTrue(store.relevant("b", "反例").isEmpty())
            store.delete("a", id, 1)
            db.close(); db = database(name); store = LearningMemoryStore(db, MemoryFingerprints(context))
            assertTrue(store.read("a").items.isEmpty())
            assertFalse(kotlinx.serialization.json.Json.encodeToString(store.read("a")).contains("喜欢先看反例"))
            val rewritten = a.currentMessages.first().copy(parts = listOf(me.rerere.ai.ui.UIMessagePart.Text("请记住我偏好先看反例")))
            val editedOldMessage = a.copy(messageNodes = listOf(a.messageNodes.first().copy(messages = listOf(rewritten)), a.messageNodes.last()))
            store.reconcile(editedOldMessage)
            assertEquals(0, store.enqueue(editedOldMessage, true))
            assertEquals(0, store.enqueue(a, true))
            assertTrue(store.hiddenMessageIds("a").contains(a.currentMessages.first().id.toString()))
            assertTrue(store.hiddenMessageIds("b").isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun sourceEditBranchInvalidationAndHumanCAS() = runBlocking {
        val name = "qa-memory-${Uuid.random()}.db"; val db = database(name)
        try {
            val store = LearningMemoryStore(db, MemoryFingerprints(context))
            val chat = conversation("a"); store.reconcile(chat); store.enqueue(chat, true)
            val job = store.claim("a", true, System.currentTimeMillis())!!
            store.commit("a", job, listOf(candidate(job.sourceIds.single())), true)
            val old = store.read("a").items.single()
            store.edit("a", old.id, old.revision, "喜欢先看定义")
            try { store.edit("a", old.id, old.revision, "旧编辑"); fail("CAS must reject stale edit") } catch (_: IllegalArgumentException) {}
            val edited = store.read("a").items.single(); assertEquals("USER_EDITED", edited.origin); assertEquals(1, edited.history.size)
            val source = chat.currentMessages.first()
            val changed = chat.copy(messageNodes = listOf(chat.messageNodes.first().copy(messages = listOf(source.copy(parts = listOf(me.rerere.ai.ui.UIMessagePart.Text("我喜欢独立推导")))))))
            store.reconcile(changed)
            assertEquals("SUPERSEDED", store.read("a").items.single().status)
            assertTrue(store.relevant("a", "定义").isEmpty())
            store.delete("a", edited.id, store.read("a").items.single().revision)
            assertTrue(store.read("a").items.isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun retriesDisabledLateResultsAndEmptySuccess() = runBlocking {
        val name = "qa-memory-${Uuid.random()}.db"; val db = database(name)
        try {
            val store = LearningMemoryStore(db, MemoryFingerprints(context)); val chat = conversation("a")
            store.reconcile(chat); store.enqueue(chat, true)
            val first = store.claim("a", true, System.currentTimeMillis())!!
            store.fail("a", first)
            assertEquals("RETRY_WAIT", store.read("a").jobs.single().state); assertTrue(store.read("a").items.isEmpty())
            val second = store.claim("a", true, Long.MAX_VALUE - 200_000)!!
            try { store.commit("a", first, listOf(candidate(first.sourceIds.single())), true); fail("Late result") } catch (_: IllegalArgumentException) {}
            store.commit("a", second, emptyList(), true)
            assertEquals("SUCCEEDED", store.read("a").jobs.single().state); assertEquals(0, store.read("a").jobs.single().savedCount)
            val another = conversation("a"); store.reconcile(another); store.enqueue(another, true)
            val running = store.claim("a", true, System.currentTimeMillis())!!
            store.claim("a", false, System.currentTimeMillis())
            try { store.commit("a", running, listOf(candidate(running.sourceIds.single())), true); fail("Closed memory cannot commit") } catch (_: IllegalArgumentException) {}
            assertTrue(store.read("a").items.isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun explicitMemoryDeletionNewRequestAndTopicSuppression() = runBlocking {
        val name = "qa-memory-${Uuid.random()}.db"; val db = database(name)
        try {
            val store = LearningMemoryStore(db, MemoryFingerprints(context)); val chat = conversation("a")
            store.reconcile(chat)
            val message = chat.currentMessages.first()
            val id = store.saveExplicit("a", chat.id.toString(), message.id.toString(), message.toText(), MemoryType.PREFERENCE, "教学", "顺序", "喜欢先看反例")
            assertEquals(id, store.saveExplicit("a", chat.id.toString(), message.id.toString(), message.toText(), MemoryType.PREFERENCE, "教学", "顺序", "喜欢先看反例"))
            store.delete("a", id, 1, true)
            try { store.saveExplicit("a", chat.id.toString(), message.id.toString(), message.toText(), MemoryType.PREFERENCE, "教学", "顺序", "喜欢先看反例"); fail("Old source cannot reinstate") } catch (_: IllegalArgumentException) {}
            val fresh = conversation("a", "重新记住我喜欢先看反例"); store.reconcile(fresh)
            store.saveExplicit("a", fresh.id.toString(), fresh.currentMessages.first().id.toString(), fresh.currentMessages.first().toText(), MemoryType.PREFERENCE, "教学", "顺序", "喜欢先看反例")
            assertEquals(1, store.relevant("a", "反例").size)
            assertTrue(store.isSuppressed("a", message.id.toString(), message.toText()))
            assertFalse(store.isSuppressed("a", fresh.currentMessages.first().id.toString(), fresh.currentMessages.first().toText()))
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
