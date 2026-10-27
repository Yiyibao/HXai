package me.rerere.rikkahub.service

import android.graphics.Bitmap
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.agent.AgentTaskStore
import me.rerere.rikkahub.feature.material.MaterialFile
import me.rerere.rikkahub.feature.material.MaterialFiles
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.uuid.Uuid

/** Local synthetic HTTP only. Exercises the installed ChatService, providers, vault and Room. */
@RunWith(AndroidJUnit4::class)
class AgentChatAcceptanceTest {
    @Test fun queuedConversationUsesAcceptedModelAfterSettingsChange(): Unit = runBlocking {
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsStore>()
        val original = withTimeout(20_000) { settings.settingsFlow.first { !it.init } }
        val server = ServerSocket(0)
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val bodies = CopyOnWriteArrayList<String>()
        val failure = CompletableDeferred<Throwable>()
        val worker = thread(name="qa-agent-local-http") {
            try {
                repeat(5) { index -> server.accept().use { socket ->
                    socket.soTimeout = 20_000
                    val input = socket.getInputStream().buffered()
                    fun header(): String {
                        val line = StringBuilder()
                        while(true) {
                            val byte = input.read(); check(byte >= 0) { "Missing request" }
                            if(byte == 10) return line.toString().trimEnd('\r')
                            require(line.length < 8192); line.append(byte.toChar())
                        }
                    }
                    var length = 0
                    while(true) {
                        val line = header()
                        if(line.isEmpty()) break
                        if(line.startsWith("Content-Length:",ignoreCase=true)) length = line.substringAfter(':').trim().toInt()
                    }
                    require(length in 1..2_000_000)
                    val body = ByteArray(length)
                    var offset = 0
                    while(offset < length) { val count = input.read(body,offset,length-offset); check(count>0); offset+=count }
                    bodies += body.toString(Charsets.UTF_8)
                    if(index==0) { arrived.complete(Unit); runBlocking { release.await() } }
                    val requestedModel = Json.parseToJsonElement(bodies.last()).jsonObject.getValue("model").jsonPrimitive.content
                    val response = """{"id":"local-$index","object":"chat.completion","model":"$requestedModel","choices":[{"index":0,"message":{"role":"assistant","content":"local-result-$index"},"finish_reason":"stop"}],"usage":{"prompt_tokens":17,"completion_tokens":8,"total_tokens":25}}""".toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(response); flush()
                    }
                } }
            } catch(error: Throwable) { failure.complete(error); arrived.completeExceptionally(error) }
        }
        val model = Model(modelId="qa-accepted",displayName="Synthetic accepted model",
            abilities=listOf(me.rerere.ai.provider.ModelAbility.TOOL,me.rerere.ai.provider.ModelAbility.REASONING))
        val changed = Model(modelId="qa-new-settings",displayName="Synthetic changed selection")
        val ocr = Model(modelId="qa-ocr-accepted",inputModalities=listOf(me.rerere.ai.provider.Modality.TEXT,me.rerere.ai.provider.Modality.IMAGE))
        val changedOcr = Model(modelId="qa-ocr-new-settings",inputModalities=ocr.inputModalities)
        val compression = Model(modelId="qa-compression-selected")
        val provider = ProviderSetting.OpenAI(name="QA agent localhost",models=listOf(model,changed,ocr,changedOcr,compression),apiKey="synthetic-only",baseUrl="http://127.0.0.1:${server.localPort}/v1")
        val assistant = Assistant(name="QA acceptance",chatModelId=model.id,reasoningLevel=ReasoningLevel.HIGH,streamOutput=false,localTools=emptyList())
        val ids = listOf(Uuid.random(),Uuid.random())
        val inputIds = listOf(Uuid.random(),Uuid.random())
        val repo = koin.get<ConversationRepository>()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val materials = MaterialFiles(context)
        val image = MaterialFile(java.util.UUID.randomUUID().toString(),original.activeProfileId,"QA synthetic image","png")
        materials.runTask(image) { file ->
            val bitmap = Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888)
            try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) } }
            finally { bitmap.recycle() }
            "Synthetic pixels"
        }
        var service: ChatService? = null
        try {
            settings.update(original.copy(providers=listOf(provider),assistants=original.assistants+assistant,assistantId=assistant.id,chatModelId=model.id,fastModelId=model.id,ocrModelId=ocr.id,compressModelId=Uuid.random(),enableSuggestion=false,
                learnerProfiles=original.learnerProfiles.map { it.copy(memoryEnabled=false) },networkSetting=original.networkSetting.copy(proxyUrl="",proxyUsername="",proxyPassword="",enableAutoRetry=false)))
            settings.settingsFlow.first { it.assistantId==assistant.id }
            service = koin.get()
            ids.forEach { id ->
                service.addConversationReference(id)
                service.saveConversation(id,Conversation.ofId(id,assistant.id).copy(profileId=original.activeProfileId,title="QA task acceptance",newConversation=true))
            }
            service.sendMessageCommitted(ids[0],listOf(UIMessagePart.Text("synthetic first task"),UIMessagePart.Image(materials.file(image).toUri().toString())),inputIds[0])
            withTimeout(20_000) {
                kotlinx.coroutines.coroutineScope {
                    val errorWaiter = async {
                        service.errors.first { errors -> errors.any { it.conversationId == ids[0] } }
                            .first { it.conversationId == ids[0] }.error
                    }
                    try {
                        kotlinx.coroutines.selects.select<Unit> {
                            arrived.onAwait { }
                            failure.onAwait { throw it }
                            errorWaiter.onAwait { throw it }
                        }
                    } finally { errorWaiter.cancel() }
                }
            }
            service.sendMessageCommitted(ids[1],listOf(UIMessagePart.Text("synthetic queued task")),inputIds[1])
            val accepted = settings.settingsFlow.value
            settings.update(accepted.copy(chatModelId=changed.id,ocrModelId=changedOcr.id,assistants=accepted.assistants.map { if(it.id==assistant.id) it.copy(chatModelId=changed.id,reasoningLevel=ReasoningLevel.LOW) else it }))
            settings.settingsFlow.first { it.chatModelId==changed.id }
            service.sendMessageCommitted(ids[1],listOf(UIMessagePart.Text("synthetic queued task")),inputIds[1])
            release.complete(Unit)
            ids.forEachIndexed { index,id ->
                withTimeout(30_000) {
                    service.getConversationFlow(id).first { it.currentMessages.lastOrNull()?.generationState=="COMPLETED" }
                    while(service.getGenerationJobStateFlow(id).first()!=null) delay(10)
                }
                val saved = requireNotNull(repo.getConversationById(id))
                assertEquals(inputIds[index],saved.currentMessages.first().id)
                assertEquals("local-result-${index+1}",saved.currentMessages.last().toText())
                assertEquals(model.id,saved.currentMessages.last().modelId)
                assertTrue(saved.queue.messages.isEmpty())
            }
            if(failure.isCompleted) throw failure.await()
            service.generateTitle(ids[0],requireNotNull(repo.getConversationById(ids[0])),force=true)
            assertEquals("local-result-3",requireNotNull(repo.getConversationById(ids[0])).title)
            val beforeCompression = requireNotNull(repo.getConversationById(ids[0]))
            assertTrue(service.compressConversation(ids[0],beforeCompression,"",1000,keepRecentMessages=0).isFailure)
            assertEquals(beforeCompression.messageNodes,requireNotNull(repo.getConversationById(ids[0])).messageNodes)
            assertEquals(4,bodies.size)
            settings.update(settings.settingsFlow.value.copy(compressModelId=compression.id))
            settings.settingsFlow.first { it.compressModelId==compression.id }
            withTimeout(20_000) {
                kotlinx.coroutines.coroutineScope {
                    val compressionCall = async {
                        service.compressConversation(ids[0],beforeCompression,"",1000,keepRecentMessages=0)
                    }
                    kotlinx.coroutines.selects.select<Unit> {
                        compressionCall.onAwait { it.getOrThrow() }
                        failure.onAwait { throw it }
                    }
                }
            }
            assertEquals(me.rerere.ai.core.MessageRole.USER,
                requireNotNull(repo.getConversationById(ids[0])).currentMessages.single().role)
            val compressedConversation=requireNotNull(repo.getConversationById(ids[0]))
            assertTrue(compressedConversation.currentMessages.single().isHistorySummary)
            val memories=koin.get<me.rerere.rikkahub.feature.memory.LearningMemoryStore>()
            memories.reconcile(compressedConversation)
            assertFalse(memories.read(original.activeProfileId).sources.any { it.valid && it.conversationId==ids[0].toString() })
            assertEquals(listOf("qa-ocr-accepted","qa-accepted","qa-accepted","qa-accepted","qa-compression-selected"),
                bodies.map { Json.parseToJsonElement(it).jsonObject.getValue("model").jsonPrimitive.content })
            listOf(1,2).forEach { index ->
                assertEquals("high",Json.parseToJsonElement(bodies[index]).jsonObject.getValue("reasoning_effort").jsonPrimitive.content)
            }
            AgentTaskStore(context).use { taskStore ->
                assertEquals(inputIds[0].toString(),taskStore.current(original.activeProfileId,ids[0].toString())!!.requestId)
                taskStore.readableDatabase.rawQuery("SELECT u.role,u.provenance,u.prompt_tokens,u.completion_tokens,u.created_at,u.cached_tokens,u.total_tokens FROM usage u JOIN tasks t ON t.id=u.task_id WHERE t.owner=? AND t.conversation_id IN (?,?) ORDER BY u.role",
                    arrayOf(original.activeProfileId,ids[0].toString(),ids[1].toString())).use { rows ->
                    val roles = mutableListOf<String>()
                    while(rows.moveToNext()) {
                        roles += rows.getString(0)
                        assertEquals("reported",rows.getString(1)); assertEquals(17,rows.getInt(2)); assertEquals(8,rows.getInt(3)); assertFalse(rows.isNull(4))
                        assertTrue(rows.isNull(5)); assertEquals(25,rows.getInt(6))
                    }
                    assertEquals(listOf("compression","main","main","ocr","title"),roles)
                }
            }
            assertTrue(runCatching {
                service.sendMessageCommitted(ids[1],listOf(UIMessagePart.Text("different intent under the same request")),inputIds[1])
            }.exceptionOrNull() is IllegalArgumentException)
        } finally {
            release.complete(Unit)
            println("QA_REQUESTS models=${bodies.map { Json.parseToJsonElement(it).jsonObject["model"] }}")
            println("QA_ERRORS ${service?.errors?.value?.filter { it.conversationId in ids }?.map { it.error }}")
            try {
                ids.forEach { id ->
                    repo.getConversationById(id)?.let { conversation ->
                        service?.stopGeneration(id)
                        requireNotNull(service).deleteConversation(conversation)
                    }
                    service?.removeConversationReference(id)
                }
            } finally {
                settings.update(original)
                server.close()
                worker.join(5000)
                // Only these random synthetic conversations and the closed localhost server.
                // Never use this fixture cleanup to reconcile a real provider's unknown outcome.
                AgentTaskStore(context).use { taskStore ->
                    val db = taskStore.writableDatabase
                    db.beginTransaction()
                    try {
                        ids.forEach { id ->
                            val ownedTasks = "SELECT id FROM tasks WHERE owner=? AND conversation_id=?"
                            val args = arrayOf(original.activeProfileId,id.toString())
                            db.execSQL("UPDATE slot SET task_id=NULL WHERE task_id IN ($ownedTasks)",args)
                            // Schema 13 receipts remain in production. Only these random QA
                            // conversations, after the synthetic localhost server is closed, are cleaned.
                            val requests = "SELECT request_id FROM usage WHERE task_id IN ($ownedTasks)"
                            for (table in listOf("usage_costs", "agent_hosted_results"))
                                db.execSQL("DELETE FROM $table WHERE request_id IN ($requests)", args)
                            for(table in listOf("agent_provider_commands","agent_sessions","agent_host_credentials",
                                "agent_executor_bindings","executor_handoffs",
                                "credential_overrides","credential_validation_intents","credential_commands",
                                "task_tool_decisions","task_approval_waits","plan_steps","plans","task_commands","grants",
                                "task_control","calls","usage"))
                                db.execSQL("DELETE FROM $table WHERE task_id IN ($ownedTasks)",args)
                            db.execSQL("DELETE FROM tasks WHERE owner=? AND conversation_id=?",args)
                        }
                        db.rawQuery("PRAGMA foreign_key_check", null).use { rows ->
                            assertFalse("Synthetic cleanup left a broken task reference", rows.moveToFirst())
                        }
                        db.setTransactionSuccessful()
                    } finally { db.endTransaction() }
                }
                materials.file(image).delete()
                materials.file(image).parentFile!!.resolve("${image.id}.meta.json").delete()
            }
        }
        Unit
    }

    @Test fun historyOnlyInputDoesNotRequireConfiguredProvider(): Unit = runBlocking {
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsStore>()
        val original = withTimeout(20_000) { settings.settingsFlow.first { !it.init } }
        val repository = koin.get<ConversationRepository>()
        val assistant = Assistant(name="QA history only",chatModelId=Uuid.random())
        val id = Uuid.random()
        var service: ChatService? = null
        try {
            settings.update(original.copy(providers=emptyList(),assistants=original.assistants+assistant,
                assistantId=assistant.id,chatModelId=assistant.chatModelId!!,enableSuggestion=false))
            settings.settingsFlow.first { it.assistantId==assistant.id }
            service = koin.get()
            service.addConversationReference(id)
            service.saveConversation(id,Conversation.ofId(id,assistant.id).copy(profileId=original.activeProfileId,
                title="QA history only",newConversation=true))
            service.sendMessage(id,listOf(UIMessagePart.Text("history-only input")),answer=false)
            withTimeout(20_000) {
                service.getConversationFlow(id).first { it.currentMessages.singleOrNull()?.toText()=="history-only input" }
                while(service.getGenerationJobStateFlow(id).first()!=null) delay(10)
            }
            val stored = requireNotNull(repository.getConversationById(id))
            assertEquals(me.rerere.ai.core.MessageRole.USER,stored.currentMessages.single().role)
            assertEquals("history-only input",stored.currentMessages.single().toText())
            assertTrue(stored.queue.messages.isEmpty())
        } finally {
            try {
                repository.getConversationById(id)?.let { service?.stopGeneration(id); requireNotNull(service).deleteConversation(it) }
                service?.removeConversationReference(id)
            } finally { settings.update(original) }
        }
        Unit
    }
    @Test fun legacyQueueCannotSilentlyAdoptCurrentModelConfiguration(): Unit = runBlocking {
        val koin=GlobalContext.get()
        val settings=koin.get<SettingsStore>()
        val original=withTimeout(20_000) { settings.settingsFlow.first { !it.init } }
        val repo=koin.get<ConversationRepository>()
        val id=Uuid.random()
        val assistant=Assistant(name="QA legacy queue",chatModelId=Uuid.random())
        val input=me.rerere.rikkahub.data.model.PendingInput(Uuid.random(),listOf(UIMessagePart.Text("legacy input without a snapshot")))
        var service: ChatService?=null
        try {
            settings.update(original.copy(providers=emptyList(),assistants=original.assistants+assistant,
                assistantId=assistant.id,chatModelId=assistant.chatModelId!!,enableSuggestion=false))
            settings.settingsFlow.first { it.assistantId==assistant.id }
            repo.insertConversation(Conversation.ofId(id,assistant.id).copy(profileId=original.activeProfileId,title="QA legacy",
                queue=me.rerere.rikkahub.data.model.ConversationQueue(listOf(input),paused=true)))
            service=koin.get()
            service.addConversationReference(id)
            service.initializeConversation(id)
            service.resumeMessageQueue(id)
            withTimeout(20_000) {
                service.errors.first { errors -> errors.any { it.conversationId==id && it.error.message?.contains("历史输入没有持久模型快照")==true } }
                while(service.getGenerationJobStateFlow(id).first()!=null) delay(10)
            }
            val stored=requireNotNull(repo.getConversationById(id))
            assertEquals(listOf(input),stored.queue.messages)
            assertTrue(stored.queue.paused)
            assertEquals(emptyList<me.rerere.ai.ui.UIMessage>(),stored.currentMessages)
            AgentTaskStore(InstrumentationRegistry.getInstrumentation().targetContext).use {
                assertNull(it.find(original.activeProfileId,id.toString(),input.id.toString()))
            }
        } finally {
            try {
                repo.getConversationById(id)?.let { service?.stopGeneration(id); requireNotNull(service).deleteConversation(it) }
                service?.errors?.value?.filter { it.conversationId==id }?.forEach { service.dismissError(it.id) }
                service?.removeConversationReference(id)
            } finally { settings.update(original) }
        }
    }
}
