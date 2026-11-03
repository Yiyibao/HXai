package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolExecutionState
import me.rerere.rikkahub.data.ai.UncertainToolExecutionException
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

private const val IDLE_TIMEOUT_MS = 5_000L

class ConversationSession(
    val id: Uuid,
    initial: Conversation,
    private val scope: CoroutineScope,
    private val onIdle: (Uuid) -> Unit,
    private val onGenerationFinished: (Uuid, Throwable?) -> Unit = { _, _ -> },
) {
    // 会话状态
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<Conversation> = _state.asStateFlow()
    private val initializationMutex = Mutex()
    private val metadataMutex = Mutex()
    private val backgroundEpoch = AtomicInteger()
    @Volatile internal var deleted = false
        private set
    private fun requireActive() {
        if (deleted) throw CancellationException("Conversation deleted")
    }
    internal fun backgroundToken(): Int = backgroundEpoch.get()
    internal fun invalidateBackgroundResults() { backgroundEpoch.incrementAndGet() }
    @Volatile
    private var initialized = false
    val messageQueue = MessageQueue()

    // 页面切换和 SSE 重连只加载一次；活跃 session 的内存状态始终优先。
    suspend fun initialize(
        persistRecovery: suspend (Conversation) -> Unit = {},
        load: suspend () -> Conversation,
    ) {
        initializationMutex.withLock {
            requireActive()
            if (initialized) return
            val stored = load()
            val conversation = stored.withGenerationState("INTERRUPTED", onlyRunning = true)
                .copy(queue = stored.queue.recovered())
            metadataMutex.withLock {
                requireActive()
                // 加载挂起期间可能已经通过保存或编辑写入了更新的状态。
                if (!initialized) {
                    if (conversation != stored) persistRecovery(conversation)
                    synchronized(this) { if (!initialized) {
                        messageQueue.restore(conversation.queue)
                        updateConversation(conversation)
                    } }
                }
            }
        }
    }

    @Synchronized
    fun updateConversation(conversation: Conversation) {
        require(conversation.id == id)
        if (!initialized && conversation.queue != messageQueue.persisted()) {
            messageQueue.restore(conversation.queue.recovered())
        }
        _state.value = conversation.copy(queue = messageQueue.persisted())
        initialized = true
    }

    internal suspend fun <T> updateQueue(
        persist: suspend (Conversation) -> Unit,
        mutation: (MessageQueue) -> T,
    ): T = metadataMutex.withLock {
        requireActive()
        val base = messageQueue.state.value
        val draft = MessageQueue(base, notifyObservers = false)
        val result = mutation(draft)
        val updated = state.value.copy(queue = draft.persisted())
        persist(updated)
        val currentEditing = messageQueue.state.value.messages.associateBy { it.id }
        val before = base.messages.associateBy { it.id }
        messageQueue.publish(draft.state.value.copy(messages = draft.state.value.messages.map { item ->
            if (item.isEditing == before[item.id]?.isEditing) item.copy(isEditing = currentEditing[item.id]?.isEditing ?: item.isEditing) else item
        }))
        updateConversation(updated)
        if (draft.state.value.paused) messageQueue.failPausedWaiters()
        result
    }

    internal suspend fun commitQueuedInput(
        queued: QueuedMessage,
        parts: List<me.rerere.ai.ui.UIMessagePart>,
        persist: suspend (Conversation) -> Unit,
    ) = metadataMutex.withLock {
        requireActive()
        val draft = MessageQueue(messageQueue.state.value, notifyObservers = false)
        check(draft.takeNext()?.id == queued.id) { "Queued input changed before submission" }
        val current = state.value
        val messages = if (current.currentMessages.any { it.id == queued.id }) current.messageNodes else
            current.messageNodes + UIMessage(id = queued.id, role = me.rerere.ai.core.MessageRole.USER, parts = parts).toMessageNode()
        val updated = current.copy(messageNodes = messages, queue = draft.persisted())
        persist(updated)
        messageQueue.publish(draft.state.value)
        updateConversation(updated)
    }

    // 元数据先应用到最新内存状态；落库只更新对应列，不能用旧消息快照覆盖流式输出。
    internal suspend fun updateMetadata(
        update: (Conversation) -> Conversation,
        persist: suspend (Conversation) -> Unit,
    ) {
        metadataMutex.withLock {
            requireActive()
            val updated = synchronized(this) {
                update(state.value).also(::updateConversation)
            }
            persist(updated)
        }
    }

    internal suspend fun commitConversation(
        conversation: Conversation,
        persist: suspend (Conversation) -> Unit,
    ) = withContext(NonCancellable) { metadataMutex.withLock {
        requireActive()
        require(conversation.id == id)
        persist(conversation)
        updateConversation(conversation)
    } }

    /** Read the queue under its mutation mutex, and publish only after atomic persistence. */
    internal suspend fun commitToolApproval(
        update: (Conversation) -> Conversation?,
        persist: suspend (Conversation) -> Unit,
    ): Conversation? = withContext(NonCancellable) { metadataMutex.withLock {
        requireActive()
        val updated = (update(state.value) ?: return@withLock null).copy(queue = messageQueue.persisted())
        require(updated.id == id)
        persist(updated)
        updateConversation(updated)
        updated
    } }

    internal suspend fun applyBackgroundMetadata(
        expected: Conversation,
        token: Int,
        title: String? = null,
        suggestions: List<String>? = null,
        persist: suspend () -> Boolean,
    ): Boolean = metadataMutex.withLock {
        if (deleted) return@withLock false
        fun valid(current: Conversation) = token == backgroundToken() &&
            current.profileId == expected.profileId && current.messageNodes == expected.messageNodes &&
            (title == null || current.title == expected.title)
        if (!valid(state.value)) return@withLock false
        if (!persist()) return@withLock false
        synchronized(this) {
            val current = state.value
            if (!valid(current)) return@synchronized false
            updateConversation(current.copy(
                title = title ?: current.title,
                chatSuggestions = suggestions ?: current.chatSuggestions,
            ))
            true
        }
    }

    internal suspend fun delete(persist: suspend () -> Unit) = withContext(NonCancellable) { metadataMutex.withLock {
        requireActive()
        persist()
        deleted = true
        invalidateBackgroundResults()
    } }

    internal suspend fun restoreCommitted(persist: suspend () -> Conversation) = withContext(NonCancellable) { metadataMutex.withLock {
        val conversation = persist()
        invalidateBackgroundResults()
        deleted = false
        messageQueue.restore(conversation.queue.recovered())
        updateConversation(conversation)
    } }

    internal suspend fun restore(conversation: Conversation, persist: suspend () -> Unit) = withContext(NonCancellable) { metadataMutex.withLock {
        persist()
        invalidateBackgroundResults()
        deleted = false
        messageQueue.restore(conversation.queue.recovered())
        updateConversation(conversation)
    } }

    // 失败和取消也必须保存已收到的内容，且保存完成前不能释放生成任务。
    // Publish only after commit: a process death cannot discard already displayed text.
    internal suspend fun checkpointGeneration(
        messages: List<UIMessage>,
        persist: suspend (Conversation) -> Unit,
    ) {
        metadataMutex.withLock {
            requireActive()
            val updated = state.value.updateCurrentMessages(me.rerere.rikkahub.feature.interactive.InteractiveSurface.mergeClientState(messages, state.value.currentMessages)).withGenerationState("RUNNING", messageId = messages.lastOrNull()?.id)
            persist(updated)
            updateConversation(updated)
        }
    }

    internal suspend fun recordGenerationState(status: String, persist: suspend (Conversation) -> Unit) {
        commitGenerationState(status, null, persist)
    }

    internal suspend fun recordGenerationResult(cause: Throwable?, persist: suspend (Conversation) -> Unit) {
        val hasUncertain = state.value.currentMessages.any { it.getTools().any { tool -> tool.isExecutionUncertain } }
        val status = when {
            hasUncertain -> "INTERRUPTED"
            cause == null && state.value.currentMessages.any { it.getTools().any { tool -> !tool.isExecuted } } -> "WAITING_TOOL"
            else -> generationFailureState(cause)
        }
        commitGenerationState(status, (cause as? me.rerere.ai.util.HttpException)?.statusCode, persist)
    }

    private suspend fun commitGenerationState(status: String, errorCode: Int?, persist: suspend (Conversation) -> Unit) {
        withContext(NonCancellable) {
            metadataMutex.withLock {
                requireActive()
                val updated = state.value.withGenerationState(status, errorCode = errorCode)
                persist(updated)
                updateConversation(updated)
            }
        }
    }

    suspend fun finishGeneration(save: suspend (Conversation) -> Unit): Conversation =
        withContext(NonCancellable) { metadataMutex.withLock {
            requireActive()
            val current = state.value
            val conversation = current.copy(
                messageNodes = current.messageNodes.map { node ->
                    node.copy(messages = node.messages.map { it.finishReasoning() })
                },
                updateAt = Instant.now(),
            )
            save(conversation)
            updateConversation(conversation)
            conversation
        } }

    // 从队列取出到写入会话历史之间，附件仍需作为有效引用保留。
    @Volatile
    var submittingMessage: QueuedMessage? = null
        internal set

    // 原子引用计数
    private val refCount = AtomicInteger(0)

    // 处理状态（如 OCR 识别中）
    val processingStatus = MutableStateFlow<String?>(null)

    // 生成任务（内聚在 session 中）
    private val _generationJob = MutableStateFlow<Job?>(null)
    private val activeJobs = mutableSetOf<Job>()
    val generationJob: StateFlow<Job?> = _generationJob.asStateFlow()
    val isGenerating: Boolean get() = _generationJob.value?.isActive == true
    val isInUse: Boolean
        get() = refCount.get() > 0 || _generationJob.value != null ||
                messageQueue.state.value.messages.isNotEmpty()

    // 空闲检查任务
    private var idleCheckJob: Job? = null

    internal fun acquire(): Int = refCount.incrementAndGet().also {
        cancelIdleCheck()
    }

    internal fun release(): Int = refCount.decrementAndGet().also {
        if (it <= 0) scheduleIdleCheck()
    }

    @Synchronized
    fun setJob(job: Job?, cancelPrevious: Boolean = true) {
        val previous = _generationJob.value
        _generationJob.value = job
        if (cancelPrevious) previous?.cancel()
        if (job != null) activeJobs.add(job)
        job?.invokeOnCompletion { cause ->
            synchronized(this) {
                activeJobs.remove(job)
                // Also propagate cancellation when a queued coroutine never entered its body.
                if (!cancelPrevious && cause is CancellationException) previous?.cancel()
                // A replaced job must not clear or advance its successor.
                if (_generationJob.compareAndSet(job, null)) {
                    onGenerationFinished(id, cause)
                    if (refCount.get() <= 0) scheduleIdleCheck()
                }
            }
        }
        job?.start()
    }

    fun getJob(): Job? = _generationJob.value

    @Synchronized
    fun cancelJobs(): List<Job> = activeJobs.toList().also { jobs ->
        // Cancel waiters first so a predecessor finishing cannot start the next approval.
        jobs.asReversed().forEach { it.cancel() }
    }

    private fun scheduleIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            if (refCount.get() <= 0 && !isGenerating) {
                onIdle(id)
            }
        }
    }

    private fun cancelIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = null
    }

    @Synchronized
    fun cleanup() {
        _generationJob.value = null
        cancelJobs()
        idleCheckJob?.cancel()
        idleCheckJob = null
    }
}

