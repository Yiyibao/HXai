package me.rerere.rikkahub.flutter

import androidx.room.withTransaction
import me.rerere.rikkahub.flutter.generated.ProjectDto
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.net.toUri
import androidx.core.content.FileProvider
import io.flutter.plugin.common.EventChannel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.diagnostics.Diagnostics
import me.rerere.rikkahub.feature.material.MaterialFiles
import me.rerere.rikkahub.feature.memory.LearningMemoryStore
import me.rerere.rikkahub.feature.studychat.LearnerProfile
import me.rerere.rikkahub.feature.studychat.StudyMode
import me.rerere.rikkahub.flutter.generated.ArtifactDto
import me.rerere.rikkahub.flutter.generated.ConversationDetailDto
import me.rerere.rikkahub.flutter.generated.ConversationSummaryDto
import me.rerere.rikkahub.flutter.generated.DiagnosticsDto
import me.rerere.rikkahub.flutter.generated.LearnerProfileDto
import me.rerere.rikkahub.flutter.generated.MaterialFileDto
import me.rerere.rikkahub.flutter.generated.MemoryItemDto
import me.rerere.rikkahub.flutter.generated.MessageDto
import me.rerere.rikkahub.flutter.generated.MessageNodeDto
import me.rerere.rikkahub.flutter.generated.MessagePartDto
import me.rerere.rikkahub.flutter.generated.ModelDto
import me.rerere.rikkahub.flutter.generated.ProviderDto
import me.rerere.rikkahub.flutter.generated.SendMessageRequest
import me.rerere.rikkahub.flutter.generated.SimpleCommandResult
import me.rerere.rikkahub.flutter.generated.StudyBootstrapDto
import me.rerere.rikkahub.flutter.generated.StudyErrorDto
import me.rerere.rikkahub.flutter.generated.StudyHostApi
import me.rerere.rikkahub.service.ChatService
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.uuid.Uuid
import kotlinx.serialization.json.*
import me.rerere.rikkahub.feature.interactive.InteractiveSurface

/**
 * Kotlin Facade implementing the Pigeon typed bridge [StudyHostApi].
 * Acts as the single point of entry from Flutter into native business logic.
 * Respects profile isolation, persistence contracts, idempotency, and error propagation.
 */
