package me.rerere.rikkahub.feature.agent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.datastore.CredentialVault
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.Proxy
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A durable pin to ONE network authority. This file is a journal/mapping, not a scheduler.
 * Once configured, an unavailable authority never enables a second local execution slot.
 */
internal class CloudWorkspace private constructor(private val context: Context) {
    private val file=AtomicFile(File(context.noBackupFilesDir,"cloud-workspace-v1.json"))
    private val vault=CredentialVault(context)
    private val http=OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false)
        .followSslRedirects(false).retryOnConnectionFailure(false).callTimeout(120,TimeUnit.SECONDS).build()
    private val processInstance=UUID.randomUUID().toString()
    private val activeAdmissions=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val json=Json { ignoreUnknownKeys=false }
    private val keyAlias="hxai-cloud-stop-v1"

    @Synchronized internal fun read(): JsonObject {
        if(!file.baseFile.exists()) return buildJsonObject { put("version",1);put("enabled",false);put("leases",JsonObject(emptyMap()));put("delegations",JsonObject(emptyMap())) }
        return json.parseToJsonElement(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) }).jsonObject.also {
            require(it.getValue("version").jsonPrimitive.int==1) { "Unknown cloud workspace version; data preserved" }
        }
    }
    @Synchronized internal fun write(value: JsonObject) {
        val stream=file.startWrite()
        try { stream.write(value.toString().toByteArray());file.finishWrite(stream) }
        catch(error: Throwable) { file.failWrite(stream);throw error }
    }
    internal fun configured()=read()["enabled"]?.jsonPrimitive?.boolean==true
    internal fun state(): Map<String,Any?> {
        val value=read()
        return mapOf("enabled" to configured(),"endpoint" to value["endpoint"]?.jsonPrimitive?.content,
            "actor" to value["actor"]?.jsonPrimitive?.content,"owners" to value["owners"]?.jsonArray?.map { it.jsonPrimitive.content },
            "pendingAdmissions" to value.getValue("leases").jsonObject.size,
            "delegations" to value.getValue("delegations").jsonObject.keys.toList())
    }
    private fun publicKey(): String {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if(!store.containsAlias(keyAlias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA,"AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(keyAlias,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1).build())
            }.generateKeyPair()
        }
        val encoded=store.getCertificate(keyAlias).publicKey.encoded
        return "-----BEGIN PUBLIC KEY-----\n"+Base64.getMimeEncoder(64,byteArrayOf(10)).encodeToString(encoded)+"\n-----END PUBLIC KEY-----\n"
    }
    internal fun sign(value: JsonObject): String {
        publicKey()
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val signature=Signature.getInstance("SHA256withRSA")
        signature.initSign(store.getKey(keyAlias,null) as java.security.PrivateKey)
        signature.update(canonical(value).toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(signature.sign())
    }
    internal fun signed(value: JsonObject)=JsonObject(value+ ("signature" to JsonPrimitive(sign(value))))
    private fun endpoint(value: String): String {
        val uri=java.net.URI(value)
        require(uri.userInfo==null && uri.query==null && uri.fragment==null && uri.host!=null && uri.path.orEmpty().trim('/').isEmpty())
        require(uri.scheme=="https" || BuildConfig.SYNTHETIC_MCP && uri.scheme=="http" && uri.host in setOf("127.0.0.1","::1","localhost")) {
            "Cloud authority requires HTTPS; HTTP is only available in the isolated loopback QA variant"
        }
        return value.trimEnd('/')
    }
    private fun send(address: String,token: String,path: String,value: JsonObject): JsonObject {
        val request=Request.Builder().url(endpoint(address)+path).header("Authorization","Bearer $token")
            .post(value.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            if(!response.isSuccessful)throw CloudRejected(response.code)
            val body=requireNotNull(response.body)
            require(body.contentLength()<=2_097_152)
            val source=body.source()
            check(!source.request(2_097_153) && source.buffer.size<=2_097_152) { "Cloud response exceeds the safety limit" }
            val bytes=source.buffer.readByteArray()
            return json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        }
    }
    internal fun rpc(operation: String,value: JsonObject,command: String=UUID.randomUUID().toString()): JsonObject {
        val configuration=read();check(configuration.getValue("enabled").jsonPrimitive.boolean)
        return transmit(configuration,operation,value,command)
    }
    private fun transmit(configuration: JsonObject,operation: String,value: JsonObject,command: String): JsonObject {
        val address=configuration.getValue("endpoint").jsonPrimitive.content
        val reference=configuration.getValue("tokenReference").jsonPrimitive.content
        check(CredentialVault.isReference(reference))
        val token=vault.resolve(reference)
        return send(address,token,"/v1/rpc",buildJsonObject {
            put("protocolVersion",1);put("operation",operation);put("commandId",command);put("value",value)
        })
    }
    internal fun authorityBinding(): JsonObject {
        val current=read();check(current.getValue("enabled").jsonPrimitive.boolean)
        return JsonObject(current.filterKeys { it in setOf("endpoint","actor","domain","tokenReference") })
    }
    internal fun activeFor(record: JsonObject): Boolean {
        val current=read();val bound=record.getValue("authority").jsonObject
        return current.getValue("enabled").jsonPrimitive.boolean &&
            current["endpoint"]==bound["endpoint"] && current["actor"]==bound["actor"] && current["domain"]==bound["domain"]
    }
    internal fun rpcFor(record: JsonObject,operation: String,value: JsonObject,
        command: String=UUID.randomUUID().toString()): JsonObject {
        if(activeFor(record))return rpc(operation,value,command)
        val reads=operation in setOf("task.read","device.pending","artifact.read","device.finish","device.abort","device.recover")
        val safety=operation=="task.control" && value["action"]?.jsonPrimitive?.content in setOf("pause","cancel","revoke")
        check(reads || safety) { "此任务属于已退役权威；只允许读回或安全停止，不能在另一个执行域恢复" }
        return transmit(record.getValue("authority").jsonObject,operation,value,command)
    }
    internal fun connect(address: String,token: String,owners: Set<String>) {
        val existing=read()
        check(!existing.getValue("enabled").jsonPrimitive.boolean) { "Retire the current authority before changing it" }
        require(token.matches(Regex("[A-Za-z0-9_-]{43}")))
        val result=send(address,token,"/v1/pair",buildJsonObject { put("publicKey",publicKey()) })
        val allowed=result.getValue("owners").jsonArray.map { it.jsonPrimitive.content }.toSet()
        require(allowed.containsAll(owners)) { "The enrolled authority must cover every profile in this user workspace" }
        check(result.getValue("retired").jsonPrimitive.boolean==false) { "此工作域已由管理员安全退役；不能再次派发，请配置新权威" }
        write(buildJsonObject {
            put("version",1);put("enabled",true);put("endpoint",endpoint(address));put("actor",result.getValue("actor"))
            put("domain",result.getValue("domain"))
            put("tokenReference",vault.protect(token));put("owners",JsonArray(allowed.sorted().map(::JsonPrimitive)))
            put("leases",JsonObject(emptyMap()));put("delegations",existing.getValue("delegations"))
        })
    }
    internal fun disconnect() {
        val value=read()
        check(value.getValue("leases").jsonObject.isEmpty()) { "Unconfirmed native admissions retain this authority" }
        for((_,element) in value.getValue("delegations").jsonObject) {
            val entry=element.jsonObject
            if(!activeFor(entry))continue
            val task=rpcFor(entry,"task.read",buildJsonObject { put("owner",entry.getValue("owner"));put("taskId",entry.getValue("remoteId")) })
            check(task.getValue("state").jsonPrimitive.content in setOf("SUCCEEDED","CANCELED")) { "The delegated executor must terminate before disconnecting" }
            check(task.getValue("executorActive").jsonPrimitive.boolean==false) { "权威执行器尚未确认退出；不能启用第二个执行域" }
        }
        val retired=rpc("domain.status",JsonObject(emptyMap()))
        check(retired.getValue("domain")==value.getValue("domain") && retired.getValue("retired").jsonPrimitive.boolean) {
            "旧权威尚未安全退役；请管理员确认全部任务与实际执行器已退出后运行 retire，再停用。仅关闭本机开关会形成两个执行域。"
        }
        // Keep immutable delegation/receipt mappings; disabling does not erase remote records.
        write(JsonObject(value+("enabled" to JsonPrimitive(false))))
    }
    private fun leases()=read().getValue("leases").jsonObject
    private fun updateLease(id: String,value: JsonObject?) {
        synchronized(this) {
            val current=read();val values=current.getValue("leases").jsonObject.toMutableMap()
            if(value==null) values.remove(id) else values[id]=value
            write(JsonObject(current+("leases" to JsonObject(values))))
        }
    }
    internal fun delegated(owner: String,id: String): JsonObject? = read().getValue("delegations").jsonObject[id]?.jsonObject?.also {
        require(it.getValue("owner").jsonPrimitive.content==owner)
    }
    internal fun delegation(id: String,value: JsonObject) {
        synchronized(this) {
            val current=read();val values=current.getValue("delegations").jsonObject.toMutableMap()
            values[id]?.let { require(it.jsonObject.getValue("owner")==value.getValue("owner")) }
            // Only the safety journal writer may replace controls; late claim/receipt
            // snapshots must not erase a concurrently persisted Stop.
            val controls=values[id]?.jsonObject?.get("controls")
            values[id]=if(controls==null)value else JsonObject(value+("controls" to controls))
            write(JsonObject(current+("delegations" to JsonObject(values))))
        }
    }
    @Synchronized private fun persistControls(id: String,controls: Map<String,JsonElement>) {
        val current=read();val values=current.getValue("delegations").jsonObject.toMutableMap()
        values[id]=JsonObject(values.getValue(id).jsonObject+("controls" to JsonObject(controls)))
        write(JsonObject(current+("delegations" to JsonObject(values))))
    }
    internal fun protect(value: JsonObject)=vault.protect(value.toString())
    internal fun resolve(reference: String)=json.parseToJsonElement(vault.resolve(reference)).jsonObject

    internal fun safety(store: AgentTaskStore,owner: String,id: String,requestId: String,action: String): Boolean {
        if(delegated(owner,id)==null)return false
        check(action in setOf("pause","cancel","revoke")) { "云端任务须在云端动作面按远端版本恢复或补充" }
        CloudDeviceControl.stop(owner,id)
        val native=store.get(owner,id)
        synchronized(this) {
            val record=delegated(owner,id)!!
            val controls=record["controls"]?.jsonObject.orEmpty().toMutableMap()
            val key="native_"+AgentTaskStore.hash(requestId)
            val existing=controls[key]?.jsonObject
            if(existing==null) {
                controls[key]=buildJsonObject { put("action",action);put("nativeFence",native.fence);put("acknowledged",false) }
                persistControls(id,controls)
            } else require((existing["action"] ?: existing.getValue("value").jsonObject.getValue("action")).jsonPrimitive.content==action)
            flushSafety(owner,id)
        }
        return true
    }

    /** Persist Stop before any network read; pending safety commands gate all later dispatch. */
    @Synchronized internal fun flushSafety(owner: String,id: String) {
        val original=delegated(owner,id) ?: return
        for(key in original["controls"]?.jsonObject.orEmpty().keys) {
            val record=delegated(owner,id)!!
            val controls=record.getValue("controls").jsonObject.toMutableMap()
            val saved=controls.getValue(key).jsonObject
            if(saved["acknowledged"]?.jsonPrimitive?.boolean==true)continue
            val value=saved["value"]?.jsonObject ?: run {
                val action=saved.getValue("action").jsonPrimitive.content
                check(action in setOf("pause","cancel","revoke"))
                val remote=rpcFor(record,"task.read",buildJsonObject { put("owner",owner);put("taskId",record.getValue("remoteId")) })
                buildJsonObject { put("owner",owner);put("taskId",record.getValue("remoteId"));put("revision",remote.getValue("revision"))
                    put("action",action);put("payload","") }
            }
            controls[key]=JsonObject(saved+("value" to value))
            persistControls(id,controls)
            try { rpcFor(record,"task.control",value,saved["command"]?.jsonPrimitive?.content ?: key) }
            catch(error: CloudRejected) {
                if(error.status==409) {
                    // A confirmed rejection is not a lost ACK. Next read may bind the
                    // same safety-only intent to a fresh revision under a new command.
                    controls[key]=JsonObject(controls.getValue(key).jsonObject.filterKeys { it!="value" }+mapOf(
                        "action" to value.getValue("action"),"command" to JsonPrimitive(UUID.randomUUID().toString())))
                    persistControls(id,controls)
                }
                throw error
            }
            controls[key]=JsonObject(controls.getValue(key).jsonObject+("acknowledged" to JsonPrimitive(true)))
            persistControls(id,controls)
        }
    }

    internal suspend fun reserve(task: AgentTask,store: AgentTaskStore): CloudGate? {
        check(delegated(task.owner,task.id)==null) { "This task is delegated; disabling or rebinding cannot authorize a local replay" }
        if(!configured())return null
        recoverAdmissions(store)
        val id=UUID.randomUUID().toString()
        val source="${task.id}:$id"
        val accepted=buildJsonObject {
            put("owner",task.owner);put("sourceId",source);put("goal","Native execution admission; user content remains on device")
            put("inputHash",AgentTaskStore.hash(store.plan(task.owner,task.id).goal))
            put("grantEpoch",store.control(task.owner,task.id).grantEpoch)
            put("model",buildJsonObject {
                put("provider_id",task.providerId);put("model_id",task.modelId);put("reasoning",task.reasoning)
                put("configuration_revision",task.configurationRevision)
            })
        }
        var record=buildJsonObject {
            put("owner",task.owner);put("nativeId",task.id);put("sourceId",source);put("command",id)
            put("instance",processInstance);put("accepted",accepted)
        }
        updateLease(id,record) // Before any remote admission, including a lost acceptance ACK.
        val received=rpc("native.accept",accepted,id)
        record=JsonObject(record+("remoteId" to received.getValue("taskId")));updateLease(id,record)
        while(true) {
            currentCoroutineContext().ensureActive()
            val result=rpc("native.claim",buildJsonObject {
                put("owner",task.owner);put("taskId",record.getValue("remoteId"));put("instance",processInstance)
            },id)
            if(result.getValue("state").jsonPrimitive.content=="RUNNING") {
                record=JsonObject(record+("fence" to result.getValue("fence")));updateLease(id,record)
                activeAdmissions.add(id)
                return CloudGate(this,id,record,false)
            }
            delay(500) // Idempotent admission queries, never model/tool retries.
        }
    }
    internal fun recoverAdmissions(store: AgentTaskStore) {
        for((id,item) in leases()) {
            var record=item.jsonObject
            // A live scope retires its own admission; another observer cannot infer stop.
            if(id in activeAdmissions) continue
            if(record["remoteId"]==null) {
                val accepted=rpc("native.accept",record.getValue("accepted").jsonObject,record.getValue("command").jsonPrimitive.content)
                record=JsonObject(record+("remoteId" to accepted.getValue("taskId")));updateLease(id,record)
            }
            val remote=rpc("task.read",buildJsonObject { put("owner",record.getValue("owner"));put("taskId",record.getValue("remoteId")) })
            if(remote.getValue("state").jsonPrimitive.content=="QUEUED") {
                // No local executor ran; acquire this exact generation before signed retirement.
                val claimed=rpc("native.claim",buildJsonObject { put("owner",record.getValue("owner"));put("taskId",record.getValue("remoteId"));put("instance",record.getValue("instance")) })
                check(claimed.getValue("state").jsonPrimitive.content=="RUNNING") { "The old pending admission remains queued" }
                record=JsonObject(record+("fence" to claimed.getValue("fence")));updateLease(id,record)
            } else if(record["fence"]==null) {
                record=JsonObject(record+("fence" to remote.getValue("fence")));updateLease(id,record)
            }
            retire(id,record,store)
        }
    }
    internal fun retire(id: String,record: JsonObject,store: AgentTaskStore) {
        val owner=record.getValue("owner").jsonPrimitive.content
        val native=record.getValue("nativeId").jsonPrimitive.content
        val payload=record["proof"]?.jsonObject ?: run {
            val proof=store.networkRetirementEvidence(owner,native)
            signed(buildJsonObject {
                put("owner",owner);put("taskId",record.getValue("remoteId"));put("fence",record.getValue("fence"));put("instance",record.getValue("instance"))
                put("proof",buildJsonObject { put("sourceId",record.getValue("sourceId"));put("sourceFence",proof.fence)
                    put("state",proof.state);put("journalHash",proof.journalHash) })
            }).also { updateLease(id,JsonObject(record+("proof" to it))) }
        }
        val ack=rpc("native.retire",payload,record.getValue("command").jsonPrimitive.content)
        check(ack.getValue("state").jsonPrimitive.content=="STOPPED")
        updateLease(id,null)
        activeAdmissions.remove(id)
    }
    internal fun deviceGate(value: JsonObject): CloudGate=CloudGate(this,null,value,true)
    internal fun releaseActive(id: String) { activeAdmissions.remove(id) }

    companion object {
        private val instances=java.util.concurrent.ConcurrentHashMap<String,CloudWorkspace>()
        fun shared(context: Context): CloudWorkspace=instances.computeIfAbsent(context.noBackupFilesDir.canonicalPath) { CloudWorkspace(context.applicationContext) }
        internal fun canonical(value: JsonElement): String=when(value) {
            is JsonObject -> value.entries.sortedBy { it.key }.joinToString(",","{","}") { JsonPrimitive(it.key).toString()+":"+canonical(it.value) }
            is JsonArray -> value.joinToString(",","[","]") { canonical(it) }
            else -> value.toString()
        }
    }
}

internal class CloudGate internal constructor(private val workspace: CloudWorkspace,private val id: String?,
    private val record: JsonObject,val device: Boolean) {
    fun check() {
        workspace.rpc("execution.check",buildJsonObject {
            put("owner",record.getValue("owner"));put("taskId",record.getValue("remoteId"));put("fence",record.getValue("fence"));put("instance",record.getValue("instance"))
        })
    }
    fun retire(store: AgentTaskStore) {
        if(!device) try { workspace.retire(requireNotNull(id),record,store) }
        finally { workspace.releaseActive(requireNotNull(id)) }
    }
}

internal class CloudRejected(val status: Int): IllegalStateException("Cloud authority rejected request ($status); execution was not retried")
