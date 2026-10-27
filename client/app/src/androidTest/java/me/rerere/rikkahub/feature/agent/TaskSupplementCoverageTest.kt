package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.material.MaterialFiles
import me.rerere.rikkahub.feature.material.MaterialReader
import me.rerere.rikkahub.flutter.StudyHostFacade
import me.rerere.rikkahub.service.ChatService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/** Real Chat/Room/host command and owned Word readback; only the model is a loopback fixture. */
@RunWith(AndroidJUnit4::class)
class TaskSupplementCoverageTest {
    @Test fun sameTaskSupplementReordersPlanKeepsModelAndReplaysReceiptWithoutAnotherWrite()=scenario("valid")
    @Test fun retainedVerifiedStepCannotAuthorizeNewSameToolWrite()=scenario("underplanned")
    @Test fun replanDiscardingVerifiedStepFailsWithoutDamagingOriginalFile()=scenario("discard")

    private fun scenario(mode:String):Unit=runBlocking {
        val koin=GlobalContext.get();val context=koin.get<android.content.Context>()
        val settings=koin.get<SettingsStore>();val original=withTimeout(20_000) { settings.settingsFlow.first { !it.init } }
        val owner=original.activeProfileId;val materials=koin.get<MaterialFiles>()
        val contents=listOf("first-required-${Uuid.random()}","additional-required-${Uuid.random()}")
        val titles=listOf("QA retained ${Uuid.random()}","QA supplemental ${Uuid.random()}")
        val calls=AtomicInteger();val revisedGoalSeen=AtomicBoolean();val verifiedStepSeen=AtomicBoolean()
        fun input(index:Int)=buildJsonObject { put("format","docx");put("title",titles[index]);put("content",buildJsonObject { put("body",contents[index]) }.toString()) }.toString()
        fun response(indices:List<Int>,prefix:String)=buildJsonObject {
            put("id","synthetic-$prefix");put("choices",buildJsonArray { add(buildJsonObject {
                put("index",0);put("finish_reason","tool_calls");put("message",buildJsonObject {
                    put("role","assistant");put("content","");put("tool_calls",buildJsonArray { indices.forEach { index ->
                        add(buildJsonObject { put("id","$prefix-$index");put("type","function");put("function",buildJsonObject {
                            put("name","create_material_file");put("arguments",input(index))
                        }) })
                    } })
                })
            }) })
        }.toString()
        val server=LocalModelServer { body ->
            val request=Json.parseToJsonElement(body).jsonObject
            val messages=request.getValue("messages").jsonArray
            val system=messages.first().jsonObject.getValue("content").jsonPrimitive.content
            val user=messages.last().jsonObject["content"]?.jsonPrimitive?.content.orEmpty()
            when {
                "HXAI_TASK_PLAN_V6" in system -> {
                    val data=Json.parseToJsonElement(user).jsonObject
                    val revised=data.getValue("goalRevision").jsonPrimitive.long==2L
                    if(revised) {
                        verifiedStepSeen.set(data.getValue("verifiedSteps").jsonArray.any { it.jsonObject["id"]?.jsonPrimitive?.content=="first" })
                        check(contents[1] in data.getValue("goal").jsonPrimitive.content)
                    }
                    val ids=if(!revised) listOf(0) else when(mode) { "discard" -> listOf(1);"underplanned" -> listOf(0);else -> listOf(1,0) }
                    LocalModelServer.completion(buildJsonObject {
                        put("steps",buildJsonArray { ids.forEach { index -> add(buildJsonObject {
                            put("id",if(index==0) "first" else "additional");put("tool","create_material_file");put("purpose","Create requested Word ${contents[index]}")
                        }) } })
                        put("acceptance",buildJsonArray { ids.forEach { index -> add(buildJsonObject {
                            put("stepId",if(index==0) "first" else "additional");put("kind","artifact_content");put("format","docx");put("contains",buildJsonArray { add(contents[index]) })
                        }) } })
                    }.toString())
                }
                "HXAI_GOAL_VERIFY_V6" in system -> {
                    val data=Json.parseToJsonElement(user).jsonObject
                    val revised=data.getValue("goalRevision").jsonPrimitive.long==2L
                    val evidence=data.getValue("evidence").jsonArray.map { it.jsonObject }
                    check(evidence.size==if(revised) 2 else 1)
                    contents.take(if(revised) 2 else 1).forEach { required -> check(evidence.any { required in it.getValue("content").jsonPrimitive.content }) }
                    LocalModelServer.completion(buildJsonObject {
                        put("accepted",true);put("goalRevision",data.getValue("goalRevision"));put("evidenceHash",data.getValue("evidenceHash"));put("reason","All synthetic required owned artifacts were independently read")
                    }.toString())
                }
                else -> when(calls.incrementAndGet()) {
                    1 -> response(listOf(0),"initial")
                    3 -> { revisedGoalSeen.set(contents[1] in system);response(listOf(0,1),"supplement") }
                    else -> LocalModelServer.completion("Synthetic response; persistent acceptance decides completion.")
                }
            }
        }
        val model=Model(modelId="qa-locked-supplement",abilities=listOf(ModelAbility.TOOL))
        val changed=Model(modelId="qa-new-picker",abilities=listOf(ModelAbility.TOOL))
        val provider=ProviderSetting.OpenAI(baseUrl=server.baseUrl,apiKey="synthetic",models=listOf(model,changed))
        val assistant=Assistant(chatModelId=model.id,reasoningLevel=ReasoningLevel.OFF,streamOutput=false,localTools=emptyList())
        val accepted=original.copy(providers=listOf(provider),assistants=original.assistants+assistant,assistantId=assistant.id,
            chatModelId=model.id,fastModelId=model.id,enableSuggestion=false,
            learnerProfiles=original.learnerProfiles.map { it.copy(memoryEnabled=false) },
            networkSetting=original.networkSetting.copy(proxyUrl="",enableAutoRetry=false))
        val id=Uuid.random();val service=koin.get<ChatService>();val repo=koin.get<ConversationRepository>()
        val scheduler=AgentScheduler.shared(context);var facade:StudyHostFacade?=null
        suspend fun settled():AgentTask=withTimeout(40_000) {
            while(true) {
                val task=scheduler.current(service.getConversationFlow(id).first())
                if(task!=null && task.state !in setOf("RUNNING","QUEUED") && service.getGenerationJobStateFlow(id).first()==null) return@withTimeout task
                delay(20)
            }
            error("unreachable")
        }
        try {
            settings.update(accepted);settings.settingsFlow.first { it.assistantId==assistant.id }
            service.addConversationReference(id);service.saveConversation(id,Conversation.ofId(id,assistant.id).copy(profileId=owner,newConversation=true,title="QA task supplement"))
            service.sendMessageCommitted(id,listOf(UIMessagePart.Text("Create a Word document containing exactly ${contents[0]}")),Uuid.random())
            val initial=settled();assertEquals("SUCCEEDED",initial.state)
            val oldFile=materials.list(owner).single { it.name=="${titles[0]}.docx" }
            val oldBytes=materials.verifiedFile(owner,oldFile.id).readBytes()
            val initialStep=scheduler.plan(owner,initial.id).steps.single()
            settings.update(accepted.copy(chatModelId=changed.id,assistants=accepted.assistants.map { if(it.id==assistant.id) it.copy(chatModelId=changed.id) else it }))
            settings.settingsFlow.first { it.chatModelId==changed.id }
            val host=StudyHostFacade(context,settings,koin.get(),service,koin.get(),koin.get(),koin.get(),pickFiles={ emptyList() })
            facade=host
            val epoch=host.getBootstrap(owner,1L).contextEpoch
            val selected=requireNotNull(host.getConversationTask(owner,id.toString(),epoch))
            val command=Uuid.random().toString();val payload="Keep the first file and also create another Word containing exactly ${contents[1]}"
            assertTrue(host.taskCommand(owner,id.toString(),initial.id,command,epoch,selected.revision,selected.fence,"supplement",payload).success)
            val completed=settled();assertEquals(initial.id,completed.id)
            assertEquals(if(mode=="valid") "SUCCEEDED" else "FAILED_BLOCKED",completed.state)
            assertEquals(initial.configurationReference,completed.configurationReference)
            assertEquals(initial.providerId,completed.providerId);assertEquals(initial.modelId,completed.modelId);assertEquals(initial.reasoning,completed.reasoning)
            assertArrayEquals(oldBytes,materials.verifiedFile(owner,oldFile.id).readBytes())
            assertEquals(oldFile.sha256,materials.get(owner,oldFile.id).sha256)
            val plan=scheduler.plan(owner,initial.id)
            assertEquals(2L,plan.revision)
            assertEquals(initialStep,plan.steps.single { it.id=="first" })
            assertTrue(verifiedStepSeen.get())
            assertEquals(mode=="valid",plan.acceptanceVerified)
            if(mode!="discard") assertTrue("Main execution must receive the supplementary goal",revisedGoalSeen.get())
            if(mode=="valid") {
                assertEquals(listOf("additional","first"),plan.steps.map { it.id })
                val added=materials.list(owner).single { it.name=="${titles[1]}.docx" }
                assertTrue(MaterialReader.read(materials.verifiedFile(owner,added.id),"docx").text.contains(contents[1]))
            } else assertTrue(materials.list(owner).none { it.name=="${titles[1]}.docx" })
            AgentTaskStore(context).use { store ->
                store.readableDatabase.rawQuery("SELECT COUNT(*) FROM calls WHERE task_id=? AND tool='create_material_file'",arrayOf(initial.id)).use {
                    assertTrue(it.moveToFirst());assertEquals(if(mode=="valid") 2 else 1,it.getInt(0))
                }
            }
            val beforeAck=server.bodies.size
            assertTrue(host.taskCommand(owner,id.toString(),initial.id,command,epoch,selected.revision,selected.fence,"supplement",payload).success)
            delay(250);assertEquals("Repeated supplement ACK must not start a new request",beforeAck,server.bodies.size)
            assertTrue(server.bodies.all { Json.parseToJsonElement(it).jsonObject.getValue("model").jsonPrimitive.content==model.modelId })
        } finally {
            facade?.close();service.stopGeneration(id);service.removeConversationReference(id)
            repo.getConversationById(id)?.let { repo.deleteConversation(it) }
            settings.update(original);settings.settingsFlow.first { it.assistantId==original.assistantId };server.close()
        }
    }
}
