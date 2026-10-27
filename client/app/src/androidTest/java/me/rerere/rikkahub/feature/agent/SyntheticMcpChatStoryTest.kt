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
import me.rerere.rikkahub.service.ChatService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/** Actual Chat/Room/Provider/Planner/approval/journal/readback, only synthetic localhost data. */
@RunWith(AndroidJUnit4::class)
class SyntheticMcpChatStoryTest {
    @Test fun approvedPinnedMcpReadCanCompleteTheUnifiedChatGoal()=story(false)
    @Test fun serverChangeDuringSemanticAcceptanceCannotPublishTaskSuccess()=story(true)
    @Test fun installationRevocationDuringSemanticAcceptanceCannotPublishTaskSuccess()=story(false,true)
    private fun story(changeDuringAcceptance: Boolean,revokeDuringAcceptance: Boolean=false):Unit=runBlocking {
        val koin=GlobalContext.get();val settings=koin.get<SettingsStore>()
        val original=withTimeout(20_000) { settings.settingsFlow.first { !it.init } }
        val service=koin.get<ChatService>();val repo=koin.get<ConversationRepository>();val scheduler=AgentScheduler.shared(koin.get())
        val id=Uuid.random();val main=AtomicInteger();val semantic=AtomicInteger()
        SyntheticMcpServer().use { mcp ->
            mcp.install(original.activeProfileId)
            val modelServer=LocalModelServer { body ->
                val messages=Json.parseToJsonElement(body).jsonObject.getValue("messages").jsonArray
                val instruction=messages.first().jsonObject["content"].toString()
                when {
                    "HXAI_TASK_PLAN_V6" in instruction -> LocalModelServer.completion(buildJsonObject {
                        put("steps",buildJsonArray { add(buildJsonObject { put("id","read");put("tool",SyntheticMcpRuntime.TOOL);put("purpose","Read the approved pinned synthetic record") }) })
                        put("acceptance",buildJsonArray { add(buildJsonObject { put("stepId","read");put("kind","tool_verified") }) })
                    }.toString())
                    "HXAI_GOAL_VERIFY_V6" in instruction -> {
                        semantic.incrementAndGet()
                        val evidence=Json.parseToJsonElement(messages.last().jsonObject.getValue("content").jsonPrimitive.content).jsonObject
                        if(changeDuringAcceptance) mcp.text="Changed during semantic verification"
                        if(revokeDuringAcceptance) McpInstallationStore(koin.get()).use { registry ->
                            val pin=registry.get(original.activeProfileId)!!;registry.command(pin.owner,pin.id,pin.revision,"revoke")
                        }
                        LocalModelServer.completion(buildJsonObject {
                            put("accepted",true);put("goalRevision",evidence.getValue("goalRevision"));put("evidenceHash",evidence.getValue("evidenceHash"));put("reason","Pinned synthetic read evidence")
                        }.toString())
                    }
                    main.incrementAndGet()==1 -> buildJsonObject {
                        put("id","qa-mcp");put("model","qa-mcp");put("choices",buildJsonArray { add(buildJsonObject {
                            put("index",0);put("finish_reason","tool_calls");put("message",buildJsonObject {
                                put("role","assistant");put("content","");put("tool_calls",buildJsonArray { add(buildJsonObject {
                                    put("id","mcp-once");put("type","function");put("function",buildJsonObject { put("name",SyntheticMcpRuntime.TOOL);put("arguments",mcp.input().toString()) })
                                }) })
                            })
                        }) })
                    }.toString()
                    else -> LocalModelServer.completion("The synthetic read has returned; persistent verification decides completion.")
                }
            }
            val model=Model(modelId="qa-mcp",abilities=listOf(ModelAbility.TOOL))
            val provider=ProviderSetting.OpenAI(name="QA MCP loopback",baseUrl=modelServer.baseUrl,apiKey="synthetic",models=listOf(model))
            val assistant=Assistant(name="QA MCP",chatModelId=model.id,reasoningLevel=ReasoningLevel.OFF,streamOutput=false,localTools=emptyList())
            suspend fun idle(): Conversation=withTimeout(45_000) {
                while(true) {
                    val current=service.getConversationFlow(id).first()
                    val task=scheduler.current(current)
                    if(task!=null && task.state !in setOf("RUNNING","QUEUED") && service.getGenerationJobStateFlow(id).first()==null) return@withTimeout current
                    delay(20)
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
            try {
                settings.update(original.copy(providers=listOf(provider),assistants=original.assistants+assistant,assistantId=assistant.id,
                    chatModelId=model.id,fastModelId=model.id,enableSuggestion=false,
                    learnerProfiles=original.learnerProfiles.map { it.copy(memoryEnabled=false) },
                    networkSetting=original.networkSetting.copy(proxyUrl="",enableAutoRetry=false)))
                settings.settingsFlow.first { it.assistantId==assistant.id }
                service.addConversationReference(id)
                service.saveConversation(id,Conversation.ofId(id,assistant.id).copy(profileId=original.activeProfileId,title="QA MCP",newConversation=true))
                service.sendMessageCommitted(id,listOf(UIMessagePart.Text("Read synthetic fixture at SHA ${AgentTaskStore.hash(mcp.text)}; do not execute scripts.")),Uuid.random())
                val waiting=idle();val acceptedTask=requireNotNull(scheduler.current(waiting))
                val pending=waiting.currentMessages.flatMap { it.getTools() }.single { it.toolName==SyntheticMcpRuntime.TOOL }
                assertTrue(pending.isPending);assertEquals(0,mcp.server.bodies.size)
                service.handleToolApproval(id,pending.toolCallId,true)
                val finished=idle();val task=requireNotNull(scheduler.current(finished))
                assertEquals(acceptedTask.id,task.id);assertEquals(acceptedTask.modelId,task.modelId);assertEquals("OFF",task.reasoning)
                assertEquals(if(changeDuringAcceptance || revokeDuringAcceptance) "FAILED_BLOCKED" else "SUCCEEDED",task.state)
                assertEquals(1,semantic.get());assertTrue(mcp.reads.get()>=3)
                val result=finished.currentMessages.flatMap { it.getTools() }.single { it.toolName==SyntheticMcpRuntime.TOOL }
                assertEquals(pending.input,result.input);assertEquals(1,result.output.size)
                AgentTaskStore(koin.get()).use { store ->
                    assertEquals(!changeDuringAcceptance && !revokeDuringAcceptance,store.plan(task.owner,task.id).acceptanceVerified)
                    assertEquals(listOf("VERIFIED"),store.plan(task.owner,task.id).steps.map { it.state })
                    store.readableDatabase.rawQuery("SELECT capability,scope,grant_epoch,input_hash FROM calls WHERE task_id=? AND tool=?",arrayOf(task.id,SyntheticMcpRuntime.TOOL)).use { row ->
                        assertTrue(row.moveToFirst());assertEquals("mcp.synthetic.read",row.getString(0));assertEquals(task.owner,row.getString(1))
                        assertEquals(1,row.getLong(2));assertEquals(AgentTaskStore.hash(pending.input),row.getString(3));assertFalse(row.moveToNext())
                    }
                }
            } finally {
                service.stopGeneration(id);service.removeConversationReference(id)
                repo.getConversationById(id)?.let { repo.deleteConversation(it) }
                settings.update(original);settings.settingsFlow.first { it.assistantId==original.assistantId };modelServer.close()
            }
        }
    }
}
