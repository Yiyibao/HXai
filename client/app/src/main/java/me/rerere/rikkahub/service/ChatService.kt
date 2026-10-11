package me.rerere.rikkahub.service
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

import android.app.Application
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ToolExecutionState
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.ai.TranslationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.ai.tools.InvalidMcpServerNamesException
import me.rerere.rikkahub.data.ai.tools.shouldUseExternalWebSearch
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.localFileUrls
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatService"

internal fun backgroundTextGenerationParams(
    model: Model,
    conversationId: Uuid,
    reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
    sessionId = conversationId.toString(),
)

private val forkTitleSuffixRegex = Regex("""\((\d+)\)$""")

internal fun forkConversationTitle(sourceTitle: String, existingTitles: Set<String>): String {
    // 源标题已带 (N) 后缀时递增序号，避免多次 fork 后叠加成 xxx(1)(1)(1)
    val suffix = forkTitleSuffixRegex.find(sourceTitle)
    val baseTitle = suffix?.let { sourceTitle.removeRange(it.range) } ?: sourceTitle
    val start = suffix?.groupValues?.get(1)?.toIntOrNull()?.plus(1) ?: 1
    return generateSequence(start) { it + 1 }
        .map { "$baseTitle($it)" }
        .first { it !in existingTitles }
}

internal fun createForkConversation(
    source: Conversation,
    messageNodes: List<MessageNode>,
    existingTitles: Set<String> = emptySet(),
): Conversation = Conversation(
    id = Uuid.random(),
    assistantId = source.assistantId,
    profileId = source.profileId,
    title = forkConversationTitle(source.title, existingTitles),
    messageNodes = messageNodes,
    customSystemPrompt = source.customSystemPrompt,
    modeInjectionIds = source.modeInjectionIds,
    lorebookIds = source.lorebookIds,
    workspaceCwd = source.workspaceCwd,
    folderId = source.folderId,
    studyMode = source.studyMode,
)

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

enum class ChatErrorSolution {
    CheckFastModelSettings,
}

