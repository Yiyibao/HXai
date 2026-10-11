package me.rerere.rikkahub.service
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Context
import android.net.ConnectivityManager
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolExecutionState
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.ai.UncertainToolExecutionException
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.uuid.Uuid

/** Synthetic localhost faults through the installed application's HTTP, service and Room stack. */
@RunWith(AndroidJUnit4::class)
class ChatNetworkFaultTest {
    private lateinit var settings: SettingsStore
    private lateinit var original: Settings
    private lateinit var service: ChatService
    private lateinit var repository: ConversationRepository
    private lateinit var server: FaultServer
    private lateinit var provider: ProviderSetting.OpenAI
    private lateinit var assistant: Assistant
    private val conversations = mutableListOf<Uuid>()
    private var evidenceId: Uuid? = null

    @Before fun setUp() = runBlocking {
        val koin = GlobalContext.get()
        settings = koin.get()
        original = withTimeout(20_000) { settings.settingsFlow.first { !it.init } }
        server = FaultServer()
        val model = Model(modelId = "qa-local", displayName = "QA local fault fixture")
        provider = ProviderSetting.OpenAI(name = "QA localhost", models = listOf(model), apiKey = "synthetic-key",
            baseUrl = server.baseUrl, useResponseApi = true)
        assistant = Assistant(name = "QA network fault", chatModelId = model.id, localTools = emptyList())
        settings.update(original.copy(providers = original.providers + provider, assistants = original.assistants + assistant,
            assistantId = assistant.id, chatModelId = model.id, fastModelId = model.id, enableSuggestion = false,
            networkSetting = original.networkSetting.copy(proxyUrl = "", proxyUsername = "", proxyPassword = "", enableAutoRetry = true)))
        settings.settingsFlow.first { it.assistantId == assistant.id }
        service = koin.get()
        repository = koin.get()
        service.clearAllErrors()
    }

    @After fun tearDown() = runBlocking {
        try {
            if (::service.isInitialized) conversations.forEach { service.stopGeneration(it) }
            if (::repository.isInitialized) conversations.forEach { id ->
                if (id != evidenceId) repository.getConversationById(id)?.let { repository.deleteConversation(it) }
                service.removeConversationReference(id)
            }
        } finally {
            if (::original.isInitialized) settings.update(original)
            if (::server.isInitialized) server.close()
        }
    }

    private suspend fun send(): Uuid {
        val id = Uuid.random()
        conversations += id
        service.addConversationReference(id)
        service.saveConversation(id, Conversation.ofId(id, assistant.id).copy(profileId = original.activeProfileId, title = "QA synthetic fault", newConversation = true))
        service.sendMessage(id, listOf(UIMessagePart.Text("QA_NETWORK_FAULT synthetic question")))
        return id
    }

