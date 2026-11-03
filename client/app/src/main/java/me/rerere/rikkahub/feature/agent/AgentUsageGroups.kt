package me.rerere.rikkahub.feature.agent

import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class AgentUsageGroup(
    val providerId: String?, val modelId: String?, val role: String?,
    val requestCount: Long, val reportedRequests: Long, val estimatedRequests: Long, val unknownRequests: Long,
    val input: AgentUsageMetric, val output: AgentUsageMetric, val cached: AgentUsageMetric, val total: AgentUsageMetric,
)
internal data class AgentUsageGroupsPage(
    val owner: String, val taskId: String?, val from: Long?, val until: Long?, val groupBy: String,
    val requestCount: Long, val groupCount: Long, val undatedRequests: Long,
    val groups: List<AgentUsageGroup>, val nextCursor: String?,
)

/** Read-only aggregation of physical requests. Identity is never borrowed from task or current settings. */
internal object AgentUsageGroups {
    private fun key(column: String) = "COALESCE(CASE WHEN trim(u.$column)='' THEN NULL ELSE u.$column END,'')"
    private const val FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    fun read(db: SQLiteDatabase, owner: String, taskId: String?, from: Long?, until: Long?,
        groupBy: String, cursor: String?, limit: Int): AgentUsageGroupsPage {
        require(owner.isNotBlank() && limit in 1..100)
        return readPage(db,owner,taskId,from,until,groupBy,cursor,limit,false)
    }
    internal fun snapshot(db: SQLiteDatabase,owner: String,taskId: String?,from: Long?,until: Long?,groupBy: String): AgentUsageGroupsPage =
        readPage(db,owner,taskId,from,until,groupBy,null,AgentUsageSnapshot.MAX_RECORDS,true)
    private fun readPage(db: SQLiteDatabase,owner: String,taskId: String?,from: Long?,until: Long?,groupBy: String,
        cursor: String?,limit: Int,snapshot: Boolean): AgentUsageGroupsPage {
        require(owner.isNotBlank())
        require((from == null && until == null) || (from != null && until != null && from >= 0 && until > from))
        val dimensions = when (groupBy) {
            "provider" -> listOf(key("provider_id"))
            "model" -> listOf(key("provider_id"), key("model_id"))
            "role" -> listOf(key("role"))
            else -> error("Invalid usage grouping")
        }
        if (taskId != null) require(db.rawQuery("SELECT 1 FROM tasks WHERE owner=? AND id=?", arrayOf(owner, taskId)).use { it.moveToFirst() }) {
            "Task access denied"
        }
        val scope = "t.owner=?" + if (taskId == null) "" else " AND t.id=?"
        val scopeArgs = listOfNotNull(owner, taskId)
        val join = " FROM usage u JOIN tasks t ON t.id=u.task_id"
        val maximum = db.rawQuery("SELECT COALESCE(MAX(u.rowid),0)$join WHERE $scope", scopeArgs.toTypedArray()).use {
            check(it.moveToFirst()); it.getLong(0)
        }
        val scopeHash = AgentTaskStore.hash(Json.encodeToString(listOf(owner, taskId, from?.toString(), until?.toString(), groupBy)))
        var upper = maximum
        var last: List<String>? = null
        if (cursor != null) {
            require(cursor.length <= 128_000) { "Usage group cursor too large" }
            val match = requireNotNull(Regex("g1:([a-f0-9]{64}):([0-9]+):([A-Za-z0-9_-]+)").matchEntire(cursor)) { "Invalid usage group cursor" }
            require(match.groupValues[1] == scopeHash) { "Usage group cursor scope changed" }
            upper = requireNotNull(match.groupValues[2].toLongOrNull())
            require(upper in 1..maximum) { "Invalid usage group boundary" }
            last = Json.decodeFromString<List<String>>(Base64.decode(match.groupValues[3], FLAGS).toString(Charsets.UTF_8))
            require(last.size == dimensions.size && last.all { it.length <= 4096 }) { "Invalid usage group identity" }
        }
        val undated = db.rawQuery("SELECT COUNT(*)$join WHERE $scope AND u.rowid<=? AND u.created_at IS NULL",
            (scopeArgs + upper.toString()).toTypedArray()).use { check(it.moveToFirst()); it.getLong(0) }
        val range = if (from == null) "" else " AND u.created_at>=? AND u.created_at<?"
        val args = scopeArgs + upper.toString() + if (from == null) emptyList() else listOf(from.toString(), until.toString())
        val columns = dimensions.mapIndexed { index, expression -> "$expression AS k$index" } +
            AgentUsageLedger.summaryColumns().mapIndexed { index, expression -> "$expression AS c$index" }
        val aggregate = "SELECT ${columns.joinToString(",")}$join WHERE $scope AND u.rowid<=?$range GROUP BY ${dimensions.joinToString(",") }"
        val totals = db.rawQuery("SELECT COUNT(*),COALESCE(SUM(c0),0) FROM ($aggregate)", args.toTypedArray()).use {
            check(it.moveToFirst()); it.getLong(0) to it.getLong(1)
        }
        check(!snapshot || totals.first<=AgentUsageSnapshot.MAX_RECORDS) { "导出超过10000个分组，请缩小任务或周期" }
        if (last != null) {
            val match = dimensions.indices.joinToString(" AND ") { "k$it COLLATE BINARY=?" }
            require(db.rawQuery("SELECT 1 FROM ($aggregate) WHERE $match LIMIT 1", (args + last).toTypedArray()).use { it.moveToFirst() }) {
                "Usage group cursor identity missing; refresh required"
            }
        }
        val after = if (last == null) "" else if (dimensions.size == 1) " WHERE k0 COLLATE BINARY>?" else
            " WHERE k0 COLLATE BINARY>? OR (k0 COLLATE BINARY=? AND k1 COLLATE BINARY>?)"
        val position = if (last == null) emptyList() else if (dimensions.size == 1) last else listOf(last[0], last[0], last[1])
        val ordering = dimensions.indices.joinToString(",") { "k$it COLLATE BINARY" }
        val groups = mutableListOf<AgentUsageGroup>()
        val keys = mutableListOf<List<String>>()
        db.rawQuery("SELECT * FROM ($aggregate)$after ORDER BY $ordering LIMIT ?", (args + position + (limit + 1).toString()).toTypedArray()).use { row ->
            while (row.moveToNext()) {
                val identity = dimensions.indices.map { row.getString(it) }
                require(identity.all { it.length <= 4096 }) { "Usage group identity too large" }
                val offset = dimensions.size
                val count = row.getLong(offset); val reported = row.getLong(offset + 1); val estimated = row.getLong(offset + 2)
                val values = AgentUsageLedger.readMetrics(row, offset + 3)
                keys += identity
                groups += AgentUsageGroup(
                    if (groupBy != "role") identity[0].ifEmpty { null } else null,
                    if (groupBy == "model") identity[1].ifEmpty { null } else null,
                    if (groupBy == "role") identity[0].ifEmpty { null } else null,
                    count, reported, estimated, count - reported - estimated, values[0], values[1], values[2], values[3])
            }
        }
        val next = if (groups.size > limit) "g1:$scopeHash:$upper:" +
            Base64.encodeToString(Json.encodeToString(keys[limit - 1]).toByteArray(Charsets.UTF_8), FLAGS) else null
        return AgentUsageGroupsPage(owner, taskId, from, until, groupBy, totals.second, totals.first, undated, groups.take(limit), next)
    }
}
