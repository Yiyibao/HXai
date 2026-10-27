package me.rerere.rikkahub.feature.agent

import android.graphics.Bitmap
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.TranslationHandler
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.imagecreation.ImageCreation
import me.rerere.rikkahub.feature.material.MaterialFiles
import me.rerere.rikkahub.feature.material.materialTools
import me.rerere.rikkahub.feature.memory.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class MemoryImageAdmissionTest {
    @Test fun blockedMemorySerializesImageTranslationChatAndMaterialImageWithAcceptedSnapshots()=scenario(true)
    @Test fun blockedImageSerializesMemoryTranslationChatAndMaterialImageWithAcceptedSnapshots()=scenario(false)
    private fun scenario(memoryFirst:Boolean):Unit=runBlocking {
        val koin=GlobalContext.get();val context=koin.get<android.content.Context>();val settings=koin.get<SettingsStore>()
        val original=withTimeout(20_000) { settings.settingsFlow.first { !it.init } }
        val memoryRunner=koin.get<MemoryExtractionRunner>();memoryRunner.chatStarted()
        val bytes=ByteArrayOutputStream().use { stream ->
            val bitmap=Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888)
            try { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,stream));stream.toByteArray() } finally { bitmap.recycle() }
        }
        val server=LocalModelServer { body ->
            val value=Json.parseToJsonElement(body).jsonObject
            when(value.getValue("model").jsonPrimitive.content) {
                "gpt-image-qa" -> buildJsonObject { put("data",buildJsonArray { add(buildJsonObject { put("b64_json",Base64.encodeToString(bytes,Base64.NO_WRAP)) }) }) }.toString()
                "qa-fast" -> LocalModelServer.completion("{\"schema_version\":1,\"candidates\":[]}")
                "qwen-mt-qa" -> LocalModelServer.completion("translated")
                else -> LocalModelServer.completion("chat-response")
            }
        }
        val barrier=CountDownLatch(1);server.barrier=barrier
        val main=Model(modelId="qa-main");val fast=Model(modelId="qa-fast");val image=Model(modelId="gpt-image-qa");val translate=Model(modelId="qwen-mt-qa")
        val changed=Model(modelId="qa-settings-changed")
        val provider=ProviderSetting.OpenAI(baseUrl=server.baseUrl,apiKey="synthetic",models=listOf(main,fast,image,translate,changed))
        val assistant=Assistant(chatModelId=main.id,reasoningLevel=ReasoningLevel.OFF,streamOutput=false)
        val accepted=original.copy(providers=listOf(provider),assistants=listOf(assistant),assistantId=assistant.id,
            chatModelId=main.id,fastModelId=fast.id,fastModelReasoningLevel=ReasoningLevel.OFF,imageGenerationModelId=image.id,translateModeId=translate.id,
            learnerProfiles=original.learnerProfiles.map { it.copy(memoryEnabled=it.id==original.activeProfileId) },
            networkSetting=original.networkSetting.copy(proxyUrl="",proxyUsername="",proxyPassword="",enableAutoRetry=false))
        val materials=koin.get<MaterialFiles>();val repo=koin.get<ConversationRepository>();val memories=koin.get<LearningMemoryStore>()
        val scheduler=AgentScheduler.shared(context);val creation=ImageCreation.get(context,settings,materials)
        val memoryId=Uuid.random();val chatId=Uuid.random();val materialId=Uuid.random()
        val imageRequest="qa-image-${Uuid.random()}"
        var job:ExtractionJob?=null;var memoryDeferred:Deferred<Unit>?=null;var translationDeferred:Deferred<String>?=null
        var chatDeferred:Deferred<TextGenerationResult>?=null;var materialDeferred:Deferred<List<UIMessagePart>>?=null
        var imageTaskId:String?=null
        try {
            settings.update(accepted);settings.settingsFlow.first { it.fastModelId==fast.id }
            settings.setLearnerMemoryEnabled(accepted.activeProfileId,true)
            val conversation=Conversation.ofId(memoryId,assistant.id).copy(profileId=accepted.activeProfileId,messageNodes=listOf(
                MessageNode(messages=listOf(UIMessage.user("I prefer synthetic examples"))),
                MessageNode(messages=listOf(UIMessage(role=me.rerere.ai.core.MessageRole.ASSISTANT,parts=listOf(UIMessagePart.Text("synthetic response")),generationState="COMPLETED")))))
            repo.insertConversation(conversation);memories.reconcile(conversation)
            val state=memories.read(accepted.activeProfileId)
            job=ExtractionJob(conversationId=memoryId.toString(),sourceIds=state.sources.filter { it.conversationId==memoryId.toString() }.map { it.id },
                epoch=state.epoch,rangeHash="synthetic-range",expectedRevisions=state.items.associate { it.id to it.revision },state="RUNNING",attempts=1,leaseUntil=System.currentTimeMillis()+120000)
            memories.change(accepted.activeProfileId) { it.copy(jobs=it.jobs+job!!) to Unit }
            fun startImage() { imageTaskId=creation.start(accepted.activeProfileId,chatId.toString(),imageRequest,"synthetic image",emptyList(),null,"1024x1024","low",{}).id }
            if(memoryFirst) memoryDeferred=async(Dispatchers.IO) { memoryRunner.process(accepted.activeProfileId,job!!) } else startImage()
            withTimeout(10_000) { while(server.entered.count>0) { memoryDeferred?.takeIf { it.isCompleted }?.await();delay(10) } }
            if(memoryFirst) startImage() else memoryDeferred=async(Dispatchers.IO) { memoryRunner.process(accepted.activeProfileId,job!!) }
            val translationFlow=koin.get<TranslationHandler>().translateText(accepted,"synthetic translation",Locale.ENGLISH)
            translationDeferred=async(Dispatchers.IO) { translationFlow.first() }
            val chatConversation=Conversation.ofId(chatId,assistant.id).copy(profileId=accepted.activeProfileId)
            val chatTask=scheduler.accept(chatConversation,"qa-chat-${Uuid.random()}","synthetic chat",accepted)
            chatDeferred=async(Dispatchers.IO) { scheduler.run(chatTask,{settings.settingsFlow.value},{},{"COMPLETED_UNVERIFIED"},{false}) {
                withContext(requireNotNull(currentCoroutineContext()[AgentExecution]).accounting("main")) {
                    koin.get<ProviderManager>().getProviderByType(provider).generateText(provider,listOf(UIMessage.user("synthetic chat")),TextGenerationParams(main,reasoningLevel=ReasoningLevel.OFF))
                }
            } }
            val materialTask=scheduler.accept(Conversation.ofId(materialId,assistant.id).copy(profileId=accepted.activeProfileId),"qa-material-${Uuid.random()}","synthetic material image",accepted)
            val tool=materialTools(materials,accepted.activeProfileId,accepted,koin.get(),main).single { it.name=="generate_material_image" }
            materialDeferred=async(Dispatchers.IO) { scheduler.run(materialTask,{settings.settingsFlow.value},{},{"COMPLETED_UNVERIFIED"},{false}) { tool.execute(buildJsonObject { put("prompt","synthetic material image") }) } }
            withTimeout(10_000) {
                while(AgentTaskStore(context).use { it.find(accepted.activeProfileId,memoryId.toString(),"role:memory:${job!!.id}")==null }) delay(10)
            }
            settings.update(accepted.copy(chatModelId=changed.id,fastModelId=changed.id,imageGenerationModelId=changed.id,translateModeId=changed.id,
                assistants=listOf(assistant.copy(chatModelId=changed.id,reasoningLevel=ReasoningLevel.HIGH))))
            settings.settingsFlow.first { it.chatModelId==changed.id }
            delay(500)
            assertEquals("All roles queued while a real inference is blocked",1,server.bodies.size)
            barrier.countDown()
            withTimeout(30_000) { memoryDeferred!!.await();assertEquals("translated",translationDeferred!!.await());assertEquals("chat-response",chatDeferred!!.await().message.toText()) }
            val materialOutput=withTimeout(30_000) { materialDeferred!!.await() }
            assertTrue(materialOutput.any { it is UIMessagePart.Image })
            withTimeout(30_000) { while(creation.get(accepted.activeProfileId,imageTaskId!!).state in setOf("QUEUED","RUNNING")) delay(10) }
            val imageResult=creation.get(accepted.activeProfileId,imageTaskId!!)
            assertEquals("SUCCEEDED",imageResult.state)
            val actualBitmap=android.graphics.BitmapFactory.decodeFile(materials.verifiedFile(accepted.activeProfileId,imageResult.artifactId!!).absolutePath)
            try { assertEquals(4,actualBitmap.width);assertEquals(4,actualBitmap.height);assertEquals(android.graphics.Color.TRANSPARENT,actualBitmap.getPixel(0,0)) }
            finally { actualBitmap.recycle() }
            assertEquals("SUCCEEDED",memories.read(accepted.activeProfileId).jobs.single { it.id==job!!.id }.state)
            assertEquals(listOf("gpt-image-qa","gpt-image-qa","qa-fast","qa-main","qwen-mt-qa").sorted(),server.bodies.map { Json.parseToJsonElement(it).jsonObject.getValue("model").jsonPrimitive.content }.sorted())
            AgentTaskStore(context).use { store ->
                store.readableDatabase.rawQuery("SELECT role,provenance,prompt_tokens,completion_tokens FROM usage WHERE task_id IN (SELECT id FROM tasks WHERE conversation_id IN (?,?,?))",arrayOf(memoryId.toString(),chatId.toString(),materialId.toString())).use { rows ->
                    val roles=mutableListOf<String>();while(rows.moveToNext()) { roles+=rows.getString(0);assertEquals("unknown",rows.getString(1));assertTrue(rows.isNull(2));assertTrue(rows.isNull(3)) }
                    assertEquals(listOf("image","image","main","memory"),roles.sorted())
                }
            }
        } finally {
            barrier.countDown();memoryDeferred?.cancelAndJoin();translationDeferred?.cancelAndJoin();chatDeferred?.cancelAndJoin();materialDeferred?.cancelAndJoin()
            imageTaskId?.let { if(creation.get(accepted.activeProfileId,it).state in setOf("RUNNING","QUEUED")) creation.cancel(accepted.activeProfileId,it) }
            repo.getConversationById(memoryId)?.let { repo.deleteConversation(it) }
            settings.update(original);settings.settingsFlow.first { it.assistantId==original.assistantId }
            settings.setLearnerMemoryEnabled(original.activeProfileId,original.learnerProfiles.single { it.id==original.activeProfileId }.memoryEnabled)
            memoryRunner.chatFinished();server.close()
        }
    }
}