    private suspend fun terminal(id: Uuid, status: String): Conversation {
        var stage = "terminal-state"
        try {
            return withTimeout(20_000) {
                service.getConversationFlow(id).first { it.currentMessages.lastOrNull()?.generationState == status }
                stage = "job-completion"
                while (service.getGenerationJobStateFlow(id).first() != null) delay(20)
                stage = "database-read"
                repository.getConversationById(id)!!
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            val last = service.getConversationFlow(id).value.currentMessages.lastOrNull()
            val stored = withTimeout(2000) { repository.getConversationById(id)?.currentMessages?.lastOrNull() }
            throw AssertionError("Synthetic QA timeout: stage=$stage memory=${last?.generationState} persisted=${stored?.generationState} " +
                "http=${last?.generationErrorCode} requests=${server.requests.get()} errors=${service.errors.value.map { it.error.javaClass.simpleName }}", error)
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText().trim() }

    @Test fun deviceAirplaneModeInterruptsStreamAndQueueRequiresManualResume() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val airplane = shell("settings get global airplane_mode_on") == "1"
        assertNotNull("Fixture needs an initial device default network", manager.activeNetwork)
        assertFalse("Fixture starts with airplane mode disabled", airplane)
        server.mode = "stall"
        val id = send()
        withTimeout(20_000) { service.getConversationFlow(id).first { it.currentMessages.lastOrNull()?.toText() == "已收到的讲解" } }
        service.sendMessage(id, listOf(UIMessagePart.Text("queued after interruption")))
        withTimeout(10_000) { service.getConversationFlow(id).first { it.queue.messages.size == 1 } }
        val queuedId = service.getConversationFlow(id).value.queue.messages.single().id
        try {
            shell("cmd connectivity airplane-mode enable")
            withTimeout(20_000) { while (manager.activeNetwork != null) delay(100) }
            val saved = terminal(id, "INTERRUPTED")
            assertEquals("已收到的讲解", saved.currentMessages.last().toText())
            withTimeout(10_000) { service.getConversationFlow(id).first { it.queue.paused } }
            assertEquals(queuedId, repository.getConversationById(id)!!.queue.messages.single().id)
            assertEquals(1, server.requests.get())
        } finally {
            shell("cmd connectivity airplane-mode disable")
            withTimeout(30_000) { while (manager.activeNetwork == null) delay(100) }
        }
        delay(500)
        assertEquals("Restoring the device network must not replay a request", 1, server.requests.get())
        server.mode = "complete"
        service.resumeMessageQueue(id)
        val saved = withTimeout(20_000) {
            service.getConversationFlow(id).first { it.currentMessages.count { msg -> msg.role == MessageRole.USER } == 2 && it.currentMessages.last().generationState == "COMPLETED" }
            while (service.getGenerationJobStateFlow(id).first() != null) delay(20)
            repository.getConversationById(id)!!
        }
        assertTrue(saved.queue.messages.isEmpty())
        assertEquals(queuedId, saved.currentMessages.filter { it.role == MessageRole.USER }.last().id)
        assertEquals(2, server.requests.get())
    }

    @Test fun switchingWifiToMobileInterruptsWithoutReplay() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val wifiEnabled = shell("settings get global wifi_on") != "0"
        try {
            shell("svc wifi enable")
            assertTrue(shell("su 0 cmd wifi connect-network AndroidWifi open").contains("Connection initiated"))
            withTimeout(30_000) {
                while (manager.activeNetwork?.let(manager::getNetworkCapabilities)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) != true) delay(100)
            }
            server.mode = "stall"
            val id = send()
            withTimeout(20_000) { service.getConversationFlow(id).first { it.currentMessages.lastOrNull()?.toText() == "已收到的讲解" } }
            shell("svc wifi disable")
            withTimeout(30_000) {
                while (manager.activeNetwork?.let(manager::getNetworkCapabilities)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) != true) delay(100)
            }
            val saved = terminal(id, "INTERRUPTED")
            assertEquals("已收到的讲解", saved.currentMessages.last().toText())
            delay(500)
            assertEquals(1, server.requests.get())
            server.mode = "complete"
            service.regenerateAtMessage(id, saved.currentMessages.last())
            val completed = terminal(id, "COMPLETED")
            assertEquals(2, server.requests.get())
            assertEquals(1, completed.currentMessages.count { it.role == MessageRole.USER })
        } finally {
            shell(if (wifiEnabled) "svc wifi enable" else "svc wifi disable")
            // Restoring the switch is asynchronous. Do not let its pending route
            // change interrupt the next HTTP-only fixture before it sends a request.
            val restoredTransport = if (wifiEnabled) android.net.NetworkCapabilities.TRANSPORT_WIFI
                else android.net.NetworkCapabilities.TRANSPORT_CELLULAR
            withTimeout(30_000) {
                while (manager.activeNetwork?.let(manager::getNetworkCapabilities)?.hasTransport(restoredTransport) != true) delay(100)
            }
        }
    }

    @Test fun room27To28MigrationPreservesConversationAndMessages() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "qa-runtime-migration-${Uuid.random()}.db"
        val id = Uuid.random().toString()
        val message = me.rerere.rikkahub.data.db.entity.MessageNodeEntity(
            id = Uuid.random().toString(), conversationId = id, nodeIndex = 0,
            messages = "[]", selectIndex = 0,
        )
        var db = me.rerere.rikkahub.data.db.AppDatabaseFactory.create(context, name)
        try {
            db.conversationDao().insert(me.rerere.rikkahub.data.db.entity.ConversationEntity(
                id = id, assistantId = assistant.id.toString(), title = "QA migration", nodes = "[]",
                createAt = 1, updateAt = 2, chatSuggestions = "[]", isPinned = true, profileId = "qa-owner",
            ))
            db.messageNodeDao().insert(message)
            // Reconstruct version 27 by removing exactly the new column, then let Room migrate.
            db.openHelper.writableDatabase.execSQL("ALTER TABLE ConversationEntity DROP COLUMN queue_state")
            db.openHelper.writableDatabase.execSQL("ALTER TABLE ConversationEntity DROP COLUMN study_mode")
            db.openHelper.writableDatabase.execSQL("DROP TABLE learning_memory_state")
            db.openHelper.writableDatabase.execSQL("PRAGMA user_version=27")
            db.close()
            db = me.rerere.rikkahub.data.db.AppDatabaseFactory.create(context, name)
            val conversation = db.conversationDao().getConversationById(id)!!
            assertEquals("qa-owner", conversation.profileId)
            assertEquals("QA migration", conversation.title)
            assertTrue(conversation.isPinned)
            assertEquals("{}", conversation.queueState)
            assertEquals(listOf(message), db.messageNodeDao().getNodesOfConversation(id))
            assertEquals("GUIDED", conversation.studyMode)
            assertEquals(30, db.openHelper.writableDatabase.version)
            assertNull(db.learningMemoryDao().get("qa-owner"))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun durableQueueRestoresPausedAndSubmissionIsAtomicInRoom() = runBlocking {
        val id = Uuid.random()
        conversations += id
        val initial = Conversation.ofId(id, assistant.id).copy(profileId = original.activeProfileId, title = "QA durable queue")
        repository.insertConversation(initial)
        val session = ConversationSession(id, initial, this, {})
        session.initialize { initial }
        session.updateQueue(repository::commitRuntimeState) { queue ->
            queue.enqueue(listOf(UIMessagePart.Text("first")), answer = false)
            queue.enqueue(listOf(UIMessagePart.Text("second")), answer = false)
        }
        val stale = initial.copy(title = "metadata after enqueue")
        repository.updateConversation(stale)
        val reopened = ConversationSession(id, initial, this, {})
        reopened.initialize(persistRecovery = repository::recoverConversation) { repository.getConversationById(id)!! }
        assertTrue(reopened.messageQueue.state.value.paused)
        assertEquals(listOf("first", "second"), reopened.messageQueue.state.value.messages.map { (it.parts.single() as UIMessagePart.Text).text })
        reopened.updateQueue(repository::commitRuntimeState) { it.resume() }
        val first = reopened.messageQueue.state.value.messages.first()
        reopened.commitQueuedInput(first, first.parts, repository::commitRuntimeState)
        val saved = repository.getConversationById(id)!!
        assertEquals(first.id, saved.currentMessages.single().id)
        assertEquals(1, saved.queue.messages.size)
        assertEquals("metadata after enqueue", saved.title)
        assertTrue(runCatching { reopened.commitQueuedInput(first, first.parts, repository::commitRuntimeState) }.isFailure)
        assertEquals(1, repository.getConversationById(id)!!.currentMessages.size)
    }

    @Test fun toolNetworkFailureIsUnknownAndDoesNotAdvanceToNextTool() = runBlocking {
        val id = Uuid.random()
        conversations += id
        val initial = Conversation.ofId(id, assistant.id).copy(profileId = original.activeProfileId, title = "QA tool network")
        repository.insertConversation(initial)
        val session = ConversationSession(id, initial, this, {})
        session.initialize { initial }
        val reply = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Tool("network", "network", "{}"), UIMessagePart.Tool("next", "next", "{}"),
        ))
        session.checkpointGeneration(listOf(UIMessage.user("QA_TOOL_NETWORK"), reply), repository::checkpointMessages)
        val calls = AtomicInteger()
        val nextCalls = AtomicInteger()
        val loop = GlobalContext.get().get<GenerationLoop>()
        val failure = runCatching {
            loop.generateText(settings.settingsFlow.value, provider.models.single(), session.state.value.currentMessages,
                assistant = assistant, maxSteps = 1, tools = listOf(
                    Tool("network", "synthetic", execute = { calls.incrementAndGet(); throw IOException("response lost after simulated effect") }),
                    Tool("next", "synthetic", execute = { nextCalls.incrementAndGet(); emptyList() }),
                )).onCompletion { session.recordGenerationResult(it, repository::checkpointMessages) }.collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> session.checkpointGeneration(chunk.messages, repository::checkpointMessages)
                    is GenerationChunk.DurableCheckpoint -> {
                        session.checkpointGeneration(chunk.messages, repository::checkpointMessages)
                        chunk.committed.complete(Unit)
                    }
                }
            }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        val saved = repository.getConversationById(id)!!
        assertEquals("INTERRUPTED", saved.currentMessages.last().generationState)
        assertEquals(ToolExecutionState.INTERRUPTED, saved.currentMessages.last().getTools().first().executionState)
        assertTrue(saved.currentMessages.last().getTools().first().output.isEmpty())
        assertEquals(ToolExecutionState.NOT_STARTED, saved.currentMessages.last().getTools().last().executionState)
        assertEquals(1, calls.get()); assertEquals(0, nextCalls.get()); assertEquals(0, server.requests.get())
    }

    @Test fun resumedAutoToolStillRequiresApprovalBeforeAnyExecution() = runBlocking {
        val id = Uuid.random()
        conversations += id
        val initial = Conversation.ofId(id, assistant.id).copy(profileId = original.activeProfileId, title = "QA tool approval")
        repository.insertConversation(initial)
        val session = ConversationSession(id, initial, this, {})
        session.initialize { initial }
        session.checkpointGeneration(listOf(UIMessage.user("QA_APPROVAL"), UIMessage(role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Tool("approve", "approve", "{}")))), repository::checkpointMessages)
        val calls = AtomicInteger()
        val tool = Tool("approve", "synthetic", needsApproval = { true }, execute = { calls.incrementAndGet(); emptyList() })
        val loop = GlobalContext.get().get<GenerationLoop>()
        loop.generateText(settings.settingsFlow.value, provider.models.single(), session.state.value.currentMessages,
            assistant = assistant, maxSteps = 1, tools = listOf(tool))
            .onCompletion { session.recordGenerationResult(it, repository::checkpointMessages) }.collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> session.checkpointGeneration(chunk.messages, repository::checkpointMessages)
                    is GenerationChunk.DurableCheckpoint -> {
                        session.checkpointGeneration(chunk.messages, repository::checkpointMessages)
                        chunk.committed.complete(Unit)
                    }
                }
            }
        val saved = repository.getConversationById(id)!!
        assertEquals("WAITING_TOOL", saved.currentMessages.last().generationState)
        assertTrue(saved.currentMessages.last().getTools().single().isPending)
        assertEquals(0, calls.get()); assertEquals(0, server.requests.get())
    }

    @Test fun toolCheckpointsPreventReplayAfterExecutionInterruption() = runBlocking {
        val id = Uuid.random()
        conversations += id
        val initial = Conversation.ofId(id, assistant.id).copy(profileId = original.activeProfileId, title = "QA tool continuation")
        repository.insertConversation(initial)
        val session = ConversationSession(id, initial, this, {})
        session.initialize { initial }
        val reply = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Tool("first", "first", "{}"), UIMessagePart.Tool("second", "second", "{}"),
        ))
        session.checkpointGeneration(listOf(UIMessage.user("QA_TOOLS"), reply), repository::checkpointMessages)
        val firstCalls = AtomicInteger()
        val secondCalls = AtomicInteger()
        val secondStarted = CompletableDeferred<Unit>()
        val loop = GlobalContext.get().get<GenerationLoop>()
        val tools = listOf(
            Tool("first", "synthetic no effect", execute = {
                assertEquals(ToolExecutionState.RUNNING, repository.getConversationById(id)!!.currentMessages.last().getTools().first().executionState)
                firstCalls.incrementAndGet(); emptyList()
            }),
            Tool("second", "synthetic no effect", execute = {
                assertTrue(repository.getConversationById(id)!!.currentMessages.last().getTools().first().isExecuted)
                secondCalls.incrementAndGet(); secondStarted.complete(Unit); awaitCancellation()
            }),
        )
        val job = launch {
            loop.generateText(settings.settingsFlow.value, provider.models.single(), session.state.value.currentMessages,
                assistant = assistant, tools = tools, maxSteps = 1).collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> session.checkpointGeneration(chunk.messages, repository::checkpointMessages)
                    is GenerationChunk.DurableCheckpoint -> {
                        session.checkpointGeneration(chunk.messages, repository::checkpointMessages)
                        chunk.committed.complete(Unit)
                    }
                }
            }
        }
        withTimeout(10_000) { secondStarted.await() }
        job.cancel(); job.join()
        val reopened = ConversationSession(id, initial, this, {})
        reopened.initialize(persistRecovery = repository::recoverConversation) { repository.getConversationById(id)!! }
        val saved = repository.getConversationById(id)!!
        val states = saved.currentMessages.last().getTools()
        assertTrue("Empty successful tool results still count as completed", states.first().isExecuted)
        assertEquals(ToolExecutionState.INTERRUPTED, states.last().executionState)
        val failure = runCatching {
            loop.generateText(settings.settingsFlow.value, provider.models.single(), saved.currentMessages,
                assistant = assistant, tools = tools, maxSteps = 1).collect {}
        }.exceptionOrNull()
        assertTrue(failure is UncertainToolExecutionException)
        assertEquals(1, firstCalls.get()); assertEquals(1, secondCalls.get())
        assertEquals(0, server.requests.get())
        service.addConversationReference(id)
        service.initializeConversation(id)
        server.mode = "complete"
        service.resumeInterruptedGeneration(id, skipUncertainTools = true)
        val completed = terminal(id, "COMPLETED")
        assertTrue(completed.currentMessages.last().getTools().all { it.isExecuted })
        assertEquals(ToolExecutionState.SKIPPED_UNKNOWN, completed.currentMessages.last().getTools().last().executionState)
        assertTrue(completed.currentMessages.last().getTools().last().output.filterIsInstance<UIMessagePart.Text>().single().text.contains("unknown"))
        assertEquals(1, firstCalls.get()); assertEquals(1, secondCalls.get())
        assertEquals(1, server.requests.get())
        assertEquals(1, completed.currentMessages.count { it.role == MessageRole.USER })
    }

    @Test fun silentEofPreservesPartialAndDoesNotAutomaticallyReplay() = runBlocking {
        server.mode = "eof"
        val id = send()
        val saved = terminal(id, "INTERRUPTED")
        assertEquals("已收到的讲解", saved.currentMessages.last().toText())
        assertEquals(1, saved.currentMessages.count { it.role == MessageRole.USER })
        assertEquals(1, server.requests.get())
        assertEquals("INTERRUPTED", saved.currentMessages.last().generationState)
    }

    @Test fun resetConnectionPreservesPartialAndDoesNotAutomaticallyReplay() = runBlocking {
        server.mode = "reset"
        val saved = terminal(send(), "INTERRUPTED")
        assertEquals("已收到的讲解", saved.currentMessages.last().toText())
        assertEquals(1, server.requests.get())
    }

    @Test fun badToolSchemaIsClassifiedWithoutRetryOrLeakingResponse() = runBlocking {
        server.mode = "400-schema"
        val saved = terminal(send(), "FAILED")
        assertEquals(400, saved.currentMessages.last().generationErrorCode)
        val serviceError = service.errors.value.last().error as me.rerere.ai.util.HttpException
        assertEquals(me.rerere.ai.util.ModelRejectionReason.TOOL_SCHEMA, serviceError.rejectionReason)
        assertFalse(serviceError.stackTraceToString().contains("SECRET_CANARY"))
        assertEquals(1, server.requests.get())
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        val params = TextGenerationParams(model = provider.models.single())
        for (api in listOf<me.rerere.ai.provider.providers.openai.OpenAIImpl>(ResponseAPI(client), ChatCompletionsAPI(client, me.rerere.ai.util.KeyRoulette.default()))) {
            val failure = runCatching { api.streamText(provider, listOf(UIMessage.user("synthetic schema failure")), params).collect { } }.exceptionOrNull()
            assertTrue(failure is me.rerere.ai.util.HttpException)
            failure as me.rerere.ai.util.HttpException
            assertEquals(400, failure.statusCode)
            assertEquals(me.rerere.ai.util.ModelRejectionReason.TOOL_SCHEMA, failure.rejectionReason)
            assertFalse(failure.stackTraceToString().contains("SECRET_CANARY"))
        }
        assertEquals(3, server.requests.get())
    }

    @Test fun promptFilterRejectionIsReportedWithoutAutomaticRetry() = runBlocking {
        server.mode = "400-filter"
        val saved = terminal(send(), "FAILED")
        assertEquals(400, saved.currentMessages.last().generationErrorCode)
        val failure = service.errors.value.last().error as me.rerere.ai.util.HttpException
        assertEquals(me.rerere.ai.util.ModelRejectionReason.CONTENT_POLICY, failure.rejectionReason)
        assertTrue(failure.message.orEmpty().contains("content policy rejection"))
        assertTrue(saved.currentMessages.flatMap { it.getTools() }.isEmpty())
        assertEquals(1, server.requests.get())
    }

    @Test fun unauthorizedAndRateLimitAreDurableFailuresWithoutAutomaticRetry() = runBlocking {
        for (status in listOf("401", "429", "401-empty", "429-empty")) {
            server.mode = status
            val saved = terminal(send(), "FAILED")
            assertEquals(1, saved.currentMessages.count { it.role == MessageRole.USER })
            assertEquals("FAILED", saved.currentMessages.last().generationState)
            assertEquals(status.substringBefore('-').toInt(), saved.currentMessages.last().generationErrorCode)
        }
        assertEquals(4, server.requests.get())
    }

    @Test fun manualRegenerationCompletesWithoutDuplicatingUserMessage() = runBlocking {
        server.mode = "eof"
        val id = send()
        val partial = terminal(id, "INTERRUPTED").currentMessages.last()
        server.mode = "complete"
        service.regenerateAtMessage(id, partial)
        val saved = terminal(id, "COMPLETED")
        assertEquals(1, saved.currentMessages.count { it.role == MessageRole.USER })
        assertEquals("已收到的讲解", saved.currentMessages.last().toText())
        assertEquals(2, server.requests.get())
        assertTrue(saved.messageNodes.flatMap { it.messages }.any { it.id == partial.id && it.generationState == "INTERRUPTED" })
    }

    @Test fun regenerationFailureBeforeFirstChunkKeepsPreviousInterruptedBranch() = runBlocking {
        server.mode = "eof"
        val id = send()
        val partial = terminal(id, "INTERRUPTED").currentMessages.last()
        server.mode = "429-empty"
        service.regenerateAtMessage(id, partial)
        val failed = terminal(id, "FAILED")
        assertEquals(429, failed.currentMessages.last().generationErrorCode)
        assertEquals(1, failed.currentMessages.count { it.role == MessageRole.USER })
        assertTrue(failed.messageNodes.flatMap { it.messages }.any { it.id == partial.id && it.toText() == partial.toText() && it.generationState == "INTERRUPTED" })
        server.mode = "complete"
        service.regenerateAtMessage(id, failed.currentMessages.last())
        val completed = terminal(id, "COMPLETED")
        assertEquals(1, completed.currentMessages.count { it.role == MessageRole.USER })
        assertEquals(3, server.requests.get())
    }

    @Test fun staleApprovalCannotReplaceAnActiveGenerationJob() = runBlocking<Unit> {
        server.mode = "stall"
        val id = send()
        withTimeout(20_000) { service.getConversationFlow(id).first { it.currentMessages.lastOrNull()?.toText() == "已收到的讲解" } }
        val activeJob = service.getGenerationJobStateFlow(id).first()
        assertNotNull(activeJob)
        val before = repository.getConversationById(id)!!
        service.handleToolApproval(id, "nonexistent-completed-call", true)
        assertEquals(activeJob, service.getGenerationJobStateFlow(id).first())
        assertEquals(before.currentMessages, repository.getConversationById(id)!!.currentMessages)
        assertEquals(1, server.requests.get())
        service.stopGeneration(id)
        terminal(id, "CANCELLED")
    }

    @Test fun stoppingStalledStreamPersistsCancelledAndRetainsText() = runBlocking {
        server.mode = "stall"
        val id = send()
        withTimeout(20_000) { service.getConversationFlow(id).first { it.currentMessages.lastOrNull()?.toText() == "已收到的讲解" } }
        service.stopGeneration(id)
        val saved = terminal(id, "CANCELLED")
        assertEquals("已收到的讲解", saved.currentMessages.last().toText())
        assertEquals(1, server.requests.get())
    }

    @Test fun actualSocketReadTimeoutIsClassifiedAsInterruption() = runBlocking {
        server.mode = "stall"
        val client = OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).retryOnConnectionFailure(false).build()
        var chunks = 0
        val failure = runCatching {
            ResponseAPI(client).streamText(provider, listOf(UIMessage.user("QA_TIMEOUT")), TextGenerationParams(model = provider.models.single()))
                .collect { chunks++ }
        }.exceptionOrNull()
        assertTrue("Expected real socket timeout, got $failure", failure is SocketTimeoutException)
        assertTrue(chunks > 0)
        assertEquals("INTERRUPTED", generationFailureState(failure))
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    @Test fun chatCompletionsDistinguishesSilentEofCompletionAndEmptyHttpError() = runBlocking {
        val client = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
        try {
            for (mode in listOf("chat-eof", "chat-complete", "429-empty")) {
                server.mode = mode
                var messages = listOf(UIMessage.user("QA_CHAT_FAULT"))
                val handler = StreamChunkHandler(provider.models.single())
                val failure = runCatching {
                    ChatCompletionsAPI(client, me.rerere.ai.util.KeyRoulette.default()).streamText(provider.copy(useResponseApi = false), messages, TextGenerationParams(model = provider.models.single()))
                        .collect { messages = handler.handle(messages, it) }
                }.exceptionOrNull()
                when (mode) {
                    "chat-eof" -> { assertTrue(failure is java.io.EOFException); assertEquals("已收到的讲解", messages.last().toText()) }
                    "chat-complete" -> { assertNull(failure); assertEquals("已收到的讲解", messages.last().toText()) }
                    else -> assertEquals(429, (failure as me.rerere.ai.util.HttpException).statusCode)
                }
            }
            assertEquals(3, server.requests.get())
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }

    @Test fun roomRestartRecoveryCommitsInterruptedWithSameMessageIds() = runBlocking {
        val id = Uuid.random()
        conversations += id
        service.addConversationReference(id)
        val question = UIMessage.user("[QA 合成测试] 进程终止后的回复保留")
        val reply = UIMessage.assistant("[QA 合成测试] 已收到的讲解：导数表示函数的瞬时变化率。").copy(generationState = "RUNNING")
        val stored = Conversation.ofId(id, original.assistantId, listOf(question.toMessageNode(), reply.toMessageNode()))
            .copy(profileId = original.activeProfileId, title = "[QA] 中断恢复界面验证", newConversation = true)
        service.saveConversation(id, stored)
        val reopened = ConversationSession(id, Conversation.ofId(id), this, {})
        reopened.initialize(persistRecovery = repository::updateConversation) { repository.getConversationById(id)!! }
        val durable = repository.getConversationById(id)!!
        assertEquals("INTERRUPTED", durable.currentMessages.last().generationState)
        assertEquals(listOf(question.id, reply.id), durable.currentMessages.map { it.id })
        assertEquals(reply.toText(), durable.currentMessages.last().toText())
        if (InstrumentationRegistry.getArguments().getString("keepEvidence") == "true") {
            evidenceId = id
            android.util.Log.i("QaNetworkFault", "RECOVERY_CONVERSATION_ID=$id")
        }
    }
}

