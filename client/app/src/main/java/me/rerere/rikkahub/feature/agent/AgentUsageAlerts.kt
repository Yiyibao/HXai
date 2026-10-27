package me.rerere.rikkahub.feature.agent

import android.database.sqlite.SQLiteDatabase

internal data class AgentUsageAlerts(
    val owner: String, val taskId: String?, val from: Long?, val until: Long?, val evaluatedAt: Long,
    val uncertainRequests: Long, val unreportedCompletedRequests: Long,
    val denseTaskCount: Long, val denseRequests: Long, val undatedRequests: Long,
)

/** A read-only advisory projection. Neither an invoice nor a scheduler decision. */
internal object AgentUsageAlertReader {
    const val WINDOW_MILLIS = 300_000L
    const val DENSE_REQUESTS = 20

    fun read(db: SQLiteDatabase, owner: String, taskId: String?, from: Long?, until: Long?,
        now: Long): AgentUsageAlerts {
        require(owner.isNotBlank() && now >= 0)
        require((from == null && until == null) || (from != null && until != null && from >= 0 && until > from))
        if (taskId != null) require(db.rawQuery("SELECT 1 FROM tasks WHERE owner=? AND id=?",
            arrayOf(owner, taskId)).use { it.moveToFirst() }) { "Task access denied" }
        val scope = "t.owner=?" + if (taskId == null) "" else " AND t.id=?"
        val scopeArgs = listOfNotNull(owner, taskId)
        val join = " FROM usage u JOIN tasks t ON t.id=u.task_id LEFT JOIN calls c ON c.task_id=u.task_id AND c.call_id=u.request_id"
        val range = if (from == null) "" else " AND u.created_at>=? AND u.created_at<?"
        val args = scopeArgs + if (from == null) emptyList() else listOf(from.toString(), until.toString())
        val counts = db.rawQuery("SELECT " +
            "COALESCE(SUM(CASE WHEN c.state IS NULL OR c.state='UNKNOWN' THEN 1 ELSE 0 END),0)," +
            "COALESCE(SUM(CASE WHEN c.state='VERIFIED' AND COALESCE(u.provenance,'unknown') NOT IN ('reported','estimated') THEN 1 ELSE 0 END),0)" +
            "$join WHERE $scope$range", args.toTypedArray()).use {
            check(it.moveToFirst()); it.getLong(0) to it.getLong(1)
        }
        val dense = db.rawQuery("SELECT COUNT(*),COALESCE(SUM(n),0) FROM (SELECT COUNT(*) n" +
            "$join WHERE $scope$range AND u.created_at>=? AND u.created_at<=? GROUP BY u.task_id HAVING COUNT(*)>=CAST(? AS INTEGER))",
            (args + listOf(maxOf(0, now - WINDOW_MILLIS).toString(), now.toString(), DENSE_REQUESTS.toString())).toTypedArray()).use {
            check(it.moveToFirst()); it.getLong(0) to it.getLong(1)
        }
        val undated = db.rawQuery("SELECT COUNT(*)$join WHERE $scope AND u.created_at IS NULL",
            scopeArgs.toTypedArray()).use { check(it.moveToFirst()); it.getLong(0) }
        return AgentUsageAlerts(owner, taskId, from, until, now, counts.first, counts.second,
            dense.first, dense.second, undated)
    }
}
