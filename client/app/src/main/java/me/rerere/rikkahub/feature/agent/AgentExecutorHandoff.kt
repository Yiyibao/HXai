package me.rerere.rikkahub.feature.agent

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext

/** Trusted host lifecycle only. No model/tool/transport can construct a stopped=true receipt. */
internal class SupervisedAgentExecutor private constructor(val lease: AgentLease, private val job: Job) {
    companion object {
        fun bind(lease: AgentLease,job: Job): SupervisedAgentExecutor {
            check(job.isActive && !job.isCompleted) { "Executor must be bound before stopping" }
            return SupervisedAgentExecutor(lease,job)
        }
    }
    class Stopped private constructor(val executor: SupervisedAgentExecutor, val requestId: String) {
        val lease get()=executor.lease
        companion object {
            internal suspend fun stop(executor: SupervisedAgentExecutor, requestId: String): Stopped {
                check(currentCoroutineContext()[Job] !== executor.job) { "Executor cannot join itself" }
                executor.job.cancelAndJoin()
                check(executor.job.isCompleted)
                return Stopped(executor,requestId)
            }
        }
    }
    suspend fun stop(requestId: String): Stopped = Stopped.stop(this,requestId)
}

internal data class ExecutorHandoff(
    val taskId: String, val requestId: String, val sourceExecutor: String, val sourceFence: Long,
    val targetExecutor: String, val grantEpoch: Long, val inputHash: String, val snapshotHash: String,
    val state: String, val targetFence: Long?,
)

/** Journal shares the authoritative native slot and transaction; it is not another scheduler. */
internal object AgentExecutorHandoff {
    fun create(db: SQLiteDatabase) {
        val columns=db.rawQuery("PRAGMA table_info(slot)",null).use { c ->
            buildSet { while(c.moveToNext()) add(c.getString(1)) }
        }
        if("executor_id" !in columns) db.execSQL("ALTER TABLE slot ADD COLUMN executor_id TEXT NOT NULL DEFAULT 'local'")
        db.execSQL("CREATE TABLE IF NOT EXISTS executor_handoffs(task_id TEXT NOT NULL REFERENCES tasks(id),request_id TEXT NOT NULL,source_executor TEXT NOT NULL,source_fence INTEGER NOT NULL,target_executor TEXT NOT NULL,grant_epoch INTEGER NOT NULL,input_hash TEXT NOT NULL,snapshot_hash TEXT NOT NULL,state TEXT NOT NULL CHECK(state IN ('REQUESTED','STOPPED','ACTIVATED','ABORTED')),target_fence INTEGER,PRIMARY KEY(task_id,request_id))")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS one_pending_handoff ON executor_handoffs(task_id) WHERE state IN ('REQUESTED','STOPPED')")
    }
    fun pending(db: SQLiteDatabase,id: String): Boolean = db.rawQuery(
        "SELECT 1 FROM executor_handoffs WHERE task_id=? AND state IN ('REQUESTED','STOPPED')",arrayOf(id)).use { it.moveToFirst() }
    fun read(db: SQLiteDatabase,id: String,requestId: String): ExecutorHandoff? = db.rawQuery(
        "SELECT task_id,request_id,source_executor,source_fence,target_executor,grant_epoch,input_hash,snapshot_hash,state,target_fence FROM executor_handoffs WHERE task_id=? AND request_id=?",arrayOf(id,requestId)).use {
        if(!it.moveToFirst()) null else ExecutorHandoff(it.getString(0),it.getString(1),it.getString(2),it.getLong(3),it.getString(4),it.getLong(5),it.getString(6),it.getString(7),it.getString(8),if(it.isNull(9)) null else it.getLong(9))
    }
    fun inputHash(db: SQLiteDatabase,id: String): String = db.rawQuery(
        "SELECT goal,goal_revision FROM plans WHERE task_id=?",arrayOf(id)).use {
        check(it.moveToFirst());AgentTaskStore.hash("${it.getLong(1)}:${it.getString(0)}")
    }
    fun snapshotHash(task: AgentTask): String = AgentTaskStore.hash(kotlinx.serialization.json.buildJsonArray {
        listOf(task.owner,task.id,task.configurationReference,task.providerId,task.modelId,task.reasoning,task.configurationRevision)
            .forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
    }.toString())
}
