package me.rerere.rikkahub.feature.agent

import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.feature.memory.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Only synthetic QA data; v0 constraints deliberately have no recoverable preimages. */
@RunWith(AndroidJUnit4::class)
class LegacyMemoryContextRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val json = Json { encodeDefaults = true }

    private suspend fun fixture(block: suspend (AppDatabase, LearningMemoryStore, MemoryFingerprints, File) -> Unit) {
        val name = "qa-legacy-context-${UUID.randomUUID()}.db"
        val files = File(context.cacheDir, "qa-legacy-keys-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = File(files, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(files, "no-backup").apply { mkdirs() }
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val fingerprints = MemoryFingerprints(isolated)
            block(db, LearningMemoryStore(db, fingerprints), fingerprints, File(isolated.filesDir, PortableMemoryKey.FILE_NAME))
        } finally {
            db.close(); context.deleteDatabase(name)
            check(files.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            files.deleteRecursively()
        }
    }

    @Test fun unmappedLegacyFactsAndTopicsPauseContextAndKeepManagementRecords(): Unit = runBlocking {
        fixture { db, store, fingerprints, _ ->
            store.addManual("legacy", "original", MemoryType.PREFERENCE, "旧约束上下文哨兵")
            val original = store.read("legacy")
            val marker = fingerprints.legacyOf("legacy", "deleted-without-preimage")
            listOf(original.copy(fingerprintVersion = 0, suppressedFacts = setOf(marker)),
                original.copy(fingerprintVersion = 0, suppressedTopics = setOf(marker))).forEach { legacy ->
                val state = legacy.copy(usedMemories = mapOf("earlier-chat" to listOf(original.items.single().id)),
                    jobs = listOf(ExtractionJob(conversationId = "old-chat", sourceIds = emptyList(), rangeHash = "old", epoch = 0, expectedRevisions = emptyMap())))
                db.learningMemoryDao().put(LearningMemoryStateEntity("legacy", json.encodeToString(state)))
                val migrated = store.read("legacy")
                assertTrue(migrated.recoveryBlocked)
                assertEquals(1, migrated.fingerprintVersion)
                assertEquals(state.epoch + 1, migrated.epoch)
                assertEquals(state.items, migrated.items)
                assertEquals(state.suppressedFacts, migrated.suppressedFacts)
                assertEquals(state.suppressedTopics, migrated.suppressedTopics)
                assertEquals("CANCELLED", migrated.jobs.single().state)
                val payload = db.learningMemoryDao().get("legacy")!!.payload
                assertTrue("Unknown deletion constraints must block retrieval", store.relevant("legacy", "哨兵").isEmpty())
                assertEquals("", store.context("legacy", "new-chat", "哨兵", true))
                assertEquals(payload, db.learningMemoryDao().get("legacy")!!.payload)
                assertEquals(state.usedMemories, store.read("legacy").usedMemories)
                val item = store.read("legacy").items.single()
                store.edit("legacy", item.id, item.revision, "本机人工更正哨兵")
                assertEquals("本机人工更正哨兵", store.read("legacy").items.single().content)
                assertEquals("", store.context("legacy", "new-chat", "哨兵", true))
                store.delete("legacy", item.id, item.revision + 1)
                assertTrue(store.read("legacy").items.isEmpty())
                assertTrue(store.read("legacy").recoveryBlocked)
            }
        }
    }

    @Test fun alreadyMigratedBlockedProfileDoesNotWriteUsageAndOtherOwnerStillWorks(): Unit = runBlocking {
        fixture { db, store, fingerprints, _ ->
            val id = store.addManual("legacy", "request", MemoryType.BACKGROUND, "旧背景哨兵")
            val state = store.read("legacy").copy(recoveryBlocked = true,
                suppressedFacts = setOf(fingerprints.of("legacy", "opaque-legacy-marker")),
                usedMemories = mapOf("chat" to listOf(id)))
            val payload = json.encodeToString(state)
            db.learningMemoryDao().put(LearningMemoryStateEntity("legacy", payload))
            assertEquals("", store.context("legacy", "chat", "哨兵", true))
            assertTrue(store.relevant("legacy", "哨兵").isEmpty())
            assertEquals(payload, db.learningMemoryDao().get("legacy")!!.payload)
            val other = store.addManual("fresh-owner", "request", MemoryType.PREFERENCE, "新档案偏好哨兵")
            assertEquals(listOf(other), store.relevant("fresh-owner", "哨兵").map { it.id })
            assertTrue(store.context("fresh-owner", "chat", "哨兵", true).contains("新档案偏好哨兵"))
            assertEquals(payload, db.learningMemoryDao().get("legacy")!!.payload)
        }
    }

    @Test fun legacyMigrationMustNotClearAnExistingRecoveryBlock(): Unit = runBlocking {
        fixture { db, store, _, _ ->
            store.addManual("legacy", "request", MemoryType.PREFERENCE, "受保护偏好哨兵")
            val state = store.read("legacy").copy(fingerprintVersion = 0, recoveryBlocked = true)
            db.learningMemoryDao().put(LearningMemoryStateEntity("legacy", json.encodeToString(state)))
            assertTrue("Migration cannot relax an existing deletion barrier", store.read("legacy").recoveryBlocked)
            assertEquals("", store.context("legacy", "chat", "哨兵", true))
        }
    }

    @Test fun cleanLegacyProfileStillMigratesAndProjectsLegitimateMemory(): Unit = runBlocking {
        fixture { db, store, _, _ ->
            val id = store.addManual("legacy", "request", MemoryType.PREFERENCE, "合法旧偏好哨兵")
            val state = store.read("legacy").copy(fingerprintVersion = 0)
            db.learningMemoryDao().put(LearningMemoryStateEntity("legacy", json.encodeToString(state)))
            val migrated = store.read("legacy")
            assertEquals(1, migrated.fingerprintVersion)
            assertFalse(migrated.recoveryBlocked)
            assertEquals(state.items, migrated.items)
            assertEquals(listOf(id), store.relevant("legacy", "哨兵").map { it.id })
            assertTrue(store.context("legacy", "chat", "哨兵", true).contains("合法旧偏好哨兵"))
            assertEquals(listOf(id), store.read("legacy").usedMemories["chat"])
        }
    }

    @Test fun blockedExtractionEnqueueDoesNotFailAnOrdinaryChat(): Unit = runBlocking {
        fixture { db, store, _, _ ->
            val state = MemoryState(fingerprintVersion = 1, recoveryBlocked = true)
            val payload = json.encodeToString(state)
            db.learningMemoryDao().put(LearningMemoryStateEntity("legacy", payload))
            val chat = Conversation.ofId(kotlin.uuid.Uuid.random(), kotlin.uuid.Uuid.random()).copy(profileId = "legacy")
            assertEquals(0, store.enqueue(chat, true))
            assertEquals(0, store.enqueue(chat, false))
            assertEquals(payload, db.learningMemoryDao().get("legacy")!!.payload)
        }
    }

    @Test fun missingOrUnreadableKeyRejectsWithoutReplacingKeyOrPayload(): Unit = runBlocking {
        fixture { db, store, fingerprints, keyFile ->
            store.addManual("legacy", "request", MemoryType.PREFERENCE, "密钥保护哨兵")
            val state = store.read("legacy").copy(suppressedFacts = setOf(fingerprints.of("legacy", "deleted")))
            val payload = json.encodeToString(state)
            db.learningMemoryDao().put(LearningMemoryStateEntity("legacy", payload))
            // Only this fixture's key reference is touched; no Keystore entries are removed.
            assertTrue(keyFile.delete())
            assertNotNull(runCatching { store.context("legacy", "chat", "哨兵", true) }.exceptionOrNull())
            assertFalse(keyFile.exists())
            assertEquals(payload, db.learningMemoryDao().get("legacy")!!.payload)
            val broken = "credential-v1:${UUID.randomUUID()}"
            keyFile.writeText(broken)
            assertNotNull(runCatching { store.context("legacy", "chat", "哨兵", true) }.exceptionOrNull())
            assertEquals(broken, keyFile.readText())
            assertEquals(payload, db.learningMemoryDao().get("legacy")!!.payload)
        }
    }

    @Test fun failedLegacyMigrationPreservesPayloadAndKeyRecoveryKeepsDeletionBarrier(): Unit = runBlocking {
        fixture { db, store, fingerprints, keyFile ->
            store.addManual("legacy", "request", MemoryType.PREFERENCE, "旧迁移保全哨兵")
            fingerprints.of("legacy", "ensure-portable-key")
            val key = keyFile.readBytes()
            val state = store.read("legacy").copy(fingerprintVersion = 0,
                suppressedFacts = setOf(fingerprints.legacyOf("legacy", "unmapped-deleted-fact")))
            val payload = json.encodeToString(state)
            db.learningMemoryDao().put(LearningMemoryStateEntity("legacy", payload))
            val broken = "credential-v1:${UUID.randomUUID()}"
            keyFile.writeText(broken)
            assertNotNull(runCatching { store.context("legacy", "chat", "哨兵", true) }.exceptionOrNull())
            assertEquals(payload, db.learningMemoryDao().get("legacy")!!.payload)
            assertEquals(broken, keyFile.readText())
            keyFile.writeBytes(key)
            assertTrue(store.read("legacy").recoveryBlocked)
            assertEquals("", store.context("legacy", "chat", "哨兵", true))
            assertEquals(state.items, store.read("legacy").items)
            assertEquals(state.suppressedFacts, store.read("legacy").suppressedFacts)
        }
    }
}
