package me.rerere.rikkahub.feature.agent

import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.feature.memory.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Exact old-product counterexamples, synthetic Room/Keystore only. */
@RunWith(AndroidJUnit4::class)
class LegacySourcePreservationRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val json = Json { encodeDefaults = true }

    private suspend fun fixture(block: suspend (AppDatabase, LearningMemoryStore, MemoryFingerprints, File) -> Unit) {
        val name = "qa-orphan-${UUID.randomUUID()}.db"
        val root = File(context.cacheDir, "qa-orphan-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try { block(db, LearningMemoryStore(db, MemoryFingerprints(isolated)), MemoryFingerprints(isolated),
            File(isolated.filesDir, PortableMemoryKey.FILE_NAME)) }
        finally { db.close(); context.deleteDatabase(name); check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile); root.deleteRecursively() }
    }

    private fun retained(payload: String): Set<String> = json.parseToJsonElement(payload).jsonObject["legacySuppressedSources"]
        ?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()

    private suspend fun seed(db: AppDatabase, store: LearningMemoryStore, fp: MemoryFingerprints, orphan: Boolean = true,
                             mapped: Boolean = false): MemoryState {
        store.addManual("owner", "keep", MemoryType.PREFERENCE, "保留管理记录哨兵")
        val source = MemorySource("source", "chat", "node", "message", fp.legacyOf("owner", "content:old"), 1)
        val markers = (if (orphan) setOf(fp.legacyOf("owner", "message:orphan")) else emptySet()) +
            if (mapped) setOf(fp.legacyOf("owner", "message:message"), fp.legacyOf("owner", "message:${source.hash}")) else emptySet()
        val state = store.read("owner").copy(fingerprintVersion = 0, epoch = 7,
            suppressedSources = markers, sources = if (mapped) listOf(source) else emptyList(),
            jobs = listOf(ExtractionJob(conversationId = "chat", sourceIds = listOf("source"), rangeHash = "hash", epoch = 7, expectedRevisions = emptyMap(), state = "RUNNING", leaseUntil = 999)))
        db.learningMemoryDao().put(LearningMemoryStateEntity("owner", json.encodeToString(state)))
        return state
    }

    @Test fun orphanLegacyMarkerIsNotSilentlyDiscarded(): Unit = runBlocking {
        fixture { db, store, fp, _ ->
            val old = seed(db, store, fp)
            val migrated = store.read("owner")
            assertEquals(old.suppressedSources, retained(db.learningMemoryDao().get("owner")!!.payload))
            assertTrue(migrated.recoveryBlocked)
            assertEquals(old.items, migrated.items)
            assertEquals(8L, migrated.epoch)
            assertEquals("CANCELLED", migrated.jobs.single().state)
            val payload = db.learningMemoryDao().get("owner")!!.payload
            assertEquals(migrated, store.read("owner")); assertEquals(payload, db.learningMemoryDao().get("owner")!!.payload)
        }
    }

    @Test fun unresolvedSourceStopsHistoryAndExtractionButKeepsManagementAndOtherOwner(): Unit = runBlocking {
        fixture { db, store, fp, _ ->
            seed(db, store, fp)
            val messages = listOf(UIMessage.user("旧来源正文哨兵"), UIMessage.assistant("旧回答哨兵"))
            assertTrue("Unknown source must never enter a provider view", runCatching { store.projectHistory("owner", messages) }.isFailure)
            assertEquals("", store.context("owner", "chat", "哨兵", true))
            assertTrue(store.relevant("owner", "哨兵").isEmpty())
            assertNull(store.claim("owner", true, System.currentTimeMillis()))
            val item = store.read("owner").items.single()
            store.edit("owner", item.id, item.revision, "人工更正管理记录")
            store.delete("owner", item.id, item.revision + 1)
            assertTrue(store.read("owner").items.isEmpty())
            assertEquals(1, retained(db.learningMemoryDao().get("owner")!!.payload).size)
            assertTrue(runCatching { store.projectHistory("owner", messages) }.isFailure)
            assertEquals(messages, store.projectHistory("other-owner", messages))
            val otherId = store.addManual("other-owner", "keep", MemoryType.PREFERENCE, "其他档案哨兵")
            assertEquals(otherId, store.relevant("other-owner", "哨兵").single().id)
        }
    }

    @Test fun partiallyMappedLegacySetRetainsOnlyUnmappedConstraints(): Unit = runBlocking {
        fixture { db, store, fp, _ ->
            seed(db, store, fp, mapped = true)
            val state = store.read("owner")
            assertEquals(setOf(fp.legacyOf("owner", "message:orphan")), retained(db.learningMemoryDao().get("owner")!!.payload))
            assertTrue(fp.of("owner", "message:message") in state.suppressedSources)
            assertTrue(state.recoveryBlocked)
        }
    }

    @Test fun fullyMappedLegacySourceDoesNotBlockLegitimateNewMessages(): Unit = runBlocking {
        fixture { db, store, fp, _ ->
            seed(db, store, fp, orphan = false, mapped = true)
            val migrated = store.read("owner")
            assertFalse(migrated.recoveryBlocked)
            assertTrue(retained(db.learningMemoryDao().get("owner")!!.payload).isEmpty())
            assertTrue(store.isSuppressed("owner", "message", "rewritten old source"))
            val fresh = listOf(UIMessage.user("same text with a new ID"))
            assertEquals(fresh, store.projectHistory("owner", fresh))
        }
    }

    @Test fun crossOwnerConversationCannotSupplyMigrationText(): Unit = runBlocking {
        fixture { db, store, fp, _ ->
            val message = UIMessage.user("OTHER_OWNER_SYNTHETIC_TEXT")
            val source = MemorySource("source", "other-chat", "node", message.id.toString(), fp.legacyOf("owner", "content:${message.toText()}"), 1)
            db.conversationDao().insert(ConversationEntity("other-chat", "assistant", "", "[]", 1, 1, "[]", false, profileId = "other-owner"))
            db.messageNodeDao().insert(MessageNodeEntity("node", "other-chat", 0, json.encodeToString(listOf(message)), 0))
            db.learningMemoryDao().put(LearningMemoryStateEntity("owner", json.encodeToString(MemoryState(sources = listOf(source)))))
            val migrated = store.read("owner")
            assertFalse("A foreign conversation is not this owner's authenticated source", migrated.sources.single().valid)
            assertNotEquals(fp.of("owner", "content:${message.toText()}"), migrated.sources.single().hash)
            assertEquals(json.encodeToString(listOf(message)), db.messageNodeDao().getNodesOfConversation("other-chat").single().messages)
        }
    }

    @Test fun legitimateOwnerSourceStillMigratesContentAndPortableTombstones(): Unit = runBlocking {
        fixture { db, store, fp, _ ->
            val message = UIMessage.user("LEGITIMATE_OWNER_SOURCE")
            val hash = fp.legacyOf("owner", "content:${message.toText()}")
            val source = MemorySource("source", "own-chat", "node", message.id.toString(), hash, 1)
            db.conversationDao().insert(ConversationEntity("own-chat", "assistant", "", "[]", 1, 1, "[]", false, profileId = "owner"))
            db.messageNodeDao().insert(MessageNodeEntity("node", "own-chat", 0, json.encodeToString(listOf(message)), 0))
            val legacy = MemoryState(sources = listOf(source), suppressedSources = setOf(fp.legacyOf("owner", "${message.id}:$hash")))
            db.learningMemoryDao().put(LearningMemoryStateEntity("owner", json.encodeToString(legacy)))
            val migrated = store.read("owner")
            assertTrue(migrated.sources.single().valid)
            assertEquals(fp.of("owner", "content:${message.toText()}"), migrated.sources.single().hash)
            assertFalse(migrated.recoveryBlocked)
            assertTrue(store.isSuppressed("owner", message.id.toString(), message.toText()))
        }
    }

    @Test fun inconsistentPortableProtectionRejectsWithoutRewritingPayload(): Unit = runBlocking {
        fixture { db, store, _, _ ->
            val state = MemoryState(fingerprintVersion = 1, recoveryBlocked = false, legacySuppressedSources = setOf("opaque-v0"))
            val payload = json.encodeToString(state)
            db.learningMemoryDao().put(LearningMemoryStateEntity("owner", payload))
            assertTrue(runCatching { store.read("owner") }.isFailure)
            assertEquals(payload, db.learningMemoryDao().get("owner")!!.payload)
        }
    }

    @Test fun failedMigrationRollsBackPayloadAndSuccessfulRetryKeepsMarker(): Unit = runBlocking {
        fixture { db, store, fp, _ ->
            val old = seed(db, store, fp)
            val before = db.learningMemoryDao().get("owner")!!.payload
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER qa_orphan_abort BEFORE INSERT ON learning_memory_state BEGIN SELECT RAISE(ABORT, 'synthetic migration failure'); END")
            assertTrue(runCatching { store.read("owner") }.isFailure)
            assertEquals(before, db.learningMemoryDao().get("owner")!!.payload)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER qa_orphan_abort")
            assertTrue(store.read("owner").recoveryBlocked)
            assertEquals(old.suppressedSources, retained(db.learningMemoryDao().get("owner")!!.payload))
        }
    }

    @Test fun brokenPortableKeyCannotReplaceOldPayloadOrDiscardConstraint(): Unit = runBlocking {
        fixture { db, store, fp, key ->
            val old = seed(db, store, fp)
            fp.of("owner", "ensure-portable-key")
            val keyBytes = key.readBytes()
            val before = db.learningMemoryDao().get("owner")!!.payload
            val broken = "credential-v1:${UUID.randomUUID()}"
            key.writeText(broken)
            assertTrue(runCatching { store.read("owner") }.isFailure)
            assertEquals(before, db.learningMemoryDao().get("owner")!!.payload)
            assertEquals(broken, key.readText())
            key.writeBytes(keyBytes)
            assertTrue(store.read("owner").recoveryBlocked)
            assertEquals(old.suppressedSources, retained(db.learningMemoryDao().get("owner")!!.payload))
        }
    }
}
