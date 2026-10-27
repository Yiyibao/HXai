package me.rerere.rikkahub.feature.agent

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.rikkahub.feature.material.MaterialFile
import me.rerere.rikkahub.feature.material.MaterialFiles
import java.io.File
import java.util.UUID

internal data class AgentUsageExportReceipt(val owner: String,val taskId: String?,val from: Long?,val until: Long?,
    val groupBy: String,val requestId: String,val artifactId: String,val sha256: String,val size: Long,
    val sourceSha256: String,val capturedAt: Long,val requestCount: Long,val recordCount: Long,val reused: Boolean)

/** Explicit native user file action, not an Agent executor. Existing library metadata is the durable intent. */
internal object AgentUsageExport {
    private val mutex=Mutex()
    fun artifactId(owner: String,requestId: String)=UUID.nameUUIDFromBytes(AgentUsageSnapshot.utf8("usage-export-v1:$owner:$requestId")).toString()
    private fun requestKey(owner: String,taskId: String?,from: Long?,until: Long?,groupBy: String,requestId: String)=
        "usage-export-v1:"+AgentTaskStore.hash(Json.encodeToString(listOf(owner,taskId,from?.toString(),until?.toString(),groupBy,requestId)))
    suspend fun export(files: MaterialFiles,owner: String,taskId: String?,from: Long?,until: Long?,groupBy: String,requestId: String,
        capture: () -> JsonObject,checkAccess: () -> Unit,checkpoint: suspend (String,MaterialFile) -> Unit = { _,_ -> },history: AgentUsageExportHistory? = null): AgentUsageExportReceipt = mutex.withLock {
        require(MaterialFiles.validProfile(owner) && UUID.fromString(requestId).toString()==requestId)
        require(groupBy in setOf("requests","provider","model","role"))
        require((from==null && until==null) || (from!=null && until!=null && from>=0 && until>from))
        currentCoroutineContext().ensureActive();checkAccess()
        val id=artifactId(owner,requestId);val key=requestKey(owner,taskId,from,until,groupBy,requestId)
        val old=files.list(owner).singleOrNull { it.id==id }
        if(old!=null) {
            val result=verify(files,owner,taskId,from,until,groupBy,requestId,old,true,checkAccess)
            checkAccess();history?.record(intent(owner,taskId,from,until,groupBy,requestId,old))
            return@withLock result
        }
        val probe=MaterialFile(id,owner,"请求用量快照.json","json",output=true)
        val target=files.file(probe)
        require(!target.exists() && !File(target.parentFile,"$id.meta.json").exists() && !File(target.parentFile,"$id.part").exists()) {
            "导出目标存在未知结果；原文件保留，请明确生成新快照"
        }
        val bytes=AgentUsageSnapshot.encode(capture());currentCoroutineContext().ensureActive();checkAccess()
        val item=probe.copy(name="请求用量-${groupBy}-${requestId.take(8)}.json",requestKey=key,sha256=AgentUsageSnapshot.digest(bytes),size=bytes.size.toLong(),
            detail="请求用量快照待写入；预期摘要已保存")
        checkAccess();history?.record(intent(owner,taskId,from,until,groupBy,requestId,item))
        checkpoint("after_history",item)
        currentCoroutineContext().ensureActive();checkAccess()
        files.record(item);checkpoint("after_intent",item)
        val temp=File(target.parentFile,"$id.part")
        files.record(item.copy(state="RUNNING",detail="正在写入用量快照；等待独立核验"))
        try {
            checkAccess();currentCoroutineContext().ensureActive()
            java.io.FileOutputStream(temp).use { output ->
                var offset=0
                while(offset<bytes.size) {
                    currentCoroutineContext().ensureActive();checkAccess()
                    val length=minOf(8192,bytes.size-offset);output.write(bytes,offset,length);offset+=length
                    if(offset==length) { output.flush();checkpoint("after_partial",item) }
                }
                output.fd.sync()
            }
            val actual=temp.inputStream().use(AgentUsageSnapshot::readBounded)
            check(actual.contentEquals(bytes) && AgentUsageSnapshot.decode(actual)==AgentUsageSnapshot.decode(bytes)) { "导出临时文件回读不匹配" }
            currentCoroutineContext().ensureActive();checkAccess();checkpoint("before_commit",item)
            require(!target.exists()) { "导出目标已出现；原文件保留" }
            java.nio.file.Files.move(temp.toPath(),target.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            checkpoint("after_move",item)
            val receipt=verify(files,owner,taskId,from,until,groupBy,requestId,item,false,checkAccess)
            checkpoint("after_receipt",files.get(owner,id))
            receipt
        } catch(e: kotlinx.coroutines.CancellationException) {
            files.record(item.copy(state="CANCELLED",detail="导出已中断；重试前必须核验原文件"));throw e
        } catch(e: Exception) {
            files.record(item.copy(state="FAILED",detail="导出未交付；原文件保留，重试先核验"));throw e
        } finally {
            temp.delete()
        }
    }
    private fun intent(owner: String,task: String?,from: Long?,until: Long?,group: String,request: String,item: MaterialFile)=
        AgentUsageExportIntent(owner=owner,taskId=task,from=from,until=until,groupBy=group,requestId=request,
            artifactId=item.id,sha256=item.sha256,size=item.size,createdAt=item.createdAt)

    /** No capture callback, target creation or fallback write is reachable from this entry. */
    suspend fun verifyExisting(files: MaterialFiles,intent: AgentUsageExportIntent,checkAccess: () -> Unit): AgentUsageExportReceipt = mutex.withLock {
        currentCoroutineContext().ensureActive();checkAccess()
        val item=files.get(intent.owner,intent.artifactId)
        require(item.sha256==intent.sha256 && item.size==intent.size && item.createdAt==intent.createdAt) { "原导出意图与材料已不一致" }
        verify(files,intent.owner,intent.taskId,intent.from,intent.until,intent.groupBy,intent.requestId,item,true,checkAccess)
    }
    private suspend fun verify(files: MaterialFiles,owner: String,taskId: String?,from: Long?,until: Long?,groupBy: String,
        requestId: String,item: MaterialFile,reused: Boolean,checkAccess: () -> Unit): AgentUsageExportReceipt {
        val id=artifactId(owner,requestId);val key=requestKey(owner,taskId,from,until,groupBy,requestId)
        currentCoroutineContext().ensureActive();checkAccess()
        require(item.id==id && item.profileId==owner && item.output && item.extension=="json" && item.requestKey==key) { "导出请求范围已改变" }
        require(item.size in 1..AgentUsageSnapshot.MAX_BYTES.toLong() && item.sha256.matches(Regex("[a-f0-9]{64}"))) { "导出意图缺少可信摘要" }
        val target=files.file(item)
        require(java.nio.file.Files.isRegularFile(target.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS) && target.length()==item.size) { "导出已中断或文件缺失；请明确生成新快照" }
        val bytes=target.inputStream().use(AgentUsageSnapshot::readBounded)
        require(bytes.size.toLong()==item.size && AgentUsageSnapshot.digest(bytes)==item.sha256) { "导出文件摘要不匹配；原文件保留" }
        val root=AgentUsageSnapshot.decode(bytes);val data=root.getValue("data").jsonObject
        fun matches(name: String,value: String?)=data.getValue(name)==(value?.let(::JsonPrimitive) ?: JsonNull)
        require(matches("owner",owner) && matches("taskId",taskId) && matches("fromMs",from?.toString()) && matches("untilMs",until?.toString()) &&
            matches("groupBy",groupBy) && matches("requestId",requestId)) { "导出内容不属于原请求范围" }
        val records=data.getValue("recordCount").jsonPrimitive.content.toLong()
        val summary=data.getValue("summary").jsonObject;val count=summary.getValue("requestCount").jsonPrimitive.content.toLong()
        require(records in 0..AgentUsageSnapshot.MAX_RECORDS.toLong() && count>=0)
        require(records==(if(groupBy=="requests") count else summary.getValue("groupCount").jsonPrimitive.content.toLong())) { "导出范围不完整" }
        if(data["costSchemaVersion"]==null) require(data.getValue("cost").jsonObject==buildJsonObject { put("status","unknown");put("amount",JsonNull);put("currency",JsonNull) })
        else require(data["costSchemaVersion"]==JsonPrimitive(1) && data.getValue("cost").jsonObject["requestCount"]==JsonPrimitive(count.toString()))
        currentCoroutineContext().ensureActive();checkAccess()
        val current=files.get(owner,id)
        require(current.profileId==item.profileId && current.requestKey==key && current.sha256==item.sha256 && current.size==item.size && current.sourceId==null)
        if(current.state!="SUCCEEDED") {
            // Known local effect read back against the original durable expected bytes. Never rewrite it.
            require(current.state in setOf("PENDING","RUNNING","FAILED","CANCELLED"))
            files.record(current.copy(state="SUCCEEDED",detail="请求用量与费用来源JSON快照；已独立回读核验"))
        }
        checkAccess()
        return AgentUsageExportReceipt(owner,taskId,from,until,groupBy,requestId,id,item.sha256,item.size,
            root.getValue("sourceSha256").jsonPrimitive.content,data.getValue("capturedAtMs").jsonPrimitive.content.toLong(),count,records,reused)
    }

}
