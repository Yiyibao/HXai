package me.rerere.rikkahub.feature.agent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.material.MaterialFiles
import me.rerere.rikkahub.feature.studychat.LearnerProfile
import me.rerere.rikkahub.feature.studychat.StudyTutorDefaults
import me.rerere.rikkahub.service.ChatService
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import kotlin.uuid.Uuid

/** A stopped synthetic fixture is not a real configured account; never generalize this guard. */
internal fun isKnownLocalQualityFixture(value: ProviderSetting): Boolean {
    val provider=value as? ProviderSetting.OpenAI ?: return false
    val uri=runCatching {URI(provider.baseUrl)}.getOrNull() ?: return false
    return provider.apiKey=="synthetic" && provider.name in setOf("QA shared math","QA response") &&
        uri.scheme=="http" && uri.host=="127.0.0.1" && uri.port in 1024..65535 && uri.path=="/v1" &&
        uri.userInfo==null && uri.rawQuery==null && uri.fragment==null && !provider.useResponseApi &&
        provider.models.isNotEmpty() && provider.models.all {it.modelId in setOf("qa-shared-math","qa-response")}
}

/** Explicit opt-in only. The production Chat owns every request, lock, journal and receipt. */
class LiveQualityEvaluationTest {
    private fun hash(value: String)=AgentTaskStore.hash(value)
    private fun save(file: File,value: JsonObject) {
        QualityEvidenceFiles.write(file, value.toString().toByteArray(Charsets.UTF_8))
    }
    @Test fun authorizedPublicCasesUseOriginalChatAndSealResultsBeforeAssessment(): Unit = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Explicit public-input and paid-call authorization is required",
            args.getString("qualityLiveConsent")=="PUBLIC_INPUTS_PINNED_PROVIDER_MEDIUM")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="org.hxsi.agent.nativeqa") { "Dedicated QA package required" }
        val runId=requireNotNull(args.getString("qualityRunId"))
        require(runId.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        val base=File(context.noBackupFilesDir,"quality-evaluation")
        val manifestFile=File(base,"$runId-input.json")
        check(manifestFile.length() in 1..262144)
        val manifest=Json.parseToJsonElement(manifestFile.readText()).jsonObject
        require(manifest.getValue("modelId")==JsonPrimitive("gpt-6.1-terra") &&
            manifest.getValue("endpoint")==JsonPrimitive("https://xinyue.mom/v1") &&
            manifest.getValue("reasoning")==JsonPrimitive("MEDIUM") && manifest.getValue("store")==JsonPrimitive(false))
        val cases=manifest.getValue("cases").jsonArray
        require(cases.size in 1..40 && cases.map { it.jsonObject.getValue("id") }.distinct().size==cases.size)
        val folder=File(base,runId);check(!folder.exists() && folder.mkdirs())
        save(File(folder,"INPUT_BINDING.json"),buildJsonObject { put("inputSha256",hash(manifestFile.readText()));put("modelId","gpt-6.1-terra");put("reasoning","MEDIUM");put("store",false) })
        // One-shot random-nonce loopback provisioning. The key is never a file/argument/log.
        val seedOwner=args.getString("qualityCredentialSeedOwner")
        val seedTask=args.getString("qualityCredentialSeedTask")
        require((seedOwner==null)==(seedTask==null))
        val secret=if(seedTask!=null) {
            check(args.getString("qualityReuseConsent")=="PUBLIC_QA_ENCRYPTED_SEED_SAME_PROVIDER")
            requireNotNull(seedOwner)
            AgentTaskStore(context).use {store ->
                check(!store.readableDatabase.rawQuery("SELECT 1 FROM calls WHERE tool LIKE 'provider:%' AND state IN ('IN_FLIGHT','UNKNOWN') LIMIT 1",null).use {it.moveToFirst()}) {
                    "Unsettled physical provider request exists; no new paid run"
                }
                val task=store.get(seedOwner,seedTask)
                check(task.state=="SUCCEEDED" && task.reasoning=="MEDIUM")
                check(store.control(seedOwner,seedTask).authorization=="ACTIVE") {"Revoked seed is not reusable"}
                check(!store.readableDatabase.rawQuery("SELECT 1 FROM calls WHERE task_id=? AND state IN ('IN_FLIGHT','UNKNOWN') LIMIT 1",arrayOf(task.id)).use {it.moveToFirst()})
                val encoded=CredentialVault(context).resolve(task.configurationReference)
                check(AgentTaskStore.hash(encoded)==task.configurationRevision)
                val config=Json {ignoreUnknownKeys=true}.decodeFromString<GenerationConfiguration>(encoded)
                val selected=requireNotNull(config.settings.findModelById(config.assistant.chatModelId ?: config.settings.chatModelId))
                val pinned=requireNotNull(selected.findProvider(config.settings.providers)) as ProviderSetting.OpenAI
                check(task.providerId==pinned.id.toString() && task.modelId==selected.id.toString() &&
                    selected.modelId=="gpt-6.1-terra" && pinned.baseUrl=="https://xinyue.mom/v1" && pinned.useResponseApi &&
                    config.assistant.reasoningLevel==ReasoningLevel.MEDIUM)
                // Remains in trusted App memory/vault; never export the key or replay its source task.
                pinned.apiKey
            }
        } else {
        val provision=URI(requireNotNull(args.getString("qualityProvisionUrl")))
        require(provision.scheme=="http" && provision.host=="127.0.0.1" && provision.port in 1024..65535 &&
            provision.userInfo==null && provision.rawQuery==null && provision.path.matches(Regex("/[a-f0-9]{64}")))
        val connection=provision.toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects=false;connection.connectTimeout=15000;connection.readTimeout=15000
        try {
            check(connection.responseCode==200) { "One-shot provisioning unavailable" }
            connection.inputStream.bufferedReader().use { stream ->
                val text=StringBuilder();val chars=CharArray(1024)
                while(true) {val count=stream.read(chars);if(count<0)break;text.append(chars,0,count);check(text.length<=4096)}
                Json.parseToJsonElement(text.toString()).jsonObject.getValue("apiKey").jsonPrimitive.content
            }
        } finally { connection.disconnect() }
        }
        require(secret.length in 16..2048)
        val k=GlobalContext.get();val settings=k.get<SettingsStore>();val service=k.get<ChatService>()
        val repo=k.get<ConversationRepository>();val files=k.get<MaterialFiles>();val scheduler=AgentScheduler.shared(context)
        val original=withTimeout(20000) { settings.settingsFlow.first { !it.init } }
        check(original.providers.none { it is ProviderSetting.OpenAI && it.apiKey.isNotBlank() && !isKnownLocalQualityFixture(it) }) {
            "A real configured account cannot be replaced by the public QA harness"
        }
        val owner=settings.createLearnerProfile("Public evaluation run")
        val model=Model(modelId="gpt-6.1-terra",displayName="Authorized Terra quality run",
            abilities=listOf(ModelAbility.TOOL,ModelAbility.REASONING))
        val provider=ProviderSetting.OpenAI(name="Authorized public evaluation",baseUrl="https://xinyue.mom/v1",
            apiKey=secret,useResponseApi=true,models=listOf(model))
        val assistant=Assistant(name="Public quality evaluation",chatModelId=model.id,reasoningLevel=ReasoningLevel.MEDIUM,
            streamOutput=false,localTools=emptyList(),enableMemory=false,enabledSkills=emptySet(),
            systemPrompt=StudyTutorDefaults.systemPrompt+"\n本次只处理公开评估输入。不调用网页、图像生成、外部扩展、OCR或其他角色模型。只能使用本机文件/来源/精确数学工具；不得发送或读取其他档案。未知收费请求不得重试。")
        val rows=ArrayList<JsonElement>();var stopped=false
        val observer=QualityCoordinatorObserver(k.get<ProviderManager>(),context,secret)
        try {
            settings.update(original.copy(providers=listOf(provider),assistants=listOf(assistant),assistantId=assistant.id,
                chatModelId=model.id,activeProfileId=owner,learnerProfiles=listOf(LearnerProfile(owner,"Public evaluation")),
                enableSuggestion=false,searchServices=emptyList(),
                networkSetting=original.networkSetting.copy(proxyUrl="",enableAutoRetry=false)))
            for (item in cases) {
                val entry=item.jsonObject;val caseId=entry.getValue("id").jsonPrimitive.content
                require(caseId.matches(Regex("[a-zA-Z0-9_-]{1,100}")))
                val prompt=entry.getValue("prompt").jsonPrimitive.content
                require(prompt.length in 1..12000)
                if(stopped) {
                    rows+=buildJsonObject { put("id",caseId);put("engineeringStatus","NOT_RUN");put("response","");put("reason","Earlier execution was not safely settled") }
                    continue
                }
                val caseOwner=settings.createLearnerProfile("Public "+caseId.take(25))
                withTimeout(20000) { settings.settingsFlow.first { it.learnerProfiles.any { profile -> profile.id==caseOwner } } }
                withTimeout(20000) { service.switchLearnerProfile(caseOwner) }
                val owner=caseOwner
                val id=Uuid.random();val caseFolder=File(folder,caseId);check(caseFolder.mkdir())
                observer.observeCase(caseFolder)
                service.addConversationReference(id)
                service.saveConversation(id,Conversation.ofId(id,assistant.id).copy(profileId=owner,title=caseId,newConversation=true))
                var execution="PASS"
                try {
                    // Persist dispatch intent before any possible paid physical request. No implicit replay.
                    save(File(caseFolder,"DISPATCH_INTENT.json"),buildJsonObject { put("conversationId",id.toString());put("promptSha256",hash(prompt)) })
                    service.sendMessageCommitted(id,listOf(UIMessagePart.Text(prompt)),Uuid.random())
                    withTimeout(300000) {
                        while(true) {
                            val conversation=service.getConversationFlow(id).first()
                            if(conversation.currentMessages.lastOrNull()?.role==me.rerere.ai.core.MessageRole.ASSISTANT &&
                                conversation.currentMessages.last().generationState in setOf("COMPLETED","FAILED","INTERRUPTED") &&
                                service.getGenerationJobStateFlow(id).first()==null)break
                            delay(250)
                        }
                    }
                } catch(_: Exception) { execution="FAIL";service.stopGeneration(id) }
                val current=repo.getConversationById(id,owner) ?: error("Evaluation conversation disappeared")
                val task=scheduler.current(current)
                if(task!=null) check(task.providerId==provider.id.toString() && task.modelId==model.id.toString() &&
                    task.reasoning=="MEDIUM") { "Accepted configuration changed; preserve evidence, stop evaluation" }
                val originalVisible=current.currentMessages.filter { it.role==me.rerere.ai.core.MessageRole.ASSISTANT }
                    .flatMap { it.parts }.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                val visible=originalVisible.replace(secret,"[REDACTED]").replace(Regex("sk-[A-Za-z0-9_-]{12,}"),"[REDACTED]")
                val responseFile=File(caseFolder,"RESPONSE.json")
                save(responseFile,buildJsonObject { put("id",caseId);put("response",visible);put("responseSha256",hash(visible));put("originalResponseSha256",hash(originalVisible));put("credentialRedacted",visible!=originalVisible);put("hiddenReasoningExported",false) })
                val receipt=buildJsonObject {
                    put("id",caseId);put("taskId",task?.id?.let(::JsonPrimitive) ?: JsonNull)
                    put("taskState",task?.state?.let(::JsonPrimitive) ?: JsonNull)
                    put("acceptanceVerified",task?.let { scheduler.plan(owner,it.id).acceptanceVerified } ?: false)
                    put("errors",JsonArray(service.errors.value.filter { it.conversationId==id }.map { error -> buildJsonObject {
                        put("type",error.error.javaClass.simpleName)
                        put("message",error.error.message.orEmpty().replace(secret,"[REDACTED]")
                            .replace(Regex("sk-[A-Za-z0-9_-]{12,}"),"[REDACTED]").take(2048))
                        put("frames",JsonArray(error.error.stackTrace.take(8).map { JsonPrimitive(it.toString()) }))
                    } }))
                    put("tools",JsonArray(current.currentMessages.flatMap { it.getTools() }.map { tool -> buildJsonObject {
                        put("callId",tool.toolCallId);put("name",tool.toolName);put("inputSha256",hash(tool.input));put("state",tool.executionState.toString())
                        put("publicInput",tool.input)
                    } }))
                    put("coordinatorObservations",JsonArray(caseFolder.listFiles().orEmpty().filter {it.name.startsWith("COORDINATOR_")}.sortedBy {it.name}.map {file ->
                        buildJsonObject {put("file",file.name);put("sha256",MaterialFiles.digest(file))}
                    }))
                    put("artifacts",JsonArray(files.list(owner).filter { it.output && it.state=="SUCCEEDED" }.map { artifact ->
                        val owned=files.verifiedFile(owner,artifact.id)
                        val copy=File(caseFolder,artifact.id+"."+artifact.extension)
                        owned.copyTo(copy,overwrite=false);check(MaterialFiles.digest(copy)==artifact.sha256)
                        buildJsonObject { put("id",artifact.id);put("sha256",artifact.sha256);put("extension",artifact.extension);put("file",copy.name) }
                    }))
                }
                save(File(caseFolder,"RECEIPT.json"),receipt)
                val usage=if(task!=null) scheduler.captureUsage(owner,task.id,null,null,"requests","evaluation:$runId:$caseId")
                    else buildJsonObject { put("provenance","unknown");put("reason","No accepted task available") }
                save(File(caseFolder,"USAGE.json"),usage)
                if(task==null || task.state!="SUCCEEDED")execution="FAIL"
                val snapshot=task?.let { buildJsonObject { put("providerId",it.providerId);put("endpoint","https://xinyue.mom/v1")
                    put("modelId","gpt-6.1-terra");put("reasoning",it.reasoning);put("configRevision",it.configurationRevision) } }
                rows+=buildJsonObject { put("id",caseId);put("promptSha256",hash(prompt));put("engineeringStatus",execution)
                    put("response",visible);put("taskId",task?.id?.let(::JsonPrimitive) ?: JsonNull);put("modelSnapshot",snapshot ?: JsonNull)
                    put("receiptSha256",hash(receipt.toString()));put("usageSha256",hash(usage.toString())) }
                save(File(caseFolder,"CASE.json"),rows.last().jsonObject)
                stopped=task==null || task.state in setOf("RUNNING","STOP_REQUESTED","UNCERTAIN_SIDE_EFFECT","QUEUED") ||
                    (usage["records"] as? JsonArray).orEmpty().any { record ->
                        record.jsonObject["state"]?.jsonPrimitive?.content in setOf("PREPARED","IN_FLIGHT","UNKNOWN") }
                service.removeConversationReference(id)
            }
            save(File(folder,"RUN_OUTPUT.json"),buildJsonObject { put("contractVersion",1);put("origin","ACTUAL_ORIGINAL_ANDROID_CHAT")
                put("runId",runId);put("cases",JsonArray(rows));put("mathematics","NOT_EVALUATED");put("independentReview","NOT_EVALUATED") })
        } finally {
            observer.close()
            // Preserve tasks, encrypted credential snapshots, receipts and UNKNOWN; no cleanup/replay.
            settings.update(original)
            service.switchLearnerProfile(original.activeProfileId)
        }
    }
}
