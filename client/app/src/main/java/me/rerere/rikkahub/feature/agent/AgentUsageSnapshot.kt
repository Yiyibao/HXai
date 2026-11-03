package me.rerere.rikkahub.feature.agent

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** All reads are inside the existing Store transaction. No model, credential or operation state is consulted. */
internal object AgentUsageSnapshot {
    const val MAX_RECORDS=10_000
    const val MAX_BYTES=8*1024*1024
    const val MAX_TEXT=4096
    private fun number(value: Long?): JsonElement = value?.let { JsonPrimitive(it.toString()) } ?: JsonNull
    private fun text(value: String?): JsonElement {
        require(value==null || value.length<=MAX_TEXT) { "导出身份字段超过4096字符" }
        return value?.let(::JsonPrimitive) ?: JsonNull
    }
    private fun metric(value: AgentUsageMetric)=buildJsonObject {
        put("reported",number(value.reported));put("estimated",number(value.estimated));put("unknownRequests",number(value.unknownRequests))
    }
    private fun metrics(input: AgentUsageMetric,output: AgentUsageMetric,cached: AgentUsageMetric,total: AgentUsageMetric)=buildJsonObject {
        put("input",metric(input));put("output",metric(output));put("cached",metric(cached));put("total",metric(total))
    }
    fun capture(db: SQLiteDatabase,owner: String,taskId: String?,from: Long?,until: Long?,groupBy: String,requestId: String): JsonObject {
        require(groupBy in setOf("requests","provider","model","role"))
        require(owner.isNotBlank() && ((from==null && until==null) || (from!=null && until!=null && from>=0 && until>from)))
        if(taskId!=null) require(db.rawQuery("SELECT 1 FROM tasks WHERE owner=? AND id=?",arrayOf(owner,taskId)).use { it.moveToFirst() }) { "Task access denied" }
        val scope="t.owner=?"+(if(taskId==null) "" else " AND t.id=?")+(if(from==null) "" else " AND u.created_at>=? AND u.created_at<?")
        val args=listOfNotNull(owner,taskId)+if(from==null) emptyList() else listOf(from.toString(),until.toString())
        val fields=if(groupBy=="requests") listOf("u.request_id","u.task_id","t.conversation_id","u.role","u.provider_id","u.model_id","u.reasoning","u.provenance")
            else if(groupBy=="role") listOf("u.role") else if(groupBy=="provider") listOf("u.provider_id") else listOf("u.provider_id","u.model_id")
        val longest=fields.joinToString(",") { "COALESCE(length($it),0)" }
        val bytes=fields.joinToString("+") { "COALESCE(length(CAST($it AS BLOB)),0)" }
        db.rawQuery("SELECT COALESCE(MAX(MAX(0,$longest)),0),COALESCE(SUM($bytes),0) FROM usage u JOIN tasks t ON t.id=u.task_id WHERE $scope",args.toTypedArray()).use {
            check(it.moveToFirst());check(it.getLong(0)<=MAX_TEXT) { "导出身份字段超过4096字符" }
            if(groupBy=="requests") check(it.getLong(1)<=MAX_BYTES) { "导出文件超过8MiB，请缩小任务或周期" }
        }
        val records=mutableListOf<JsonObject>()
        val summary: JsonObject
        if(groupBy=="requests") {
            val page=AgentUsageLedger.snapshot(db,owner,taskId,from,until)
            check(page.nextCursor==null && page.entries.size.toLong()==page.requestCount)
            summary=buildJsonObject {
                put("requestCount",number(page.requestCount));put("groupCount",JsonNull);put("undatedRequests",number(page.undatedRequests))
                put("reportedRequests",number(page.reportedRequests));put("estimatedRequests",number(page.estimatedRequests));put("unknownRequests",number(page.unknownRequests))
                put("tokens",metrics(page.input,page.output,page.cached,page.total))
            }
            for(row in page.entries) records+=buildJsonObject {
                put("requestId",text(row.requestId));put("taskId",text(row.taskId));put("conversationId",text(row.conversationId))
                put("role",text(row.role));put("providerId",text(row.providerId));put("modelId",text(row.modelId));put("reasoning",text(row.reasoning))
                put("state",text(row.state));put("provenance",text(row.provenance));put("createdAtMs",number(row.createdAt))
                put("tokens",buildJsonObject { put("input",number(row.inputTokens));put("output",number(row.outputTokens));put("cached",number(row.cachedTokens));put("total",number(row.totalTokens)) })
            }
        } else {
            val page=AgentUsageGroups.snapshot(db,owner,taskId,from,until,groupBy)
            check(page.nextCursor==null && page.groups.size.toLong()==page.groupCount)
            summary=buildJsonObject { put("requestCount",number(page.requestCount));put("groupCount",number(page.groupCount));put("undatedRequests",number(page.undatedRequests)) }
            for(row in page.groups) records+=buildJsonObject {
                put("providerId",text(row.providerId));put("modelId",text(row.modelId));put("role",text(row.role));put("requestCount",number(row.requestCount))
                put("reportedRequests",number(row.reportedRequests));put("estimatedRequests",number(row.estimatedRequests));put("unknownRequests",number(row.unknownRequests))
                put("tokens",metrics(row.input,row.output,row.cached,row.total))
            }
        }
        return buildJsonObject {
            put("schemaVersion",1);put("kind","hxai_usage_snapshot");put("integerEncoding","decimal_strings")
            put("owner",text(owner));put("taskId",text(taskId));put("requestId",text(requestId));put("fromMs",number(from));put("untilMs",number(until))
            put("groupBy",groupBy);put("capturedAtMs",number(System.currentTimeMillis()));put("summary",summary);put("recordCount",number(records.size.toLong()))
            put("cost",buildJsonObject { put("status","unknown");put("amount",JsonNull);put("currency",JsonNull) });put("records",JsonArray(records))
        }
    }
    fun digest(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun readBounded(input: java.io.InputStream): ByteArray {
        val result=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(result.size()<=MAX_BYTES) {
            val count=input.read(buffer,0,minOf(buffer.size,MAX_BYTES+1-result.size()))
            if(count<0) break
            result.write(buffer,0,count)
        }
        return result.toByteArray()
    }
    fun utf8(value: String): ByteArray {
        val encoded=Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value))
        return ByteArray(encoded.remaining()).also { encoded.get(it) }
    }
    fun encode(data: JsonObject): ByteArray {
        val source=digest(utf8(data.toString()))
        val root=buildJsonObject { put("sourceSha256",source);put("data",data) }
        val bytes=utf8(Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),root)+"\n")
        check(bytes.size<=MAX_BYTES) { "导出文件超过8MiB，请缩小任务或周期" };return bytes
    }
    fun decode(bytes: ByteArray): JsonObject {
        require(bytes.size in 1..MAX_BYTES)
        val decoded=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val root=Json.parseToJsonElement(decoded).jsonObject
        require(root.keys==setOf("sourceSha256","data"))
        val data=root.getValue("data").jsonObject
        require(data.getValue("schemaVersion").jsonPrimitive.int==1 && data.getValue("kind").jsonPrimitive.content=="hxai_usage_snapshot")
        require(root.getValue("sourceSha256").jsonPrimitive.content==digest(utf8(data.toString()))) { "导出来源摘要不一致" }
        require(data.getValue("records").jsonArray.size.toLong()==data.getValue("recordCount").jsonPrimitive.content.toLong())
        return root
    }
}
