package me.rerere.rikkahub.feature.agent

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.sync.MemoryRestore
import me.rerere.rikkahub.feature.memory.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ManualMemoryExplicitDeletionRuntimeTest {
    @Test fun newExplicitFactCannotReenableDeletedManualRequestIncludingLegacyTombstone(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "qa-manual-explicit-${Uuid.random()}.db"
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val store = LearningMemoryStore(db, MemoryFingerprints(context))
            listOf(false, true).forEach { legacy ->
                val owner = "owner-$legacy"
                val id = store.addManual(owner, "original-request", MemoryType.PREFERENCE, "先解释定义")
                store.delete(owner, id, 1)
                if (legacy) store.change(owner) { state -> state.copy(suppressedFacts = setOf(
                    store.fingerprint(owner, "PREFERENCE:手动添加:$id"))) to Unit }
                val user = UIMessage.user("请重新记住先解释定义")
                val chat = Conversation(id = Uuid.random(), assistantId = Uuid.random(), profileId = owner,
                    messageNodes = listOf(MessageNode(messages = listOf(user))))
                store.reconcile(chat)
                val fresh = store.saveExplicit(owner, chat.id.toString(), user.id.toString(), user.toText(),
                    MemoryType.PREFERENCE, "手动添加", id, "先解释定义")
                assertNotEquals(id, fresh)
                val before = store.read(owner)
                assertEquals(fresh, store.relevant(owner, "定义").single().id)
                MemoryType.entries.forEach { type ->
                    try { store.addManual(owner, "original-request", type, "先解释定义"); fail("New explicit fact reenabled deleted manual request: legacy=$legacy type=$type") }
                    catch (_: IllegalArgumentException) { }
                }
                assertEquals(before, store.read(owner))
                val restored = MemoryRestore.merge(Json.decodeFromString(Json.encodeToString(before)),
                    MemoryState(fingerprintVersion = 1)) { store.fingerprint(owner, it) }
                assertEquals(fresh, restored.items.single().id)
            }
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
