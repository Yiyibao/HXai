package me.rerere.rikkahub.feature.agent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal data class UsageNotificationCandidate(val owner: String, val taskId: String, val rules: Set<String>)

/** Paged advisory metadata, derived from the original ledger; no new task state. */
internal object AgentUsageNotificationReader {
    fun query(owner: String, after: String?, now: Long, limit: Int, taskId: String? = null): Pair<String, Array<String>> {
        require(owner.isNotBlank() && now>=0 && limit in 1..100)
        // Owner/task keyset order stops after one page. Ledger probes use task/time
        // and call identity indexes instead of grouping the entire remaining ledger.
        val receipts=" FROM usage u LEFT JOIN calls c ON c.task_id=u.task_id AND c.call_id=u.request_id WHERE u.task_id=t.id"
        val unknown="EXISTS(SELECT 1$receipts AND (c.state IS NULL OR c.state='UNKNOWN'))"
        val unreported="EXISTS(SELECT 1$receipts AND c.state='VERIFIED' AND COALESCE(u.provenance,'unknown') NOT IN ('reported','estimated'))"
        val dense="EXISTS(SELECT 1 FROM usage u WHERE u.task_id=t.id AND u.created_at>=? AND u.created_at<=? LIMIT 1 OFFSET ${AgentUsageAlertReader.DENSE_REQUESTS-1})"
        val args=listOf(maxOf(0,now-AgentUsageAlertReader.WINDOW_MILLIS).toString(),now.toString(),owner)+
            listOfNotNull(after,taskId)+limit.toString()
        val sql="SELECT task_id,uncertain,unreported,dense FROM (SELECT t.id AS task_id,$unknown AS uncertain,$unreported AS unreported,$dense AS dense FROM tasks t "+
            "JOIN task_control g ON g.task_id=t.id "+
            "WHERE t.owner=? AND g.authorization!='REVOKED'"+(if(after==null) "" else " AND t.id>? COLLATE BINARY")+
            (if(taskId==null) "" else " AND t.id=?")+
            ") WHERE uncertain=1 OR unreported=1 OR dense=1 ORDER BY task_id COLLATE BINARY LIMIT ?"
        return sql to args.toTypedArray()
    }
    fun read(db: SQLiteDatabase, owner: String, after: String?, now: Long, limit: Int,
        taskId: String? = null): List<UsageNotificationCandidate> {
        val (sql,args)=query(owner,after,now,limit,taskId)
        return db.rawQuery(sql,args).use { row ->
            buildList {
                while(row.moveToNext()) add(UsageNotificationCandidate(owner,row.getString(0),buildSet {
                    if(row.getInt(1)!=0) add("uncertain")
                    if(row.getInt(2)!=0) add("unreported")
                    if(row.getInt(3)!=0) add("dense")
                }))
            }
        }
    }
}

/** At most one attempt per task/rule, including across process exit. Not a delivery receipt. */
internal class AgentUsageNotificationJournal(context: Context, name: String = "agent-usage-notifications-v1.db") :
    SQLiteOpenHelper(context,context.noBackupFilesDir.resolve(name).absolutePath,null,1) {
    override fun onConfigure(db: SQLiteDatabase) { db.execSQL("PRAGMA synchronous=FULL") }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE attempts (event_key TEXT PRIMARY KEY, attempted_at INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int): Unit = error("Unsupported notification journal; preserved")
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int): Unit = error("Unsupported notification journal; preserved")
    @Synchronized fun reserve(candidate: UsageNotificationCandidate, now: Long): Boolean {
        require(now>=0 && candidate.owner.isNotBlank() && candidate.taskId.isNotBlank())
        require(candidate.rules.isNotEmpty() && candidate.rules.all { it in setOf("uncertain","unreported","dense") })
        val db=writableDatabase;db.beginTransaction()
        try {
            var added=false
            for(rule in candidate.rules) {
                val key=AgentTaskStore.hash("usage-notification-v1\u0000${candidate.owner}\u0000${candidate.taskId}\u0000$rule")
                if(db.insertWithOnConflict("attempts",null,ContentValues().apply {
                    put("event_key",key);put("attempted_at",now)
                },SQLiteDatabase.CONFLICT_IGNORE)!=-1L) added=true
            }
            db.setTransactionSuccessful();return added
        } finally { db.endTransaction() }
    }
}

/** Shared by the real observer and Android fault tests. A failed sink is never blindly replayed. */
internal class AgentUsageNotificationDispatcher(
    private val store: AgentTaskStore,
    private val journal: AgentUsageNotificationJournal,
) {
    @Synchronized fun scan(owner: String, now: Long, allowed: () -> Boolean, post: () -> Unit): Boolean {
        var after: String?=null
        var present=false
        var posted=false
        do {
            if(!allowed()) return false
            val page=store.readUsageNotificationCandidates(owner,after,now)
            val fresh=mutableListOf<UsageNotificationCandidate>()
            for(candidate in page) {
                if(!allowed()) return false
                if(store.control(owner,candidate.taskId).authorization=="REVOKED") continue
                present=true
                if(journal.reserve(candidate,now)) fresh+=candidate
            }
            // Recheck only this page, never accumulate every matching task. The
            // single SELECT includes current authorization and uses no write transaction.
            if(!posted && fresh.isNotEmpty() && allowed() && fresh.any { candidate ->
                store.readUsageNotificationRules(owner,candidate.taskId,now).any { it in candidate.rules }
            } && allowed()) {
                post()
                posted=true
            }
            after=page.lastOrNull()?.taskId
        } while(page.size==100)
        return present
    }
}
