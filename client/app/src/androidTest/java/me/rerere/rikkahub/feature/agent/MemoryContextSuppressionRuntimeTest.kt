package me.rerere.rikkahub.feature.agent

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.feature.memory.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/** Reconstructs live records already revived by the old classification replay bug. */
@RunWith(AndroidJUnit4::class)
class MemoryContextSuppressionRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val json = Json { encodeDefaults = true }

    private suspend fun fixture(block: suspend (AppDatabase, LearningMemoryStore) -> Unit) {
        val name = "qa-memory-context-${Uuid.random()}.db"
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try { block(db, LearningMemoryStore(db, MemoryFingerprints(context))) }
        finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun legacyTombstonesExcludeAllRevivedClassificationsWithoutRewritingRecords(): Unit = runBlocking {
        fixture { db, store ->
            val owner = "owner-a"
            val id = store.addManual(owner, "deleted", MemoryType.PREFERENCE, "旧记录哨兵")
            val original = store.read(owner)
            val old = original.items.single()
            // Previous releases wrote only a per-type fact marker, then accepted a changed type.
            MemoryType.entries.forEach { deletedType -> MemoryType.entries.forEach { revivedType ->
                val revived = original.copy(items = listOf(old.copy(type = revivedType)),
                    suppressedFacts = setOf(store.fingerprint(owner, "$deletedType:手动添加:$id")))
                val payload = json.encodeToString(revived)
                db.learningMemoryDao().put(LearningMemoryStateEntity(owner, payload))
                assertTrue("$deletedType -> $revivedType", store.relevant(owner, "旧记录哨兵").isEmpty())
                assertEquals(payload, db.learningMemoryDao().get(owner)!!.payload)
                assertEquals("", store.context(owner, "chat", "旧记录哨兵", true))
                val after = store.read(owner)
                assertEquals(revived.copy(usedMemories = mapOf("chat" to emptyList())), after)
                assertEquals(listOf(old.copy(type = revivedType)), after.items)
            } }
        }
    }

    @Test fun identityMarkerCannotBeDisguisedByOriginTopicFactOrHumanEdit(): Unit = runBlocking {
        fixture { db, store ->
            val owner = "owner-a"
            val id = store.addManual(owner, "deleted", MemoryType.PREFERENCE, "旧记录哨兵")
            val original = store.read(owner)
            val source = MemorySource("source", "chat", "node", "message", "hash", 1)
            listOf("USER_MANUAL", "USER_EDITED", "USER_EXPLICIT", "AUTO_EXTRACTED").forEach { origin ->
                val state = original.copy(sources = listOf(source),
                    items = listOf(original.items.single().copy(origin = origin, topicKey = "其他分类", factKey = "其他键", sourceIds = listOf(source.id))),
                    suppressedFacts = setOf(store.fingerprint(owner, "manual-request:$id")))
                db.learningMemoryDao().put(LearningMemoryStateEntity(owner, json.encodeToString(state)))
                store.edit(owner, id, 1, "人工更正哨兵")
                val payload = db.learningMemoryDao().get(owner)!!.payload
                assertTrue("origin=$origin", store.relevant(owner, "人工更正哨兵").isEmpty())
                assertEquals(payload, db.learningMemoryDao().get(owner)!!.payload)
                assertEquals("人工更正哨兵", store.read(owner).items.single().content)
            }
        }
    }

    @Test fun sameContentWithFreshRequestAndDifferentOwnerRemainsUsable(): Unit = runBlocking {
        fixture { db, store ->
            val id = store.addManual("owner-a", "deleted", MemoryType.PREFERENCE, "同文偏好哨兵")
            val saved = store.read("owner-a")
            store.delete("owner-a", id, 1)
            val fresh = store.addManual("owner-a", "fresh", MemoryType.PREFERENCE, "同文偏好哨兵")
            val other = store.addManual("owner-b", "deleted", MemoryType.PREFERENCE, "同文偏好哨兵")
            val live = store.read("owner-a")
            val revived = saved.items.single().copy(type = MemoryType.BACKGROUND, content = "被删除哨兵")
            db.learningMemoryDao().put(LearningMemoryStateEntity("owner-a", json.encodeToString(live.copy(items = live.items + revived))))
            assertEquals(listOf(fresh), store.relevant("owner-a", "哨兵").map { it.id })
            assertEquals(listOf(other), store.relevant("owner-b", "哨兵").map { it.id })
            val data = store.context("owner-a", "chat", "哨兵", true)
            assertTrue(data.contains("同文偏好哨兵"))
            assertFalse(data.contains("被删除哨兵"))
            assertFalse(data.contains(id))
            assertEquals(listOf(fresh), store.read("owner-a").usedMemories["chat"])
            assertEquals(live.items + revived, store.read("owner-a").items)
            assertEquals(live.suppressedFacts, store.read("owner-a").suppressedFacts)
        }
    }

    @Test fun disabledContextDoesNotReadOrRewriteMalformedPayload(): Unit = runBlocking {
        fixture { db, store ->
            db.learningMemoryDao().put(LearningMemoryStateEntity("owner-a", "unreadable synthetic payload"))
            assertEquals("", store.context("owner-a", "chat", "query", false))
            assertEquals("unreadable synthetic payload", db.learningMemoryDao().get("owner-a")!!.payload)
        }
    }
}