internal fun Conversation.withGenerationState(status: String, onlyRunning: Boolean = false, errorCode: Int? = null, messageId: Uuid? = null): Conversation {
    val lastId = messageId ?: currentMessages.lastOrNull { it.generationState == "RUNNING" }?.id ?: currentMessages.lastOrNull()?.id
    return copy(messageNodes = messageNodes.map { node ->
        node.copy(messages = node.messages.map { message ->
            when {
                onlyRunning -> if (message.generationState == "RUNNING") message.recoverTools().copy(generationState = status) else message.recoverTools()
                message.id == lastId -> message.let { if (status != "RUNNING") it.recoverTools() else it }.copy(generationState = status, generationErrorCode = errorCode)
                message.generationState == "RUNNING" -> message.copy(generationState = null, generationErrorCode = null)
                else -> message
            }
        })
    })
}

private fun UIMessage.recoverTools() = copy(parts = parts.map {
    if (it is UIMessagePart.Tool && !it.isExecuted && it.executionState == ToolExecutionState.RUNNING)
        it.copy(executionState = ToolExecutionState.INTERRUPTED) else it
})

internal fun generationFailureState(cause: Throwable?): String = when (cause) {
    null -> "COMPLETED"
    is NetworkChangedException, is UncertainToolExecutionException -> "INTERRUPTED"
    is CancellationException -> "CANCELLED"
    is java.io.IOException -> "INTERRUPTED"
    else -> "FAILED"
}

internal fun prepareGenerationMessages(messages: List<UIMessage>, modelId: Uuid): List<UIMessage> =
    if (messages.lastOrNull()?.role == me.rerere.ai.core.MessageRole.USER) {
        messages + UIMessage(role = me.rerere.ai.core.MessageRole.ASSISTANT, parts = emptyList(), modelId = modelId)
    } else messages

/** Serialize approval saves without cancelling earlier decisions; stopping cancels the whole chain. */
internal suspend fun afterPreviousGeneration(previous: Job?, block: suspend () -> Unit) {
    try {
        previous?.join()
        block()
    } catch (e: CancellationException) {
        previous?.cancel()
        withContext(NonCancellable) { previous?.join() }
        throw e
    }
}
