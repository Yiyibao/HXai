package me.rerere.rikkahub.feature.agent

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessagePart

internal data class CatalogTask(val id: String, val owner: String, val conversationId: String,
    val role: String, val goal: String, val state: String, val providerId: String, val modelId: String,
    val reasoning: String, val revision: Long, val fence: Long, val manualPaused: Boolean,
    val authorization: String, val inputBlocked: Boolean, val deviceBlocked: Boolean,
    val deviceConfirmation: Boolean, val approvalBlocked: Boolean,
    val goalRevision: Long, val plannedRevision: Long, val acceptanceVerified: Boolean)
internal data class CatalogPage(val owner: String, val kind: String, val tasks: List<CatalogTask>, val nextCursor: String?)
internal data class CatalogStep(val id: String, val tool: String, val purpose: String, val state: String,
    val callId: String?, val artifactResult: String?)
internal data class CatalogCall(val id: String, val tool: String, val state: String, val inputHash: String,
    val resultHash: String?, val capability: String?, val grantEpoch: Long?)
internal data class CatalogDetail(val task: CatalogTask, val steps: List<CatalogStep>, val calls: List<CatalogCall>,
    val nextCursor: String?, val resultsPartial: Boolean)

/** Bounded metadata projection, inside the sole TaskStore transaction. Never dispatches or grants. */
internal object AgentTaskCatalog {
    private const val JOIN = " FROM tasks t LEFT JOIN plans p ON p.task_id=t.id LEFT JOIN task_control c ON c.task_id=t.id"
    private const val COLUMNS = "t.id,t.owner,t.conversation_id,t.request_id,CASE WHEN t.request_id LIKE 'role:%' THEN '' ELSE substr(p.goal,1,8192) END," +
        "t.state,t.provider_id,t.model_id,t.reasoning,t.revision,t.fence,t.manual_pause,COALESCE(c.authorization,'UNKNOWN')," +
        "c.input_blocked,c.device_blocked,c.device_confirmation,EXISTS(SELECT 1 FROM task_approval_waits w WHERE w.task_id=t.id)," +
        "p.goal_revision,p.planned_revision,p.acceptance_verified"
    private fun Cursor.nullable(index: Int): String? = if(isNull(index)) null else getString(index)
    private fun Cursor.task(): CatalogTask {
        val request=getString(3)
        val role=if(request.startsWith("role:")) request.substringAfter("role:").substringBefore(':').take(80) else "main"
        val raw=nullable(4).orEmpty()
        val initial=raw.substringBefore('\n')
        val goal=if(role!="main") "辅助请求：$role" else {
            val text=if(initial.trimStart().startsWith("[")) runCatching {
                Json.decodeFromString<List<UIMessagePart>>(initial).filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
            }.getOrDefault("").ifBlank { "含附件或长目标的任务；请在关联会话查看完整输入" } else initial
            (text+if('\n' in raw) "\n追加要求："+raw.substringAfter('\n') else "").take(500)
        }
        return CatalogTask(getString(0),getString(1),getString(2),role,goal,getString(5),getString(6),getString(7),getString(8),
            getLong(9),getLong(10),getInt(11)!=0,getString(12),getInt(13)!=0,getInt(14)!=0,getInt(15)!=0,getInt(16)!=0,
            getLong(17),getLong(18),getInt(19)!=0)
    }
    private fun window(db: SQLiteDatabase, table: String, scope: String, args: List<String>,
        key: String, cursor: String?): Pair<Long,Long?> {
        val max=db.rawQuery("SELECT COALESCE(MAX(rowid),0) FROM $table WHERE $scope",args.toTypedArray()).use {
            check(it.moveToFirst());it.getLong(0)
        }
        if(cursor==null) return max to null
        val match=requireNotNull(Regex("catalog1:([a-f0-9]{64}):([0-9]+):([0-9]+)").matchEntire(cursor)) { "Invalid task cursor" }
        require(match.groupValues[1]==AgentTaskStore.hash(key)) { "Task cursor scope changed" }
        val upper=requireNotNull(match.groupValues[2].toLongOrNull());val before=requireNotNull(match.groupValues[3].toLongOrNull())
        require(upper in 1..max && before in 1..upper) { "Invalid task cursor boundary" }
        return upper to before
    }
    fun list(db: SQLiteDatabase, owner: String, kind: String, cursor: String?, limit: Int): CatalogPage {
        require(owner.isNotBlank() && limit in 1..100)
        val filter=when(kind) { "all" -> "";"main" -> " AND request_id NOT LIKE 'role:%'";"auxiliary" -> " AND request_id LIKE 'role:%'";else -> error("Invalid task filter") }
        val key="tasks\u0000$owner\u0000$kind"
        val (upper,before)=window(db,"tasks","owner=?$filter",listOf(owner),key,cursor)
        val rows=mutableListOf<CatalogTask>();val ordinals=mutableListOf<Long>()
        db.rawQuery("SELECT $COLUMNS,t.rowid$JOIN WHERE t.owner=?${filter.replace("request_id","t.request_id")} AND t.rowid<=?"+
            (if(before==null) "" else " AND t.rowid<?")+" ORDER BY t.rowid DESC LIMIT ?",
            (listOf(owner,upper.toString())+listOfNotNull(before?.toString())+(limit+1).toString()).toTypedArray()).use { row ->
            while(row.moveToNext()) {
                ordinals+=row.getLong(20)
                rows+=row.task()
            }
        }
        val next=if(rows.size>limit) "catalog1:${AgentTaskStore.hash(key)}:$upper:${ordinals[limit-1]}" else null
        return CatalogPage(owner,kind,rows.take(limit),next)
    }
    private fun task(db: SQLiteDatabase, owner: String, id: String): CatalogTask? =
        db.rawQuery("SELECT $COLUMNS$JOIN WHERE t.owner=? AND t.id=?",arrayOf(owner,id)).use {
            if(it.moveToFirst()) it.task() else null
        }
    fun detail(db: SQLiteDatabase, owner: String, id: String, cursor: String?, limit: Int): CatalogDetail {
        require(owner.isNotBlank() && limit in 1..100)
        val task=requireNotNull(task(db,owner,id)) { "Task access denied" }
        val key="calls\u0000$owner\u0000$id"
        val (upper,before)=window(db,"calls","task_id=?",listOf(id),key,cursor)
        val steps=mutableListOf<CatalogStep>();var remaining=1_048_576L;var partial=false
        db.rawQuery("SELECT step_id,tool,substr(purpose,1,2000),state,call_id,"+
            "CASE WHEN length(CAST(result_json AS BLOB))<=262144 THEN result_json END,length(CAST(result_json AS BLOB))"+
            " FROM plan_steps WHERE task_id=? ORDER BY ordinal LIMIT 65",arrayOf(id)).use { row ->
            while(row.moveToNext()) {
                if(steps.size==64) { partial=true;break }
                val size=if(row.isNull(6)) 0L else row.getLong(6)
                val eligible=row.getString(3)=="VERIFIED" && size in 1..remaining && size<=262144
                val result=if(eligible) row.nullable(5) else null
                if(size>0 && result==null) partial=true
                if(result!=null) remaining-=size
                steps+=CatalogStep(row.getString(0),row.getString(1),row.getString(2),row.getString(3),row.nullable(4),result)
            }
        }
        val calls=mutableListOf<CatalogCall>();val ordinals=mutableListOf<Long>()
        db.rawQuery("SELECT rowid,call_id,tool,state,input_hash,result_hash,capability,grant_epoch FROM calls WHERE task_id=? AND rowid<=?"+
            (if(before==null) "" else " AND rowid<?")+" ORDER BY rowid DESC LIMIT ?",
            (listOf(id,upper.toString())+listOfNotNull(before?.toString())+(limit+1).toString()).toTypedArray()).use { row ->
            while(row.moveToNext()) {
                ordinals+=row.getLong(0)
                calls+=CatalogCall(row.getString(1),row.getString(2),row.getString(3),row.getString(4),row.nullable(5),row.nullable(6),
                    if(row.isNull(7)) null else row.getLong(7))
            }
        }
        val next=if(calls.size>limit) "catalog1:${AgentTaskStore.hash(key)}:$upper:${ordinals[limit-1]}" else null
        return CatalogDetail(task,steps,calls.take(limit),next,partial)
    }
}