internal class FaultServer : AutoCloseable {
    private val listener = ServerSocket(0, 20, java.net.InetAddress.getByName("127.0.0.1"))
    val baseUrl = "http://127.0.0.1:${listener.localPort}/v1"
    val requests = AtomicInteger()
    val contextBodies = CopyOnWriteArrayList<String>()
    @Volatile var mode = "eof"
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val acceptor = thread(isDaemon = true, name = "qa-fault-server") {
        while (!listener.isClosed) {
            val socket = try { listener.accept() } catch (_: IOException) { break }
            sockets += socket
            thread(isDaemon = true) { serve(socket) }
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = 5_000
            val input = socket.getInputStream()
            fun line(): String = buildString {
                while (true) { val b = input.read(); if (b == -1 || b == 10) break; if (b != 13) append(b.toChar()) }
            }
            line()
            var length = 0
            while (true) {
                val header = line(); if (header.isEmpty()) break
                if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
            }
            val requestBytes = ByteArray(length)
            var offset = 0
            while (offset < length) { val count = input.read(requestBytes, offset, length - offset); check(count > 0); offset += count }
            requests.incrementAndGet()
            val currentMode = mode
            val out = socket.getOutputStream()
            fun write(value: String) { out.write(value.toByteArray(Charsets.UTF_8)); out.flush() }
            if (currentMode == "context") {
                val body = requestBytes.toString(Charsets.UTF_8)
                contextBodies += body
                if (kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["stream"]?.jsonPrimitive?.content == "false") {
                    val response = """{"id":"qa-summary","model":"qa-local","status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"任务摘要：禁止覆盖原件；预算500元；继续核对文件id=file-123；已完成查证，待交付。"}]}]}"""
                    write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.toByteArray().size}\r\nConnection: close\r\n\r\n$response")
                    return
                }
            }
            if (currentMode.startsWith("400") || currentMode.startsWith("401") || currentMode.startsWith("429")) {
                val body = if (currentMode == "400-schema") """{"error":{"code":"invalid_function_parameters","message":"Invalid schema: SECRET_CANARY","param":"SECRET_CANARY"}}"""
                    else if (currentMode == "400-filter") """{"error":{"code":"prompt_blocked","message":"Request contains content blocked by prompt filter","type":"invalid_request_error"}}"""
                    else if (currentMode.endsWith("empty")) "" else """{"error":{"message":"QA synthetic HTTP $currentMode","type":"qa_fault"}}"""
                write("HTTP/1.1 ${currentMode.substringBefore('-')} QA\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body")
                return
            }
            write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n")
            if (currentMode.startsWith("chat-")) {
                write("data: {\"id\":\"qa-message\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"已收到的讲解\"},\"finish_reason\":null}]}\n\n")
            } else {
                write("event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"item_id\":\"qa-message\",\"output_index\":0,\"content_index\":0,\"delta\":\"已收到的讲解\"}\n\n")
            }
            Thread.sleep(250)
            when (currentMode) {
                "complete", "chat-complete", "context" -> write("data: [DONE]\n\n")
                "reset" -> socket.setSoLinger(true, 0)
                "stall" -> while (!socket.isClosed && !listener.isClosed) Thread.sleep(50)
            }
        } catch (_: Exception) {
            // Client cancellation and timeout deliberately close fixture sockets.
        } finally { sockets.remove(socket); socket.close() }
    }

    override fun close() { listener.close(); sockets.forEach { it.close() }; acceptor.join(1_000) }
}
