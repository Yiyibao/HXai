package me.rerere.rikkahub.feature.agent

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

internal data class AgentUsageMetric(val reported: Long?, val estimated: Long?, val unknownRequests: Long)
internal data class AgentUsageEntry(
    val requestId: String, val taskId: String, val conversationId: String, val role: String,
    val providerId: String?, val modelId: String?, val reasoning: String?, val createdAt: Long?,
    val state: String, val provenance: String, val inputTokens: Long?, val outputTokens: Long?,
    val cachedTokens: Long?, val totalTokens: Long?,
    val costJson: String? = null,
)
internal data class AgentUsagePage(
    val owner: String, val taskId: String?, val from: Long?, val until: Long?, val requestCount: Long,
    val reportedRequests: Long, val estimatedRequests: Long, val unknownRequests: Long,
    val undatedRequests: Long, val input: AgentUsageMetric, val output: AgentUsageMetric,
    val cached: AgentUsageMetric, val total: AgentUsageMetric, val entries: List<AgentUsageEntry>,
    val nextCursor: String?,
    val costJson: String? = null,
)

/** Read within the store transaction. No credentials, inputs, results or configuration references. */
internal object AgentUsageLedger {
    private val metrics = listOf("prompt_tokens", "completion_tokens", "cached_tokens", "total_tokens")
    private fun Cursor.number(index: Int): Long? = if (isNull(index)) null else getLong(index)
    internal fun summaryColumns(): List<String> = buildList {
        add("COUNT(*)")
        add("SUM(CASE WHEN u.provenance='reported' THEN 1 ELSE 0 END)")
        add("SUM(CASE WHEN u.provenance='estimated' THEN 1 ELSE 0 END)")
        for (metric in metrics) {
            add("SUM(CASE WHEN u.provenance='reported' AND u.$metric>=0 THEN u.$metric END)")
            add("SUM(CASE WHEN u.provenance='estimated' AND u.$metric>=0 THEN u.$metric END)")
            add("SUM(CASE WHEN u.provenance IN ('reported','estimated') AND u.$metric>=0 THEN 0 ELSE 1 END)")
        }
    }
    internal fun readMetrics(row: Cursor, start: Int): List<AgentUsageMetric> = metrics.indices.map { index ->
        val offset = start + index * 3
        AgentUsageMetric(row.number(offset), row.number(offset + 1), row.getLong(offset + 2))
    }

    fun read(db: SQLiteDatabase, owner: String, taskId: String?, from: Long?, until: Long?,
        cursor: String?, limit: Int): AgentUsagePage {
        require(owner.isNotBlank() && limit in 1..100)
        return readPage(db,owner,taskId,from,until,cursor,limit,false)
    }
    internal fun snapshot(db: SQLiteDatabase,owner: String,taskId: String?,from: Long?,until: Long?): AgentUsagePage =
        readPage(db,owner,taskId,from,until,null,AgentUsageSnapshot.MAX_RECORDS,true)
    private fun readPage(db: SQLiteDatabase,owner: String,taskId: String?,from: Long?,until: Long?,
        cursor: String?,limit: Int,snapshot: Boolean): AgentUsagePage {
        require(owner.isNotBlank())
        require((from == null && until == null) || (from != null && until != null && from >= 0 && until > from))
        if (taskId != null) {
            require(db.rawQuery("SELECT 1 FROM tasks WHERE owner=? AND id=?", arrayOf(owner, taskId)).use { it.moveToFirst() }) {
                "Task access denied"
            }
        }
        val scope = "t.owner=?" + if (taskId == null) "" else " AND t.id=?"
        val scopeArgs = listOfNotNull(owner, taskId)
        val join = " FROM usage u JOIN tasks t ON t.id=u.task_id LEFT JOIN calls c ON c.task_id=u.task_id AND c.call_id=u.request_id"
        val maximum = db.rawQuery("SELECT COALESCE(MAX(u.rowid),0)$join WHERE $scope", scopeArgs.toTypedArray()).use {
            check(it.moveToFirst()); it.getLong(0)
        }
        val scopeHash = AgentTaskStore.hash(listOf(owner, taskId, from, until).joinToString("\u0000"))
        var upper = maximum
        var before: Long? = null
        if (cursor != null) {
            val match = requireNotNull(Regex("v1:([a-f0-9]{64}):([0-9]+):([0-9]+)").matchEntire(cursor)) { "Invalid usage cursor" }
            require(match.groupValues[1] == scopeHash) { "Usage cursor scope changed" }
            upper = requireNotNull(match.groupValues[2].toLongOrNull())
            before = requireNotNull(match.groupValues[3].toLongOrNull())
            require(upper in 1..maximum && before in 1..upper) { "Invalid usage cursor boundary" }
        }
        val undated = db.rawQuery("SELECT COUNT(*)$join WHERE $scope AND u.rowid<=? AND u.created_at IS NULL",
            (scopeArgs + upper.toString()).toTypedArray()).use { check(it.moveToFirst()); it.getLong(0) }
        val range = if (from == null) "" else " AND u.created_at>=? AND u.created_at<?"
        val where = "$scope AND u.rowid<=?$range"
        val args = scopeArgs + upper.toString() + if (from == null) emptyList() else listOf(from.toString(), until.toString())
        val columns = summaryColumns()
        val summary = db.rawQuery("SELECT ${columns.joinToString(",")}$join WHERE $where", args.toTypedArray()).use {
            check(it.moveToFirst())
            Triple(it.getLong(0), it.getLong(1), it.getLong(2)) to readMetrics(it, 3)
        }
        val counts = summary.first
        val values = summary.second
        check(!snapshot || counts.first<=AgentUsageSnapshot.MAX_RECORDS) { "导出超过10000条记录，请缩小任务或周期" }
        val entries = mutableListOf<AgentUsageEntry>()
        val ordinals = mutableListOf<Long>()
        val after = if (before == null) "" else " AND u.rowid<?"
        db.rawQuery("SELECT u.rowid,u.request_id,u.task_id,t.conversation_id,u.role,u.provider_id,u.model_id,u.reasoning,u.created_at," +
            "COALESCE(c.state,'UNKNOWN'),u.provenance,u.prompt_tokens,u.completion_tokens,u.cached_tokens,u.total_tokens" +
            "$join WHERE $where$after ORDER BY u.rowid DESC LIMIT ?",
            (args + listOfNotNull(before?.toString()) + (limit + 1).toString()).toTypedArray()).use {
            while (it.moveToNext()) {
                ordinals += it.getLong(0)
                val provenance = it.getString(10).takeIf { value -> value in setOf("reported", "estimated") } ?: "unknown"
                fun tokens(index: Int) = if (provenance == "unknown") null else it.number(index)?.takeIf { n -> n >= 0 }
                entries += AgentUsageEntry(it.getString(1), it.getString(2), it.getString(3), it.getString(4),
                    it.getString(5), it.getString(6), it.getString(7), it.number(8), it.getString(9), provenance,
                    tokens(11), tokens(12), tokens(13), tokens(14),AgentCostLedger.receipt(db,it.getString(1)).toString())
            }
        }
        val next = if (entries.size > limit) "v1:$scopeHash:$upper:${ordinals[limit - 1]}" else null
        return AgentUsagePage(owner, taskId, from, until, counts.first, counts.second, counts.third,
            counts.first - counts.second - counts.third, undated, values[0], values[1], values[2], values[3],
            entries.take(limit), next, AgentCostLedger.summary(db,where,args).toString())
    }
}
