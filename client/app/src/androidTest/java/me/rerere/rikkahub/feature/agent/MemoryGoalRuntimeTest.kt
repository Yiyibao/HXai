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
import me.rerere.rikkahub.feature.memory.LearningMemoryStore
import me.rerere.rikkahub.service.ChatService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class MemoryGoalRuntimeTest {
    @Test fun explicitMemoryNeedsActualOwnedRecordAndEvidenceBoundAcceptance()=scenario(false)
    @Test fun editingMemoryDuringSemanticAcceptanceCannotCompleteTheOriginalGoal()=scenario(true)

    private fun scenario(editDuringAcceptance: Boolean): Unit=runBlocking {
        val koin=GlobalContext.get()
        val settings=koin.get<SettingsStore>()
        val original=withTimeout(20_000) { settings.settingsFlow.first { !it.init } }
        val memories=koin.get<LearningMemoryStore>()
        val previous=memories.read(original.activeProfileId)
        val requirement="我喜欢先看反例${Uuid.random()}"
        val calls=AtomicInteger()
        val server=LocalModelServer { body ->
            val messages=Json.parseToJsonElement(body).jsonObject.getValue("messages").jsonArray
            val system=messages.first().jsonObject["content"].toString()
            when {
                "HXAI_TASK_PLAN_V6" in system -> LocalModelServer.completion(buildJsonObject {
                    put("steps",buildJsonArray { add(buildJsonObject {
                        put("id","remember");put("tool","save_explicit_memory");put("purpose","Persist only the explicit user preference")
                    }) })
                    put("acceptance",buildJsonArray { add(buildJsonObject {
                        put("stepId","remember");put("kind","memory_content");put("contains",buildJsonArray { add(requirement) })
                    }) })
                }.toString())
                "HXAI_GOAL_VERIFY_V6" in system -> {
                    val evidence=Json.parseToJsonElement(messages.last().jsonObject.getValue("content").jsonPrimitive.content).jsonObject
                    val record=evidence.getValue("evidence").jsonArray.single().jsonObject.getValue("memory").jsonObject.getValue("record").jsonObject
                    assertEquals(requirement,record.getValue("content").jsonPrimitive.content)
                    assertEquals("SELF_REPORTED",record.getValue("evidenceType").jsonPrimitive.content)
                    if(editDuringAcceptance) runBlocking {
                        val current=memories.read(original.activeProfileId).items.single { it.id==record.getValue("id").jsonPrimitive.content }
                        memories.edit(original.activeProfileId,current.id,current.revision,"已改为别的学习偏好")
                    }
                    LocalModelServer.completion(buildJsonObject {
                        put("accepted",true);put("goalRevision",evidence.getValue("goalRevision"))
                        put("evidenceHash",evidence.getValue("evidenceHash"));put("reason","Exact user preference exists in the supplied independently read record")
                    }.toString())
                }
                calls.incrementAndGet()==1 -> buildJsonObject {
                    put("id","qa-memory");put("model","qa-memory");put("choices",buildJsonArray { add(buildJsonObject {
                        put("index",0);put("finish_reason","tool_calls");put("message",buildJsonObject {
                            put("role","assistant");put("content","");put("tool_calls",buildJsonArray { add(buildJsonObject {
                                put("id","remember-once");put("type","function");put("function",buildJsonObject {
                                    put("name","save_explicit_memory");put("arguments",buildJsonObject {
                                        put("type","PREFERENCE");put("topic_key","learning-style");put("fact_key",Uuid.random().toString());put("content",requirement)
                                    }.toString())
                                })
                            }) })
                        })
                    }) })
                }.toString()
                else -> LocalModelServer.completion("The preference was saved. Persistent independent acceptance remains authoritative.")
            }
        }
        val model=Model(modelId="qa-memory",abilities=listOf(ModelAbility.TOOL))
        val provider=ProviderSetting.OpenAI(name="QA memory loopback",baseUrl=server.baseUrl,apiKey="synthetic",models=listOf(model))
        val assistant=Assistant(name="QA explicit memory",chatModelId=model.id,reasoningLevel=ReasoningLevel.OFF,streamOutput=false,localTools=emptyList())
        val id=Uuid.random()
        val service=koin.get<ChatService>()
        val repo=koin.get<ConversationRepository>()
        try {
            settings.update(original.copy(providers=listOf(provider),assistants=original.assistants+assistant,assistantId=assistant.id,
                chatModelId=model.id,fastModelId=model.id,enableSuggestion=false,
                learnerProfiles=original.learnerProfiles.map { it.copy(memoryEnabled=false) },
                networkSetting=original.networkSetting.copy(proxyUrl="",enableAutoRetry=false)))
            settings.settingsFlow.first { it.assistantId==assistant.id }
            service.addConversationReference(id)
            service.saveConversation(id,Conversation.ofId(id,assistant.id).copy(profileId=original.activeProfileId,title="QA explicit memory",newConversation=true))
            service.sendMessageCommitted(id,listOf(UIMessagePart.Text("请记住$requirement")),Uuid.random())
            val scheduler=AgentScheduler.shared(koin.get())
            val task=withTimeout(40_000) {
                while(true) {
                    val current=scheduler.current(service.getConversationFlow(id).first())
                    if(current!=null && current.state !in setOf("RUNNING","QUEUED") && service.getGenerationJobStateFlow(id).first()==null) return@withTimeout current
                    delay(20)
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
            assertEquals(if(editDuringAcceptance) "FAILED_BLOCKED" else "SUCCEEDED",task.state)
            val persisted=requireNotNull(repo.getConversationById(id))
            val receipt=persisted.currentMessages.flatMap { it.getTools() }.single { it.toolName=="save_explicit_memory" }
            val memoryId=Json.parseToJsonElement(receipt.output.filterIsInstance<UIMessagePart.Text>().single().text).jsonObject.getValue("memory_id").jsonPrimitive.content
            val item=memories.read(task.owner).items.single { it.id==memoryId }
            assertEquals(if(editDuringAcceptance) "已改为别的学习偏好" else requirement,item.content)
            assertTrue(item.sourceIds.all { sourceId -> memories.read(task.owner).sources.any { it.id==sourceId && it.valid && it.explicitRequest && it.conversationId==id.toString() } })
            AgentTaskStore(koin.get()).use { store ->
                assertEquals("VERIFIED",store.plan(task.owner,task.id).steps.single().state)
                assertEquals(!editDuringAcceptance,store.plan(task.owner,task.id).acceptanceVerified)
            }
        } finally {
            service.stopGeneration(id);service.removeConversationReference(id)
            repo.getConversationById(id)?.let { repo.deleteConversation(it) }
            memories.change(original.activeProfileId) { previous to Unit }
            settings.update(original);settings.settingsFlow.first { it.assistantId==original.assistantId }
            server.close()
        }
    }
}
