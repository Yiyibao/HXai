package me.rerere.rikkahub.feature.agent

import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AgentUsageNotificationScaleTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(block: (AgentTaskStore, String) -> Unit) {
        val name = "qa-notify-scale-${UUID.randomUUID()}.db"
        try { AgentTaskStore(context, name).use { block(it, name) } }
        finally { SQLiteCleanup.delete(context.noBackupFilesDir.resolve(name)) }
    }

    private fun count(db: SQLiteDatabase, table: String) = db.rawQuery("SELECT COUNT(*) FROM $table", null).use {
        check(it.moveToFirst()); it.getLong(0)
    }
    private fun indexes(db: SQLiteDatabase) = db.rawQuery("SELECT name FROM sqlite_master WHERE type='index'", null).use {
        buildSet { while (it.moveToNext()) add(it.getString(0)) }
    }
    private fun originalDatabase(name: String) {
        val data = Base64.decode(InstrumentationRegistry.getInstrumentation().context.assets.open("agent_schema7_from_android.b64").use { it.readBytes() }, Base64.DEFAULT)
        assertEquals("cbe448c7da433024579bd481032c1b92e8cfc7fea7ad1c9fa4b14a4efc37cdcf", MessageDigest.getInstance("SHA-256").digest(data).toHexString())
        context.noBackupFilesDir.resolve(name).writeBytes(data)
    }
    private fun rows(db: SQLiteDatabase): Map<String, List<List<String?>>> {
        val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", null).use {
            buildList { while (it.moveToNext()) add(it.getString(0)) }
        }
        return tables.associateWith { table ->
            val projection = if (table == "task_commands") "task_id,request_id,input_hash" else "*"
            db.rawQuery("SELECT $projection FROM $table ORDER BY rowid", null).use { c ->
                buildList { add(c.columnNames.toList()); while (c.moveToNext()) add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) }) }
            }
        }
    }

    @Test fun actualSevenRowsRemainUnchangedAndUnknownKeepsItsSlot() {
        val name = "qa-actual-seven-${UUID.randomUUID()}.db"
        try {
            originalDatabase(name)
            val file = context.noBackupFilesDir.resolve(name)
            val before = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use {
                assertEquals(7, it.version); rows(it)
            }
            AgentTaskStore(context, name).use { migrated ->
                val db = migrated.writableDatabase
                assertEquals(10, db.version)
                assertEquals(before, rows(db))
                assertTrue(indexes(db).containsAll(setOf("tasks_owner_id", "usage_task_time")))
                db.rawQuery("SELECT task_id FROM slot WHERE singleton=1", null).use {
                    assertTrue(it.moveToFirst()); assertFalse(it.isNull(0))
                    val id = it.getString(0)
                    db.rawQuery("SELECT owner FROM tasks WHERE id=?", arrayOf(id)).use { owner ->
                        assertTrue(owner.moveToFirst()); assertTrue(runCatching { migrated.resume(owner.getString(0), id) }.isFailure)
                    }
                }
                assertEquals(before, rows(db))
            }
        } finally { SQLiteCleanup.delete(context.noBackupFilesDir.resolve(name)) }
    }

    @Test fun indexMigrationFailureRollsBackVersionAndAllRows() {
        val name = "qa-fail-seven-${UUID.randomUUID()}.db"
        try {
            originalDatabase(name)
            val file = context.noBackupFilesDir.resolve(name)
            val before = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("CREATE TABLE usage_task_time(marker TEXT)")
                it.execSQL("INSERT INTO usage_task_time VALUES('keep-original')")
                rows(it)
            }
            AgentTaskStore(context, name).use { assertTrue(runCatching { it.writableDatabase }.isFailure) }
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use {
                assertEquals(7, it.version); assertEquals(before, rows(it))
                assertFalse("First index creation must roll back", "tasks_owner_id" in indexes(it))
            }
        } finally { SQLiteCleanup.delete(context.noBackupFilesDir.resolve(name)) }
    }

    @Test fun queryPlanUsesTaskKeysetAndLedgerIndexesWithoutFullSort() = fixture { store, _ ->
        val (sql, args) = AgentUsageNotificationReader.query("owner-a", "task-000010", 500_000, 100)
        val details = store.readableDatabase.rawQuery("EXPLAIN QUERY PLAN $sql", args).use {
            buildList { while (it.moveToNext()) add(it.getString(3)) }
        }
        Log.i("NotifyScale", "plan=" + details.joinToString(" | "))
        assertTrue(details.any { it.contains("tasks_owner_id") && it.contains("id>?") })
        assertTrue(details.any { it.contains("usage_task_time") && it.contains("created_at>?") })
        assertFalse(details.any { it.contains("SCAN u") || it.contains("TEMP B-TREE") })
        assertTrue(store.readUsageNotificationRules("foreign", "task-000010", 500_000).isEmpty())
    }

    @Test fun millionRowsKeepExactRulesFullPaginationAndTaskState() = fixture { store, name ->
        val db = store.writableDatabase
        val seeded = SystemClock.elapsedRealtime()
        db.beginTransaction()
        try {
            db.execSQL("WITH RECURSIVE n(i) AS (SELECT 0 UNION ALL SELECT i+1 FROM n WHERE i<1999) " +
                "INSERT INTO tasks(id,owner,conversation_id,request_id,intent_hash,configuration_reference,provider_id,model_id,reasoning,configuration_revision,state) " +
                "SELECT printf('task-%06d',i),CASE WHEN i<1800 THEN 'owner-a' ELSE 'owner-b' END,'synthetic',printf('task-%06d',i),'hash','ref','provider','model','HIGH','revision','QUEUED' FROM n")
            db.execSQL("INSERT INTO task_control(task_id,authorization) SELECT id,CASE WHEN CAST(substr(id,6) AS INTEGER)%10=4 THEN 'REVOKED' ELSE 'ACTIVE' END FROM tasks")
            db.execSQL("WITH RECURSIVE n(i) AS (SELECT 0 UNION ALL SELECT i+1 FROM n WHERE i<499) " +
                "INSERT INTO usage(request_id,task_id,role,provenance,prompt_tokens,total_tokens,created_at) " +
                "SELECT t.id||'-'||printf('%03d',i),t.id,'ocr',CASE WHEN CAST(substr(t.id,6) AS INTEGER)%10=1 AND i=0 THEN 'unknown' ELSE 'reported' END,0,0," +
                "CASE WHEN CAST(substr(t.id,6) AS INTEGER)%10=2 AND i<20 THEN 500000 ELSE 1 END FROM tasks t CROSS JOIN n")
            db.execSQL("INSERT INTO calls(task_id,call_id,tool,input_hash,state) SELECT task_id,request_id,'provider:ocr','hash'," +
                "CASE WHEN CAST(substr(task_id,6) AS INTEGER)%10 IN (0,4) AND substr(request_id,-3)='000' THEN 'UNKNOWN' ELSE 'VERIFIED' END " +
                "FROM usage WHERE NOT(CAST(substr(task_id,6) AS INTEGER)%10=3 AND substr(request_id,-3)='000')")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        assertEquals(1_000_000L, count(db, "usage"))
        val before = store.get("owner-a", "task-000000")
        val started = SystemClock.elapsedRealtime()
        val found = linkedMapOf<String, Set<String>>()
        var after: String? = null
        do {
            val page = store.readUsageNotificationCandidates("owner-a", after, 500_000)
            assertTrue(page.size <= 100)
            for (candidate in page) {
                assertNull("Duplicate page result", found.put(candidate.taskId, candidate.rules))
                val index = candidate.taskId.substringAfter("task-").toInt()
                assertTrue(index < 1800)
                assertEquals(setOf(when (index % 10) { 0, 3 -> "uncertain"; 1 -> "unreported"; 2 -> "dense"; else -> error("False alert") }), candidate.rules)
            }
            after = page.lastOrNull()?.taskId
        } while (page.size == 100)
        Log.i("NotifyScale", "rows=1000000 candidates=${found.size} read_ms=${SystemClock.elapsedRealtime()-started} seed_ms=${started-seeded} sqlite=" +
            db.rawQuery("SELECT sqlite_version()", null).use { it.moveToFirst(); it.getString(0) })
        assertEquals(720, found.size)
        assertEquals(before, store.get("owner-a", "task-000000"))
        assertEquals(1_000_000L, count(db, "usage"))
        assertTrue("Missing owner/task keyset index", "tasks_owner_id" in indexes(db))
        assertTrue("Missing ledger task/time index", "usage_task_time" in indexes(db))
        val pool = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(1)
        try {
            val reader = pool.submit {
                ready.countDown()
                repeat(10) { assertEquals(100, store.readUsageNotificationCandidates("owner-a", null, 500_000).size) }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            val writeStart = SystemClock.elapsedRealtime()
            val writer = pool.submit {
                AgentTaskStore(context, name).use { other ->
                    other.accept("owner-a", "synthetic", "writer", "normal input", "ref", "provider", "model", "HIGH", "revision")
                }
            }
            writer.get(30, TimeUnit.SECONDS)
            Log.i("NotifyScale", "concurrent_writer_ms=${SystemClock.elapsedRealtime()-writeStart}")
            reader.get(30, TimeUnit.SECONDS)
            assertEquals(before, store.get("owner-a", "task-000000"))
        } finally { pool.shutdownNow() }
    }
}