private val inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        OcrTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val generationLoop: GenerationLoop,
    private val translationHandler: TranslationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val chatToolFactory: ChatToolFactory,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val workspaceRepository: WorkspaceRepository,
    private val folderRepository: FolderRepository,
    private val learningMemories: me.rerere.rikkahub.feature.memory.LearningMemoryStore,
    private val memoryRunner: me.rerere.rikkahub.feature.memory.MemoryExtractionRunner,
    private val materialFiles: me.rerere.rikkahub.feature.material.MaterialFiles = me.rerere.rikkahub.feature.material.MaterialFiles(context),
) {
    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构造)
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)

    private val sessionManager = ConversationSessionManager(
        scope = appScope,
        createInitialConversation = { id ->
            Conversation.ofId(id, assistantId = settingsStore.settingsFlow.value.getCurrentAssistant().id)
                .copy(profileId = settingsStore.settingsFlow.value.activeProfileId)
        },
        onGenerationFinished = ::onSessionGenerationFinished,
    )

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        if (switchingProfile) return
        if (conversationId != null && sessionManager.get(conversationId)?.let { !canUseSession(it) } == true) return
        _errors.update {
            it + ChatError(title = title, error = error, conversationId = conversationId, solution = solution)
        }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    // 生成完成流
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    fun cleanup() = runCatching { sessionManager.cleanup() }

    private fun onSessionGenerationFinished(session: ConversationSession, cause: Throwable?) {
        appScope.launch {
            if (session.deleted) return@launch
            if (cause != null) pauseAfterFailure(session)
            if (session.state.value.currentMessages.any { message -> message.getTools().any { !it.isExecuted } }) {
                session.messageQueue.failReplyWaiters(context.getString(R.string.chat_page_voice_tool_approval))
            }
            dispatchNextQueuedMessage(session.id)
        }
    }

    // 保留 UI/Web 的入口，生命周期和状态查询统一交给 SessionManager。
    fun addConversationReference(conversationId: Uuid) {
        sessionManager.acquire(conversationId)
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessionManager.release(conversationId)
    }

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> =
        sessionManager.getConversationFlow(conversationId)

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> =
        sessionManager.getGenerationJobStateFlow(conversationId)

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> =
        sessionManager.getProcessingStatusFlow(conversationId)

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> = sessionManager.getConversationJobs()

    private val profileSwitchMutex = Mutex()
    @Volatile private var switchingProfile = false

    suspend fun switchLearnerProfile(profileId: String) = profileSwitchMutex.withLock {
        require(settingsStore.settingsFlow.value.learnerProfiles.any { it.id == profileId })
        if (profileId == settingsStore.settingsFlow.value.activeProfileId) return@withLock
        switchingProfile = true
        try {
            // Pause queues first; cancellation saves received content before joining completes.
            val sessions = sessionManager.snapshot().filterNot { it.deleted }
            sessions.forEach { pauseMessageQueue(it) }
            sessions.forEach { stopGeneration(it.id) }
            settingsStore.selectLearnerProfile(profileId)
            clearAllErrors()
        } finally {
            switchingProfile = false
        }
    }

    private fun canUseSession(session: ConversationSession): Boolean =
        !session.deleted && !switchingProfile && session.state.value.profileId == settingsStore.settingsFlow.value.activeProfileId

    private fun launchGenerationJob(
        conversationId: Uuid,
        keepAliveInBackground: Boolean = true,
        block: suspend () -> Unit,
    ): Job {
        if (!keepAliveInBackground) return appScope.launch(start = CoroutineStart.LAZY) {
            memoryRunner.chatStarted()
            try { block() } finally { memoryRunner.chatFinished() }
        }

        return appScope.launch(start = CoroutineStart.LAZY) {
            val generationId = Uuid.random()
            val foregroundStarted = ChatGenerationForegroundService.acquire(
                context = context,
                generationId = generationId,
                conversationId = conversationId,
            )
            memoryRunner.chatStarted()
            try {
                block()
            } finally {
                memoryRunner.chatFinished()
                if (foregroundStarted) {
                    ChatGenerationForegroundService.release(context, generationId)
                }
            }
        }
    }

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid) {
        conversationRepo.requireConversationAccess(conversationId)
        sessionManager.get(conversationId)?.let {
            require(it.state.value.profileId == settingsStore.settingsFlow.value.activeProfileId) { "Conversation access denied" }
        }
        sessionManager.withSession(conversationId) { session ->
            session.initialize(persistRecovery = conversationRepo::recoverConversation) {
                conversationRepo.getConversationById(conversationId) ?: run {
                    // 新建对话, 并添加预设消息
                    val currentSettings = settingsStore.settingsFlowRaw.first()
                    val assistant = currentSettings.getCurrentAssistant()
                    Conversation.ofId(
                        id = conversationId,
                        assistantId = assistant.id,
                        newConversation = true
                    ).copy(profileId = currentSettings.activeProfileId).updateCurrentMessages(assistant.presetMessages)
                }
            }
            settingsStore.updateAssistant(session.state.value.assistantId)
        }
    }

    // ---- 发送消息 ----

    fun getMessageQueueFlow(conversationId: Uuid): StateFlow<MessageQueueState> =
        sessionManager.getMessageQueueFlow(conversationId)

    fun removeQueuedMessage(conversationId: Uuid, messageId: Uuid) {
        if (sessionManager.get(conversationId)?.submittingMessage?.id == messageId) return
        mutateMessageQueue(conversationId, { it.remove(messageId) }) { removed ->
            removed?.reply?.complete(null)
            removed?.let(::cleanupQueuedAttachments)
        }
    }

    fun beginEditQueuedMessage(conversationId: Uuid, messageId: Uuid): QueuedMessage? =
        sessionManager.get(conversationId)?.takeIf { canUseSession(it) && it.submittingMessage?.id != messageId }
            ?.messageQueue?.beginEdit(messageId)

    fun finishEditQueuedMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>? = null
    ) {
        mutateMessageQueue(conversationId, { it.finishEdit(messageId, parts) }) { it?.let(::cleanupQueuedAttachments) }
    }

    private fun cleanupQueuedAttachments(previous: QueuedMessage) {
        val candidates = previous.parts.localFileUrls()
        if (candidates.isEmpty()) return
        appScope.launch {
            try {
                // 未打开的会话及未选中的分支也可能引用同一附件。
                val persistedReferences =
                    candidates.filter { conversationRepo.hasFileReference(it) }.toSet()
                // 数据库查询挂起期间队列可能已推进，删除前重新读取内存引用。
                val currentSessions = sessionManager.snapshot()
                val unusedFiles = unreferencedQueuedAttachmentUrls(
                    previous = previous,
                    conversations = currentSessions.map { it.state.value },
                    pendingMessages = currentSessions.flatMap {
                        it.messageQueue.state.value.messages + listOfNotNull(it.submittingMessage)
                    },
                ) - persistedReferences
                if (unusedFiles.isNotEmpty()) {
                    filesManager.deleteChatFiles(unusedFiles.map { it.toUri() })
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // 无法确认引用时保留文件，避免误删。
                Log.w(TAG, "Failed to clean queued attachments")
            }
        }
    }

    fun resumeMessageQueue(conversationId: Uuid) {
        mutateMessageQueue(conversationId, { it.resume() }) {}
    }

    private val queueMutationMutex = kotlinx.coroutines.sync.Mutex()

    private suspend fun pauseMessageQueue(session: ConversationSession) {
        if (session.deleted) return
        try {
            session.updateQueue(conversationRepo::commitRuntimeState) { it.pause() }
        } catch (error: CancellationException) {
            if (!session.deleted) throw error
        }
    }

    /** A second storage failure must not hide the error that interrupted the operation. */
    private suspend fun pauseAfterFailure(session: ConversationSession) {
        try {
            pauseMessageQueue(session)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            session.messageQueue.pause()
            Log.w(TAG, "Queue pause could not be persisted after failure")
        }
    }
    private fun <T> mutateMessageQueue(
        conversationId: Uuid,
        mutation: (MessageQueue) -> T,
        failed: (Throwable) -> Unit = {},
        committed: (T) -> Unit,
    ) = appScope.launch(start = CoroutineStart.UNDISPATCHED) {
        queueMutationMutex.lock()
        try {
            sessionManager.withSession(conversationId) { session ->
                session.initialize(persistRecovery = conversationRepo::recoverConversation) {
                    conversationRepo.getConversationById(conversationId) ?: session.state.value
                }
                check(canUseSession(session)) { "Conversation access denied" }
                committed(session.updateQueue(conversationRepo::commitRuntimeState, mutation))
                dispatchNextQueuedMessage(conversationId)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            failed(e)
            addError(e, conversationId)
        } finally { queueMutationMutex.unlock() }
    }

    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return
        val session = sessionManager.getOrCreate(conversationId)
        if (!canUseSession(session)) return
        mutateMessageQueue(conversationId, {
            if (it.state.value.messages.isEmpty()) it.resume()
            it.enqueue(content, answer)
        }) {}
    }

    /** Bridge acknowledgement follows the durable queue commit, not merely job creation. */
    suspend fun sendMessageCommitted(conversationId: Uuid, content: List<UIMessagePart>, inputId: Uuid, validate: (Conversation) -> Unit = {}) {
        require(!content.isEmptyInputMessage()) { "消息不能为空" }
        val committed = CompletableDeferred<Unit>()
        mutateMessageQueue(conversationId, {
            val conversation = getConversationFlow(conversationId).value
            validate(conversation)
            if (conversation.messageNodes.any { node -> node.messages.any { message -> message.id == inputId } } ||
                it.state.value.messages.any { pending -> pending.id == inputId }) return@mutateMessageQueue
            if (it.state.value.messages.isEmpty()) it.resume()
            it.enqueue(content, true, id = inputId)
        }, { committed.completeExceptionally(it) }) { committed.complete(Unit) }
        committed.await()
    }

    /** Enqueue immediately; the result belongs to this item even after edits or later turns. */
    fun enqueueVoiceMessage(conversationId: Uuid, text: String): Deferred<String?> {
        val session = sessionManager.getOrCreate(conversationId)
        check(canUseSession(session)) { "Conversation access denied" }
        val reply = CompletableDeferred<String?>()
        synchronized(session) {
            check(text.isNotBlank()) { context.getString(R.string.chat_page_voice_empty) }
            check(!session.messageQueue.state.value.paused || session.messageQueue.state.value.messages.isEmpty()) {
                context.getString(R.string.chat_page_voice_resume_queue)
            }
            check(session.state.value.currentMessages.none { message ->
                message.parts.any { it is UIMessagePart.Tool && it.isPending }
            }) { context.getString(R.string.chat_page_voice_tools_before_resume) }
            mutateMessageQueue(conversationId, {
                check(!it.state.value.paused || it.state.value.messages.isEmpty()) { context.getString(R.string.chat_page_voice_resume_queue) }
                check(session.state.value.currentMessages.none { message -> message.getTools().any { tool -> !tool.isExecuted } }) {
                    context.getString(R.string.chat_page_voice_tools_before_resume)
                }
                if (it.state.value.messages.isEmpty()) it.resume()
                it.enqueue(listOf(UIMessagePart.Text(text)), reply = reply)
            }, { reply.completeExceptionally(it) }, {}).invokeOnCompletion { error -> if (error != null) reply.completeExceptionally(error) }

        }
        return reply
    }

    private fun dispatchNextQueuedMessage(conversationId: Uuid): Job? {
        val session = sessionManager.get(conversationId) ?: return null
        if (!canUseSession(session)) return null
        synchronized(session) {
            // A pending tool approval is still part of the current turn.
            if (session.getJob() != null || session.state.value.currentMessages.any { message ->
                    message.parts.any { it is UIMessagePart.Tool && !it.isExecuted }
                }) return null
            val queue = session.messageQueue.state.value
            if (queue.paused) return null
            val next = queue.messages.firstOrNull()?.takeUnless { it.isEditing } ?: return null
            session.submittingMessage = next
            return sendQueuedMessage(session, next)
        }
    }

    private fun sendQueuedMessage(session: ConversationSession, queued: QueuedMessage): Job {
        val conversationId = session.id
        val content = queued.parts
        val answer = queued.answer
        val job = launchGenerationJob(
            conversationId = conversationId,
            keepAliveInBackground = answer,
        ) {
            try {
                finishInterruptedPendingTools(conversationId)

                val currentConversation = session.state.value
                val settings = settingsStore.settingsFlow.first()
                val assistant = settings.getAssistantById(currentConversation.assistantId)
                    ?: settings.getCurrentAssistant()
                val processedContent = materialFiles.bindParts(currentConversation.profileId, preprocessUserInputParts(content, assistant))

                // 添加消息到列表
                session.commitQueuedInput(queued, processedContent, conversationRepo::commitRuntimeState)
                session.submittingMessage = null

                // 开始补全
                if (answer) {
                    handleMessageComplete(conversationId)
                }

                queued.reply?.completeWith(runCatching {
                    val messages = session.state.value.currentMessages
                    check(!session.messageQueue.state.value.paused) { context.getString(R.string.chat_page_voice_generation_failed) }
                    check(messages.none { message -> message.parts.any { it is UIMessagePart.Tool && it.isPending } }) {
                        context.getString(R.string.chat_page_voice_tool_approval)
                    }
                    val previousIds = currentConversation.currentMessages.map { it.id }.toSet()
                    messages.filter { it.id !in previousIds && it.role == MessageRole.ASSISTANT }
                        .joinToString("\n") { it.toText() }
                })
                // Voice owns playback, including when its observer has already left the page.
                // The ordinary autoplay collector must not read a late voice reply again.
                if (queued.reply == null) _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                queued.reply?.completeExceptionally(e)
                if (e is CancellationException) throw e
                pauseAfterFailure(session)
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
        job.invokeOnCompletion { cause ->
            if (cause != null) queued.reply?.completeExceptionally(cause)
            synchronized(session) {
                if (session.submittingMessage?.id == queued.id) session.submittingMessage = null
            }
        }
        session.setJob(job)
        return job
    }

    private fun preprocessUserInputParts(parts: List<UIMessagePart>, assistant: Assistant): List<UIMessagePart> {
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    part.copy(
                        text = part.text.replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.USER,
                            visual = false
                        )
                    )
                }

                else -> part
            }
        }
    }

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) = synchronized(sessionManager.getOrCreate(conversationId)) {
        val session = sessionManager.getOrCreate(conversationId)
        check(canUseSession(session)) { "Conversation access denied" }
        val previousJob = session.getJob()

        val job = launchGenerationJob(
            conversationId = conversationId,
            keepAliveInBackground = message.role == MessageRole.USER || regenerateAssistantMsg,
        ) {
            try {
                previousJob?.join()
                val conversation = session.state.value

                if (message.role == MessageRole.USER) {
                    // 如果是用户消息，则截止到当前消息
                    val node = conversation.getMessageNodeByMessage(message)
                    val indexAt = conversation.messageNodes.indexOf(node)
                    if (indexAt < 0) throw NotFoundException("Message not found")
                    archiveDependentBranch(conversationId, conversation, indexAt)
                    val newConversation = conversation.copy(
                        messageNodes = conversation.messageNodes.subList(0, indexAt + 1)
                    )
                    saveConversation(conversationId, newConversation)
                    handleMessageComplete(conversationId)
                } else {
                    if (regenerateAssistantMsg) {
                        val node = conversation.getMessageNodeByMessage(message)
                        val nodeIndex = conversation.messageNodes.indexOf(node)
                        if (nodeIndex < 1) throw NotFoundException("Message not found")
                        archiveDependentBranch(conversationId, conversation, nodeIndex)
                        saveConversation(conversationId, conversation.copy(
                            messageNodes = conversation.messageNodes.take(nodeIndex + 1),
                            chatSuggestions = emptyList(),
                        ))
                        handleMessageComplete(conversationId, messageRange = 0..<nodeIndex)
                    } else {
                        saveConversation(conversationId, conversation)
                    }
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                pauseAfterFailure(session)
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }

        session.setJob(job)
    }

    fun resumeInterruptedGeneration(conversationId: Uuid, skipUncertainTools: Boolean = false) {
        val session = sessionManager.getOrCreate(conversationId)
        if (!canUseSession(session)) return
        synchronized(session) {
            if (session.getJob() != null) return
            val job = launchGenerationJob(conversationId) {
                val current = session.state.value
                val last = current.currentMessages.lastOrNull() ?: return@launchGenerationJob
                if (last.generationState !in setOf("INTERRUPTED", "CANCELLED", "FAILED", "WAITING_TOOL")) return@launchGenerationJob
                if (last.getTools().any { it.isPending }) return@launchGenerationJob
                if (last.getTools().any { it.isExecutionUncertain }) {
                    if (!skipUncertainTools) return@launchGenerationJob
                    val resolved = last.copy(parts = last.parts.map {
                        if (it is UIMessagePart.Tool && it.isExecutionUncertain) it.copy(
                            executionState = ToolExecutionState.SKIPPED_UNKNOWN,
                            output = listOf(UIMessagePart.Text("""{"status":"unknown","error":"Execution was interrupted; effects may already exist. User chose to skip replay. Verify effects before relying on this result."}""")),
                        ) else it
                    })
                    session.checkpointGeneration(current.currentMessages.dropLast(1) + resolved, conversationRepo::checkpointMessages)
                }
                handleMessageComplete(conversationId)
            }
            session.setJob(job)
        }
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ) = synchronized(sessionManager.getOrCreate(conversationId)) {
        val session = sessionManager.getOrCreate(conversationId)
        check(canUseSession(session)) { "Conversation access denied" }
        // Completed or inactive decisions must not create a job, foreground service,
        // or replace a newer turn's job. Recheck after queued predecessors as well.
        if (session.state.value.currentMessages.none { message ->
                message.getTools().any { it.toolCallId == toolCallId && it.isPending }
            }) return@synchronized
        val previousJob = session.getJob()

        val hasOtherPendingTools = session.state.value.messageNodes.any { node ->
            node.currentMessage.parts.any { part ->
                part is UIMessagePart.Tool && part.isPending && part.toolCallId != toolCallId
            }
        }

        val job = launchGenerationJob(
            conversationId = conversationId,
            keepAliveInBackground = !hasOtherPendingTools,
        ) {
            try {
                afterPreviousGeneration(previousJob) {
                    val conversation = session.state.value
                    // Ignore double taps and stale approvals for completed or inactive tools.
                    if (conversation.currentMessages.none { message ->
                            message.getTools().any { it.toolCallId == toolCallId && it.isPending }
                        }) return@afterPreviousGeneration
                    val newApprovalState = when {
                        answer != null -> ToolApprovalState.Answered(answer)
                        approved -> ToolApprovalState.Approved
                        else -> ToolApprovalState.Denied(reason)
                    }

                    // Update the tool approval state
                    val updatedConversation = session.commitToolApproval(update = { latest ->
                    if (latest.currentMessages.none { message -> message.getTools().any { it.toolCallId == toolCallId && it.isPending } }) return@commitToolApproval null
                    val updatedNodes = latest.messageNodes.map { node ->
                        node.copy(
                            messages = node.messages.map { msg ->
                                msg.copy(
                                    parts = msg.parts.map { part ->
                                        when {
                                            msg.id == node.currentMessage.id && part is UIMessagePart.Tool && part.isPending && part.toolCallId == toolCallId -> {
                                                part.copy(approvalState = newApprovalState)
                                            }

                                            else -> part
                                        }
                                    }
                                )
                            }
                        )
                    }
                    latest.copy(messageNodes = updatedNodes)
                    }, persist = conversationRepo::commitToolApproval) ?: return@afterPreviousGeneration
                    val updatedNodes = updatedConversation.messageNodes

                    // Check if there are still pending tools
                    val hasPendingTools = updatedNodes.any { node ->
                        node.currentMessage.parts.any { part ->
                            part is UIMessagePart.Tool && part.isPending
                        }
                    }

                    // Only continue generation when all pending tools are handled
                    if (!hasPendingTools) {
                        handleMessageComplete(conversationId)
                    }

                    _generationDoneFlow.emit(conversationId)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                pauseAfterFailure(session)
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }

        session.setJob(job, cancelPrevious = false)
    }

    // ---- 处理消息补全 ----

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null
    ) {
        val diagnosticOperation = me.rerere.rikkahub.feature.diagnostics.Diagnostics.operationId()
        me.rerere.rikkahub.feature.diagnostics.Diagnostics.record(
            me.rerere.rikkahub.feature.diagnostics.Diagnostics.Stage.CHAT,
            me.rerere.rikkahub.feature.diagnostics.Diagnostics.Result.STARTED, diagnosticOperation)
        val settings = settingsStore.settingsFlow.first()
        val initialConversation = getConversationFlow(conversationId).value
        val assistant = settings.getAssistantById(initialConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
            ?: throw IllegalStateException("No chat model selected")

        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }
        val useExternalWebSearch = shouldUseExternalWebSearch(assistant, model)

        runCatching {

            // reset suggestions
            updateConversation(conversationId, initialConversation.copy(chatSuggestions = emptyList()))
            val requestMessages = initialConversation.currentMessages.let {
                if (messageRange != null) it.subList(messageRange.start, messageRange.endInclusive + 1) else it
            }.let { prepareGenerationMessages(it, model.id) }
            sessionManager.getOrCreate(conversationId).checkpointGeneration(requestMessages, conversationRepo::checkpointMessages)

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (useExternalWebSearch || mcpManager.getAllAvailableTools().isNotEmpty()) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

            // check invalid messages
            checkInvalidMessages(conversationId)
            val conversation = getConversationFlow(conversationId).value

            val tools = try {
                chatToolFactory.createTools(
                    settings = settings,
                    assistant = assistant,
                    model = model,
                    workspaceCwd = conversation.workspaceCwd,
                    sourceConversation = conversation,
                    profileId = conversation.profileId,
                    allowRemoteDocuments = me.rerere.rikkahub.feature.material.DocumentDispatchPolicy.allowsRemote(conversation.currentMessages),
                )
            } catch (error: InvalidMcpServerNamesException) {
                sessionManager.get(conversationId)?.let { pauseAfterFailure(it) }
                addError(
                    error = IllegalStateException(
                        context.getString(
                            R.string.error_mcp_invalid_server_name,
                            error.names.joinToString(", "),
                        )
                    ),
                    conversationId = conversationId,
                )
                return
            }

            val explicitSource = conversation.currentMessages.lastOrNull { it.role == MessageRole.USER }
            val memoryTools = if (explicitSource != null && Regex("^(请)?(重新)?记住").containsMatchIn(explicitSource.toText().trim()))
                listOf(me.rerere.rikkahub.feature.memory.explicitMemoryTool(learningMemories, conversation.profileId, conversation.id.toString(), explicitSource.id.toString(), explicitSource.toText())) else emptyList()

            // start generating
            val session = sessionManager.getOrCreate(conversationId)
            monitorDeviceNetwork(context) { generationLoop.generateText(
                settings = settings,
                model = model,
                processingStatus = session.processingStatus,
                messages = conversation.currentMessages.take(requestMessages.size),
                assistant = assistant,
                conversationId = conversationId,
                profileId = conversation.profileId,
                conversationSystemPrompt = conversation.customSystemPrompt,
                studyMode = conversation.studyMode,
                conversationModeInjectionIds = conversation.modeInjectionIds,
                conversationLorebookIds = conversation.lorebookIds,
                workspaceCwd = null,
                memories = emptyList(),
                learningMemoryContext = learningMemories.context(
                    conversation.profileId, conversation.id.toString(), explicitSource?.toText().orEmpty(),
                    enabled = settingsStore.settingsFlow.value.learnerProfiles.any { it.id == conversation.profileId && it.memoryEnabled },
                ),
                hiddenSourceMessageIds = learningMemories.hiddenMessageIds(conversation.profileId),
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    add(DocumentAsPromptTransformer(materialFiles, conversation.profileId))
                    add(templateTransformer)
                },
                outputTransformers = outputTransformers,
                tools = tools + memoryTools,
            ).conflate().onCompletion { cause ->
                me.rerere.rikkahub.feature.diagnostics.Diagnostics.record(
                    me.rerere.rikkahub.feature.diagnostics.Diagnostics.Stage.CHAT,
                    when (cause) {
                        null -> me.rerere.rikkahub.feature.diagnostics.Diagnostics.Result.COMPLETED
                        is CancellationException -> me.rerere.rikkahub.feature.diagnostics.Diagnostics.Result.CANCELLED
                        else -> me.rerere.rikkahub.feature.diagnostics.Diagnostics.Result.FAILED
                    }, diagnosticOperation)
                if (cause != null) me.rerere.rikkahub.feature.diagnostics.Diagnostics.recordFailure(
                    me.rerere.rikkahub.feature.diagnostics.Diagnostics.Stage.CHAT, cause, diagnosticOperation)
                session.recordGenerationResult(cause, conversationRepo::checkpointMessages)
                session.processingStatus.value = null
                // 可能被取消了，或者意外结束，兜底更新
                val updatedConversation = session.finishGeneration { conversation ->
                    persistConversation(conversation)
                }

                if (cause == null && updatedConversation.currentMessages.lastOrNull()?.generationState == "COMPLETED") {
                    learningMemories.enqueue(updatedConversation, settingsStore.settingsFlow.value.learnerProfiles.any { it.id == updatedConversation.profileId && it.memoryEnabled })
                    memoryRunner.wake()
                }

                // 生成结束：取消 Live Update 通知，后台时发送完成通知
                appEventBus.emit(
                    AppEvent.ChatGenerationEnded(
                        conversationId = conversationId,
                        senderName = senderName,
                        contentPreview = if (cause == null) updatedConversation.currentMessages.lastOrNull()
                            ?.toText()?.take(50)?.trim() ?: "" else null,
                    )
                )
            }.collect { chunk ->
                when (chunk) {
                    is GenerationChunk.DurableCheckpoint -> {
                        try {
                            session.checkpointGeneration(chunk.messages, conversationRepo::checkpointMessages)
                            chunk.committed.complete(Unit)
                        } catch (error: Throwable) {
                            chunk.committed.completeExceptionally(error)
                            throw error
                        }
                    }
                    is GenerationChunk.Messages -> {
                        session.checkpointGeneration(chunk.messages, conversationRepo::checkpointMessages)

                        // 通知等边缘副作用由 ChatNotificationManager 消费；
                        // tryEmit 不挂起，事件丢失只影响单次通知更新，不能反压生成链
                        chunk.messages.lastOrNull()?.let { lastMessage ->
                            appEventBus.tryEmit(
                                AppEvent.ChatGenerationUpdate(conversationId, lastMessage, senderName)
                            )
                        }
                    }
                }
            } }
        }.onFailure {
            // 兜底取消 Live Update 通知（生成开始前失败时 onCompletion 不会执行）
            me.rerere.rikkahub.feature.diagnostics.Diagnostics.record(
                me.rerere.rikkahub.feature.diagnostics.Diagnostics.Stage.CHAT,
                if (it is CancellationException) me.rerere.rikkahub.feature.diagnostics.Diagnostics.Result.CANCELLED
                else me.rerere.rikkahub.feature.diagnostics.Diagnostics.Result.FAILED, diagnosticOperation)
            me.rerere.rikkahub.feature.diagnostics.Diagnostics.recordFailure(
                me.rerere.rikkahub.feature.diagnostics.Diagnostics.Stage.CHAT, it, diagnosticOperation)
            appEventBus.tryEmit(AppEvent.ChatGenerationEnded(conversationId, senderName, null))
            sessionManager.get(conversationId)?.recordGenerationResult(it, conversationRepo::checkpointMessages)
            if (it is CancellationException) throw it
            sessionManager.get(conversationId)?.let { pauseAfterFailure(it) }
            addError(it, conversationId, title = context.getString(R.string.error_title_generation))
            Logging.log(TAG, "handleMessageComplete: generation failed")

        }.onSuccess {
            val finalConversation = getConversationFlow(conversationId).value

            sessionManager.launchWithSession(conversationId) {
                generateTitle(conversationId, finalConversation)
            }
            sessionManager.launchWithSession(conversationId) {
                generateSuggestion(conversationId, finalConversation)
            }
        }
    }

    // ---- 检查无效消息 ----

    private fun checkInvalidMessages(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        var messagesNodes = conversation.messageNodes

        // 移除无效 tool (未执行的 Tool)
        messagesNodes = messagesNodes.mapIndexed { _, node ->
            // Check for Tool type with non-executed tools
            val hasPendingTools = node.currentMessage.getTools().any { !it.isExecuted }

            if (hasPendingTools) {
                // Keep messages that are ready to resume, such as approved/denied/answered tools.
                val hasResumableTool = node.currentMessage.getTools().any { !it.isExecuted }
                if (hasResumableTool) {
                    return@mapIndexed node
                }

                // If all tools are executed, it's valid
                val allToolsExecuted = node.currentMessage.getTools().all { it.isExecuted }
                if (allToolsExecuted && node.currentMessage.getTools().isNotEmpty()) {
                    return@mapIndexed node
                }

                // Remove messages that still have unresolved tool approvals.
                return@mapIndexed node.copy(
                    messages = node.messages.filter { it.id != node.currentMessage.id },
                    selectIndex = node.selectIndex - 1
                )
            }
            node
        }

        // 更新index
        messagesNodes = messagesNodes.map { node ->
            if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
                node.copy(selectIndex = 0)
            } else {
                node
            }
        }

        // 移除无效消息
        messagesNodes = messagesNodes.filter { it.messages.isNotEmpty() }

        updateConversation(conversationId, conversation.copy(messageNodes = messagesNodes))
    }

    private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        if (tool.isExecutionUncertain) return tool.copy(executionState = ToolExecutionState.INTERRUPTED)
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
                )
            )
        )
    }

    private suspend fun finishInterruptedPendingTools(conversationId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val lastNode = currentConversation.messageNodes.lastOrNull() ?: return
        val lastMessage = lastNode.currentMessage
        val updatedMessage = lastMessage.finishPendingTools(::cancelToolByUser)
        if (updatedMessage == lastMessage) {
            return
        }

        val updatedConversation = currentConversation.copy(
            messageNodes = currentConversation.messageNodes.dropLast(1) + lastNode.copy(
                messages = lastNode.messages.map { message ->
                    if (message.id == lastMessage.id) updatedMessage else message
                }
            )
        )
        saveConversation(conversationId, updatedConversation)
    }

    // ---- 生成标题 ----

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false
    ) = withContext(Dispatchers.IO) {
        if (switchingProfile || conversation.profileId != settingsStore.settingsFlow.value.activeProfileId) return@withContext
        val session = sessionManager.getOrCreate(conversationId)
        val token = session.backgroundToken()
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return@withContext

        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.fastModelId)
                ?: throw IllegalStateException(context.getString(R.string.error_fast_model_not_found))
            val provider = model.findProvider(settings.providers)
                ?: throw IllegalStateException(context.getString(R.string.error_fast_model_provider_not_found))

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText(maxLength = 500) })
                    ),
                ),
                params = backgroundTextGenerationParams(model, conversationId, settings.fastModelReasoningLevel),
            )

            if (!canUseSession(session)) return@runCatching
            val title = result.message.toText().trim()
            session.applyBackgroundMetadata(conversation, token, title = title) {
                conversationRepo.updateGeneratedMetadata(conversation, title = title)
            }
        }.onFailure {
            addError(
                error = it,
                conversationId = conversationId,
                title = context.getString(R.string.error_title_generate_title),
                solution = ChatErrorSolution.CheckFastModelSettings,
            )
        }
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(
        conversationId: Uuid,
        conversation: Conversation,
    ) = withContext(Dispatchers.IO) {
        if (switchingProfile || conversation.profileId != settingsStore.settingsFlow.value.activeProfileId) return@withContext
        val session = sessionManager.getOrCreate(conversationId)
        val token = session.backgroundToken()
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            if (!settings.enableSuggestion) return@runCatching
            val model = settings.findModelById(settings.fastModelId)
                ?: return@runCatching
            val provider = model.findProvider(settings.providers) ?: return@runCatching

            session.applyBackgroundMetadata(conversation, token, suggestions = emptyList()) {
                conversationRepo.updateGeneratedMetadata(conversation, suggestions = emptyList())
            }

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(8).joinToString("\n\n") { it.summaryAsText(maxLength = 500) }),
                    )
                ),
                params = backgroundTextGenerationParams(model, conversationId, settings.fastModelReasoningLevel),
            )
            val suggestions =
                result.message.toText().split("\n").map { it.trim() }
                    .filter { it.isNotBlank() }

            if (switchingProfile || conversation.profileId != settingsStore.settingsFlow.value.activeProfileId) return@runCatching

            session.applyBackgroundMetadata(conversation, token, suggestions = suggestions.take(10)) {
                conversationRepo.updateGeneratedMetadata(conversation, suggestions = suggestions.take(10))
            }
        }.onFailure {
        }
    }

    // ---- 压缩对话历史 ----

    suspend fun compressConversation(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 32
    ): Result<Unit> = runCatching {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.findModelById(settings.compressModelId)
            ?: settings.getCurrentChatModel()
            ?: throw IllegalStateException("No model available for compression")
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException("Provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        val maxMessagesPerChunk = 256
        val allMessages = conversation.currentMessages

        // Split messages into those to compress and those to keep
        val messagesToCompress: List<UIMessage>
        val messagesToKeep: List<UIMessage>

        if (keepRecentMessages > 0 && allMessages.size > keepRecentMessages) {
            messagesToCompress = allMessages.dropLast(keepRecentMessages)
            messagesToKeep = allMessages.takeLast(keepRecentMessages)
        } else if (keepRecentMessages > 0) {
            // Not enough messages to compress while keeping recent ones
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        } else {
            messagesToCompress = allMessages
            messagesToKeep = emptyList()
        }

        fun splitMessages(messages: List<UIMessage>): List<List<UIMessage>> {
            if (messages.size <= maxMessagesPerChunk) return listOf(messages)
            val mid = messages.size / 2
            val left = splitMessages(messages.subList(0, mid))
            val right = splitMessages(messages.subList(mid, messages.size))
            return left + right
        }

        suspend fun compressMessages(messages: List<UIMessage>): String {
            val contentToCompress = messages.joinToString("\n\n") { it.summaryAsText(maxLength = 2000) }
            val prompt = settings.compressPrompt.applyPlaceholders(
                "content" to contentToCompress,
                "target_tokens" to targetTokens.toString(),
                "additional_context" to if (additionalPrompt.isNotBlank()) {
                    "Additional instructions from user: $additionalPrompt"
                } else "",
                "locale" to Locale.getDefault().displayName
            )

            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = backgroundTextGenerationParams(model, conversationId),
            )

            return result.message.toText().trim().takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Failed to generate compressed summary")
        }

        val compressedSummaries = coroutineScope {
            splitMessages(messagesToCompress)
                .map { chunk -> async { compressMessages(chunk) } }
                .awaitAll()
        }

        // Create new conversation with compressed history as multiple user messages + kept messages
        val newMessageNodes = buildList {
            compressedSummaries.forEach { summary ->
                add(UIMessage.user(summary).toMessageNode())
            }
            addAll(messagesToKeep.map { it.toMessageNode() })
        }
        val newConversation = conversation.copy(
            messageNodes = newMessageNodes,
            chatSuggestions = emptyList(),
        )

        saveConversation(conversationId, newConversation)
    }

    // ---- 对话状态更新 ----

    private fun updateConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        val session = sessionManager.getOrCreate(conversationId)
        session.updateConversation(conversation)
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val current = getConversationFlow(conversationId).value
        updateConversation(conversationId, update(current))
    }

    suspend fun updateStudyMode(conversationId: Uuid, mode: me.rerere.rikkahub.feature.studychat.StudyMode) {
        val session = sessionManager.getOrCreate(conversationId)
        if (!canUseSession(session) || session.getJob() != null) return
        session.updateMetadata(
            update = { it.copy(studyMode = mode) },
            persist = { persistConversation(it) },
        )
    }

    private suspend fun updateConversationMetadata(
        conversationId: Uuid,
        update: (Conversation) -> Conversation,
        persist: suspend (Conversation) -> Unit,
        profileId: String? = null,
    ) {
        sessionManager.withSession(conversationId) { session ->
            session.initialize(persistRecovery = conversationRepo::recoverConversation) {
                (if (profileId == null) conversationRepo.getConversationById(conversationId) else conversationRepo.getConversationById(conversationId, profileId))
                    ?: throw NotFoundException("Conversation not found")
            }
            session.updateMetadata(update, persist)
        }
    }

    /** Image jobs update only their own deterministic nodes under the session lock. */
    suspend fun commitImageCreation(task: me.rerere.rikkahub.feature.imagecreation.ImageCreationTask, imageUrl: String?) {
        val id = Uuid.parse(task.conversationId)
        updateConversationMetadata(id, update = { current ->
            require(current.profileId == task.profileId)
            val userId = Uuid.parse(java.util.UUID.nameUUIDFromBytes("image-user:${task.id}".toByteArray()).toString())
            val assistantId = Uuid.parse(task.messageId)
            val label = when (task.state) {
                "RUNNING" -> "正在生成图片"
                "SUCCEEDED" -> "已生成图片 · AI 生成 · v${task.revision}"
                else -> task.error ?: "图片生成未完成"
            }
            val answer = me.rerere.ai.ui.UIMessage(id = assistantId, role = me.rerere.ai.core.MessageRole.ASSISTANT,
                parts = listOf(me.rerere.ai.ui.UIMessagePart.Text(label)) + listOfNotNull(imageUrl?.let { me.rerere.ai.ui.UIMessagePart.Image(it) }))
            val nodes = current.messageNodes.toMutableList()
            if (nodes.none { n -> n.messages.any { it.id == userId } }) {
                nodes.add(me.rerere.rikkahub.data.model.MessageNode.of(me.rerere.ai.ui.UIMessage(id = userId, role = me.rerere.ai.core.MessageRole.USER, parts = listOf(me.rerere.ai.ui.UIMessagePart.Text(task.prompt)))))
            }
            val index = nodes.indexOfFirst { n -> n.messages.any { it.id == assistantId } }
            if (index < 0) nodes.add(me.rerere.rikkahub.data.model.MessageNode.of(answer))
            else nodes[index] = nodes[index].copy(messages = listOf(answer), selectIndex = 0)
            current.copy(messageNodes = nodes, title = current.title.ifBlank { task.prompt.take(32) })
        }, persist = { persistConversation(it) }, profileId = task.profileId)
    }

    suspend fun toggleConversationPinned(conversationId: Uuid) {
        updateConversationMetadata(
            conversationId = conversationId,
            update = { it.copy(isPinned = !it.isPinned) },
            persist = { conversationRepo.updatePinStatus(conversationId, it.isPinned) },
        )
    }

    suspend fun setConversationPinned(conversationId: Uuid, pinned: Boolean, validate: () -> Unit) {
        updateConversationMetadata(conversationId,
            update = { validate(); it.copy(isPinned = pinned) },
            persist = { validate(); conversationRepo.updatePinStatus(conversationId, it.isPinned) })
    }

    suspend fun setConversationArchived(conversationId: Uuid, archived: Boolean, validate: () -> Unit) {
        updateConversationMetadata(conversationId,
            update = { validate(); it.copy(isArchived = archived) },
            persist = { validate(); conversationRepo.updateArchiveStatus(conversationId, it.isArchived) })
    }

    suspend fun moveConversationToAssistant(conversationId: Uuid, assistantId: Uuid) {
        updateConversationMetadata(
            conversationId = conversationId,
            // 文件夹属于助手，移动后清除原助手的文件夹归属。
            update = { it.copy(assistantId = assistantId, folderId = null) },
            persist = { conversationRepo.updateConversationAssistant(conversationId, it.assistantId) },
        )
    }

    /**
     * 移动会话到文件夹（folderId 为 null 表示移出到未归类）。
     *
     * 若该会话当前有活跃 session（正在查看或后台生成），先同步内存态再落库：
     * 否则仅改数据库 folder_id，而内存里那份 Conversation 仍是旧 folderId，
     * 后续任意 saveConversation(id, state.value) 会用整对象把 folder_id 覆盖回旧值，导致移动丢失。
     * 先改内存可确保这段窗口内的整对象保存也带上新 folderId。
     */
    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        conversationRepo.updateConversationFolderId(conversationId, folderId)
        if (sessionManager.get(conversationId) != null) {
            updateConversationState(conversationId) { it.copy(folderId = folderId) }
        }
    }

    /**
     * 文件夹内是否存在正在生成回复的会话。
     * 仅活跃 session 可能在生成；内存态 folderId 为权威（移动会先同步内存态）。
     */
    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean {
        return sessionManager.snapshot().any { it.isGenerating && it.state.value.folderId == folderId }
    }

    /**
     * 删除文件夹（folder_id 归属会被清空，会话本身保留）。
     *
     * 先把内存中归属该文件夹的活跃 session folderId 置空，再删库：
     * 否则 clearFolder 只改了数据库，而活跃 session 内存态仍指向该文件夹，
     * 后续整对象保存会写回一个已被删除的 folder_id，导致会话在列表中悬空。
     */
    suspend fun deleteFolder(folderId: Uuid) {
        require(folderRepository.getFolderById(folderId) != null) { "Folder access denied" }
        sessionManager.snapshot()
            .filter { it.state.value.folderId == folderId && it.state.value.profileId == settingsStore.settingsFlow.value.activeProfileId }
            .forEach { updateConversationState(it.id) { c -> c.copy(folderId = null) } }
        folderRepository.deleteFolder(folderId)
    }

    private suspend fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        val session = sessionManager.get(newConversation.id)
        val queuedFiles = (session?.messageQueue?.state?.value?.messages.orEmpty() +
                listOfNotNull(session?.submittingMessage))
            .flatMap { it.parts }.localFileUrls().map { it.toUri() }
        val newFiles = newConversation.files + queuedFiles
        val oldFiles = oldConversation.files
        val candidates = oldFiles.filter { file ->
            newFiles.none { it == file }
        }
        val deletedFiles = candidates.filter { file ->
            !conversationRepo.hasFileReference(file.toString()) && sessionManager.snapshot().none { other ->
                (if (other.id == newConversation.id) newConversation.files else other.state.value.files)
                    .plus((other.messageQueue.state.value.messages + listOfNotNull(other.submittingMessage))
                        .flatMap { it.parts }.localFileUrls().map { it.toUri() }).contains(file)
            }
        }
        if (deletedFiles.isNotEmpty()) {
            filesManager.deleteChatFiles(deletedFiles)
            Log.w(TAG, "checkFilesDelete: attachment cleanup completed")
        }
    }

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        require(conversationId == conversation.id)
        conversationRepo.requireConversationAccess(conversationId, conversation.profileId)
        val session = sessionManager.getOrCreate(conversationId)
        session.commitConversation(conversation, ::persistConversation)
        dispatchNextQueuedMessage(conversationId)
    }

    private suspend fun persistConversation(conversation: Conversation) {
        conversationRepo.requireConversationAccess(conversation.id, conversation.profileId)
        val exists = conversationRepo.existsConversationById(conversation.id, conversation.profileId)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
            return // 新会话且为空时不保存
        }

        val old = conversationRepo.getConversationById(conversation.id, conversation.profileId)
        if (!exists) {
            require(conversation.newConversation) { "Missing existing conversation; explicit create or restore required" }
            conversationRepo.insertConversation(conversation)
        } else {
            conversationRepo.updateConversation(conversation)
        }
        // Cleanup failure must not turn a committed approval into an apparent failed save.
        if (old != null) runCatching { checkFilesDelete(conversation, old) }.onFailure {
            Log.w(TAG, "Attachment cleanup deferred")
        }
    }

    suspend fun deleteConversation(conversation: Conversation) {
        require(conversation.profileId == settingsStore.settingsFlow.value.activeProfileId) { "Conversation access denied" }
        val session = sessionManager.getOrCreate(conversation.id)
        session.initialize(persistRecovery = conversationRepo::recoverConversation) {
            conversationRepo.getConversationById(conversation.id, conversation.profileId)
                ?: throw NotFoundException("Conversation not found")
        }
        session.invalidateBackgroundResults()
        stopGeneration(conversation.id)
        session.delete { conversationRepo.deleteConversation(conversation) }
    }

    suspend fun restoreConversation(conversation: Conversation) {
        require(conversation.profileId == settingsStore.settingsFlow.value.activeProfileId) { "Conversation access denied" }
        val session = sessionManager.getOrCreate(conversation.id)
        session.restoreCommitted { conversationRepo.restoreConversation(conversation) }
    }

    suspend fun deleteConversationsOfAssistant(assistantId: Uuid) {
        conversationRepo.getConversationsOfAssistant(assistantId).first().forEach { deleteConversation(it) }
    }

    // ---- 翻译消息 ----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()

                val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()

                if (messageText.isBlank()) return@launch

                // Set loading state for translation
                val loadingText = context.getString(R.string.translating)
                updateTranslationField(conversationId, message.id, loadingText)

                translationHandler.translateText(
                    settings = settings,
                    sourceText = messageText,
                    targetLanguage = targetLanguage
                ) { translatedText ->
                    // Update translation field in real-time
                    updateTranslationField(conversationId, message.id, translatedText)
                }.collect { /* Final translation already handled in onStreamUpdate */ }

                // Save the conversation after translation is complete
                saveConversation(conversationId, getConversationFlow(conversationId).value)
            } catch (e: Exception) {
                // Clear translation field on error
                clearTranslationField(conversationId, message.id)
                addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
            }
        }
    }

    private fun updateTranslationField(
        conversationId: Uuid,
        messageId: Uuid,
        translationText: String
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = translationText)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>,
        editId: Uuid = Uuid.random()
    ) {
        if (parts.isEmptyInputMessage()) return
        requireEditableSession(conversationId)

        val currentConversation = getConversationFlow(conversationId).value
        currentConversation.messageNodes.flatMap { it.messages }.firstOrNull { it.id == editId }?.let {
            require(it.parts == parts) { "Edit request reused with different content" }
            return
        }
        require(currentConversation.currentMessages.any { it.id == messageId }) { "Message branch changed before edit" }
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(currentConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val processedParts = preprocessUserInputParts(parts, assistant)
        var edited = false

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (!node.messages.any { it.id == messageId }) {
                return@map node
            }
            edited = true

            node.copy(
                messages = node.messages + UIMessage(
                    id = editId,
                    role = node.role,
                    parts = processedParts,
                ),
                selectIndex = node.messages.size
            )
        }

        if (!edited) return
        val index = currentConversation.messageNodes.indexOfFirst { node -> node.messages.any { it.id == messageId } }
        archiveDependentBranch(conversationId, currentConversation, index)
        val session = sessionManager.getOrCreate(conversationId)
        session.commitToolApproval({ latest ->
            requireEditableSession(conversationId)
            require(latest.messageNodes == currentConversation.messageNodes) { "Message branch changed before edit commit" }
            latest.copy(messageNodes = updatedNodes.take(index + 1), chatSuggestions = emptyList())
        }, ::persistConversation)
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        check(canUseSession(sessionManager.getOrCreate(conversationId))) { "Conversation access denied" }
        val currentConversation = getConversationFlow(conversationId).value
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            throw NotFoundException("Message not found")
        }

        val copiedNodes = currentConversation.messageNodes
            .subList(0, targetNodeIndex + 1)
            .map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message ->
                        message.copy(
                            parts = message.parts.map { part ->
                                part.copyWithForkedFileUrl()
                            }
                        )
                    }
                )
            }

        val existingTitles = conversationRepo
            .getConversationsOfAssistant(currentConversation.assistantId, currentConversation.profileId)
            .first()
            .mapTo(mutableSetOf()) { it.title }
        val forkConversation = createForkConversation(currentConversation, copiedNodes, existingTitles)

        saveConversation(forkConversation.id, forkConversation.copy(newConversation = true, lifecycleRevision = 0))
        return forkConversation
    }

    suspend fun saveInteractiveState(conversationId: Uuid, messageId: String, toolCallId: String, revision: String, values: kotlinx.serialization.json.JsonObject, validate: (Conversation) -> Unit) {
        val session = sessionManager.getOrCreate(conversationId)
        session.commitToolApproval({ current ->
            validate(current)
            val source = current.currentMessages.single { it.id.toString() == messageId }
            val tool = source.parts.filterIsInstance<UIMessagePart.Tool>().single { it.toolCallId == toolCallId && me.rerere.rikkahub.feature.interactive.InteractiveSurface.isSurfaceTool(it.toolName) }
            val raw = me.rerere.rikkahub.feature.interactive.InteractiveSurface.definition(tool)
            require(me.rerere.rikkahub.feature.interactive.InteractiveSurface.hash(raw) == revision)
            me.rerere.rikkahub.feature.interactive.InteractiveSurface.validateState(me.rerere.rikkahub.feature.interactive.InteractiveSurface.validate(raw), values)
            val saved = kotlinx.serialization.json.buildJsonObject { put("version", kotlinx.serialization.json.JsonPrimitive(1)); put("revision", kotlinx.serialization.json.JsonPrimitive(revision)); put("values", values) }
            current.copy(messageNodes = current.messageNodes.map { node -> node.copy(messages = node.messages.map { message ->
                if (message.id != source.id) message else message.copy(parts = message.parts.map { part ->
                    if (part is UIMessagePart.Tool && part.toolCallId == toolCallId) part.copy(metadata = kotlinx.serialization.json.JsonObject(part.metadata.orEmpty() + (me.rerere.rikkahub.feature.interactive.InteractiveSurface.STATE_KEY to saved))) else part
                })
            }) })
        }, ::persistConversation)
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        requireEditableSession(conversationId)
        val currentConversation = getConversationFlow(conversationId).value
        val targetNode = currentConversation.messageNodes.firstOrNull { it.id == nodeId }
            ?: throw NotFoundException("Message node not found")

        if (selectIndex !in targetNode.messages.indices) {
            throw BadRequestException("Invalid selectIndex")
        }

        if (targetNode.selectIndex == selectIndex) {
            return
        }

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.id == nodeId) {
                node.copy(selectIndex = selectIndex)
            } else {
                node
            }
        }

        val index = currentConversation.messageNodes.indexOfFirst { it.id == nodeId }
        archiveDependentBranch(conversationId, currentConversation, index)
        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes.take(index + 1), chatSuggestions = emptyList()))
    }

    private fun requireEditableSession(conversationId: Uuid) {
        val session = sessionManager.getOrCreate(conversationId)
        check(canUseSession(session)) { "Conversation access denied" }
        check(session.getJob() == null) { "Stop generation before changing a branch" }
    }

    // Preserve the full old path in history before detaching replies derived from another version.
    private suspend fun archiveDependentBranch(conversationId: Uuid, conversation: Conversation, changedIndex: Int) {
        val session = sessionManager.getOrCreate(conversationId)
        if (session.messageQueue.state.value.messages.isNotEmpty()) pauseMessageQueue(session)
        if (changedIndex < conversation.messageNodes.lastIndex) {
            conversation.currentMessages.lastOrNull()?.let { forkConversationAtMessage(conversationId, it.id) }
        }
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)

        if (updatedConversation == null) {
            if (failIfMissing) {
                throw NotFoundException("Message not found")
            }
            return
        }

        saveConversation(conversationId, updatedConversation)
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    private fun buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            return null
        }

        val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
            if (index != targetNodeIndex) {
                return@mapIndexedNotNull node
            }

            val nextMessages = node.messages.filterNot { it.id == messageId }
            if (nextMessages.isEmpty()) {
                return@mapIndexedNotNull null
            }

            val nextSelectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex)
            node.copy(
                messages = nextMessages,
                selectIndex = nextSelectIndex,
            )
        }

        return conversation.copy(messageNodes = updatedNodes)
    }

    private fun UIMessagePart.copyWithForkedFileUrl(): UIMessagePart {
        fun copyLocalFileIfNeeded(url: String): String {
            if (!url.startsWith("file:")) return url
            val copied = filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()
            return copied?.toString() ?: url
        }

        return when (this) {
            is UIMessagePart.Image -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Document -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Video -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Audio -> copy(url = copyLocalFileIfNeeded(url))
            else -> this
        }
    }

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = null)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // 停止当前会话生成任务（不清理会话缓存）
    suspend fun stopGeneration(conversationId: Uuid) {
        val session = sessionManager.get(conversationId) ?: return
        if (session.deleted) return
        pauseMessageQueue(session)
        val jobs = synchronized(session) {
            session.cancelJobs()
        }
        if (jobs.isEmpty()) return
        jobs.forEach { it.join() }
        finishInterruptedPendingTools(conversationId)
    }
}
