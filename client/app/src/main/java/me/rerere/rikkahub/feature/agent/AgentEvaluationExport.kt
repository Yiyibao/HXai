package me.rerere.rikkahub.feature.agent

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import me.rerere.rikkahub.feature.material.MaterialFile
import me.rerere.rikkahub.feature.material.MaterialFiles
import java.util.UUID

/** Library metadata is the immutable expected-byte intent. A lost ACK never captures again. */
internal object AgentEvaluationExport {
    private val mutex = Mutex()
    fun artifactId(owner: String, request: String) = UUID.nameUUIDFromBytes("evaluation-v1:$owner:$request".toByteArray(Charsets.UTF_8)).toString()
    private fun requestKey(owner: String,taskId: String,request: String,revision: Long,fence: Long) =
        "evaluation-v1:"+buildJsonArray { add(owner);add(taskId);add(request);add(revision.toString());add(fence.toString()) }
    /** Read-only discovery from the existing durable library intent, including interrupted writes. */
    fun last(files: MaterialFiles,owner: String,taskId: String): Map<String,Any>? = files.list(owner)
        .filter { it.output && it.extension=="json" && it.requestKey.startsWith("evaluation-v1:[") }
        .mapNotNull { item -> runCatching {
            val fields=Json.parseToJsonElement(item.requestKey.removePrefix("evaluation-v1:")).jsonArray.map { it.jsonPrimitive.content }
            require(fields.size==5 && fields[0]==owner && fields[1]==taskId)
            val request=fields[2];val revision=fields[3].toLong();val fence=fields[4].toLong()
            require(UUID.fromString(request).toString()==request && revision>=0 && fence>=0 && item.id==artifactId(owner,request)
                && item.requestKey==requestKey(owner,taskId,request,revision,fence))
            item to mapOf<String,Any>("owner" to owner,"taskId" to taskId,"requestId" to request,"revision" to revision.toString(),
                "fence" to fence.toString(),"artifactId" to item.id,"state" to item.state)
        }.getOrNull() }.maxWithOrNull(compareBy<Pair<MaterialFile,Map<String,Any>>> { it.first.createdAt }.thenBy { it.first.id })?.second
    suspend fun export(files: MaterialFiles, owner: String, taskId: String, request: String, revision: Long, fence: Long,
        capture: suspend () -> JsonObject, allowed: () -> Unit,
        checkpoint: suspend (String) -> Unit = {}): MaterialFile = mutex.withLock {
        require(MaterialFiles.validProfile(owner) && UUID.fromString(request).toString()==request && revision>=0 && fence>=0)
        val id=artifactId(owner,request)
        val key=requestKey(owner,taskId,request,revision,fence)
        currentCoroutineContext().ensureActive();allowed()
        fun verify(item: MaterialFile): MaterialFile {
            allowed()
            require(item.profileId==owner && item.id==id && item.requestKey==key && item.output && item.extension=="json") { "导出请求范围已变化" }
            require(item.size in 1..AgentEvaluationSnapshot.MAX_BYTES.toLong()) { "原导出未完整写入，请明确创建新快照" }
            val target=files.file(item)
            require(java.nio.file.Files.isRegularFile(target.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS) && target.length()==item.size && MaterialFiles.digest(target)==item.sha256) { "原快照缺失或损坏，未重新捕获" }
            val root=Json.parseToJsonElement(target.readText(Charsets.UTF_8)).jsonObject
            require(root["contractVersion"]==JsonPrimitive(1) && root["kind"]==JsonPrimitive("hxai_agent_evaluation_snapshot"))
            val task=root.getValue("evidence").jsonObject.getValue("task").jsonObject
            require(task["owner"]==JsonPrimitive(owner) && task["id"]==JsonPrimitive(taskId) && task["revision"]==JsonPrimitive(revision.toString()) && task["fence"]==JsonPrimitive(fence.toString()))
            allowed()
            val current=files.get(owner,id)
            require(current.sha256==item.sha256 && current.size==item.size && current.requestKey==key)
            return current.copy(state="SUCCEEDED",detail="原Chat公开回答、任务配置与回执引用快照；已独立回读，数学质量未评估").also(files::record)
        }
        val existing=files.list(owner).singleOrNull { it.id==id }
        if(existing!=null) return@withLock verify(existing)
        // get/list excludes tombstones; do not create an intent over a deleted export or an orphan file.
        val probe=MaterialFile(id,owner,"任务评测-${request.take(8)}.json","json",output=true,requestKey=key)
        require(!files.file(probe).exists() && files.trash(owner).none { it.id==id }) { "原快照已存在或被删除，未覆盖" }
        val value=capture()
        val bytes=AgentUsageSnapshot.utf8(Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),value)+"\n")
        require(bytes.size in 1..AgentEvaluationSnapshot.MAX_BYTES) { "完整快照过大，未截断" }
        currentCoroutineContext().ensureActive();allowed()
        val item=probe.copy(size=bytes.size.toLong(),sha256=AgentUsageSnapshot.digest(bytes))
        files.record(item);checkpoint("after_intent")
        val target=files.file(item)
        val temp=java.io.File(target.parentFile,"$id.part")
        require(!temp.exists()) { "快照暂存存在未知结果，未覆盖" }
        try {
            currentCoroutineContext().ensureActive();allowed()
            checkpoint("before_write")
            val coroutine=currentCoroutineContext()
            // MaterialFiles.recover uses the same monitor. Keep the bounded local
            // write/rename free of suspension so a concurrent repository constructor
            // cannot delete this live .part or mark the write interrupted.
            files.withSourceMutationLock {
                coroutine.ensureActive();allowed()
                require(files.get(owner,id).state=="PENDING" && !target.exists() && !temp.exists()) { "快照状态变化，未覆盖原件" }
                files.record(item.copy(state="RUNNING",detail="正在写入评测快照；等待独立核验"))
                java.nio.file.Files.newOutputStream(temp.toPath(),java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE).use { it.write(bytes) }
                java.io.FileOutputStream(temp,true).use { it.fd.sync() }
                require(temp.readBytes().contentEquals(bytes)) { "临时快照回读不匹配" }
                coroutine.ensureActive()
                allowed()
                java.nio.file.Files.move(temp.toPath(),target.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            }
            checkpoint("after_move")
            currentCoroutineContext().ensureActive();allowed()
            verify(item)
        } catch(error: Exception) {
            val state=if(error is kotlinx.coroutines.CancellationException) "CANCELLED" else "FAILED"
            runCatching { val current=files.get(owner,id);if(current.state!="SUCCEEDED") files.record(current.copy(state=state,detail="快照未交付；重试先核验原文件")) }
            throw error
        } finally { temp.delete() }
    }
}
