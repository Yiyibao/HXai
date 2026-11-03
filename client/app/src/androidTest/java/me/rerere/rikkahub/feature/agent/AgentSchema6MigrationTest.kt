package me.rerere.rikkahub.feature.agent

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.UUID

/** Source: r03-directed-02-state.tar, actual native schema 6, never a Python protocol substitute. */
@RunWith(AndroidJUnit4::class)
class AgentSchema6MigrationTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private data class Rows(val columns: List<String>,val cells: List<List<Pair<Int,String?>>>)
    private fun snapshot(db: SQLiteDatabase,table: String,columns: List<String>?=null): Rows {
        val names=columns ?: db.rawQuery("PRAGMA table_info($table)",null).use {
            buildList { while(it.moveToNext()) add(it.getString(1)) }
        }
        return Rows(names,db.rawQuery("SELECT ${names.joinToString(",")} FROM $table ORDER BY rowid",null).use { cursor ->
            buildList {
                while(cursor.moveToNext()) add(names.indices.map { index ->
                    val type=cursor.getType(index)
                    require(type!=Cursor.FIELD_TYPE_BLOB) { "Unexpected binary value in the source fixture" }
                    type to if(cursor.isNull(index)) null else cursor.getString(index)
                })
            }
        })
    }
    @Test fun actualSixDatabasePreservesAllOldRowsNullableUsageUnknownSlotAndReceipts() {
        val name="qa-real-schema6-${UUID.randomUUID()}.db"
        val path=context.noBackupFilesDir.resolve(name)
        try {
            val encoded=InstrumentationRegistry.getInstrumentation().context.assets.open("agent_schema6_from_android.b64").use { it.readBytes() }
            val bytes=Base64.decode(encoded,Base64.DEFAULT)
            assertEquals("18a9ed82a482ea606676329f0bd7689c5df562e7f440847d69ca91bfc407b71d",MessageDigest.getInstance("SHA-256").digest(bytes).toHexString())
            path.writeBytes(bytes)
            val tables=listOf("tasks","calls","usage","slot","task_control","grants","plans","plan_steps","task_commands")
            lateinit var original: Map<String,Rows>
            lateinit var owner: String
            lateinit var id: String
            SQLiteDatabase.openDatabase(path.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
                assertEquals(6,db.version)
                db.rawQuery("SELECT id,owner FROM tasks ORDER BY rowid LIMIT 1",null).use {
                    assertTrue(it.moveToFirst());id=it.getString(0);owner=it.getString(1)
                }
                // Enrich the actual old schema on Android to exercise the migration's uncertain path.
                db.execSQL("UPDATE tasks SET state='UNCERTAIN_SIDE_EFFECT',fence=47,revision=17,manual_pause=1 WHERE id=?",arrayOf(id))
                db.execSQL("UPDATE slot SET task_id=?,fence=47 WHERE singleton=1",arrayOf(id))
                db.execSQL("INSERT INTO calls(task_id,call_id,tool,input_hash,state) VALUES(?,'fixture-unknown','provider:main','synthetic-input','UNKNOWN')",arrayOf(id))
                db.execSQL("INSERT INTO usage(request_id,task_id,role,provenance) VALUES('fixture-unknown',?,'main','unknown')",arrayOf(id))
                original=tables.associateWith { snapshot(db,it) }
            }
            AgentTaskStore(context,name).use { migrated ->
                val db=migrated.readableDatabase
                assertEquals(10,db.version)
                tables.forEach { table -> assertEquals("Old $table rows changed during native migration",original.getValue(table),snapshot(db,table,original.getValue(table).columns)) }
                migrated.recover()
                assertEquals("UNCERTAIN_SIDE_EFFECT",migrated.get(owner,id).state)
                assertEquals(47L,migrated.get(owner,id).fence)
                assertTrue(migrated.get(owner,id).manualPaused)
                assertTrue(runCatching { migrated.resume(owner,id) }.isFailure)
                db.rawQuery("SELECT task_id,fence FROM slot",null).use { assertTrue(it.moveToFirst());assertEquals(id,it.getString(0));assertEquals(47L,it.getLong(1)) }
                db.rawQuery("SELECT provenance,prompt_tokens,completion_tokens,cached_tokens,total_tokens FROM usage WHERE request_id='fixture-unknown'",null).use {
                    assertTrue(it.moveToFirst());assertEquals("unknown",it.getString(0));for(column in 1..4) assertTrue(it.isNull(column))
                }
                db.rawQuery("SELECT accepted_configuration_reference,configuration_reference FROM usage WHERE request_id!='fixture-unknown'",null).use {
                    while(it.moveToNext()) assertEquals(it.getString(1),it.getString(0))
                }
            }
        } finally { SQLiteCleanup.delete(path) }
    }
}
