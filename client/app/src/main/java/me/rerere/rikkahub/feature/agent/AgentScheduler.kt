package me.rerere.rikkahub.feature.agent

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.CredentialVault
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import me.rerere.ai.core.ProviderRequestAccounting
import me.rerere.ai.core.ProviderRequestReceipt
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
internal data class GenerationConfiguration(val settings: Settings, val assistant: Assistant)
internal data class AcceptedModelIdentity(val providerName: String,val modelName: String,val origin: String)

internal class AgentExecution(
    val store: AgentTaskStore, val lease: AgentLease, val configuration: GenerationConfiguration,
    val verifyTool: suspend (me.rerere.ai.ui.UIMessagePart.Tool) -> Boolean,
    private val validate: () -> Unit,
    private val validateProvider: (ProviderSetting, me.rerere.ai.provider.Model) -> Unit,
    val configurationReference: String,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AgentExecution>
    fun check() { validate(); store.check(lease) }
    fun beginCall(callId: String, tool: String, input: String) { check(); store.beginCall(lease,callId,tool,input) }
    fun accounting(role: String, validateRole: (() -> Unit)? = null): ProviderRequestAccounting =
        object : ProviderRequestAccounting() {
            override fun begin(provider: ProviderSetting, params: TextGenerationParams, body: JsonObject, encodedBody: String): ProviderRequestReceipt {
                check()
                validateRole?.invoke()
                val settings = configuration.settings
                val selectedId = when (role) {
                    "ocr" -> settings.ocrModelId
                    "image" -> settings.imageGenerationModelId
                    "context_summary" -> settings.compressModelId
                    else -> configuration.assistant.chatModelId ?: settings.chatModelId
                }
                val selected = requireNotNull(settings.findModelById(selectedId)) { "辅助角色没有已锁定的模型配置。" }
                require(params.model == selected && provider == selected.findProvider(settings.providers)) { "Provider request differs from the accepted role configuration" }
                validateProvider(provider,selected)
                val reasoning = when (role) {
                    "ocr" -> ReasoningLevel.OFF
                    "context_summary" -> ReasoningLevel.AUTO
                    "image" -> if (configuration.assistant.chatModelId == settings.imageGenerationModelId)
                        configuration.assistant.reasoningLevel else ReasoningLevel.OFF
                    else -> configuration.assistant.reasoningLevel
                }
                require(params.reasoningLevel == reasoning) { "Provider request reasoning differs from the accepted role configuration" }
                body["model"]?.let {
                    require(it.jsonPrimitive.contentOrNull == selected.modelId) { "Custom request body cannot replace the locked model" }
                }
                val requestId = store.beginRequest(lease,role,encodedBody,AgentRequestSelection(
                    provider.id.toString(),selected.id.toString(),reasoning.name,configurationReference))
                return object : ProviderRequestReceipt() {
                    override fun complete(usage: TokenUsage?) {
                        store.finishRequest(lease,requestId,usage)
                        store.reconcileKnownResults(lease)
                    }
                    override fun authorizationRejected(status: Int) { store.rejectAuthorization(lease,requestId,status) }
                }
            }
        }
}

/** Wraps the existing executor. No alternative model loop or per-profile execution mutex. */
internal class AgentScheduler(context: Context, databaseName: String = "agent-tasks-v1.db") : AutoCloseable {
    private val store = AgentTaskStore(context,databaseName)
    private val vault = CredentialVault(context)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val execution = Mutex()
    private val running = java.util.concurrent.ConcurrentHashMap<Job,AgentTask>()
    private val modelIdentities=android.util.LruCache<String,AcceptedModelIdentity>(64)
    private val domain = context.noBackupFilesDir.resolve(databaseName).canonicalPath
    @Volatile private var closed = false
    init {
        check(liveSchedulers.putIfAbsent(domain,this)==null) { "A task executor already owns this local domain" }
        try { store.recover() }
        catch (error: Throwable) { liveSchedulers.remove(domain,this); store.close(); throw error }
    }

