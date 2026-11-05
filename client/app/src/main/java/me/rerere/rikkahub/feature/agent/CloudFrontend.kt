package me.rerere.rikkahub.feature.agent

import android.content.Context
import android.app.KeyguardManager
import android.os.PowerManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.provider.HostedConfiguration
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.material.MaterialFile
import me.rerere.rikkahub.feature.material.MaterialFiles
import me.rerere.rikkahub.service.ChatService
import java.util.UUID
import kotlin.uuid.Uuid

/** Trusted UI boundary. No credentials or private cloud intents are returned to Flutter. */
internal class CloudFrontend(private val context: Context,private val settings: SettingsStore,
    private val conversations: ConversationRepository,private val chat: ChatService,private val materials: MaterialFiles) {
    private val workspace=CloudWorkspace.shared(context)
    private val documents=me.rerere.rikkahub.feature.material.DocumentServiceRepository(context,materials)
    private val scheduler=AgentScheduler.shared(context)
    private val deviceNames=setOf("create_material_file","edit_material_file","read_material_file","math_verify")
    private fun base(owner: String,entry: JsonObject)=buildJsonObject { put("owner",owner);put("taskId",entry.getValue("remoteId")) }
    private fun entry(owner: String,id: String)=requireNotNull(workspace.delegated(owner,id)) { "此任务尚未移交" }
    private fun available(foreground: () -> Boolean) {
        check(foreground() && context.getSystemService(PowerManager::class.java).isInteractive &&
            !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "请保持本应用前台并解锁手机；不会自动接管。" }
    }
    private fun envelope(value: JsonElement): Any?=when(value) {
        JsonNull -> null
        is JsonObject -> value.mapValues { envelope(it.value) }
        is JsonArray -> value.map(::envelope)
        is JsonPrimitive -> if(value.isString) value.content else value.booleanOrNull ?: value.longOrNull ?: value.doubleOrNull
    }
    private fun hostingGrant(args: Map<*,*>): JsonObject {
        val mode=args["hostingMode"] as? String ?: "UNTIL"
        require(mode in setOf("UNTIL","UNTIL_REVOKED")) { "未知托管时长" }
        return buildJsonObject {
            put("mode",mode);put("expiresAt",if(mode=="UNTIL_REVOKED")JsonNull else JsonPrimitive(System.currentTimeMillis()/1000.0+86400))
            put("dataScope","THIS_GOAL_SELECTED_SOURCES_AND_PINNED_PROVIDER")
        }
    }
    companion object { private val mutations=Mutex() }
    suspend fun handle(method: String,owner: String,args: Map<*,*>,validate: () -> Unit,foreground: () -> Boolean): Any? =
        if(method in setOf("cloud.connect","cloud.disconnect","cloud.delegate","cloud.control","cloud.hosting","cloud.replaceKey","cloud.import"))
            mutations.withLock { dispatch(method,owner,args,validate,foreground) }
        else dispatch(method,owner,args,validate,foreground)
    private suspend fun dispatch(method: String,owner: String,args: Map<*,*>,validate: () -> Unit,foreground: () -> Boolean): Any? {
        validate()
        if(method=="cloud.state")return workspace.state()+mapOf("crossAppEnabled" to CloudBoundary.CROSS_APP_ENABLED)
        if(method=="cloud.connect") {
            available(foreground)
            check(args["consent"]==true) { "请明确同意将整个用户工作域接入此唯一权威" }
            scheduler.configureCloud(args["endpoint"] as? String ?: "",args["token"] as? String ?: "",
                settings.settingsFlow.value.learnerProfiles.map { it.id }.toSet())
            validate();return workspace.state()
        }
        if(method=="cloud.disconnect") { available(foreground);scheduler.disconnectCloud();validate();return workspace.state() }
        val id=args["taskId"] as? String ?: error("缺少原任务ID")
        val source=scheduler.get(owner,id)
        if(method=="cloud.stopDevice") {
            CloudDeviceControl.stop(owner,id)
            scheduler.cloudSafety(owner,id,"device-stop:${UUID.randomUUID()}","pause")
            return mapOf("state" to "STOP_REQUESTED")
        }
        if(method=="cloud.control") {
            val action=args["action"] as? String ?: error("缺少动作")
            if(action=="pause" || action=="cancel" || action=="revoke") {
                available(foreground)
                val command=args["commandId"] as? String ?: error("缺少幂等命令ID")
                check(scheduler.cloudSafety(owner,id,command,action)) { "此任务尚未移交" }
                validate();return mapOf("state" to "STOP_REQUESTED")
            }
        }
        if(method=="cloud.preview") {
            val snapshot=scheduler.hostedConfiguration(owner,id)
            val model=requireNotNull(snapshot.settings.findModelById(snapshot.assistant.chatModelId ?: snapshot.settings.chatModelId))
            val provider=requireNotNull(model.findProvider(snapshot.settings.providers))
            val documentConfiguration=documents.config(owner)
            return mapOf("owner" to owner,"taskId" to id,"provider" to provider.name,"model" to model.displayName,
                "reasoning" to source.reasoning,"goal" to scheduler.plan(owner,id).goal,
                "sourceState" to source.state,"manualPaused" to source.manualPaused,
                "endpoint" to workspace.state()["endpoint"],"delegated" to (workspace.delegated(owner,id)!=null),
                "sources" to materials.list(owner).filter { it.state=="SUCCEEDED" &&
                    (it.extension in setOf("txt","md") && it.size<=16000 ||
                        documentConfiguration.enabled && it.extension in setOf("docx","xlsx","pptx","pdf","png","jpg","jpeg","webp") && it.size<=30L*1024*1024) }.map {
                    mapOf("id" to it.id,"name" to it.name,"sha256" to it.sha256,"size" to it.size,"extension" to it.extension) },
                "documentEndpoint" to documentConfiguration.takeIf { it.enabled }?.baseUrl)
        }
        if(method=="cloud.delegate") {
            available(foreground);check(args["consent"]==true) { "托管目标、已核验步骤、所选文本与锁定模型凭据需要逐任务明确授权" }
            check(workspace.configured())
            var record=workspace.delegated(owner,id)
            if(record==null) scheduler.cloudHandoff(owner,id) { proof ->
                val source=scheduler.get(owner,id)
                val control=scheduler.control(owner,id)
                check(control.authorization=="ACTIVE" && !control.cancelRequested && !control.approvalBlocked) { "先处理原任务授权、取消或待审批工具" }
                val snapshot=scheduler.hostedConfiguration(owner,id)
                val model=requireNotNull(snapshot.settings.findModelById(snapshot.assistant.chatModelId ?: snapshot.settings.chatModelId))
                val provider=requireNotNull(model.findProvider(snapshot.settings.providers))
                val configuration=HostedConfiguration.encode(provider,TextGenerationParams(model=model,
                    reasoningLevel=snapshot.assistant.reasoningLevel,temperature=snapshot.assistant.temperature,
                    topP=snapshot.assistant.topP,maxTokens=snapshot.assistant.maxTokens,
                    customHeaders=snapshot.assistant.customHeaders+model.customHeaders,
                    customBody=snapshot.assistant.customBodies+model.customBodies,sessionId=source.conversationId))
                val selected=(args["sources"] as? List<*>)?.map { it as String }.orEmpty().distinct()
                require(selected.size<=16)
                val documentConfiguration=if(args["documentConsent"]==true)documents.hostedConfiguration(owner) else null
                val sources=selected.map { materialId ->
                    val item=materials.get(owner,materialId)
                    if(item.extension !in setOf("txt","md")) {
                        check(documentConfiguration!=null) { "所选非文本来源需要单独同意文档服务凭据托管及文件外传" }
                        require(item.extension in setOf("docx","xlsx","pptx","pdf","png","jpg","jpeg","webp") && item.size in 1..30L*1024*1024)
                        materials.verifiedFile(owner,materialId)
                        val upload=AgentTaskStore.hash(CloudWorkspace.canonical(JsonArray(listOf(
                            JsonPrimitive(workspace.state()["actor"] as String),JsonPrimitive(owner),JsonPrimitive(item.id),
                            JsonPrimitive(item.sha256),JsonPrimitive(item.extension),JsonPrimitive(item.size)))))
                        return@map buildJsonObject { put("id",item.id);put("name",item.name);put("sha256",item.sha256);put("extension",item.extension);put("size",item.size);put("uploadId",upload) }
                    }
                    require(item.extension in setOf("txt","md") && item.size<=16000)
                    val bytes=materials.verifiedFile(owner,materialId).readBytes()
                    val data=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                    check(AgentTaskStore.hash(data)==item.sha256) { "所选原件并非无损UTF-8文本，未上传" }
                    buildJsonObject { put("id",item.id);put("text",data);put("sha256",item.sha256) }
                }
                val conversation=conversations.getConversationById(Uuid.parse(source.conversationId),owner) ?: error("原会话不存在")
                val oldPlan=scheduler.plan(owner,id)
                val history=oldPlan.steps.filter { it.state=="VERIFIED" }.map { step ->
                    val tool=conversation.currentMessages.flatMap { it.getTools() }.singleOrNull { it.toolCallId==step.callId }
                        ?: error("已核验步骤缺少原会话凭证，未移交")
                    check(chat.cloudVerify(owner,id,tool)) { "旧成果独立读回失败，未移交" }
                    val input=Json.parseToJsonElement(tool.input)
                    buildJsonObject { put("id","native_"+AgentTaskStore.hash(step.id).take(32));put("tool",step.tool);put("arguments",input)
                        put("result",CloudNativeEvidence.result(owner,source,AgentTaskStore.hash(CloudWorkspace.canonical(input)),tool,materials)) }
                }
                val historyProof=workspace.signed(buildJsonObject { put("owner",owner);put("sourceId",id)
                    put("journalHash",proof.journalHash);put("steps",JsonArray(history)) })
                val nativeTools=chat.cloudTools(owner,id).filter { it.name in deviceNames }
                val context=chat.cloudContext(owner,id,args["includeHistory"]==true,nativeTools)
                require(context.toString().toByteArray().size<=100000) { "上下文超过安全传输范围，先在原会话整理再移交；不会静默丢弃" }
                val tools=nativeTools.map { tool ->
                    val schema=requireNotNull(tool.parameters() as? InputSchema.Obj)
                    buildJsonObject { put("name",tool.name);put("description",tool.description)
                        put("parameters",buildJsonObject { put("type","object");put("properties",schema.properties)
                            put("required",JsonArray(schema.required.orEmpty().map(::JsonPrimitive)));put("additionalProperties",false) }) }
                }
                val intent=buildJsonObject {
                    put("owner",owner);put("sourceId",id);put("goal",oldPlan.goal)
                    put("model",buildJsonObject { put("provider_id",source.providerId);put("model_id",source.modelId)
                        put("reasoning",source.reasoning);put("configuration_revision",source.configurationRevision) })
                    put("configuration",configuration);put("tools",JsonArray(tools));put("sources",JsonArray(sources))
                    put("context",context)
                    documentConfiguration?.let { put("documentService",it) }
                    put("requireEvidence",args["requireEvidence"]!=false)
                    put("grant",hostingGrant(args))
                    put("nativeHistory",historyProof)
                    put("initialControl",buildJsonObject { put("manualPause",source.manualPaused);put("inputBlocked",control.inputBlocked)
                        put("deviceBlocked",control.deviceBlocked);put("deviceConfirmation",control.deviceConfirmation) })
                }
                validate();available(foreground)
                check(scheduler.get(owner,id).revision==source.revision &&
                    chat.cloudContext(owner,id,args["includeHistory"]==true,nativeTools)==context) { "移交准备期间目标或来源已变化，尚未发送" }
                // Persist the delegation BEFORE dispatch; ACK loss can never enable local fallback.
                record=buildJsonObject { put("owner",owner);put("nativeId",id);put("command",UUID.randomUUID().toString())
                    put("authority",workspace.authorityBinding())
                    put("intentReference",workspace.protect(intent));put("state","SUBMITTING") }
                workspace.delegation(id,requireNotNull(record))
                chat.cloudPrepareSource(owner,id,validate)
                record=JsonObject(requireNotNull(record)+("sourceBranch" to JsonPrimitive(chat.cloudBranch(owner,id))))
                workspace.delegation(id,requireNotNull(record))
            }
            val submission=requireNotNull(record)
            val acceptedRecord=if(submission["remoteId"]==null) {
                val intent=workspace.resolve(submission.getValue("intentReference").jsonPrimitive.content)
                for(source in intent.getValue("sources").jsonArray.map { it.jsonObject }.filter { it["uploadId"]!=null }) {
                    val sourceId=source.getValue("id").jsonPrimitive.content
                    val item=materials.get(owner,sourceId)
                    check(item.sha256==source.getValue("sha256").jsonPrimitive.content && item.size==source.getValue("size").jsonPrimitive.long) { "已授权来源版本改变，未发送新版本" }
                    materials.verifiedFile(owner,sourceId).inputStream().use { stream ->
                        val buffer=ByteArray(512*1024);var offset=0L
                        while(offset<item.size) {
                            validate();available(foreground)
                            val count=stream.read(buffer);check(count>0)
                            val encoded=java.util.Base64.getEncoder().encode(java.nio.ByteBuffer.wrap(buffer,0,count))
                            val value=buildJsonObject { put("owner",owner);put("sourceId",sourceId);put("sha256",item.sha256);put("extension",item.extension)
                                put("size",item.size);put("offset",offset);put("bytes",String(encoded.array(),Charsets.US_ASCII));put("consent",true) }
                            val ack=workspace.rpcFor(submission,"source.chunk",value,"chunk_"+AgentTaskStore.hash(source.getValue("uploadId").jsonPrimitive.content+":"+offset))
                            check(ack.getValue("uploadId")==source.getValue("uploadId") && ack.getValue("nextOffset").jsonPrimitive.long==offset+count)
                            offset+=count
                        }
                        check(stream.read()==-1)
                    }
                }
                val accepted=workspace.rpcFor(submission,"cloud.create",intent,submission.getValue("command").jsonPrimitive.content)
                JsonObject(submission+("remoteId" to accepted.getValue("taskId"))+("state" to JsonPrimitive("ACCEPTED"))).also { workspace.delegation(id,it) }
            } else submission
            validate();return envelope(workspace.rpcFor(acceptedRecord,"task.read",base(owner,acceptedRecord)))
        }
        val record=entry(owner,id)
        workspace.flushSafety(owner,id)
        if(method=="cloud.read") {
            val state=workspace.rpcFor(record,"task.read",base(owner,record))
            val pending=workspace.rpcFor(record,"device.pending",base(owner,record))
            validate()
            val expected=buildJsonObject { put("provider_id",source.providerId);put("model_id",source.modelId)
                put("reasoning",source.reasoning);put("configuration_revision",source.configurationRevision) }
            require(state.getValue("model")==expected && state.getValue("sourceId").jsonPrimitive.content==id)
            scheduler.ingestHostedUsage(owner,id,record,state)
            var applied=false
            if(state.getValue("state").jsonPrimitive.content=="SUCCEEDED" && record["sourceBranch"]!=null) {
                chat.cloudPrepareSource(owner,id,validate)
                val result=state.getValue("result").jsonObject
                check(result.getValue("evidenceHash").jsonPrimitive.content.matches(Regex("[0-9a-f]{64}")))
                applied=chat.cloudPublishResult(owner,id,record.getValue("sourceBranch").jsonPrimitive.content,
                    result.getValue("answer").jsonPrimitive.content,validate)
            }
            validate();return envelope(JsonObject(state+("device" to pending.getValue("request"))+("chatApplied" to JsonPrimitive(applied))+("canDispatch" to JsonPrimitive(workspace.activeFor(record) && state["authorityRetired"]!=JsonPrimitive(true)))))
        }
        if(method=="cloud.confirmReplan") {
            available(foreground)
            val proof=workspace.signed(buildJsonObject { put("owner",owner);put("taskId",record.getValue("remoteId"))
                put("revision",JsonPrimitive((args["revision"] as? Number)?.toLong() ?: error("缺少远端版本")))
                put("grantEpoch",JsonPrimitive((args["grantEpoch"] as? Number)?.toLong() ?: error("缺少授权代次")))
                put("goalHash",AgentTaskStore.hash(args["goal"] as? String ?: error("缺少目标")));put("foregroundNonce",UUID.randomUUID().toString()) })
            return envelope(workspace.rpcFor(record,"device.confirm_replan",proof,args["commandId"] as? String ?: error("缺少幂等命令ID")))
        }
        if(method=="cloud.control") {
            available(foreground)
            val command=args["commandId"] as? String ?: error("缺少幂等命令ID")
            val action=args["action"] as? String ?: error("缺少动作")
            val response=workspace.rpcFor(record,"task.control",JsonObject(base(owner,record)+mapOf(
                "revision" to JsonPrimitive((args["revision"] as? Number)?.toLong() ?: error("缺少远端版本")),
                "action" to JsonPrimitive(action),"payload" to JsonPrimitive(args["payload"] as? String ?: ""))),command)
            validate();return envelope(response)
        }
        if(method=="cloud.hosting") {
            available(foreground);check(args["consent"]==true) { "变更托管期限需要明确同意" }
            val response=workspace.rpcFor(record,"hosting.update",JsonObject(base(owner,record)+mapOf(
                "revision" to JsonPrimitive((args["revision"] as? Number)?.toLong() ?: error("缺少远端版本")),
                "grant" to hostingGrant(args))),args["commandId"] as? String ?: error("缺少幂等命令ID"))
            validate();return envelope(response)
        }
        if(method=="cloud.replaceKey") {
            available(foreground);check(args["consent"]==true)
            val response=workspace.rpcFor(record,"credential.replace",JsonObject(base(owner,record)+mapOf(
                "revision" to JsonPrimitive((args["revision"] as? Number)?.toLong() ?: error("缺少远端版本")),
                "secret" to JsonPrimitive(args["secret"] as? String ?: error("缺少新密钥")))),args["commandId"] as? String ?: error("缺少幂等命令ID"))
            validate();return envelope(response)
        }
        if(method=="cloud.import") {
            val artifactId=args["artifactId"] as? String ?: error("缺少成果ID")
            val received=workspace.rpcFor(record,"artifact.read",JsonObject(base(owner,record)+("artifactId" to JsonPrimitive(artifactId))))
            val digest=received.getValue("sha256").jsonPrimitive.content
            val extension=received.getValue("extension").jsonPrimitive.content
            require(digest.matches(Regex("[a-f0-9]{64}")) && extension in setOf("md","txt","pdf","xlsx"))
            val parent=received["sourceSha256"]?.jsonPrimitive?.content?.let { hash ->
                materials.get(owner,received.getValue("parentId").jsonPrimitive.content).also {
                    check(it.sha256==hash) { "原来源已改变，未把云成果绑定到另一个版本" };materials.verifiedFile(owner,it.id)
                }
            }
            val localId=UUID.nameUUIDFromBytes("cloud-artifact:$owner:${record.getValue("remoteId")}:$artifactId".toByteArray()).toString()
            val existing=materials.list(owner).singleOrNull { it.id==localId }
            validate()
            if(existing!=null) { check(existing.sha256==digest && existing.extension==extension && existing.sourceId==parent?.id);materials.verifiedFile(owner,localId) }
            else materials.runTask(MaterialFile(id=localId,profileId=owner,name="云端成果-${artifactId.take(12)}.$extension",extension=extension,
                output=true,sourceId=parent?.id,sourceReferences=parent?.let { listOf(me.rerere.rikkahub.feature.material.MaterialSourceBinding(it.id,it.sha256)) }.orEmpty(),
                requestKey="cloud:$artifactId")) { output ->
                var chunk=received;var offset=0L
                output.outputStream().use { stream ->
                    do {
                        validate();parent?.let { check(materials.get(owner,it.id).sha256==it.sha256) }
                        check(chunk.getValue("artifactId").jsonPrimitive.content==artifactId && chunk.getValue("sha256").jsonPrimitive.content==digest &&
                            chunk.getValue("extension").jsonPrimitive.content==extension && (chunk["offset"]?.jsonPrimitive?.long ?: 0L)==offset)
                        val bytes=java.util.Base64.getDecoder().decode(chunk.getValue("bytes").jsonPrimitive.content)
                        val size=chunk["size"]?.jsonPrimitive?.long ?: bytes.size.toLong()
                        check(size in 1..30L*1024*1024 && bytes.isNotEmpty() && offset+bytes.size<=size)
                        stream.write(bytes);offset+=bytes.size
                        if(offset==size)break
                        check(chunk.getValue("nextOffset").jsonPrimitive.long==offset)
                        chunk=workspace.rpcFor(record,"artifact.read",JsonObject(base(owner,record)+mapOf(
                            "artifactId" to JsonPrimitive(artifactId),"offset" to JsonPrimitive(offset))))
                    } while(true)
                }
                check(MaterialFiles.digest(output)==digest) { "云端成果独立回读摘要失败" }
                me.rerere.rikkahub.feature.material.MaterialReader.read(output,extension)
                validate();"已独立核对云端/本机SHA-256并用原资料读取器打开；原件及来源版本保留"
            }
            materials.verifiedFile(owner,localId);validate();return mapOf("id" to localId,"sha256" to digest)
        }
        if(method=="cloud.executeDevice")return execute(owner,id,record,args,validate,foreground)
        error("不支持的云端动作")
    }

    /** Settlement after the original Job ended is permitted even when dispatch was revoked. */
    private suspend fun settleDevice(owner: String,id: String): JsonObject? {
        val record=workspace.delegated(owner,id) ?: return null
        val lease=record["deviceLease"]?.jsonObject ?: return null
        fun clear()=workspace.delegation(id,JsonObject(workspace.delegated(owner,id)!!.filterKeys {
            it !in setOf("deviceReceipt","deviceStopReceipt","deviceLease","deviceRecovery")
        }))
        record["deviceReceipt"]?.jsonObject?.let { val ack=workspace.rpcFor(record,"device.finish",it);clear();return ack }
        record["deviceStopReceipt"]?.jsonObject?.let { val ack=workspace.rpcFor(record,"device.abort",it);clear();return ack }
        val recovered=try { workspace.rpcFor(record,"device.recover",buildJsonObject { put("owner",owner);put("taskId",record.getValue("remoteId"))
            put("callId",lease.getValue("callId"));put("instance",lease.getValue("instance")) }) }
        catch(error: CloudRejected) { if(lease["fence"]==null && error.status in setOf(404,409))return null;throw error }
        val stopped=scheduler.requireCloudHandoffSafe(owner,id)
        val nativeCall="cloud_"+AgentTaskStore.hash(record.getValue("remoteId").toString()+lease.getValue("callId").toString()).take(40)
        val callState=scheduler.cloudCallState(owner,id,nativeCall)
        val operation: String
        val receipt: JsonObject
        if(callState=="VERIFIED") {
            val pending=lease.getValue("pending").jsonObject
            val toolName=pending.getValue("tool").jsonPrimitive.content
            val input=CloudWorkspace.canonical(pending.getValue("arguments"))
            val output=requireNotNull(scheduler.cloudCachedResult(owner,id,toolName,input))
            val tool=UIMessagePart.Tool(nativeCall,toolName,input,Json.decodeFromString<List<UIMessagePart>>(output),
                ToolApprovalState.Approved,ToolExecutionState.COMPLETED)
            check(chat.cloudVerify(owner,id,tool)) { "原结果独立回读失败，未签发成功回执" }
            val result=CloudNativeEvidence.result(owner,scheduler.get(owner,id),recovered.getValue("inputHash").jsonPrimitive.content,tool,materials)
            operation="device.finish"
            receipt=workspace.signed(buildJsonObject { put("owner",owner);put("taskId",record.getValue("remoteId"));put("callId",lease.getValue("callId"))
                put("instance",lease.getValue("instance"));put("fence",recovered.getValue("fence"));put("result",result) })
        } else {
            check(callState=="NOT_DISPATCHED") { "本机结果未核实，权威执行位继续保留；不会重新发送" }
            operation="device.abort"
            receipt=workspace.signed(buildJsonObject { put("owner",owner);put("taskId",record.getValue("remoteId"));put("callId",lease.getValue("callId"))
                put("instance",lease.getValue("instance"));put("fence",recovered.getValue("fence"))
                put("proof",buildJsonObject { put("sourceId",id);put("sourceFence",stopped.fence);put("state",stopped.state)
                    put("journalHash",stopped.journalHash);put("nativeCallId",nativeCall);put("callState",callState) }) })
        }
        val key=if(operation=="device.finish")"deviceReceipt" else "deviceStopReceipt"
        workspace.delegation(id,JsonObject(workspace.delegated(owner,id)!!+(key to receipt)))
        val ack=workspace.rpcFor(record,operation,receipt);clear();return ack
    }

    private suspend fun execute(owner: String,id: String,record: JsonObject,args: Map<*,*>,validate: () -> Unit,
        foreground: () -> Boolean): Any? = coroutineScope {
        available(foreground);validate()
        val job=requireNotNull(currentCoroutineContext()[Job]);CloudDeviceControl.start(owner,id,job)
        val guard=launch { while(isActive) { delay(200);try { validate();available(foreground) }
            catch(error: Exception) { job.cancel(CancellationException("设备前台或档案已改变",error));break } } }
        try {
            settleDevice(owner,id)?.let { return@coroutineScope envelope(it) }
            var local=workspace.delegated(owner,id)!!
            val pending=workspace.rpcFor(record,"device.pending",base(owner,record))["request"]?.takeUnless { it==JsonNull }?.jsonObject ?: error("没有设备动作")
            check(pending.getValue("tool").jsonPrimitive.content in deviceNames) { "跨App工具默认关闭，本接收端未授权该能力" }
            check(args["callId"]==pending.getValue("callId").jsonPrimitive.content &&
                args["inputHash"]==pending.getValue("inputHash").jsonPrimitive.content &&
                (args["grantEpoch"] as? Number)?.toLong()==pending.getValue("grantEpoch").jsonPrimitive.long &&
                (args["taskRevision"] as? Number)?.toLong()==pending.getValue("taskRevision").jsonPrimitive.long) {
                "动作、授权代次或任务修订已变化，请刷新并重新核对；旧确认不会升级为新授权"
            }
            if(pending.getValue("state").jsonPrimitive.content=="WAITING") {
                val proof=workspace.signed(buildJsonObject { put("owner",owner);put("taskId",record.getValue("remoteId"))
                    put("callId",pending.getValue("callId"));put("revision",pending.getValue("taskRevision"))
                    put("inputHash",pending.getValue("inputHash"));put("grantEpoch",pending.getValue("grantEpoch"));put("foregroundNonce",UUID.randomUUID().toString()) })
                workspace.rpcFor(record,"device.confirm",proof)
            }
            // A prior physical claim is recovered only through its exact persisted instance.
            val instance=local["deviceLease"]?.jsonObject?.getValue("instance")?.jsonPrimitive?.content ?: UUID.randomUUID().toString()
            if(local["deviceLease"]==null) {
                local=JsonObject(local+("deviceLease" to buildJsonObject { put("instance",instance);put("callId",pending.getValue("callId"));put("pending",pending) }))
                workspace.delegation(id,local)
            }
            val claimed=workspace.rpcFor(record,"device.claim",buildJsonObject { put("owner",owner);put("taskId",record.getValue("remoteId"));put("callId",pending.getValue("callId"));put("instance",instance) })
            check(claimed.getValue("state").jsonPrimitive.content=="RUNNING") { "设备恢复已优先入队，不会抢占当前任务；请稍后继续" }
            val lease=buildJsonObject { put("owner",owner);put("remoteId",record.getValue("remoteId"));put("fence",claimed.getValue("fence"))
                put("instance",instance);put("callId",pending.getValue("callId"));put("pending",pending) }
            workspace.delegation(id,JsonObject(local+("deviceLease" to lease)))
            val callId="cloud_"+AgentTaskStore.hash(record.getValue("remoteId").toString()+pending.getValue("callId").toString()).take(40)
            val toolName=pending.getValue("tool").jsonPrimitive.content
            val input=CloudWorkspace.canonical(pending.getValue("arguments"))
            chat.cloudPrepareSource(owner,id,validate)
            val sourceBranch=record["sourceBranch"]?.jsonPrimitive?.content ?: error("原分支凭证缺失，不能自动接管；远端结果仍可读回")
            val validateBranch={ validate();available(foreground);check(chat.cloudBranch(owner,id)==sourceBranch) { "原会话分支已变化，旧动作未接管" };Unit }
            var completed: UIMessagePart.Tool?=null
            scheduler.run(scheduler.get(owner,id),{settings.settingsFlow.value},validateBranch,{"INTERRUPTED"},
                { chat.cloudVerify(owner,id,it) },resumeRequested=true,resumeManualPause=true,deviceGate=workspace.deviceGate(lease)) {
                val active=requireNotNull(currentCoroutineContext()[AgentExecution])
                val cached=active.store.completedResult(owner,id,toolName,input)
                val tool=UIMessagePart.Tool(callId,toolName,input,approvalState=ToolApprovalState.Approved)
                if(cached!=null) {
                    val found=tool.copy(output=Json.decodeFromString<List<UIMessagePart>>(cached),executionState=ToolExecutionState.COMPLETED)
                    chat.cloudCheckpoint(owner,id,found,validateBranch);check(chat.cloudVerify(owner,id,found));completed=found
                } else {
                    check(claimed["dispatch"]?.jsonPrimitive?.boolean==true) { "旧动作已物理派发，先核验既有结果；不会重新执行" }
                    val old=active.store.plan(owner,id)
                    val retained=old.plan?.steps.orEmpty().filter { s -> old.steps.any { it.id==s.id && it.state=="VERIFIED" } }
                    active.store.publishPlan(active.lease,old.revision,AgentPlan(steps=retained+PlanStep(callId,toolName,"已签名云任务的设备步骤")),
                        (retained.map { it.tool }+toolName).toSet())
                    active.beginCall(callId,toolName,input)
                    val running=tool.copy(executionState=ToolExecutionState.RUNNING)
                    chat.cloudCheckpoint(owner,id,running,validateBranch)
                    val implementation=chat.cloudTools(owner,id).single { it.name==toolName }
                    val output=withContext(AgentToolDispatch(active,running)) {
                        currentCoroutineContext().ensureActive();currentCoroutineContext()[AgentToolDispatch]!!.check()
                        implementation.execute(Json.parseToJsonElement(input))
                    }
                    val found=running.copy(output=output,executionState=ToolExecutionState.COMPLETED)
                    chat.cloudCheckpoint(owner,id,found,validateBranch)
                    val verified=chat.cloudVerify(owner,id,found)
                    val encoded=Json.encodeToString(output)
                    active.store.finishCall(active.lease,callId,AgentTaskStore.hash(encoded),verified,encoded)
                    check(verified) { "原生独立核验未通过，不会签发成功回执" };completed=found
                }
            }
            guard.cancelAndJoin();validate();available(foreground)
            val task=scheduler.get(owner,id)
            scheduler.requireCloudHandoffSafe(owner,id)
            val result=CloudNativeEvidence.result(owner,task,pending.getValue("inputHash").jsonPrimitive.content,requireNotNull(completed),materials)
            val receipt=workspace.signed(buildJsonObject { put("owner",owner);put("taskId",record.getValue("remoteId"));put("callId",pending.getValue("callId"))
                put("instance",instance);put("fence",claimed.getValue("fence"));put("result",result) })
            local=workspace.delegated(owner,id)!!;workspace.delegation(id,JsonObject(local+("deviceReceipt" to receipt)))
            val ack=workspace.rpcFor(record,"device.finish",receipt)
            workspace.delegation(id,JsonObject(workspace.delegated(owner,id)!!.filterKeys { it!="deviceReceipt" && it!="deviceLease" }))
            envelope(ack)
        } finally {
            withContext(NonCancellable) {
                guard.cancelAndJoin()
                try { settleDevice(owner,id) }
                catch(_: Exception) {
                    val pending=workspace.delegated(owner,id)
                    if(pending?.get("deviceLease")!=null)workspace.delegation(id,JsonObject(pending+("deviceRecovery" to JsonPrimitive("UNCONFIRMED_REQUIRES_READBACK"))))
                }
                finally { CloudDeviceControl.finish(owner,id,job) }
            }
        }
    }
}

/** UI Stop cancels the actual structured scope; it never claims that a cancellation request is a stop receipt. */
internal object CloudDeviceControl {
    private data class Active(val owner: String,val id: String,val job: Job)
    private var active: Active?=null
    @Synchronized fun start(owner: String,id: String,job: Job) { check(active==null) { "已有设备执行器" };active=Active(owner,id,job) }
    @Synchronized fun stop(owner: String,id: String) { active?.takeIf { it.owner==owner && it.id==id }?.job?.cancel() }
    @Synchronized fun finish(owner: String,id: String,job: Job) { if(active?.job===job)active=null }
}
