package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.koin.core.context.GlobalContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.flutter.StudyHostFacade

@RunWith(AndroidJUnit4::class)
class TaskResumeReceiptRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun committedResumeRetainsExactBoundariesAfterDatabaseReopen() {
        val name = "receipt-${UUID.randomUUID()}.db"
        val id = AgentTaskStore(context, name).use { store ->
            val task = store.accept("owner", "conversation", "input", "goal", "synthetic-reference", "p", "m", "OFF", "v1")
            store.command("owner", task.id, "lost-resume", task.revision, task.fence, "resume")
            task.id
        }
        AgentTaskStore(context, name).use { store ->
            store.readableDatabase.rawQuery("SELECT resume_revision,resume_fence FROM task_commands WHERE task_id=? AND request_id=?", arrayOf(id, "lost-resume")).use {
                assertTrue(it.moveToFirst()); assertFalse(it.isNull(0)); assertFalse(it.isNull(1))
                assertEquals(0L, it.getLong(0)); assertEquals(0L, it.getLong(1))
            }
            assertEquals("QUEUED", store.get("owner", id).state)
            val before = store.snapshot("owner", id)
            val receipt = requireNotNull(store.readResumeReceipt("owner", id, null))
            assertEquals("lost-resume", receipt.requestId); assertTrue(receipt.canCoordinate)
            assertEquals(receipt, store.readResumeReceipt("owner", id, "lost-resume"))
            assertNull(store.readResumeReceipt("owner", id, "wrong-request"))
            assertTrue(runCatching { store.readResumeReceipt("foreign", id, null) }.isFailure)
            assertEquals(before, store.snapshot("owner", id))
        }
    }

    private fun fixture(block: (AgentTaskStore, AgentTask) -> Unit) {
        val name = "qa-resume-receipt-${UUID.randomUUID()}.db"
        try { AgentTaskStore(context, name).use { store ->
            val task = store.accept("owner", "conversation", "input", "goal", "synthetic-reference", "p", "m", "HIGH", "v1")
            store.command(task.owner, task.id, "original", task.revision, task.fence, "resume")
            block(store, task)
        } } finally { SQLiteCleanup.delete(context.noBackupFilesDir.resolve(name)) }
    }

    @Test fun laterPauseRevokeAndNewResumeCannotCoordinateOldIntent() = fixture { store, task ->
        var current = store.get(task.owner, task.id)
        store.command(task.owner, task.id, "pause", current.revision, current.fence, "pause")
        assertFalse(requireNotNull(store.readResumeReceipt(task.owner, task.id, "original")).canCoordinate)
        current = store.get(task.owner, task.id)
        store.command(task.owner, task.id, "new-resume", current.revision, current.fence, "resume")
        assertFalse(requireNotNull(store.readResumeReceipt(task.owner, task.id, "original")).canCoordinate)
        assertEquals("new-resume", requireNotNull(store.readResumeReceipt(task.owner, task.id, null)).requestId)
        assertTrue(requireNotNull(store.readResumeReceipt(task.owner, task.id, null)).canCoordinate)
        current = store.get(task.owner, task.id)
        store.command(task.owner, task.id, "revoke", current.revision, current.fence, "revoke")
        val before = store.snapshot(task.owner, task.id)
        assertFalse(requireNotNull(store.readResumeReceipt(task.owner, task.id, null)).canCoordinate)
        assertEquals(before, store.snapshot(task.owner, task.id))
        assertEquals("HIGH", before.task.reasoning)
    }

    @Test fun unknownReadCannotReleaseSlotOrReconcileSideEffect() = fixture { store, task ->
        val lease = store.claim(task.owner, task.id)
        store.beginCall(lease, "unknown", "create_material_file", "{}")
        store.finish(lease, "FAILED_BLOCKED")
        val before = store.snapshot(task.owner, task.id)
        assertEquals("UNCERTAIN_SIDE_EFFECT", before.task.state)
        assertFalse(requireNotNull(store.readResumeReceipt(task.owner, task.id, null)).canCoordinate)
        assertEquals(before, store.snapshot(task.owner, task.id))
        val other = store.accept("foreign", "other", "other", "goal", "ref", "p", "m", "OFF", "v1")
        assertTrue(runCatching { store.claim(other.owner, other.id) }.exceptionOrNull() is AgentTaskBlocked)
    }

    @Test fun changedCommandIdentityOrDamagedMetadataNeverBecomesReceipt() = fixture { store, task ->
        assertTrue(runCatching { store.command(task.owner, task.id, "original", 99, 0, "resume") }.isFailure)
        val db = store.writableDatabase
        db.execSQL("UPDATE task_commands SET resume_fence=1 WHERE task_id=?", arrayOf(task.id))
        assertTrue(runCatching { store.readResumeReceipt(task.owner, task.id, null) }.isFailure)
        db.execSQL("UPDATE task_commands SET resume_fence=NULL WHERE task_id=?", arrayOf(task.id))
        assertTrue(runCatching { store.readResumeReceipt(task.owner, task.id, "original") }.isFailure)
    }

    private fun oldFixture(name: String) {
        val encoded = InstrumentationRegistry.getInstrumentation().context.assets.open("agent_schema8_resume_from_android.b64").use { it.readBytes() }
        val raw = Base64.decode(encoded, Base64.DEFAULT)
        assertEquals("5a509019deb7a77c475a16a4961c115a3cfcfa4e11318310815191b921d6e63c", MessageDigest.getInstance("SHA-256").digest(raw).toHexString())
        context.noBackupFilesDir.resolve(name).writeBytes(raw)
    }
    private fun rows(db: SQLiteDatabase): Map<String, List<List<String?>>> =
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", null).use { tables ->
            buildMap { while (tables.moveToNext()) {
                val table = tables.getString(0)
                val projection = if (table == "task_commands") "task_id,request_id,input_hash" else "*"
                put(table, db.rawQuery("SELECT $projection FROM $table ORDER BY rowid", null).use { c ->
                    buildList { add(c.columnNames.toList()); while (c.moveToNext()) add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) }) }
                })
            } }
        }

    @Test fun actualSchemaEightMigratesWithoutInventingLegacyResumeAndKeepsUnknown() {
        val name = "qa-real-eight-${UUID.randomUUID()}.db"
        val path = context.noBackupFilesDir.resolve(name)
        try {
            oldFixture(name)
            lateinit var id: String
            val before = SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                assertEquals(8, db.version)
                id = db.rawQuery("SELECT id FROM tasks", null).use { assertTrue(it.moveToFirst()); it.getString(0) }
                db.execSQL("UPDATE tasks SET state='UNCERTAIN_SIDE_EFFECT',fence=43,manual_pause=1")
                db.execSQL("UPDATE slot SET task_id=?,fence=43 WHERE singleton=1", arrayOf(id))
                db.execSQL("INSERT INTO calls(task_id,call_id,tool,input_hash,state) VALUES(?,'unknown','create_material_file','synthetic','UNKNOWN')", arrayOf(id))
                rows(db)
            }
            AgentTaskStore(context, name).use { store ->
                val db = store.readableDatabase
                assertEquals(13, db.version)
                val after=rows(db)
                val expected=before.toMutableMap()
                expected["slot"]=requireNotNull(before["slot"]).mapIndexed { index,row ->
                    row+if(index==0) "executor_id" else "local"
                }
                expected["agent_executor_bindings"]=listOf(listOf("task_id","fence","executor_id","instance_id"))
                expected["agent_accounts"]=listOf(listOf("id","epoch","revoked"))
                expected["agent_account_profiles"]=listOf(listOf("owner","account_id"))
                expected["agent_sessions"]=listOf(listOf("digest","account_id","account_epoch","owner","task_id","executor_id","grant_epoch","expires_at","revoked"))
                expected["agent_host_credentials"]=listOf(listOf("id","account_id","owner","task_id","provider_id","grant_epoch","reference","revision","revoked"))
                expected["agent_transport_audit"]=listOf(listOf("id","account_id","task_id","operation","created_at"))
                expected["agent_provider_commands"]=listOf(listOf("account_id","task_id","command_id","intent_hash","request_id"))
                expected["agent_hosted_results"]=listOf(listOf("request_id","response_text","response_hash"))
                expected["sqlite_sequence"]=listOf(listOf("name","seq"))
                assertEquals(expected,after-"usage_costs"-"executor_handoffs")
                assertEquals(listOf(listOf("request_id","basis","receipt")),after["usage_costs"])
                assertEquals(listOf(listOf("task_id","request_id","source_executor","source_fence","target_executor","grant_epoch","input_hash","snapshot_hash","state","target_fence")),after["executor_handoffs"])
                assertNull(store.readResumeReceipt("owner", id, "lost-resume"))
                assertNull(store.readResumeReceipt("owner", id, null))
                db.rawQuery("SELECT resume_revision,resume_fence FROM task_commands", null).use {
                    assertTrue(it.moveToFirst()); assertTrue(it.isNull(0)); assertTrue(it.isNull(1))
                }
                store.recover()
                assertEquals(after, rows(db))
                assertTrue(runCatching { store.resume("owner", id) }.isFailure)
            }
        } finally { SQLiteCleanup.delete(path) }
    }

    @Test fun migrationFailureRollsBackFirstAddedColumnAndVersion() {
        val name = "qa-fail-eight-${UUID.randomUUID()}.db"
        val path = context.noBackupFilesDir.resolve(name)
        try {
            oldFixture(name)
            val before = SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                // Inject a schema conflict at the second ALTER after the first succeeded.
                db.execSQL("ALTER TABLE task_commands ADD COLUMN RESUME_FENCE INTEGER")
                rows(db)
            }
            AgentTaskStore(context, name).use { assertTrue(runCatching { it.writableDatabase }.isFailure) }
            SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                assertEquals(8, db.version); assertEquals(before, rows(db))
                db.rawQuery("PRAGMA table_info(task_commands)", null).use { cols ->
                    while (cols.moveToNext()) assertNotEquals("resume_revision", cols.getString(1))
                }
            }
        } finally { SQLiteCleanup.delete(path) }
    }

    @Test fun hostReadDoesNotNeedConversationAndRejectsWrongOwnerOrEpoch(): Unit = runBlocking {
        val koin = GlobalContext.get(); val settings = koin.get<SettingsStore>()
        val owner = settings.settingsFlow.first { !it.init }.activeProfileId
        val host = StudyHostFacade(context, settings, koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), pickFiles = { emptyList() })
        try {
            val epoch = host.getBootstrap(owner, 1L).contextEpoch
            AgentTaskStore(context).use { store ->
                val task = store.accept(owner, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "synthetic receipt goal", "ref", "p", "m", "OFF", "v1")
                store.command(owner, task.id, "original", task.revision, task.fence, "resume")
                val before = store.snapshot(owner, task.id)
                val receipt = requireNotNull(host.getTaskResumeReceipt(owner, task.id, null, epoch))
                assertEquals("original", receipt.requestId); assertEquals(task.conversationId, receipt.conversationId)
                assertTrue(receipt.canCoordinate)
                assertNull(host.getTaskResumeReceipt(owner, task.id, "different-request", epoch))
                assertTrue(runCatching { host.getTaskResumeReceipt("foreign", task.id, null, epoch) }.isFailure)
                assertTrue(runCatching { host.getTaskResumeReceipt(owner, task.id, null, epoch + 1) }.isFailure)
                assertEquals(before, store.snapshot(owner, task.id))
                assertTrue(store.readTaskDetail(owner, task.id, null, 20).calls.isEmpty())
            }
        } finally { host.close() }
    }
}