    fun accept(conversation: Conversation, requestId: String, intent: String, settings: Settings): AgentTask {
        check(!closed) { "Task executor closed" }
        require(conversation.profileId==settings.activeProfileId) { "Task configuration owner mismatch" }
        store.find(conversation.profileId,conversation.id.toString(),requestId)?.let { previous ->
            // The store binds the request identity to input even when the caller retries an ACK.
            return store.accept(previous.owner,previous.conversationId,requestId,intent,previous.configurationReference,previous.providerId,previous.modelId,previous.reasoning,previous.configurationRevision)
        }
        val assistant = settings.getAssistantById(conversation.assistantId) ?: settings.getCurrentAssistant()
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId) ?: error("No chat model selected")
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        require(provider.enabled && model.findProvider(settings.providers,checkOverwrite=false)?.enabled == true) { "Provider disabled" }
        // JSON roundtrip detaches mutable ProviderSetting fields from live Settings.
        val encoded = json.encodeToString(GenerationConfiguration(settings,assistant))
        val reference = vault.protect(encoded)
        return store.accept(conversation.profileId,conversation.id.toString(),requestId,intent,reference,provider.id.toString(),model.id.toString(),assistant.reasoningLevel.name,AgentTaskStore.hash(encoded))
    }
    fun get(owner: String, taskId: String) = store.get(owner,taskId)
    fun readUsage(owner: String,taskId: String?,from: Long?,until: Long?,cursor: String?,limit: Int) =
        store.readUsage(owner,taskId,from,until,cursor,limit)
    fun control(owner: String,id: String) = store.control(owner,id)
    fun plan(owner: String,id: String) = store.plan(owner,id)
    fun snapshot(owner: String,id: String) = store.snapshot(owner,id)
    fun approvalCallIds(owner: String,id: String) = store.approvalCallIds(owner,id)
    fun toolDecisions(task: AgentTask) = store.toolDecisions(task.owner,task.id)
    fun reconcileApprovalWaits(task: AgentTask,tools: List<me.rerere.ai.ui.UIMessagePart.Tool>) {
        val current=store.get(task.owner,task.id)
        store.reconcileApprovalWaits(current.owner,current.id,current.revision,current.fence,tools)
    }
    fun decideTool(task: AgentTask,tool: me.rerere.ai.ui.UIMessagePart.Tool,decision: me.rerere.ai.ui.ToolApprovalState) {
        store.decideTool(task.owner,task.id,tool,decision,store.control(task.owner,task.id).grantEpoch)
    }
    fun recordedRemoteTask(conversation: Conversation,tool: me.rerere.ai.ui.UIMessagePart.Tool): AgentTask? =
        store.recordedRemoteTask(conversation.profileId,conversation.id.toString(),tool)
    fun commandRecorded(owner: String,id: String,requestId: String) = store.commandRecorded(owner,id,requestId)
    suspend fun command(owner: String,id: String,requestId: String,revision: Long,fence: Long,action: String,payload: String): AgentTask {
        val result=try { store.command(owner,id,requestId,revision,fence,action,payload) }
        catch (_: IllegalArgumentException) { throw AgentCommandRejected() }
        catch (_: IllegalStateException) { throw AgentCommandRejected() }
        if(action in setOf("pause","cancel","revoke")) {
            val jobs=running.entries.filter { it.value.owner==owner && it.value.id==id }.map { it.key }
            check(currentCoroutineContext()[Job] !in jobs)
            jobs.forEach { it.cancel() };jobs.joinAll()
        }
        return store.get(owner,result.id)
    }
    private fun selectedProvider(config: GenerationConfiguration): ProviderSetting {
        val model=requireNotNull(config.settings.findModelById(config.assistant.chatModelId ?: config.settings.chatModelId))
        return model.providerOverwrite ?: requireNotNull(model.findProvider(config.settings.providers,checkOverwrite=false))
    }
    private fun key(provider: ProviderSetting): String = when(provider) {
        is ProviderSetting.OpenAI -> provider.apiKey
        is ProviderSetting.Claude -> provider.apiKey
        is ProviderSetting.Google -> provider.apiKey
    }
    private fun replaceKey(config: GenerationConfiguration,value: String): GenerationConfiguration {
        when(val provider=selectedProvider(config)) {
            is ProviderSetting.OpenAI -> provider.apiKey=value
            is ProviderSetting.Claude -> provider.apiKey=value
            is ProviderSetting.Google -> provider.apiKey=value
        }
        return config
    }
    private fun configuration(task: AgentTask,reference: String): GenerationConfiguration {
        val candidate=json.decodeFromString<GenerationConfiguration>(vault.resolve(reference))
        if(reference!=task.configurationReference) {
            val original=json.decodeFromString<GenerationConfiguration>(vault.resolve(task.configurationReference))
            require(json.encodeToString(replaceKey(original,key(selectedProvider(candidate))))==json.encodeToString(candidate)) {
                "Verified credentials cannot change the accepted model configuration"
            }
        }
        return candidate
    }
    fun modelIdentity(owner: String,id: String): AcceptedModelIdentity {
        val task=store.get(owner,id)
        modelIdentities.get(task.configurationReference)?.let { return it }
        val config=configuration(task,task.configurationReference)
        val model=requireNotNull(config.settings.findModelById(config.assistant.chatModelId ?: config.settings.chatModelId))
        val provider=selectedProvider(config)
        val url=when(provider) {
            is ProviderSetting.OpenAI -> provider.baseUrl
            is ProviderSetting.Claude -> provider.baseUrl
            is ProviderSetting.Google -> provider.baseUrl
        }
        val uri=android.net.Uri.parse(url)
        val origin="${requireNotNull(uri.scheme)}://${requireNotNull(uri.host)}"+if(uri.port>=0) ":${uri.port}" else ""
        return AcceptedModelIdentity(provider.name,model.modelId,origin).also { modelIdentities.put(task.configurationReference,it) }
    }
    suspend fun updateCredential(owner: String,id: String,requestId: String,revision: Long,fence: Long,newKey: String,
        providers: me.rerere.ai.provider.ProviderManager,liveSettings: () -> Settings,validate: () -> Unit): AgentTask {
        require(requestId.length in 1..200 && newKey.isNotBlank() && newKey.length<=16384)
        validate()
        val task=store.get(owner,id)
        val digest=AgentTaskStore.hash("$owner:$id:$requestId:$revision:$fence:${AgentTaskStore.hash(newKey)}")
        if(store.credentialApplied(owner,id,requestId,digest)) return task
        var intent=store.credentialValidation(owner,id,requestId)
        if(intent!=null) {
            require(intent.inputHash==digest) { "Credential command identity changed" }
        } else {
            require(task.revision==revision && task.fence==fence) { "Task revision or fence has changed" }
            val control=store.control(owner,id)
            check(control.authorization=="EXPIRED" && !control.cancelRequested) { "Only expired original authorization can be restored" }
            check(providerEnabled(task,liveSettings())) { "The original provider is disabled; credentials cannot reauthorize it" }
            val scopeHash=store.grantScopeHash(owner,id)
            val config=replaceKey(configuration(task,store.credentialReference(owner,id) ?: task.configurationReference),newKey)
            val conversation=Conversation.ofId(kotlin.uuid.Uuid.parse(task.conversationId),config.assistant.id).copy(profileId=owner)
            val validation=accept(conversation,"role:credential_validation:$id:$requestId","$digest:${control.grantEpoch}:$scopeHash",config.settings)
            intent=store.registerCredentialValidation(owner,id,requestId,digest,validation.id,revision,fence,control.grantEpoch,scopeHash)
        }
        return continueCredential(task,requireNotNull(intent),providers,liveSettings,validate)
    }
    suspend fun continueCredential(owner: String,id: String,requestId: String,
        providers: me.rerere.ai.provider.ProviderManager,liveSettings: () -> Settings,validate: () -> Unit): AgentTask {
        validate()
        val task=store.get(owner,id)
        val intent=requireNotNull(store.credentialValidation(owner,id,requestId)) { "No accepted credential validation" }
        return continueCredential(task,intent,providers,liveSettings,validate)
    }
    private suspend fun continueCredential(task: AgentTask,accepted: CredentialValidationIntent,
        providers: me.rerere.ai.provider.ProviderManager,liveSettings: () -> Settings,validate: () -> Unit): AgentTask {
        val owner=task.owner
        val id=task.id
        if(store.credentialApplied(owner,id,accepted.requestId,accepted.inputHash)) return store.get(owner,id)
        val validation=store.get(owner,accepted.validationTaskId)
        configuration(task,validation.configurationReference)
        val validateScope={
            validate()
            val control=store.control(owner,id)
            check(control.authorization=="EXPIRED" && !control.cancelRequested && control.grantEpoch==accepted.grantEpoch &&
                store.grantScopeHash(owner,id)==accepted.scopeHash && providerEnabled(task,liveSettings())) { "Original authorization scope changed" }
        }
        val noRequest=store.physicalRequestCount(owner,validation.id)==0
        val resume=noRequest && (validation.state=="INTERRUPTED" || validation.state=="PAUSED_BY_USER" || validation.state=="FAILED_BLOCKED")
        if(validation.state=="QUEUED" || resume) run(validation,liveSettings,validateScope,{ "COMPLETED_UNVERIFIED" },{ false },
            resumeRequested=resume,resumeManualPause=true) {
            val active=requireNotNull(currentCoroutineContext()[AgentExecution])
            val config=active.configuration
            val model=requireNotNull(config.settings.findModelById(config.assistant.chatModelId ?: config.settings.chatModelId))
            val provider=requireNotNull(model.findProvider(config.settings.providers))
            withContext(active.accounting("credential_validation",validateScope)) {
                providers.getProviderByType(provider).generateText(provider,
                    listOf(me.rerere.ai.ui.UIMessage.user("Reply OK for credential verification.")),
                    TextGenerationParams(model=model,reasoningLevel=config.assistant.reasoningLevel,
                        temperature=config.assistant.temperature,topP=config.assistant.topP,maxTokens=config.assistant.maxTokens,
                        customHeaders=config.assistant.customHeaders+model.customHeaders,
                        customBody=config.assistant.customBodies+model.customBodies,sessionId=validation.id))
            }
        }
        validateScope()
        return store.installCredential(owner,id,accepted.requestId,accepted.inputHash,accepted.grantEpoch,accepted.scopeHash,
            validation.configurationReference,validation.id)
    }
    fun current(conversation: Conversation) = store.current(conversation.profileId,conversation.id.toString())
    fun queued(conversation: Conversation, inputId: String): AgentTask? =
        store.find(conversation.profileId,conversation.id.toString(),inputId)
    fun resumeQueued(conversation: Conversation) {
        conversation.queue.messages.filter { it.answer }.forEach { input ->
            store.find(conversation.profileId,conversation.id.toString(),input.id.toString())?.let { task ->
                if (task.state!="QUEUED" || task.manualPaused) store.resume(task.owner,task.id)
            }
        }
    }
    fun acceptQueued(conversation: Conversation, input: me.rerere.rikkahub.data.model.PendingInput, settings: Settings?) {
        val intent = json.encodeToString(input.parts)
        val previous = store.find(conversation.profileId,conversation.id.toString(),input.id.toString())
        if(previous == null) accept(conversation,input.id.toString(),intent,requireNotNull(settings) { "此历史输入没有持久模型快照；请重新提交。" })
        else store.reviseQueued(conversation.profileId,previous.id,intent)
    }
    fun validateExistingInput(conversation: Conversation, inputId: String, parts: List<me.rerere.ai.ui.UIMessagePart>) {
        val previous = store.find(conversation.profileId,conversation.id.toString(),inputId) ?: return
        store.accept(previous.owner,previous.conversationId,inputId,json.encodeToString(parts),previous.configurationReference,
            previous.providerId,previous.modelId,previous.reasoning,previous.configurationRevision)
    }

    fun providerEnabled(task: AgentTask, settings: Settings): Boolean =
        providerEnabled(task.providerId,task.modelId,settings)
    private fun providerEnabled(providerId: String, modelId: String, settings: Settings): Boolean = settings.providers.any { source ->
        source.enabled && (source.id.toString()==providerId || source.models.any { selected ->
            selected.id.toString()==modelId && selected.providerOverwrite?.let { it.id.toString()==providerId && it.enabled }==true
        })
    }

    suspend fun stop(conversation: Conversation) {
        store.pausePending(conversation.profileId,conversation.id.toString())
        val jobs = running.entries.filter { it.value.owner==conversation.profileId && it.value.conversationId==conversation.id.toString() }.map { it.key }
        check(currentCoroutineContext()[Job] !in jobs) { "Cannot join the calling executor" }
        jobs.forEach { it.cancel() }
        jobs.joinAll()
    }

    suspend fun requestRole(
        conversation: Conversation, role: String, settings: Settings, model: me.rerere.ai.provider.Model,
        reasoning: me.rerere.ai.core.ReasoningLevel, messages: List<me.rerere.ai.ui.UIMessage>,
        liveSettings: () -> Settings,
        validate: () -> Unit,
        call: suspend (me.rerere.ai.provider.Model, me.rerere.ai.provider.ProviderSetting) -> me.rerere.ai.provider.TextGenerationResult,
    ): me.rerere.ai.provider.TextGenerationResult {
        val assistant = (settings.getAssistantById(conversation.assistantId) ?: settings.getCurrentAssistant())
            .copy(chatModelId=model.id,reasoningLevel=reasoning)
        val roleSettings = settings.copy(assistants=listOf(assistant),assistantId=assistant.id,chatModelId=model.id)
        val input = json.encodeToString(messages)
        val task = accept(conversation,"role:$role:${java.util.UUID.randomUUID()}",input,roleSettings)
        return run(task,liveSettings,{ validate(); check(providerEnabled(task,liveSettings())) { "辅助调用的原提供商已停用；不会更换提供商。" } },{ "COMPLETED_UNVERIFIED" },{ false }) {
            val active = requireNotNull(currentCoroutineContext()[AgentExecution])
            val lockedModel = requireNotNull(active.configuration.settings.findModelById(active.configuration.assistant.chatModelId))
            val lockedProvider = requireNotNull(lockedModel.findProvider(active.configuration.settings.providers))
            withContext(active.accounting(role)) { call(lockedModel,lockedProvider) }
        }
    }

    suspend fun <T> run(task: AgentTask, liveSettings: () -> Settings, validate: () -> Unit, outcome: () -> String,
        verifyTool: suspend (me.rerere.ai.ui.UIMessagePart.Tool) -> Boolean,
        resumeRequested: Boolean = false, resumeManualPause: Boolean = false, block: suspend () -> T): T {
        val context = currentCoroutineContext()
        check(!closed) { "Task executor closed" }
        check(context[AgentExecution]==null) { "Nested independent tasks are not allowed" }
        val job = requireNotNull(context[Job])
        running[job] = task
        try {
            return execution.withLock {
                val activeValidate = { job.ensureActive(); validate() }
                activeValidate()
                val configurationReference=store.credentialReference(task.owner,task.id) ?: task.configurationReference
                val configuration = configuration(task,configurationReference)
                if (resumeRequested) store.resume(task.owner,task.id,allowManualPause=resumeManualPause)
                val lease = store.claim(task.owner,task.id)
                var state = "INTERRUPTED"
                try {
                    val validateProvider = { provider: ProviderSetting, model: me.rerere.ai.provider.Model ->
                        val live = liveSettings()
                        val unsavedConnectionTest=task.requestId.startsWith("role:connection_test:") &&
                            live.providers.none { it.id==provider.id }
                        check(live.learnerProfiles.any { it.id==task.owner } &&
                            (providerEnabled(provider.id.toString(),model.id.toString(),live) || unsavedConnectionTest && provider.enabled)) {
                            "此角色提供商授权已停用；不会切换提供商或模型。"
                        }
                    }
                    withContext(AgentExecution(store,lease,configuration,verifyTool,activeValidate,validateProvider,configurationReference)) {
                        activeValidate()
                        block().also { state = outcome() }
                    }
                } catch (error: CancellationException) {
                    val control=store.control(task.owner,task.id)
                    state=when {
                        control.cancelRequested -> "CANCELED"
                        control.authorization!="ACTIVE" -> "WAITING_AUTHORIZATION"
                        store.get(task.owner,task.id).manualPaused -> "PAUSED_BY_USER"
                        else -> "INTERRUPTED"
                    }
                    throw error
                } catch (error: Throwable) {
                    state = "FAILED_BLOCKED"
                    throw error
                } finally {
                    // Checkpoint before unlocking; unresolved calls retain the persistent slot.
                    withContext(NonCancellable) { store.finish(lease,state) }
                }
            }
        } finally {
            running.remove(job,task)
            if(!job.isActive) {
                val current=store.get(task.owner,task.id)
                if(current.state=="QUEUED") store.command(current.owner,current.id,
                    "cancel-before-claim:${current.id}",current.revision,current.fence,"pause")
            }
        }
    }
    override fun close() {
        check(!execution.isLocked && running.isEmpty()) { "Executor is still running" }
        closed = true
        store.close()
        liveSchedulers.remove(domain,this)
    }
    companion object {
        private val liveSchedulers = java.util.concurrent.ConcurrentHashMap<String,AgentScheduler>()
        @Synchronized fun shared(context: Context): AgentScheduler {
            val domain = context.noBackupFilesDir.resolve("agent-tasks-v1.db").canonicalPath
            return liveSchedulers[domain] ?: AgentScheduler(context.applicationContext)
        }
    }
}
