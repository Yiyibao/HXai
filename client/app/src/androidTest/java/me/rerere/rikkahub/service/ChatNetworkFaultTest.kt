package me.rerere.rikkahub.service
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray

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

    private fun airplane(enabled: Boolean) {
        if(android.os.Build.VERSION.SDK_INT>=30) {
            shell("cmd connectivity airplane-mode ${if(enabled) "enable" else "disable"}")
        } else {
            shell("su 0 settings put global airplane_mode_on ${if(enabled) 1 else 0}")
            check(shell("su 0 am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $enabled").contains("Broadcast completed"))
        }
    }

    @Test fun deviceAirplaneModeInterruptsStreamAndQueueRequiresManualResume() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val wasAirplane = shell("settings get global airplane_mode_on") == "1"
        assertNotNull("Fixture needs an initial device default network", manager.activeNetwork)
        assertFalse("Fixture starts with airplane mode disabled", wasAirplane)
        server.mode = "stall"
        val id = send()
        withTimeout(20_000) { service.getConversationFlow(id).first { it.currentMessages.lastOrNull()?.toText() == "已收到的讲解" } }
        service.sendMessage(id, listOf(UIMessagePart.Text("queued after interruption")))
        withTimeout(10_000) { service.getConversationFlow(id).first { it.queue.messages.size == 1 } }
        val queuedId = service.getConversationFlow(id).value.queue.messages.single().id
        try {
            airplane(true)
            withTimeout(20_000) { while (manager.activeNetwork != null) delay(100) }
            val saved = terminal(id, "INTERRUPTED")
            assertEquals("已收到的讲解", saved.currentMessages.last().toText())
            withTimeout(10_000) { service.getConversationFlow(id).first { it.queue.paused } }
            assertEquals(queuedId, repository.getConversationById(id)!!.queue.messages.single().id)
            assertEquals(1, server.requests.get())
        } finally {
            airplane(false)
            withTimeout(30_000) { while (manager.activeNetwork == null) delay(100) }
        }
        delay(500)
        assertEquals("Restoring the device network must not replay a request", 1, server.requests.get())
        // Even an explicit queue resume cannot release the earlier unknown request.
        server.mode = "complete"
        service.resumeMessageQueue(id)
        val saved = terminal(id, "INTERRUPTED")
        assertEquals(listOf(queuedId), saved.queue.messages.map { it.id })
        assertEquals(1, saved.currentMessages.count { it.role == MessageRole.USER })
        assertEquals(1, server.requests.get())
    }

    @Test fun switchingWifiToMobileInterruptsWithoutReplay() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val wifiEnabled = shell("settings get global wifi_on") != "0"
        val mobileEnabled=shell("settings get global mobile_data")!="0"
        try {
            shell("svc wifi enable")
            if(android.os.Build.VERSION.SDK_INT>=30) shell("su 0 cmd wifi connect-network AndroidWifi open")
            shell("svc data enable")
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
        } finally {
            try {
                shell(if (wifiEnabled) "svc wifi enable" else "svc wifi disable")
                // Wait for the original route before the next HTTP-only fixture.
                val restoredTransport = if (wifiEnabled) android.net.NetworkCapabilities.TRANSPORT_WIFI
                    else android.net.NetworkCapabilities.TRANSPORT_CELLULAR
                withTimeout(30_000) {
                    while (manager.activeNetwork?.let(manager::getNetworkCapabilities)?.hasTransport(restoredTransport) != true) delay(100)
                }
            } finally {
                if(!mobileEnabled) shell("svc data disable")
            }
        }
    }

    @Test fun genuineRoom27MigrationPreservesConversationAndMessages() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "qa-runtime-migration-${Uuid.random()}.db"
        val id = Uuid.random().toString()
        val message = me.rerere.rikkahub.data.db.entity.MessageNodeEntity(
            id = Uuid.random().toString(), conversationId = id, nodeIndex = 0,
            messages = Json.encodeToString(listOf(UIMessage.user("QA historical message"))), selectIndex = 0,
        )
        // Use the exported old schema, not a current database relabeled as v27.
        val schema = instrumentation.context.assets.open("me.rerere.rikkahub.data.db.AppDatabase/27.json")
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject.getValue("database").jsonObject }
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { old ->
            for (entity in schema.getValue("entities").jsonArray) {
                val value = entity.jsonObject
                val table = value.getValue("tableName").jsonPrimitive.content
                old.execSQL(value.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                value["indices"]?.jsonArray?.forEach { index ->
                    old.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach { old.execSQL(it.jsonPrimitive.content) }
            old.execSQL("INSERT INTO ConversationEntity(id,assistant_id,title,nodes,create_at,update_at,is_pinned,profile_id) VALUES(?,?,?,'[]',1,2,1,?)",
                arrayOf(id, assistant.id.toString(), "QA migration", "qa-owner"))
            old.execSQL("INSERT INTO message_node(id,conversation_id,node_index,messages,select_index) VALUES(?,?,0,?,0)",
                arrayOf(message.id, id, message.messages))
            old.version = 27
        }
        val db = me.rerere.rikkahub.data.db.AppDatabaseFactory.create(context, name)
        try {
            val conversation = db.conversationDao().getConversationById(id)!!
            assertEquals("qa-owner", conversation.profileId)
            assertEquals("QA migration", conversation.title)
            assertTrue(conversation.isPinned)
            assertEquals(listOf(message), db.messageNodeDao().getNodesOfConversation(id))
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
            UIMessagePart.Tool("network", "get_time_info", "{}"), UIMessagePart.Tool("next", "read_material_file", "{}"),
        ))
        session.checkpointGeneration(listOf(UIMessage.user("QA_TOOL_NETWORK"), reply), repository::checkpointMessages)
        val calls = AtomicInteger()
        val nextCalls = AtomicInteger()
        val tools = listOf(
            Tool("get_time_info", "synthetic", execute = { calls.incrementAndGet(); throw IOException("response lost after simulated effect") }),
            Tool("read_material_file", "synthetic", execute = { nextCalls.incrementAndGet(); emptyList() }),
        )
        val loop = GlobalContext.get().get<GenerationLoop>()
        LeasedGenerationFixture(settings.settingsFlow.value, assistant, id).use { fixture ->
            val failure = runCatching {
                fixture.run(tools) {
                    loop.generateText(settings.settingsFlow.value, provider.models.single(), session.state.value.currentMessages,
                        assistant = assistant, maxSteps = 1, tools = tools)
                        .onCompletion { session.recordGenerationResult(it, repository::checkpointMessages) }.collect { chunk ->
                            when (chunk) {
                                is GenerationChunk.Messages -> session.checkpointGeneration(chunk.messages, repository::checkpointMessages)
                                is GenerationChunk.DurableCheckpoint -> {
                                    session.checkpointGeneration(chunk.messages, repository::checkpointMessages)
                                    chunk.committed.complete(Unit)
                                }
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
            assertEquals("UNCERTAIN_SIDE_EFFECT", fixture.snapshot().task.state)
            assertTrue(runCatching { fixture.resume { error("Unknown tool must never resume") } }.isFailure)
            assertEquals(1, calls.get()); assertEquals(0, nextCalls.get()); assertEquals(0, server.requests.get())
        }
    }

    @Test fun resumedAutoToolStillRequiresApprovalBeforeAnyExecution() = runBlocking {
        val id = Uuid.random()
        conversations += id
        val initial = Conversation.ofId(id, assistant.id).copy(profileId = original.activeProfileId, title = "QA tool approval")
        repository.insertConversation(initial)
        val session = ConversationSession(id, initial, this, {})
        session.initialize { initial }
        session.checkpointGeneration(listOf(UIMessage.user("QA_APPROVAL"), UIMessage(role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Tool("approve", "convert_material_to_pdf", "{}")))), repository::checkpointMessages)
        val calls = AtomicInteger()
        val tool = Tool("convert_material_to_pdf", "synthetic", needsApproval = { true }, execute = { calls.incrementAndGet(); emptyList() })
        val loop = GlobalContext.get().get<GenerationLoop>()
        LeasedGenerationFixture(settings.settingsFlow.value, assistant, id).use { fixture ->
            fixture.run {
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
            }
            val saved = repository.getConversationById(id)!!
            assertEquals("WAITING_TOOL", saved.currentMessages.last().generationState)
            assertTrue(saved.currentMessages.last().getTools().single().isPending)
            assertTrue(fixture.snapshot().control.approvalBlocked)
            assertEquals(0, calls.get()); assertEquals(0, server.requests.get())
        }
    }

    @Test fun toolCheckpointsPreventReplayAfterExecutionInterruption() = runBlocking {
        val id = Uuid.random()
        conversations += id
        val initial = Conversation.ofId(id, assistant.id).copy(profileId = original.activeProfileId, title = "QA tool continuation")
        repository.insertConversation(initial)
        val session = ConversationSession(id, initial, this, {})
        session.initialize { initial }
        val reply = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Tool("first", "get_time_info", "{}"), UIMessagePart.Tool("second", "read_material_file", "{}"),
        ))
        session.checkpointGeneration(listOf(UIMessage.user("QA_TOOLS"), reply), repository::checkpointMessages)
        val firstCalls = AtomicInteger()
        val secondCalls = AtomicInteger()
        val secondStarted = CompletableDeferred<Unit>()
        val loop = GlobalContext.get().get<GenerationLoop>()
        val tools = listOf(
            Tool("get_time_info", "synthetic no effect", execute = {
                assertEquals(ToolExecutionState.RUNNING, repository.getConversationById(id)!!.currentMessages.last().getTools().first().executionState)
                firstCalls.incrementAndGet(); emptyList()
            }),
            Tool("read_material_file", "synthetic no effect", execute = {
                assertTrue(repository.getConversationById(id)!!.currentMessages.last().getTools().first().isExecuted)
                secondCalls.incrementAndGet(); secondStarted.complete(Unit); awaitCancellation()
            }),
        )
        LeasedGenerationFixture(settings.settingsFlow.value, assistant, id).use { fixture ->
            val job = launch {
                fixture.run(tools) {
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
            }
            withTimeout(10_000) { secondStarted.await() }
            job.cancel(); job.join()
            val reopened = ConversationSession(id, initial, this, {})
            reopened.initialize(persistRecovery = repository::recoverConversation) { repository.getConversationById(id)!! }
            val saved = repository.getConversationById(id)!!
            val states = saved.currentMessages.last().getTools()
            assertTrue("Empty successful tool results still count as completed", states.first().isExecuted)
            assertEquals(ToolExecutionState.INTERRUPTED, states.last().executionState)
            assertEquals("UNCERTAIN_SIDE_EFFECT", fixture.snapshot().task.state)
            runCatching {
                fixture.resume {
                    loop.generateText(settings.settingsFlow.value, provider.models.single(), saved.currentMessages,
                        assistant = assistant, tools = tools, maxSteps = 1).collect {}
                }
            }
            assertEquals("UNCERTAIN_SIDE_EFFECT", fixture.snapshot().task.state)
            assertEquals(states, repository.getConversationById(id)!!.currentMessages.last().getTools())
            assertEquals(1, firstCalls.get()); assertEquals(1, secondCalls.get())
            assertEquals(0, server.requests.get())
        }
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

    @Test fun unauthorizedIsDurableWithoutAutomaticRetry() = assertHttpFailure("401")
    @Test fun emptyUnauthorizedIsDurableWithoutAutomaticRetry() = assertHttpFailure("401-empty")
    @Test fun rateLimitIsDurableWithoutAutomaticRetry() = assertHttpFailure("429")
    @Test fun emptyRateLimitIsDurableWithoutAutomaticRetry() = assertHttpFailure("429-empty")

    private fun assertHttpFailure(status: String) = runBlocking {
        server.mode = status
        val saved = terminal(send(), "FAILED")
        assertEquals(1, saved.currentMessages.count { it.role == MessageRole.USER })
        assertEquals(status.substringBefore('-').toInt(), saved.currentMessages.last().generationErrorCode)
        assertEquals(1, server.requests.get())
    }

    @Test fun manualRegenerationCompletesWithoutDuplicatingUserMessage() = runBlocking {
        server.mode = "complete"
        val id = send()
        val previous = terminal(id, "COMPLETED").currentMessages.last()
        service.regenerateAtMessage(id, previous)
        val saved = terminal(id, "COMPLETED")
        assertEquals(1, saved.currentMessages.count { it.role == MessageRole.USER })
        assertEquals("已收到的讲解", saved.currentMessages.last().toText())
        assertEquals(2, server.requests.get())
        assertTrue(saved.messageNodes.flatMap { it.messages }.any { it.id == previous.id && it.toText() == previous.toText() })
    }

    @Test fun regenerationFailureBeforeFirstChunkKeepsPreviousCompletedBranch() = runBlocking {
        server.mode = "complete"
        val id = send()
        val previous = terminal(id, "COMPLETED").currentMessages.last()
        server.mode = "429-empty"
        service.regenerateAtMessage(id, previous)
        val failed = terminal(id, "FAILED")
        assertEquals(429, failed.currentMessages.last().generationErrorCode)
        assertEquals(1, failed.currentMessages.count { it.role == MessageRole.USER })
        assertTrue(failed.messageNodes.flatMap { it.messages }.any { it.id == previous.id && it.toText() == previous.toText() })
        server.mode = "complete"
        service.regenerateAtMessage(id, failed.currentMessages.last())
        val blocked = terminal(id, "FAILED")
        assertEquals(failed.messageNodes, blocked.messageNodes)
        assertEquals(2, server.requests.get())
    }

    @Test fun manualRegenerationCannotBypassUnknownPartialRequest() = runBlocking {
        server.mode = "eof"
        val id = send()
        val partial = terminal(id, "INTERRUPTED")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val task = me.rerere.rikkahub.feature.agent.AgentTaskStore(context).use { store ->
            requireNotNull(store.current(original.activeProfileId, id.toString()))
        }
        server.mode = "complete"
        service.regenerateAtMessage(id, partial.currentMessages.last())
        val blocked = terminal(id, "INTERRUPTED")
        assertEquals(partial.messageNodes, blocked.messageNodes)
        assertEquals(1, server.requests.get())
        me.rerere.rikkahub.feature.agent.AgentTaskStore(context).use { store ->
            assertEquals("UNCERTAIN_SIDE_EFFECT", store.get(task.owner, task.id).state)
            store.readableDatabase.rawQuery("SELECT task_id FROM slot", null).use {
                assertTrue(it.moveToFirst()); assertEquals(task.id, it.getString(0))
            }
        }
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