class StudyHostFacade(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
    private val learningMemories: LearningMemoryStore,
    private val materialFiles: MaterialFiles,
    private val providerManager: ProviderManager,
    private val pickFiles: suspend () -> List<android.net.Uri>,
    private val saveFile: suspend (String, String) -> android.net.Uri? = { _, _ -> null },
    private val documents: me.rerere.rikkahub.feature.material.DocumentServiceRepository = org.koin.core.context.GlobalContext.get().get(),
) : StudyHostApi {

    /** Appearance-side document screens reuse the existing protected repository. */
    fun handleFrontendFeature(method: String, args: Map<*, *>, result: io.flutter.plugin.common.MethodChannel.Result) {
        scope.launch {
            try {
                val owner = args["profileId"] as? String ?: error("缺少档案")
                val epoch = (args["epoch"] as? Number)?.toLong() ?: error("缺少上下文版本")
                requireProfile(owner, epoch)
                val value: Any? = withContext(Dispatchers.IO) {
                    when (method) {
                        "imageConfig" -> imageCreation.modelInfo()
                        "selectImageModel" -> {
                            val id = Uuid.parse(args["modelId"] as? String ?: error("缺少模型"))
                            val model = settingsStore.settingsFlow.value.findModelById(id) ?: error("模型不存在")
                            require(model.findProvider(settingsStore.settingsFlow.value.providers) is ProviderSetting.OpenAI)
                            settingsStore.update { requireProfile(owner, epoch); it.copy(imageGenerationModelId = id) }
                            imageCreation.modelInfo()
                        }
                        "imageTemplates" -> imageTemplates.list(owner).map(imageTemplates::toMap)
                        "imageTemplateSave" -> commandMutex.withLock {
                            requireProfile(owner, epoch)
                            imageTemplates.toMap(imageTemplates.save(owner, args["id"] as? String,
                                args["name"] as? String ?: "", args["prompt"] as? String ?: "",
                                args["size"] as? String ?: "1024x1024", args["quality"] as? String ?: "low"))
                        }
                        "imageTemplateDelete" -> commandMutex.withLock {
                            requireProfile(owner, epoch)
                            imageTemplates.delete(owner, args["id"] as? String ?: error("缺少模板 ID")); null
                        }
                        "imageTasks" -> imageCreation.list(owner).map(imageCreation::toMap)
                        "imageStart" -> commandMutex.withLock {
                            requireProfile(owner, epoch)
                            val requestId = args["requestId"] as? String ?: error("缺少请求 ID")
                            val supplied = args["conversationId"] as? String
                            val conv = if (supplied != null) ownedConversation(owner, supplied) else {
                                val id = Uuid.parse(java.util.UUID.nameUUIDFromBytes("image-conversation:$owner:$requestId".toByteArray()).toString())
                                conversationRepo.getConversationById(id, owner) ?: Conversation.ofId(id, settingsStore.settingsFlow.value.getCurrentAssistant().id).copy(profileId = owner).also { conversationRepo.insertConversation(it) }
                            }
                            require(chatService.getProcessingStatusFlow(conv.id).value == null) { "请等待当前回复完成" }
                            val task = imageCreation.start(owner, conv.id.toString(), requestId, args["prompt"] as? String ?: "",
                                (args["references"] as? List<*>)?.map { it as String }.orEmpty(), args["parentId"] as? String,
                                args["size"] as? String ?: "1024x1024", args["quality"] as? String ?: "low") { changed ->
                                val url = changed.artifactId?.let { materialFiles.verifiedFile(owner, it).toUri().toString() }
                                chatService.commitImageCreation(changed, url)
                            }
                            imageCreation.toMap(task)
                        }
                        "imageCancel" -> { imageCreation.cancel(owner, args["id"] as? String ?: error("缺少图片 ID")); null }
                        "imagePick" -> {
                            val paths = withContext(Dispatchers.Main) { pickAttachments(owner, epoch) }
                            requireProfile(owner, epoch)
                            paths.map { path -> materialFiles.list(owner).single { materialFiles.file(it).canonicalPath == File(path).canonicalPath } }
                                .also { items -> require(items.size <= 4 && items.all { it.extension in setOf("png", "jpg", "jpeg", "webp") }) { "请选择最多四张 PNG/JPG/WebP 图片" } }
                                .map { mapOf("id" to it.id, "path" to materialFiles.verifiedFile(owner, it.id).absolutePath, "name" to it.name) }
                        }
                        "docConfig" -> documents.config(owner).let { mapOf("endpoint" to it.baseUrl, "enabled" to it.enabled, "hasCredential" to it.credentialReference.isNotBlank()) }
                        "saveDocConfig" -> {
                            commandMutex.withLock {
                                requireProfile(owner, epoch)
                                documents.configure(owner, args["endpoint"] as? String ?: "", args["token"] as? String ?: "", args["enabled"] == true)
                            }
                            null
                        }
                        "testDocService" -> documents.capabilities(owner).let { mapOf("ready" to (it["ready"]?.jsonPrimitive?.booleanOrNull == true)) }
                        "docTasks" -> documents.tasks(owner).map { task ->
                            val source = materialFiles.list(owner).firstOrNull { it.id == task.sourceId }
                            mapOf("id" to task.id, "sourceId" to task.sourceId, "fileName" to (source?.name ?: "源文件不可用"),
                                "start" to task.start, "end" to task.end, "state" to task.state,
                                "downloaded" to task.downloaded, "error" to task.errorCode)
                        }
                        "docTaskAction" -> {
                            val id = args["id"] as? String ?: error("缺少任务 ID")
                            val task = documents.tasks(owner).singleOrNull { it.id == id } ?: error("任务不属于当前档案")
                            requireProfile(owner, epoch)
                            when (args["action"]) {
                                "cancel" -> { require(task.state in setOf("SUBMITTING", "QUEUED", "RUNNING", "CANCELLING")); documents.cancel(owner, id) }
                                "refresh" -> documents.refresh(owner, id)
                                "download" -> { require(task.downloaded || task.state in setOf("SUCCEEDED", "PARTIAL")); documents.download(owner, id) }
                                "retry" -> { require(task.remoteId == null); documents.resumeSubmission(owner, id) }
                                else -> error("无效任务操作")
                            }
                            null
                        }
                        "chatContextSettings" -> mapOf("budget" to me.rerere.rikkahub.feature.studychat.ChatContextCache(context).budget(owner))
                        "saveChatContextSettings" -> {
                            val budget = (args["budget"] as? Number)?.toInt() ?: error("缺少预算")
                            require(budget in 4000..64000)
                            requireProfile(owner, epoch)
                            me.rerere.rikkahub.feature.studychat.ChatContextCache(context).setBudget(owner, budget)
                            null
                        }
                        "artifactVersions" -> {
                            val family = me.rerere.rikkahub.feature.material.MaterialVersions.family(materialFiles.list(owner), args["id"] as? String ?: error("缺少成果ID"))
                            family.map { mapOf("id" to it.id, "name" to it.name, "sha256" to it.sha256, "sourceId" to it.sourceId, "createdAt" to it.createdAt, "state" to it.state) }
                        }
                        "chatStatus" -> {
                            val conv = ownedConversation(owner, args["conversationId"] as? String ?: error("缺少会话"))
                            mapOf("processing" to chatService.getProcessingStatusFlow(conv.id).value,
                                "error" to if (conv.currentMessages.lastOrNull()?.generationState == "FAILED") chatService.errors.value.lastOrNull { it.conversationId == conv.id }?.error?.message?.take(500) else null)
                        }
                        "resumeChat" -> {
                            val conv = ownedConversation(owner, args["conversationId"] as? String ?: error("缺少会话"))
                            require(conv.currentMessages.lastOrNull()?.id.toString() == args["messageId"]) { "消息已改变，请刷新" }
                            require(conv.currentMessages.last().parts.filterIsInstance<UIMessagePart.Tool>().none { it.executionState in setOf(me.rerere.ai.ui.ToolExecutionState.INTERRUPTED, me.rerere.ai.ui.ToolExecutionState.SKIPPED_UNKNOWN, me.rerere.ai.ui.ToolExecutionState.RUNNING) }) { "请先核对工具执行结果" }
                            requireProfile(owner, epoch)
                            chatService.resumeInterruptedGeneration(conv.id)
                            null
                        }
                        "materialPath" -> materialFiles.verifiedFile(owner, args["id"] as? String ?: error("缺少文件 ID")).absolutePath
                        else -> error("未接入的前端功能")
                    }
                }
                requireProfile(owner, epoch)
                result.success(value)
            } catch (e: kotlinx.coroutines.CancellationException) {
                result.error("CANCELLED", "操作已中断", null)
            } catch (e: Exception) {
                result.error("FEATURE_FAILED", e.message ?: "操作失败", null)
            }
        }
    }

    private val imageCreation = me.rerere.rikkahub.feature.imagecreation.ImageCreation.get(context, settingsStore, materialFiles)
    private val imageTemplates = me.rerere.rikkahub.feature.imagecreation.ImageTemplates(context.filesDir.resolve("image_templates"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val contextEpoch = AtomicLong(1L)
    private val commandMutex = Mutex()
    private var observation: Job? = null
    private var documentObservation: Job? = null
    private var statusObservation: Job? = null
    private val documentErrors = ConcurrentHashMap<String, String>()
    private var observedId: Uuid? = null
    private val sequence = AtomicLong()
    private val watchVersion = AtomicLong()
    private val surfaceStates = context.getSharedPreferences("interactive_surface_states", Context.MODE_PRIVATE)

    private fun checkedSurface(conv: Conversation, messageId: String, toolCallId: String, revision: String): JsonObject {
        val message = conv.currentMessages.singleOrNull { it.id.toString() == messageId && it.role == MessageRole.ASSISTANT }
            ?: error("界面所属消息已不在当前分支")
        val tool = message.parts.filterIsInstance<UIMessagePart.Tool>().singleOrNull { it.toolCallId == toolCallId && InteractiveSurface.isSurfaceTool(it.toolName) }
            ?: error("交互界面不存在")
        require(tool.executionState == me.rerere.ai.ui.ToolExecutionState.COMPLETED)
        val definition = InteractiveSurface.definition(tool)
        require(InteractiveSurface.hash(definition) == revision) { "界面版本已过期" }
        InteractiveSurface.clientOutput(tool)
        return InteractiveSurface.validate(definition)
    }

    private fun surfaceKey(profileId: String, conversationId: String, messageId: String, toolCallId: String, revision: String) =
        InteractiveSurface.hash(listOf(profileId, conversationId, messageId, toolCallId, revision).toString())

    override suspend fun getSurfaceState(profileId: String, conversationId: String, messageId: String, toolCallId: String, revision: String): String = withContext(Dispatchers.IO) {
        val conv = ownedConversation(profileId, conversationId)
        val surface = checkedSurface(conv, messageId, toolCallId, revision)
        val key = surfaceKey(profileId, conversationId, messageId, toolCallId, revision)
        val defaults = buildJsonObject { surface.getValue("components").jsonArray.forEach {
            val c = it.jsonObject
            if (c["type"]?.jsonPrimitive?.content in setOf("slider", "select", "input")) put(c.getValue("id").jsonPrimitive.content, c.getValue("value"))
            if (c["selectable"]?.jsonPrimitive?.booleanOrNull == true) put(c.getValue("id").jsonPrimitive.content, JsonPrimitive(-1))
        } }
        val tool = conv.currentMessages.single { it.id.toString() == messageId }.parts.filterIsInstance<UIMessagePart.Tool>().single { it.toolCallId == toolCallId }
        val recorded = tool.metadata?.get(InteractiveSurface.STATE_KEY)?.jsonObject
        val legacy = surfaceStates.getString(key, null)?.let { Json.parseToJsonElement(it).jsonObject }
        val stored = if (recorded != null) {
            require(!recorded.getValue("version").jsonPrimitive.isString && recorded.getValue("version").jsonPrimitive.int == 1 && recorded.getValue("revision").jsonPrimitive.content == revision)
            recorded.getValue("values").jsonObject
        } else legacy ?: defaults
        InteractiveSurface.validateState(surface, stored)
        if (recorded == null && legacy != null) {
            chatService.saveInteractiveState(conv.id, messageId, toolCallId, revision, stored) { current -> requireProfile(profileId); checkedSurface(current, messageId, toolCallId, revision) }
            surfaceStates.edit().remove(key).apply()
        }
        stored.toString()
    }

    override suspend fun surfaceAction(profileId: String, conversationId: String, messageId: String, toolCallId: String, revision: String, stateJson: String, actionId: String?, requestId: String, expectedEpoch: Long): SimpleCommandResult = withContext(Dispatchers.IO) { commandMutex.withLock {
        requireProfile(profileId, expectedEpoch)
        val conv = ownedConversation(profileId, conversationId)
        val surface = checkedSurface(conv, messageId, toolCallId, revision)
        require(stateJson.length <= 65536)
        val values = InteractiveSurface.validateState(surface, Json.parseToJsonElement(stateJson).jsonObject)
        requireProfile(profileId, expectedEpoch)
        if (actionId != null) {
            val button = surface.getValue("components").jsonArray.map { it.jsonObject }.singleOrNull {
                it["id"]?.jsonPrimitive?.content == actionId && it["type"]?.jsonPrimitive?.content == "button" && it["action"]?.jsonPrimitive?.content == "continue"
            } ?: error("未登记的交互动作")
            require(requestId.isNotBlank() && requestId.length <= 200)
            val inputId = Uuid.parse(java.util.UUID.nameUUIDFromBytes("surface:$profileId:$conversationId:$messageId:$toolCallId:$requestId".toByteArray()).toString())
            val fingerprint = InteractiveSurface.hash("$revision:$actionId:$values")
            val previous = receipts.getString(inputId.toString(), null)
            require(previous == null || previous == fingerprint) { "动作请求内容已改变" }
            chatService.saveInteractiveState(conv.id, messageId, toolCallId, revision, values) { current -> requireProfile(profileId, expectedEpoch); checkedSurface(current, messageId, toolCallId, revision) }
            check(receipts.edit().putString(inputId.toString(), fingerprint).commit())
            val text = "用户提交交互界面 ${surface.getValue("title").jsonPrimitive.content} 的操作：${button.getValue("label").jsonPrimitive.content}。\n成果引用 message_id=$messageId tool_call_id=$toolCallId expected_revision=$revision。\n用户选择数据：$values"
            chatService.sendMessageCommitted(conv.id, listOf(UIMessagePart.Text(text)), inputId) { current ->
                requireProfile(profileId, expectedEpoch)
                checkedSurface(current, messageId, toolCallId, revision)
            }
        } else {
            chatService.saveInteractiveState(conv.id, messageId, toolCallId, revision, values) { current -> requireProfile(profileId, expectedEpoch); checkedSurface(current, messageId, toolCallId, revision) }
        }
        SimpleCommandResult(success = true)
    } }

    fun close() {
        watchVersion.incrementAndGet()
        scope.cancel()
        observedId?.let(chatService::removeConversationReference)
        observedId = null
        eventSink = null
    }
    private fun requireProfile(profileId: String, epoch: Long? = null) {
        require(profileId == settingsStore.settingsFlow.value.activeProfileId) { "档案已改变，请重新加载" }
        require(epoch == null || epoch == contextEpoch.get()) { "请求已过期，请重新加载" }
    }
    private suspend fun ownedConversation(profileId: String, id: String): Conversation {
        requireProfile(profileId)
        val uuid = Uuid.parse(id)
        conversationRepo.requireConversationAccess(uuid, profileId)
        chatService.initializeConversation(uuid)
        return chatService.getConversationFlow(uuid).value
    }

    override suspend fun watchConversation(profileId: String, conversationId: String?, expectedEpoch: Long): SimpleCommandResult {
        requireProfile(profileId, expectedEpoch)
        val watch = watchVersion.incrementAndGet()
        observation?.cancel()
        documentObservation?.cancel()
        statusObservation?.cancel()
        observedId?.let(chatService::removeConversationReference)
        observedId = null
        if (conversationId != null) {
            val conv = ownedConversation(profileId, conversationId)
            requireProfile(profileId, expectedEpoch)
            if (watch != watchVersion.get()) return SimpleCommandResult(success = true)
            chatService.addConversationReference(conv.id)
            observedId = conv.id
            statusObservation = scope.launch {
                combine(chatService.getProcessingStatusFlow(conv.id), chatService.errors) { processing, _ -> processing }
                    .collect {
                        requireProfile(profileId, expectedEpoch)
                        emitEvent("chat_status", profileId, mapOf("conversationId" to conversationId))
                    }
            }
            observation = scope.launch {
                var previous: Conversation? = null
                var wasGenerating: Boolean? = null
                var documentVersion: Any? = null
                combine(chatService.getConversationFlow(conv.id), chatService.getGenerationJobStateFlow(conv.id), documents.changes) { current, job, documents -> Triple(current, job?.isActive == true, documents) }
                    .collect { (current, generating, documents) ->
                        requireProfile(profileId, expectedEpoch)
                        val patches = if (generating == wasGenerating && documentVersion == documents) ChatDisplayPatch.between(previous, current) else null
                        if (patches == null) emitEvent("conversation_changed", profileId, mapOf("conversationId" to conversationId))
                        else if (patches.isNotEmpty()) emitEvent("message_patch", profileId, mapOf("conversationId" to conversationId, "changes" to patches))
                        previous = current
                        wasGenerating = generating
                        documentVersion = documents
                    }
            }
        }
        if (conversationId != null) documentObservation = scope.launch(Dispatchers.IO) {
            val delivered = mutableSetOf<String>()
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                try { syncChatDocuments(profileId, conversationId, expectedEpoch, delivered) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { /* State remains durable; retry uses the original task. */ }
                kotlinx.coroutines.delay(10_000)
            }
        }
        return SimpleCommandResult(success = true)
    }

    private fun documentTool(conv: Conversation, messageId: String, toolCallId: String): Pair<UIMessagePart.Tool, me.rerere.rikkahub.feature.material.DocumentTask> {
        val message = conv.currentMessages.singleOrNull { it.id.toString() == messageId && it.role == MessageRole.ASSISTANT } ?: error("文件任务已离开当前分支")
        val tool = message.parts.filterIsInstance<UIMessagePart.Tool>().singleOrNull { it.toolCallId == toolCallId && it.toolName == "parse_material_enhanced" && it.executionState == me.rerere.ai.ui.ToolExecutionState.COMPLETED } ?: error("文件任务不存在")
        val output = tool.output.filterIsInstance<UIMessagePart.Text>().firstOrNull()?.text ?: error("任务没有确认提交")
        val taskId = Json.parseToJsonElement(output).jsonObject.getValue("task_id").jsonPrimitive.content
        val task = documents.tasks(conv.profileId).single { it.id == taskId }
        val input = Json.parseToJsonElement(tool.input).jsonObject
        require(input.getValue("id").jsonPrimitive.content == task.sourceId && input.getValue("start_page").jsonPrimitive.content.toInt() == task.start && minOf(task.start + 4, input.getValue("end_page").jsonPrimitive.content.toInt()) == task.end)
        return tool to task
    }

    private suspend fun syncChatDocuments(owner: String, id: String, epoch: Long, delivered: MutableSet<String>) {
        requireProfile(owner, epoch)
        val conv = ownedConversation(owner, id)
        syncOfficeConversions(owner, id, epoch, delivered)
        for (message in conv.currentMessages.filter { it.role == MessageRole.ASSISTANT }) {
            for (tool in message.parts.filterIsInstance<UIMessagePart.Tool>().filter { it.toolName == "parse_material_enhanced" && it.executionState == me.rerere.ai.ui.ToolExecutionState.COMPLETED }) {
                val bound = documentTool(conv, message.id.toString(), tool.toolCallId).second
                try {
                if (bound.id in delivered) continue
                var task = bound
                val batchId = Json.parseToJsonElement(tool.output.filterIsInstance<UIMessagePart.Text>().first().text).jsonObject["batch_id"]?.jsonPrimitive?.content
                if (batchId != null && documents.batch(owner, batchId).cancelled) {
                    documents.cancelBatch(owner, batchId)
                    if (documentErrors.remove(bound.id) != null) emitEvent("conversation_changed", owner, mapOf("conversationId" to id))
                    continue
                }
                if (task.cancelRequested) {
                    if (!task.cancelConfirmed) documents.cancel(owner, task.id)
                    continue
                }
                if (task.remoteId == null && task.state == "SUBMITTING") {
                    require(me.rerere.rikkahub.feature.material.DocumentDispatchPolicy.allowsRemote(chatService.getConversationFlow(conv.id).value.currentMessages)) { "当前聊天禁止再次上传" }
                    task = documents.resumeSubmission(owner, task.id)
                }
                if (!task.downloaded && task.state in setOf("QUEUED", "RUNNING", "SUCCEEDED", "PARTIAL")) {
                    task = documents.refresh(owner, task.id)
                    requireProfile(owner, epoch)
                    if (task.state in setOf("SUCCEEDED", "PARTIAL")) {
                        documents.download(owner, task.id)
                        task = documents.tasks(owner).single { it.id == task.id }
                    }
                }
                if (task.downloaded && !task.cancelRequested && task.state !in setOf("FAILED", "CANCELLED")) {
                    // read() verifies cached content; state flags alone never justify continuation.
                    documents.read(owner, task.id)
                    val completedTasks = if (batchId != null) {
                        val next = documents.advanceBatch(owner, batchId) {
                            requireProfile(owner, epoch)
                            require(me.rerere.rikkahub.feature.material.DocumentDispatchPolicy.allowsRemote(chatService.getConversationFlow(conv.id).value.currentMessages)) { "当前聊天禁止新增上传" }
                        }
                        if (next != null) {
                            if (next.remoteId == null) {
                                require(me.rerere.rikkahub.feature.material.DocumentDispatchPolicy.allowsRemote(ownedConversation(owner, id).currentMessages))
                                documents.resumeSubmission(owner, next.id)
                            } else if (next.state in setOf("QUEUED", "RUNNING", "SUCCEEDED", "PARTIAL")) {
                                val refreshed = documents.refresh(owner, next.id)
                                if (refreshed.state in setOf("SUCCEEDED", "PARTIAL")) documents.download(owner, next.id)
                            }
                            continue
                        }
                        documents.batchTasks(owner, documents.batch(owner, batchId))
                    } else listOf(task)
                    val requestId = Uuid.parse(java.util.UUID.nameUUIDFromBytes("document:$owner:$id:${message.id}:${tool.toolCallId}:${batchId ?: task.id}".toByteArray()).toString())
                    val coverage = completedTasks.joinToString("；") { "任务${it.id}:物理页${it.start}–${it.end}" }
                    chatService.sendMessageCommitted(conv.id, listOf(UIMessagePart.Text("增强解析已下载并逐批校验：$coverage。请对每个任务用 read_material_parse_blocks 读取全部实际结果（首次cursor为空字符串、count为20，之后原样传next_cursor直到不再返回），检查覆盖范围和识别警告，继续用户原文件任务并交付成果；不要把解析完成当作全文理解完成。")), requestId) { current ->
                        requireProfile(owner, epoch)
                        check(batchId == null || !documents.batch(owner, batchId).cancelled) { "批任务已取消，不再续聊" }
                        check(!documents.tasks(owner).single { it.id == task.id }.cancelRequested) { "任务已取消，不再续聊" }
                        documentTool(current, message.id.toString(), tool.toolCallId)
                    }
                    delivered += task.id
                }
                if (documentErrors.remove(bound.id) != null) emitEvent("conversation_changed", owner, mapOf("conversationId" to id))
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) {
                    if (documentErrors.put(bound.id, "连接或结果校验失败；原文件保留，可重试") == null) emitEvent("conversation_changed", owner, mapOf("conversationId" to id))
                }
            }
        }
    }

    private fun officeTool(conv: Conversation, messageId: String, toolCallId: String): me.rerere.rikkahub.feature.material.OfficeConversionTask? {
        val message = conv.currentMessages.singleOrNull { it.id.toString() == messageId && it.role == MessageRole.ASSISTANT } ?: return null
        val tool = message.parts.filterIsInstance<UIMessagePart.Tool>().singleOrNull { it.toolCallId == toolCallId && it.toolName == "convert_material_to_pdf" && it.executionState == me.rerere.ai.ui.ToolExecutionState.COMPLETED } ?: return null
        val result = runCatching { Json.parseToJsonElement(tool.output.filterIsInstance<UIMessagePart.Text>().first().text).jsonObject }.getOrNull() ?: return null
        if (result["operation"]?.jsonPrimitive?.content != "office_pdf") return null
        val task = documents.officeConversions.task(conv.profileId, result.getValue("task_id").jsonPrimitive.content)
        val input = Json.parseToJsonElement(tool.input).jsonObject
        require(input.getValue("id").jsonPrimitive.content == task.sourceId && input.getValue("expected_sha256").jsonPrimitive.content == task.sourceSha256)
        return task
    }

    private suspend fun syncOfficeConversions(owner: String, id: String, epoch: Long, delivered: MutableSet<String>) {
        val conversions = documents.officeConversions
        val conv = ownedConversation(owner, id)
        for (message in conv.currentMessages.filter { it.role == MessageRole.ASSISTANT }) {
            for (tool in message.parts.filterIsInstance<UIMessagePart.Tool>().filter { it.toolName == "convert_material_to_pdf" && it.executionState == me.rerere.ai.ui.ToolExecutionState.COMPLETED }) {
                var task = officeTool(conv, message.id.toString(), tool.toolCallId) ?: continue
                try {
                    if (task.id in delivered) continue
                    if (task.cancelRequested) task = conversions.cancel(owner, task.id)
                    if (task.state == "SUBMITTING" && task.remoteId == null) {
                        require(me.rerere.rikkahub.feature.material.DocumentDispatchPolicy.allowsRemote(ownedConversation(owner, id).currentMessages)) { "当前聊天禁止再次上传" }
                        requireProfile(owner, epoch); task = conversions.resume(owner, task.id)
                    }
                    if (!task.downloaded && task.state in setOf("QUEUED", "RUNNING", "SUCCEEDED")) {
                        task = conversions.refresh(owner, task.id); requireProfile(owner, epoch)
                        if (task.state == "SUCCEEDED") { conversions.download(owner, task.id); task = conversions.task(owner, task.id) }
                    }
                    if (task.downloaded) {
                        val artifact = conversions.artifact(owner, task.id)
                        val requestId = Uuid.parse(java.util.UUID.nameUUIDFromBytes("office:$owner:$id:${message.id}:${tool.toolCallId}:${task.id}".toByteArray()).toString())
                        chatService.sendMessageCommitted(conv.id, listOf(UIMessagePart.Text("原Office文件的排版PDF已下载并校验，已作为真实成果交付。${artifact.detail}。继续原任务，准确说明兼容性或字体提示；无需重新上传或另行重生成PDF。"), UIMessagePart.Document(materialFiles.verifiedFile(owner, artifact.id).toUri().toString(), artifact.name, "application/pdf")), requestId) { current ->
                            requireProfile(owner, epoch); require(officeTool(current, message.id.toString(), tool.toolCallId)?.id == task.id) { "转换任务已离开当前分支" }
                        }
                        delivered += task.id
                    }
                    if (documentErrors.remove(task.id) != null) emitEvent("conversation_changed", owner, mapOf("conversationId" to id))
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { if (documentErrors.put(task.id, "转换连接或结果校验失败；原件保留，可重试") == null) emitEvent("conversation_changed", owner, mapOf("conversationId" to id)) }
            }
        }
    }

    override suspend fun documentTaskAction(profileId: String, conversationId: String, messageId: String, toolCallId: String, action: String, expectedEpoch: Long): SimpleCommandResult = withContext(Dispatchers.IO) {
        requireProfile(profileId, expectedEpoch)
        val conversion = officeTool(ownedConversation(profileId, conversationId), messageId, toolCallId)
        if (conversion != null) {
            require(action in setOf("retry", "cancel"))
            if (action == "cancel") documents.officeConversions.cancel(profileId, conversion.id)
            else if (conversion.state in setOf("FAILED", "CANCELLED", "EXPIRED") && !conversion.downloaded) {
                require(me.rerere.rikkahub.feature.material.DocumentDispatchPolicy.allowsRemote(ownedConversation(profileId, conversationId).currentMessages)) { "当前聊天禁止重新上传" }
                val requestId = Uuid.parse(java.util.UUID.nameUUIDFromBytes("office-retry:$profileId:$conversationId:$messageId:$toolCallId:${conversion.id}".toByteArray()).toString())
                chatService.sendMessageCommitted(Uuid.parse(conversationId), listOf(UIMessagePart.Text("重试原Office文件的排版PDF转换，材料ID ${conversion.sourceId}，源摘要 ${conversion.sourceSha256}。保留原任务要求与上传限制，不以文字PDF替代。")), requestId) { current -> requireProfile(profileId, expectedEpoch); require(officeTool(current, messageId, toolCallId)?.id == conversion.id) }
            } else {
                syncOfficeConversions(profileId, conversationId, expectedEpoch, mutableSetOf()); documentErrors[conversion.id]?.let { error(it) }
            }
            return@withContext SimpleCommandResult(success = true)
        }
        val (tool, firstTask) = documentTool(ownedConversation(profileId, conversationId), messageId, toolCallId)
        val batchId = Json.parseToJsonElement(tool.output.filterIsInstance<UIMessagePart.Text>().first().text).jsonObject["batch_id"]?.jsonPrimitive?.content
        val batch = batchId?.let { documents.batch(profileId, it) }
        val task = batch?.let { documents.batchTasks(profileId, it).lastOrNull() } ?: firstTask
        require(action in setOf("retry", "cancel"))
        if (action == "cancel") {
            if (batchId != null) documents.cancelBatch(profileId, batchId) else documents.cancel(profileId, task.id)
        }
        else {
            if (batch?.cancelled == true || (task.cancelRequested && task.cancelConfirmed) || (task.state in setOf("FAILED", "CANCELLED", "EXPIRED") && !task.downloaded)) {
                require(me.rerere.rikkahub.feature.material.DocumentDispatchPolicy.allowsRemote(ownedConversation(profileId, conversationId).currentMessages)) { "当前聊天禁止重新上传；仍可查询或清理已批准任务" }
                val request = Uuid.parse(java.util.UUID.nameUUIDFromBytes("document-retry:$profileId:$conversationId:$messageId:$toolCallId:${task.id}".toByteArray()).toString())
                chatService.sendMessageCommitted(Uuid.parse(conversationId), listOf(UIMessagePart.Text("请重试之前的文件任务，材料ID ${task.sourceId}，完整物理页范围 ${batch?.start ?: task.start}–${batch?.end ?: task.end}。保留原任务目的和上传限制，复用已校验的结果，按实际工具结果继续。")), request) { current ->
                    requireProfile(profileId, expectedEpoch)
                    documentTool(current, messageId, toolCallId)
                }
            } else {
                syncChatDocuments(profileId, conversationId, expectedEpoch, mutableSetOf())
                documentErrors[task.id]?.let { error(it) }
            }
        }
        SimpleCommandResult(success = true)
    }

    override suspend fun pickAttachments(profileId: String, expectedEpoch: Long): List<String> {
        requireProfile(profileId, expectedEpoch)
        val uris = withContext(Dispatchers.Main) { pickFiles() }
        return uris.map { uri ->
            requireProfile(profileId, expectedEpoch)
            val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: error("无法获取文件名")
            val item = materialFiles.import(profileId, uri, name)
            requireProfile(profileId, expectedEpoch)
            materialFiles.verifiedFile(profileId, item.id).absolutePath
        }
    }

    override suspend fun renameConversation(profileId: String, conversationId: String, title: String): SimpleCommandResult {
        val conv = ownedConversation(profileId, conversationId)
        require(title.isNotBlank() && title.length <= 200)
        check(chatService.getGenerationJobStateFlow(conv.id).firstOrNull()?.isActive != true) { "请先停止生成" }
        chatService.saveConversation(conv.id, conv.copy(title = title.trim()))
        return SimpleCommandResult(success = true)
    }

    override suspend fun deleteConversation(profileId: String, conversationId: String): SimpleCommandResult {
        chatService.deleteConversation(ownedConversation(profileId, conversationId))
        return SimpleCommandResult(success = true)
    }

    override suspend fun setConversationPinned(profileId: String, conversationId: String, pinned: Boolean, expectedEpoch: Long): SimpleCommandResult = withContext(Dispatchers.IO) {
        commandMutex.withLock {
            requireProfile(profileId, expectedEpoch)
            val conv = ownedConversation(profileId, conversationId)
            chatService.setConversationPinned(conv.id, pinned) { requireProfile(profileId, expectedEpoch) }
            SimpleCommandResult(success = true, idResult = conversationId)
        }
    }

    override suspend fun setConversationArchived(profileId: String, conversationId: String, archived: Boolean, expectedEpoch: Long): SimpleCommandResult = withContext(Dispatchers.IO) {
        commandMutex.withLock {
            requireProfile(profileId, expectedEpoch)
            val conv = ownedConversation(profileId, conversationId)
            chatService.setConversationArchived(conv.id, archived) { requireProfile(profileId, expectedEpoch) }
            SimpleCommandResult(success = true, idResult = conversationId)
        }
    }

    override suspend fun getSkills(profileId: String): List<me.rerere.rikkahub.flutter.generated.SkillDto> = withContext(Dispatchers.IO) {
        requireProfile(profileId)
        val assistant = settingsStore.settingsFlow.value.getCurrentAssistant()
        val manager = org.koin.core.context.GlobalContext.get().get<me.rerere.rikkahub.data.files.SkillManager>()
        manager.listSkills().map { skill ->
            val status = me.rerere.rikkahub.data.files.SkillCompatibility.inspect(skill)
            me.rerere.rikkahub.flutter.generated.SkillDto(name = skill.name, description = skill.description, builtin = skill.builtin, enabled = skill.name in assistant.enabledSkills, state = status.state, detail = status.detail, files = status.files, compatibility = skill.compatibility, license = skill.license, allowedTools = skill.allowedTools)
        }
    }

    override suspend fun setSkillEnabled(profileId: String, name: String, enabled: Boolean, expectedEpoch: Long): SimpleCommandResult {
        requireProfile(profileId, expectedEpoch)
        val manager = org.koin.core.context.GlobalContext.get().get<me.rerere.rikkahub.data.files.SkillManager>()
        require(manager.findSkill(name) != null) { "技能不存在，请刷新" }
        val assistantId = settingsStore.settingsFlow.value.getCurrentAssistant().id
        settingsStore.update { current ->
            requireProfile(profileId, expectedEpoch)
            require(current.getCurrentAssistant().id == assistantId)
            current.copy(assistants = current.assistants.map { assistant -> if (assistant.id != assistantId) assistant else assistant.copy(enabledSkills = if (enabled) assistant.enabledSkills + name else assistant.enabledSkills - name) })
        }
        return SimpleCommandResult(success = true)
    }

    override suspend fun openNativePage(page: String): SimpleCommandResult = withContext(Dispatchers.Main) {
        require(page in setOf("providers", "files", "skills", "backup"))
        context.startActivity(Intent(context, me.rerere.rikkahub.RouteActivity::class.java).apply {
            putExtra("nativePage", page)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        SimpleCommandResult(success = true)
    }
    private val receipts = context.getSharedPreferences("chat_command_receipts", Context.MODE_PRIVATE)

    @Volatile
    var eventSink: EventChannel.EventSink? = null

    fun emitEvent(type: String, profileId: String, payload: Map<String, Any?>) {
        val sink = eventSink ?: return
        val event = mapOf(
            "apiVersion" to "1.0",
            "type" to type,
            "profileId" to profileId,
            "contextEpoch" to contextEpoch.get(),
            "timestamp" to System.currentTimeMillis(),
            "sequence" to sequence.incrementAndGet(),
            "payload" to payload
        )
        scope.launch { sink.success(event) }
    }

    override suspend fun getBootstrap(profileId: String, contextEpoch: Long): StudyBootstrapDto = withContext(Dispatchers.IO) {
        val settings = withTimeout(15_000) { settingsStore.settingsFlow.first { !it.init } }
        val effectiveProfileId = if (settings.learnerProfiles.any { it.id == profileId }) {
            profileId
        } else {
            settings.activeProfileId
        }

        val profileDtos = settings.learnerProfiles.map {
            LearnerProfileDto(
                id = it.id,
                name = it.name,
                memoryEnabled = it.memoryEnabled,
                activeStudyMode = "CHAT"
            )
        }

        val currentModel = runCatching { settings.getCurrentChatModel() }.getOrNull()
        val currentProvider = currentModel?.findProvider(settings.providers)

        val assistant = settings.getCurrentAssistant()
        val recentConv = conversationRepo.getRecentConversations(assistant.id, limit = 1, profileId = effectiveProfileId).firstOrNull()
        require(effectiveProfileId == settingsStore.settingsFlow.value.activeProfileId) { "档案已改变，请重新加载" }

        StudyBootstrapDto(
            currentProfileId = effectiveProfileId,
            contextEpoch = this@StudyHostFacade.contextEpoch.get(),
            profiles = profileDtos,
            studyMode = recentConv?.studyMode?.name ?: "CHAT",
            reduceMotion = false,
            darkMode = false,
            activeModelId = currentModel?.id?.toString(),
            activeModelName = currentModel?.displayName,
            activeProviderId = currentProvider?.id?.toString(),
            activeProviderName = currentProvider?.name,
            defaultConversationId = recentConv?.id?.toString()
        )
    }

    override suspend fun switchProfile(profileId: String, expectedEpoch: Long): SimpleCommandResult = withContext(Dispatchers.IO) {
        require(expectedEpoch == contextEpoch.get()) { "请求已过期" }
        observation?.cancel()
        documentObservation?.cancel()
        val settings = settingsStore.settingsFlow.value
        val target = settings.learnerProfiles.firstOrNull { it.id == profileId }
            ?: return@withContext SimpleCommandResult(
                success = false,
                error = StudyErrorDto(code = "NOT_FOUND", message = "档案不存在: $profileId", retryable = false, requestId = "")
            )

        settingsStore.update { it.copy(activeProfileId = target.id) }
        chatService.switchLearnerProfile(target.id)
        val newEpoch = contextEpoch.incrementAndGet()

        emitEvent("profile_changed", target.id, mapOf("newEpoch" to newEpoch))

        SimpleCommandResult(success = true, idResult = target.id)
    }

    override suspend fun getConversations(profileId: String, limit: Long, offset: Long, archived: Boolean): List<ConversationSummaryDto> = withContext(Dispatchers.IO) {
        val settings = settingsStore.settingsFlow.value
        requireProfile(profileId)
        require(limit in 1..100 && offset in 0..100000)
        val assistant = settings.getCurrentAssistant()
        val list = conversationRepo.getRecentConversations(assistant.id, limit = (limit + offset).toInt().coerceAtLeast(50), profileId = profileId, archived = archived)

        val paged = list.drop(offset.toInt()).take(limit.toInt())
        paged.map { conv ->
            val preview = conv.currentMessages.lastOrNull()?.toText()?.take(80) ?: ""
            ConversationSummaryDto(
                id = conv.id.toString(),
                title = conv.title.ifEmpty { "新对话" },
                previewText = preview,
                updatedAt = conv.updateAt.toString(),
                isPinned = conv.isPinned,
                studyMode = conv.studyMode.name,
                profileId = conv.profileId, isArchived = conv.isArchived
            )
        }
    }

    override suspend fun searchConversations(profileId: String, query: String, limit: Long, offset: Long, archived: Boolean): List<ConversationSummaryDto> = withContext(Dispatchers.IO) {
        requireProfile(profileId)
        require(limit in 1..100 && offset in 0..100000)
        conversationRepo.searchHistory(query.trim(), profileId, limit.toInt(), offset.toInt(), archived).map { conv ->
            ConversationSummaryDto(conv.id.toString(), conv.title.ifEmpty { "新对话" },
                conv.currentMessages.lastOrNull()?.toText()?.take(80).orEmpty(), conv.updateAt.toString(),
                conv.isPinned, conv.studyMode.name, conv.profileId, conv.isArchived)
        }
    }

    override suspend fun editMessage(profileId: String, conversationId: String, messageId: String, text: String, requestId: String, expectedEpoch: Long): SimpleCommandResult = withContext(Dispatchers.IO) { commandMutex.withLock {
        requireProfile(profileId, expectedEpoch)
        require(text.isNotBlank() && text.length <= 100000 && requestId.length in 1..200)
        val conv = ownedConversation(profileId, conversationId)
        val id = Uuid.parse(java.util.UUID.nameUUIDFromBytes("edit:$profileId:$conversationId:$messageId:$requestId".toByteArray()).toString())
        conv.messageNodes.flatMap { it.messages }.firstOrNull { it.id == id }?.let {
            require(it.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { part -> part.text } == text) { "请求标识已用于不同编辑" }
            return@withLock SimpleCommandResult(success = true, idResult = id.toString())
        }
        val source = conv.currentMessages.singleOrNull { it.id.toString() == messageId && it.role == MessageRole.USER }
            ?: error("消息已改变，请刷新后再编辑")
        val parts = listOf(UIMessagePart.Text(text)) + source.parts.filterNot { it is UIMessagePart.Text }
        chatService.editMessage(conv.id, source.id, parts, id)
        SimpleCommandResult(success = true, idResult = id.toString())
    } }

    override suspend fun getConversationDetails(profileId: String, conversationId: String): ConversationDetailDto = withContext(Dispatchers.IO) {
        val uuid = runCatching { Uuid.parse(conversationId) }.getOrNull()
            ?: throw IllegalArgumentException("Invalid conversation UUID: $conversationId")

        val conv = ownedConversation(profileId, conversationId)

        val isGenerating = chatService.getGenerationJobStateFlow(uuid).firstOrNull()?.isActive == true

        val nodeDtos = conv.messageNodes.map { node ->
            val branchDtos = node.messages.map { msg ->
                val exposedParts = msg.parts.flatMap { part -> if (part is UIMessagePart.Tool && part.executionState == me.rerere.ai.ui.ToolExecutionState.COMPLETED) {
                    val converted = runCatching { officeTool(conv.copy(messageNodes = listOf(me.rerere.rikkahub.data.model.MessageNode(messages = listOf(msg)))), msg.id.toString(), part.toolCallId)?.takeIf { it.downloaded }?.let { task -> documents.officeConversions.artifact(profileId, task.id) }?.let { file -> UIMessagePart.Document(materialFiles.verifiedFile(profileId, file.id).toUri().toString(), file.name, "application/pdf") } }.getOrNull()
                    listOf(part) + part.output.filter { it is UIMessagePart.Document || it is UIMessagePart.Image || (part.toolName == "display_image" && it is UIMessagePart.Text && part.output.any { output -> output is UIMessagePart.Image }) || (it is UIMessagePart.InteractiveSurfaceRef && !InteractiveSurface.isSurfaceTool(part.toolName)) } + listOfNotNull(converted)
                } else listOf(part) }
                val partDtos = exposedParts.map { part ->
                    when (part) {
                        is UIMessagePart.Text -> MessagePartDto(type = "text", text = part.text)
                        is UIMessagePart.Image -> MessagePartDto(type = "image", url = part.url, text = part.metadata?.get("caption")?.jsonPrimitive?.content)
                        is UIMessagePart.InteractiveSurfaceRef -> MessagePartDto(type = "interactive_surface", surfaceId = part.surfaceId, surfaceTitle = part.title, surfaceDefinition = part.definitionJson, surfaceRevision = 0)
                        is UIMessagePart.Video -> MessagePartDto(type = "video", url = part.url)
                        is UIMessagePart.Audio -> MessagePartDto(type = "audio", url = part.url)
                        is UIMessagePart.Document -> MessagePartDto(type = "file", url = part.url, fileName = part.fileName, mime = part.mime, artifactId = materialFiles.list(profileId).firstOrNull { it.output && materialFiles.file(it).toUri().toString() == part.url }?.id)
                        is UIMessagePart.Reasoning -> MessagePartDto(type = "reasoning", reasoning = part.reasoning)
                        is UIMessagePart.Tool -> MessagePartDto(
                            type = "tool",
                            toolCallId = part.toolCallId,
                            toolName = part.toolName,
                            toolInput = part.input,
                            toolOutput = if (InteractiveSurface.isSurfaceTool(part.toolName)) runCatching { InteractiveSurface.clientOutput(part) }.getOrElse { part.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text } }
                            else if (part.toolName == "parse_material_enhanced" && part.executionState == me.rerere.ai.ui.ToolExecutionState.COMPLETED) runCatching {
                                val task = documentTool(conv.copy(messageNodes = listOf(me.rerere.rikkahub.data.model.MessageNode(messages = listOf(msg)))), msg.id.toString(), part.toolCallId).second
                                val batchId = Json.parseToJsonElement(part.output.filterIsInstance<UIMessagePart.Text>().first().text).jsonObject["batch_id"]?.jsonPrimitive?.content
                                val batch = batchId?.let { documents.batch(profileId, it) }
                                val tasks = batch?.let { documents.batchTasks(profileId, it) }.orEmpty()
                                val active = tasks.firstOrNull { !it.downloaded } ?: tasks.lastOrNull() ?: task
                                val covered = tasks.filter { it.downloaded }.flatMap { documents.read(profileId, it.id).processedPages }.distinct().sorted()
                                val complete = batch != null && covered == (batch.start..batch.end).toList()
                                buildJsonObject { put("task_id", active.id); put("state", if (batch?.cancelled == true) "CANCELLED" else if (batch != null && !complete && active.downloaded) "QUEUED" else active.state); put("downloaded", if (batch != null) complete else task.downloaded); put("start_page", batch?.start ?: task.start); put("end_page", batch?.end ?: task.end); if (batch != null) put("covered_pages", JsonArray(covered.map(::JsonPrimitive))); documentErrors[task.id]?.let { put("client_error", it) }; active.errorCode?.let { put("error_code", it) } }.toString()
                            }.getOrElse { part.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text } }
                            else if (part.toolName == "convert_material_to_pdf" && part.executionState == me.rerere.ai.ui.ToolExecutionState.COMPLETED) runCatching {
                                val task = officeTool(conv.copy(messageNodes = listOf(me.rerere.rikkahub.data.model.MessageNode(messages = listOf(msg)))), msg.id.toString(), part.toolCallId) ?: error("local conversion")
                                buildJsonObject { put("operation", "office_pdf"); put("task_id", task.id); put("state", task.state); put("downloaded", task.downloaded); put("renderer_version", task.rendererVersion); task.errorCode?.let { put("error_code", it) }; documentErrors[task.id]?.let { put("client_error", it) }; if (task.downloaded) put("detail", documents.officeConversions.artifact(profileId, task.id).detail) }.toString()
                            }.getOrElse { part.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text } }
                            else part.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text },
                            approvalState = part.approvalState::class.simpleName ?: "Auto",
                            executionState = part.executionState.name
                        )
                        else -> MessagePartDto(type = "other", text = part.toString())
                    }
                }
                MessageDto(
                    id = msg.id.toString(),
                    nodeId = node.id.toString(),
                    role = msg.role.name.lowercase(),
                    parts = partDtos,
                    createdAt = msg.createdAt.toString(),
                    revision = 0L,
                    generationState = msg.generationState
                )
            }
            MessageNodeDto(
                nodeId = node.id.toString(),
                selectIndex = node.selectIndex.toLong(),
                branches = branchDtos
            )
        }

        val activeModel = runCatching { settingsStore.settingsFlow.value.getCurrentChatModel() }.getOrNull()

        ConversationDetailDto(
            id = conv.id.toString(),
            title = conv.title.ifEmpty { "新对话" },
            profileId = conv.profileId,
            studyMode = conv.studyMode.name,
            nodes = nodeDtos,
            isGenerating = isGenerating,
            lifecycleRevision = conv.lifecycleRevision,
            draftText = "",
            activeModelId = activeModel?.id?.toString(),
            activeModelName = activeModel?.displayName
        )
    }

    override suspend fun sendMessage(request: SendMessageRequest): SimpleCommandResult = withContext(Dispatchers.IO) { commandMutex.withLock {
        requireProfile(request.profileId, request.contextEpoch)
        require(imageCreation.list(request.profileId).none { it.state == "RUNNING" && it.conversationId == request.conversationId }) { "请等待当前图片完成或停止生成" }
        require(request.text.isNotBlank() || !request.attachmentPaths.isNullOrEmpty()) { "消息不能为空" }
        require(request.requestId.isNotBlank() && request.requestId.length <= 200)
        val commandKey = Uuid.parse(java.util.UUID.nameUUIDFromBytes("${request.profileId}:${request.requestId}".toByteArray()).toString())
        val fingerprint = me.rerere.rikkahub.feature.memory.MemoryValidation.hash(
            listOf(request.conversationId, request.text, request.studyMode, request.attachmentPaths).toString())
        val oldFingerprint = receipts.getString(commandKey.toString(), null)
        require(oldFingerprint == null || oldFingerprint == fingerprint) { "同一请求标识不能用于不同消息" }
        check(receipts.edit().putString(commandKey.toString(), fingerprint).commit()) { "请求状态保存失败" }

        val convUuid = if (!request.conversationId.isNullOrEmpty()) {
            ownedConversation(request.profileId, request.conversationId).id
        } else {
            val assistant = settingsStore.settingsFlow.value.getCurrentAssistant()
            val newId = Uuid.parse(java.util.UUID.nameUUIDFromBytes("conversation:$commandKey".toByteArray()).toString())
            val newConv = Conversation.ofId(newId, assistantId = assistant.id)
                .copy(profileId = request.profileId, studyMode = runCatching { StudyMode.valueOf(request.studyMode.uppercase()) }.getOrThrow())
            if (conversationRepo.getConversationById(newId, request.profileId) == null) conversationRepo.insertConversation(newConv)
            newConv.id
        }

        val parts = mutableListOf<UIMessagePart>()
        if (request.text.isNotBlank()) {
            parts.add(UIMessagePart.Text(request.text))
        }
        request.attachmentPaths?.forEach { path ->
            val file = File(path)
            val owner = materialFiles.list(request.profileId).firstOrNull { materialFiles.file(it).canonicalFile == file.canonicalFile }
                ?: error("附件不属于当前档案")
            materialFiles.verifiedFile(request.profileId, owner.id)
            if (file.isFile) {
                val ext = file.extension.lowercase()
                if (ext in listOf("png", "jpg", "jpeg", "webp", "gif")) {
                    parts.add(UIMessagePart.Image(file.toUri().toString()))
                } else {
                    parts.add(UIMessagePart.Document(url = file.toUri().toString(), fileName = file.name))
                }
            }
        }

        chatService.initializeConversation(convUuid)
        chatService.updateStudyMode(convUuid, StudyMode.valueOf(request.studyMode.uppercase()))
        chatService.sendMessageCommitted(convUuid, parts, commandKey)

        val result = SimpleCommandResult(success = true, idResult = convUuid.toString())
        result
    } }

    override suspend fun stopGeneration(profileId: String, conversationId: String, requestId: String): SimpleCommandResult = withContext(Dispatchers.IO) {
        val uuid = runCatching { Uuid.parse(conversationId) }.getOrNull()
            ?: return@withContext SimpleCommandResult(success = false, error = StudyErrorDto("INVALID_ID", "无效会话ID", false, requestId))

        ownedConversation(profileId, conversationId)
        chatService.stopGeneration(uuid)
        SimpleCommandResult(success = true, idResult = conversationId)
    }

    override suspend fun retryMessage(profileId: String, conversationId: String, nodeId: String, requestId: String): SimpleCommandResult = withContext(Dispatchers.IO) {
        val convUuid = runCatching { Uuid.parse(conversationId) }.getOrNull()
            ?: return@withContext SimpleCommandResult(success = false, error = StudyErrorDto("INVALID_ID", "无效会话ID", false, requestId))

        val conv = ownedConversation(profileId, conversationId)

        val targetNode = conv.messageNodes.firstOrNull { it.id.toString() == nodeId }
            ?: return@withContext SimpleCommandResult(success = false, error = StudyErrorDto("NOT_FOUND", "节点不存在", false, requestId))

        val targetMsg = targetNode.messages.getOrNull(targetNode.selectIndex)
            ?: return@withContext SimpleCommandResult(success = false, error = StudyErrorDto("NOT_FOUND", "消息不存在", false, requestId))

        chatService.regenerateAtMessage(convUuid, targetMsg)
        SimpleCommandResult(success = true, idResult = requestId)
    }

    override suspend fun switchBranch(profileId: String, conversationId: String, nodeId: String, branchIndex: Long): SimpleCommandResult = withContext(Dispatchers.IO) {
        val convUuid = runCatching { Uuid.parse(conversationId) }.getOrNull()
            ?: return@withContext SimpleCommandResult(success = false, error = StudyErrorDto("INVALID_ID", "无效会话ID", false, ""))

        val conv = conversationRepo.getConversationById(convUuid, profileId = profileId)
            ?: return@withContext SimpleCommandResult(success = false, error = StudyErrorDto("NOT_FOUND", "会话不存在", false, ""))

        ownedConversation(profileId, conversationId)
        chatService.selectMessageNode(convUuid, Uuid.parse(nodeId), branchIndex.toInt())
        SimpleCommandResult(success = true)
    }

    override suspend fun setStudyMode(profileId: String, conversationId: String, studyMode: String): SimpleCommandResult = withContext(Dispatchers.IO) {
        val convUuid = runCatching { Uuid.parse(conversationId) }.getOrNull()
            ?: return@withContext SimpleCommandResult(success = false, error = StudyErrorDto("INVALID_ID", "无效会话ID", false, ""))

        val conv = conversationRepo.getConversationById(convUuid, profileId = profileId)
            ?: return@withContext SimpleCommandResult(success = false, error = StudyErrorDto("NOT_FOUND", "会话不存在", false, ""))

        val mode = runCatching { StudyMode.valueOf(studyMode.uppercase()) }.getOrThrow()
        ownedConversation(profileId, conversationId)
        check(chatService.getGenerationJobStateFlow(convUuid).firstOrNull()?.isActive != true) { "请先停止生成" }
        chatService.updateStudyMode(convUuid, mode)
        SimpleCommandResult(success = true)
    }

    override suspend fun approveToolCall(profileId: String, conversationId: String, toolCallId: String, approved: Boolean, requestId: String): SimpleCommandResult = withContext(Dispatchers.IO) {
        val convUuid = runCatching { Uuid.parse(conversationId) }.getOrNull()
            ?: return@withContext SimpleCommandResult(success = false, error = StudyErrorDto("INVALID_ID", "无效会话ID", false, requestId))

        val conv = ownedConversation(profileId, conversationId)

        chatService.handleToolApproval(convUuid, toolCallId, approved)
        SimpleCommandResult(success = true, idResult = requestId)
    }

    override suspend fun getMaterials(profileId: String, filter: String): List<MaterialFileDto> = withContext(Dispatchers.IO) {
        requireProfile(profileId)
        materialFiles.list(profileId)
            .filter { !it.output }
            .filter { filter.isBlank() || it.name.contains(filter, ignoreCase = true) }
            .map {
                MaterialFileDto(
                    id = it.id,
                    profileId = it.profileId,
                    name = it.name,
                    extension = it.extension,
                    state = it.state,
                    detail = it.detail,
                    size = it.size,
                    sha256 = it.sha256,
                    createdAt = it.createdAt,
                    pageNumber = it.pageNumber?.toLong(),
                    symbolCorrection = it.symbolCorrection,
                    symbolCheckPending = it.symbolCheckPending
                )
            }
    }

    override suspend fun checkSymbol(profileId: String, materialId: String, chosenSymbol: String, requestId: String): SimpleCommandResult = withContext(Dispatchers.IO) {
        requireProfile(profileId)
        val item = materialFiles.list(profileId).firstOrNull { it.id == materialId }
            ?: return@withContext SimpleCommandResult(success = false, error = StudyErrorDto("NOT_FOUND", "材料不存在", false, requestId))

        val updated = item.copy(
            symbolCorrection = chosenSymbol,
            symbolCheckPending = false,
            detail = "符号已校对: $chosenSymbol"
        )
        materialFiles.record(updated)
        SimpleCommandResult(success = true, idResult = materialId)
    }

    private val projectDatabase: me.rerere.rikkahub.data.db.AppDatabase get() = org.koin.core.context.GlobalContext.get().get()

    override suspend fun getProjects(profileId: String): List<ProjectDto> = withContext(Dispatchers.IO) {
        requireProfile(profileId)
        val assistant = settingsStore.settingsFlow.value.getCurrentAssistant().id.toString()
        val db = projectDatabase
        db.folderDao().getFoldersOfAssistant(assistant, profileId).first().map { folder ->
            ProjectDto(folder.id, folder.name, db.conversationDao().projectSummaries(profileId, folder.id).map { conv ->
                ConversationSummaryDto(conv.id, conv.title.ifEmpty { "新对话" }, "", conv.updateAt.toString(), conv.isPinned, conv.studyMode, profileId, false)
            })
        }
    }

    private suspend fun projectMutation(profileId: String, epoch: Long, transactional: Boolean = true, block: suspend () -> String?): SimpleCommandResult = withContext(Dispatchers.IO) {
        commandMutex.withLock {
            try {
                val operation: suspend () -> String? = {
                    requireProfile(profileId, epoch)
                    val result = block()
                    requireProfile(profileId, epoch)
                    result
                }
                val id = if (transactional) projectDatabase.withTransaction { operation() } else operation()
                SimpleCommandResult(success = true, idResult = id)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                SimpleCommandResult(success = false, error = StudyErrorDto("PROJECT_ERROR", e.message ?: "项目操作失败", false, ""))
            }
        }
    }
    private suspend fun checkedProject(profileId: String, id: String): me.rerere.rikkahub.data.db.entity.FolderEntity {
        val folder = projectDatabase.folderDao().getFolderById(id, profileId)
        require(folder != null && folder.assistantId == settingsStore.settingsFlow.value.getCurrentAssistant().id.toString()) { "项目不存在或无权访问" }
        return folder
    }
    override suspend fun saveProject(profileId: String, projectId: String?, name: String, expectedEpoch: Long) = projectMutation(profileId, expectedEpoch) {
        require(name.trim().isNotEmpty() && name.trim().length <= 100) { "项目名称应为1–100字" }
        val id = projectId ?: Uuid.random().toString()
        if (projectId == null) projectDatabase.folderDao().insert(me.rerere.rikkahub.data.db.entity.FolderEntity(
            profileId = profileId, id = id, assistantId = settingsStore.settingsFlow.value.getCurrentAssistant().id.toString(), name = name.trim(), createAt = System.currentTimeMillis()))
        else { checkedProject(profileId, id); projectDatabase.folderDao().rename(id, name.trim(), profileId) }
        id
    }
    override suspend fun deleteProject(profileId: String, projectId: String, expectedEpoch: Long) = projectMutation(profileId, expectedEpoch) {
        checkedProject(profileId, projectId)
        chatService.deleteFolder(Uuid.parse(projectId))
        projectId
    }
    override suspend fun moveConversationToProject(profileId: String, conversationId: String, projectId: String?, expectedEpoch: Long) = projectMutation(profileId, expectedEpoch) {
        val conversation = projectDatabase.conversationDao().getConversationById(conversationId)
        require(conversation != null && conversation.profileId == profileId) { "对话不存在或无权访问" }
        if (projectId != null) require(checkedProject(profileId, projectId).assistantId == conversation.assistantId) { "项目与对话所属助手不同" }
        chatService.moveConversationToFolder(Uuid.parse(conversationId), projectId?.let { Uuid.parse(it) })
        conversationId
    }
    // insertConversation owns its transaction and indexes FTS after committing; an outer transaction
    // would keep the SQLite writer locked while the index switches to a different IO thread.
    override suspend fun createProjectChat(profileId: String, projectId: String, expectedEpoch: Long) = projectMutation(profileId, expectedEpoch, transactional = false) {
        val folder = checkedProject(profileId, projectId)
        val id = Uuid.random()
        conversationRepo.insertConversation(Conversation.ofId(id, assistantId = Uuid.parse(folder.assistantId)).copy(profileId = profileId, folderId = Uuid.parse(projectId)))
        id.toString()
    }
    override suspend fun addMemory(profileId: String, content: String, type: String, requestId: String, expectedEpoch: Long): SimpleCommandResult = withContext(Dispatchers.IO) {
        commandMutex.withLock {
            try {
                val id = projectDatabase.withTransaction {
                    requireProfile(profileId, expectedEpoch)
                    val result = learningMemories.addManual(profileId, requestId, me.rerere.rikkahub.feature.memory.MemoryType.valueOf(type), content)
                    requireProfile(profileId, expectedEpoch)
                    result
                }
                SimpleCommandResult(success = true, idResult = id)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                SimpleCommandResult(success = false, error = StudyErrorDto("MEMORY_ERROR", e.message ?: "添加失败", false, requestId))
            }
        }
    }

    override suspend fun getMemories(profileId: String, filter: String): List<MemoryItemDto> = withContext(Dispatchers.IO) {
        requireProfile(profileId)
        val state = learningMemories.read(profileId)
        state.items
            .filter { filter.isBlank() || it.content.contains(filter, ignoreCase = true) || it.topicKey.contains(filter, ignoreCase = true) }
            .map {
                MemoryItemDto(
                    id = it.id,
                    type = it.type.name,
                    content = it.content,
                    sourceTopic = it.topicKey,
                    sourceFact = if (it.origin == "USER_MANUAL") "用户直接输入" else it.factKey,
                    evidenceType = it.evidenceType.name,
                    status = it.status,
                    revision = it.revision.toLong(),
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                    sourceQuote = null,
                    sourceConversationId = state.sources.firstOrNull { source -> source.id in it.sourceIds }?.conversationId
                )
            }
    }

    override suspend fun updateMemory(profileId: String, memoryId: String, newContent: String, expectedRevision: Long, requestId: String): SimpleCommandResult = withContext(Dispatchers.IO) {
        requireProfile(profileId)
        try {
            learningMemories.edit(profileId, memoryId, expectedRevision.toInt(), newContent)
            SimpleCommandResult(success = true, idResult = memoryId)
        } catch (e: Exception) {
            SimpleCommandResult(success = false, error = StudyErrorDto("CONFLICT", e.message ?: "更新记忆冲突", true, requestId))
        }
    }

    override suspend fun deleteMemory(profileId: String, memoryId: String, requestId: String): SimpleCommandResult = withContext(Dispatchers.IO) {
        requireProfile(profileId)
        try {
            val state = learningMemories.read(profileId)
            val item = state.items.firstOrNull { it.id == memoryId }
                ?: return@withContext SimpleCommandResult(success = false, error = StudyErrorDto("NOT_FOUND", "记录不存在", false, requestId))
            learningMemories.delete(profileId, memoryId, item.revision, topic = false)
            SimpleCommandResult(success = true, idResult = memoryId)
        } catch (e: Exception) {
            SimpleCommandResult(success = false, error = StudyErrorDto("ERROR", e.message ?: "删除失败", false, requestId))
        }
    }

    override suspend fun toggleAutoMemory(profileId: String, enabled: Boolean): SimpleCommandResult = withContext(Dispatchers.IO) {
        requireProfile(profileId)
        settingsStore.setLearnerMemoryEnabled(profileId, enabled)
        SimpleCommandResult(success = true)
    }

    override suspend fun getArtifacts(profileId: String): List<ArtifactDto> = withContext(Dispatchers.IO) {
        requireProfile(profileId)
        materialFiles.list(profileId)
            .filter { it.output }
            .map {
                ArtifactDto(
                    id = it.id,
                    name = it.name,
                    extension = it.extension,
                    state = it.state,
                    size = it.size,
                    sha256 = it.sha256,
                    createdAt = it.createdAt,
                    statusDetail = it.detail
                )
            }
    }

    override suspend fun getArtifactPreview(profileId: String, artifactId: String, expectedEpoch: Long): ArtifactDto = withContext(Dispatchers.IO) {
        requireProfile(profileId, expectedEpoch)
        val item = materialFiles.get(profileId, artifactId)
        require(item.output) { "不是成果文件" }
        var content: me.rerere.rikkahub.feature.material.MaterialContent? = null
        var coverage: String? = null
        if (item.state == "SUCCEEDED") {
            try {
                val file = materialFiles.verifiedFile(profileId, artifactId)
                content = me.rerere.rikkahub.feature.material.MaterialReader.read(file, item.extension)
                coverage = content.coverage
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                coverage = "无法提取正文：${e.message?.take(160) ?: "不支持此格式"}"
            }
        }
        requireProfile(profileId, expectedEpoch)
        check(materialFiles.get(profileId, artifactId) == item) { "成果已改变，请重新打开" }
        ArtifactDto(
            id = item.id, name = item.name, extension = item.extension, state = item.state,
            size = item.size, sha256 = item.sha256, createdAt = item.createdAt,
            previewContent = content?.text, statusDetail = item.detail, previewCoverage = coverage,
        )
    }

    private fun artifactMime(extension: String): String = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"

    override suspend fun openArtifact(profileId: String, artifactId: String): SimpleCommandResult = withContext(Dispatchers.Main) {
        requireProfile(profileId)
        val item = materialFiles.list(profileId).firstOrNull { it.id == artifactId }
            ?: return@withContext SimpleCommandResult(success = false, error = StudyErrorDto("NOT_FOUND", "成果不存在", false, ""))

        val file = withContext(Dispatchers.IO) { materialFiles.verifiedFile(profileId, item.id) }
        if (!file.exists()) {
            return@withContext SimpleCommandResult(success = false, error = StudyErrorDto("NOT_FOUND", "文件不存在", false, ""))
        }

        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, artifactMime(item.extension))
                clipData = android.content.ClipData.newRawUri(item.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            SimpleCommandResult(success = true)
        } catch (e: Exception) {
            SimpleCommandResult(success = false, error = StudyErrorDto("NO_APP", "未找到可打开此文件的应用", false, ""))
        }
    }

    override suspend fun saveArtifact(profileId: String, artifactId: String): SimpleCommandResult = withContext(Dispatchers.IO) {
        requireProfile(profileId)
        val epoch = contextEpoch.get()
        val item = materialFiles.get(profileId, artifactId)
        materialFiles.verifiedFile(profileId, artifactId)
        val destination = withContext(Dispatchers.Main) { saveFile(item.name, artifactMime(item.extension)) }
            ?: return@withContext SimpleCommandResult(success = false, error = StudyErrorDto("CANCELLED", "已取消另存", false, ""))
        requireProfile(profileId, epoch)
        materialFiles.saveVerified(profileId, artifactId, destination, { settingsStore.settingsFlow.value.activeProfileId == profileId && contextEpoch.get() == epoch })
        emitEvent("artifact_saved", profileId, mapOf("artifactId" to artifactId, "uri" to destination.toString()))
        SimpleCommandResult(success = true, idResult = destination.toString())
    }

    override suspend fun shareArtifact(profileId: String, artifactId: String): SimpleCommandResult = withContext(Dispatchers.Main) {
        requireProfile(profileId)
        val item = materialFiles.list(profileId).firstOrNull { it.id == artifactId }
            ?: return@withContext SimpleCommandResult(success = false, error = StudyErrorDto("NOT_FOUND", "成果不存在", false, ""))

        val file = withContext(Dispatchers.IO) { materialFiles.verifiedFile(profileId, item.id) }
        if (!file.exists()) {
            return@withContext SimpleCommandResult(success = false, error = StudyErrorDto("NOT_FOUND", "文件不存在", false, ""))
        }

        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = artifactMime(item.extension)
                clipData = android.content.ClipData.newRawUri(item.name, uri)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "分享成果").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            SimpleCommandResult(success = true)
        } catch (e: Exception) {
            SimpleCommandResult(success = false, error = StudyErrorDto("SHARE_FAIL", e.message ?: "分享失败", false, ""))
        }
    }

    override suspend fun getProviders(): List<ProviderDto> = withContext(Dispatchers.IO) {
        val settings = settingsStore.settingsFlow.value
        val primaryModelId = settings.chatModelId
        settings.providers.map { p ->
            val modelDtos = p.models.map { m ->
                ModelDto(
                    id = m.id.toString(),
                    name = m.displayName,
                    providerId = p.id.toString(),
                    enabled = true,
                    supportText = true,
                    supportImage = m.inputModalities.contains(Modality.IMAGE),
                    supportTool = m.abilities.contains(ModelAbility.TOOL)
                )
            }
            val endpoint = when (p) {
                is ProviderSetting.OpenAI -> p.baseUrl
                is ProviderSetting.Claude -> p.baseUrl
                is ProviderSetting.Google -> p.baseUrl
                else -> ""
            }
            val apiKeyMasked = when (p) {
                is ProviderSetting.OpenAI -> if (p.apiKey.isNotBlank()) "••••••••" else ""
                is ProviderSetting.Claude -> if (p.apiKey.isNotBlank()) "••••••••" else ""
                is ProviderSetting.Google -> if (p.apiKey.isNotBlank()) "••••••••" else ""
                else -> ""
            }
            ProviderDto(
                id = p.id.toString(),
                name = p.name,
                type = p::class.simpleName ?: "Unknown",
                endpoint = endpoint,
                apiKeyMasked = apiKeyMasked,
                enabled = p.enabled,
                isPrimary = p.models.any { it.id == primaryModelId },
                models = modelDtos
            )
        }
    }

    override suspend fun saveProvider(provider: ProviderDto): SimpleCommandResult = withContext(Dispatchers.IO) {
        val settings = settingsStore.settingsFlow.value
        val existing = settings.providers.firstOrNull { it.id.toString() == provider.id }
        if (existing != null) {
            val updated = existing.copyProvider(
                name = provider.name,
                enabled = provider.enabled
            )
            val updatedList = settings.providers.map { if (it.id == existing.id) updated else it }
            settingsStore.update { it.copy(providers = updatedList) }
            SimpleCommandResult(success = true, idResult = provider.id)
        } else {
            SimpleCommandResult(success = false, error = StudyErrorDto("NOT_FOUND", "提供商不存在", false, ""))
        }
    }

    override suspend fun testProvider(providerId: String): SimpleCommandResult = withContext(Dispatchers.IO) {
        val settings = settingsStore.settingsFlow.value
        val p = settings.providers.firstOrNull { it.id.toString() == providerId }
            ?: return@withContext SimpleCommandResult(success = false, error = StudyErrorDto("NOT_FOUND", "提供商不存在", false, ""))

        try {
            val models = providerManager.getProviderByType(p).listModels(p)
            SimpleCommandResult(success = true, idResult = "连接成功（${models.size} 个模型）")
        } catch (e: Exception) {
            SimpleCommandResult(success = false, error = StudyErrorDto("CONNECTION_FAILED", e.message ?: "连接失败", true, ""))
        }
    }

    override suspend fun selectActiveModel(providerId: String, modelId: String): SimpleCommandResult = withContext(Dispatchers.IO) {
        val modelUuid = runCatching { Uuid.parse(modelId) }.getOrNull()
            ?: return@withContext SimpleCommandResult(success = false, error = StudyErrorDto("INVALID_ID", "无效模型ID", false, ""))

        val provider = settingsStore.settingsFlow.value.providers.firstOrNull { it.id.toString() == providerId && it.enabled }
            ?: error("提供商不存在或未启用")
        require(provider.models.any { it.id == modelUuid }) { "模型不属于此提供商" }
        settingsStore.update { it.copy(chatModelId = modelUuid) }
        SimpleCommandResult(success = true, idResult = modelId)
    }

    override suspend fun getDiagnostics(): DiagnosticsDto = withContext(Dispatchers.IO) {
        val summary = Diagnostics.summary(context)
        DiagnosticsDto(
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            appVersion = "1.0.0",
            recentCrashCount = 0L,
            databaseRevision = 31L,
            logSummary = summary
        )
    }

    override suspend fun exportDiagnostics(): SimpleCommandResult = withContext(Dispatchers.IO) {
        try {
            val file = Diagnostics.export(context, "用户主动导出")
            SimpleCommandResult(success = true, idResult = file.absolutePath)
        } catch (e: Exception) {
            SimpleCommandResult(success = false, error = StudyErrorDto("EXPORT_FAIL", e.message ?: "导出诊断失败", false, ""))
        }
    }
}
