package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

/** Real Android Chat -> task approval -> HTTP MCP -> Linux container -> read_result -> goal. */
@RunWith(AndroidJUnit4::class)
class SandboxMcpChatStoryTest {
    @Test fun approvedSkillRunsInIndependentServerAndGoalUsesReadback(): Unit=runBlocking {
        val arguments=InstrumentationRegistry.getArguments()
        val code=requireNotNull(arguments.getString("code_sha256"));require(code.matches(Regex("[a-f0-9]{64}")))
        val token=requireNotNull(arguments.getString("sandbox_token"))
        val port=requireNotNull(arguments.getString("sandbox_port")).toInt();require(port in 1024..65535)
        val koin=GlobalContext.get();val settings=koin.get<SettingsStore>();val context=koin.get<android.content.Context>()
        val original=withTimeout(20_000) { settings.settingsFlow.first { !it.init } }
        val owner=original.activeProfileId;val id=Uuid.random();val service=koin.get<ChatService>();val repo=koin.get<ConversationRepository>();val scheduler=AgentScheduler.shared(context)
        val schema=buildJsonObject {
            put("type","object");put("properties",buildJsonObject {
                put("script_id",buildJsonObject { put("type","string");put("enum",buildJsonArray { add(JsonPrimitive("sum")) }) })
                put("script_sha256",buildJsonObject { put("type","string");put("enum",buildJsonArray { add(JsonPrimitive(code)) }) })
                put("input_json",buildJsonObject { put("type","string");put("maxLength",32768) })
                put("run_id",buildJsonObject { put("type","string");put("minLength",64);put("maxLength",64) })
            });put("required",buildJsonArray { for(v in listOf("script_id","script_sha256","input_json","run_id")) add(JsonPrimitive(v)) });put("additionalProperties",false)
        }
        val pin=ExtensionInstallationStore(context).use { db ->
            val old=db.get(owner,"sandbox-story")
            val p=db.install(owner,"sandbox-story",old?.revision ?: 0,ExtensionConfig(name="Approved sum Skill",endpoint="http://127.0.0.1:$port/mcp",
                serverName="hxai-skill-sandbox",serverVersion="1",tools=listOf(ExtensionToolPin("run_python",schema,verification="sandbox_receipt",imageSha256="sha256:69e69785e7156672be426d8e758cfe1becdcfabd7583ae9ca9a88bc41e3c7433"))),token)
            val live=db.command(owner,p.slot,p.id,p.revision,"enable")
            ExtensionToolGrantStore(context).use { it.grant(owner, live.id, "run_python", live.grantEpoch, ExtensionToolAdmission.contractHash(live.config.tools.first { it.name == "run_python" })) }
            live
        }
        val input=buildJsonObject { put("installation_id",pin.id);put("tool","run_python");put("arguments",buildJsonObject { put("script_id","sum");put("script_sha256",code);put("input_json","{\"values\":[1,2,3]}") }.toString()) }
        val main=AtomicInteger();val semantic=AtomicInteger()
        LocalModelServer { body ->
            val messages=Json.parseToJsonElement(body).jsonObject.getValue("messages").jsonArray
            val instruction=messages.first().jsonObject["content"].toString()
            when {
                "HXAI_TASK_PLAN_V6" in instruction -> LocalModelServer.completion("""{"steps":[{"id":"sum","tool":"call_authorized_mcp","purpose":"Compute approved sum in isolated Python"}],"acceptance":[{"stepId":"sum","kind":"tool_verified"}]}""")
                "HXAI_GOAL_VERIFY_V6" in instruction -> {
                    semantic.incrementAndGet()
                    val evidence=Json.parseToJsonElement(messages.last().jsonObject.getValue("content").jsonPrimitive.content).jsonObject
                    LocalModelServer.completion(buildJsonObject { put("accepted",true);put("goalRevision",evidence.getValue("goalRevision"));put("evidenceHash",evidence.getValue("evidenceHash"));put("reason","Real sum receipt") }.toString())
                }
                main.incrementAndGet()==1 -> buildJsonObject { put("id","qa-sandbox");put("model","qa-sandbox");put("choices",buildJsonArray { add(buildJsonObject {
                    put("index",0);put("finish_reason","tool_calls");put("message",buildJsonObject { put("role","assistant");put("content","");put("tool_calls",buildJsonArray { add(buildJsonObject {
                        put("id","sandbox-once");put("type","function");put("function",buildJsonObject { put("name",ExtensionMcpRuntime.TOOL);put("arguments",input.toString()) })
                    }) }) })
                }) }) }.toString()
                else -> LocalModelServer.completion("The approved computation returned; independent readback decides completion.")
            }
        }.use { modelServer ->
            val model=Model(modelId="qa-sandbox",abilities=listOf(ModelAbility.TOOL))
            val provider=ProviderSetting.OpenAI(name="QA local",baseUrl=modelServer.baseUrl,apiKey="synthetic",models=listOf(model))
            val assistant=Assistant(name="QA sandbox",chatModelId=model.id,reasoningLevel=ReasoningLevel.OFF,streamOutput=false,localTools=emptyList())
            suspend fun idle(): Conversation=withTimeout(60_000) {
                while(true) { val c=service.getConversationFlow(id).first();val task=scheduler.current(c)
                    if(task != null && task.state !in setOf("RUNNING","QUEUED") && service.getGenerationJobStateFlow(id).first()==null) return@withTimeout c
                    delay(25) }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
            try {
                settings.update(original.copy(providers=listOf(provider),assistants=original.assistants+assistant,assistantId=assistant.id,
                    chatModelId=model.id,fastModelId=model.id,enableSuggestion=false,learnerProfiles=original.learnerProfiles.map { it.copy(memoryEnabled=false) },networkSetting=original.networkSetting.copy(proxyUrl="",enableAutoRetry=false)))
                settings.settingsFlow.first { it.assistantId==assistant.id }
                service.addConversationReference(id);service.saveConversation(id,Conversation.ofId(id,assistant.id).copy(profileId=owner,newConversation=true,title="Sandbox sum"))
                service.sendMessageCommitted(id,listOf(UIMessagePart.Text("Use the approved sum Skill to compute 1+2+3; return the independently read result.")),Uuid.random())
                val waiting=idle();val pending=waiting.currentMessages.flatMap { it.getTools() }.single { it.toolName==ExtensionMcpRuntime.TOOL }
                assertTrue(pending.isPending)
                val accepted=requireNotNull(scheduler.current(waiting));assertEquals("WAITING_INPUT",accepted.state)
                assertTrue(scheduler.control(owner,accepted.id).approvalBlocked)
                service.handleToolApproval(id,pending.toolCallId,true)
                val completed=idle();val task=requireNotNull(scheduler.current(completed))
                if(task.state != "SUCCEEDED") {
                    val diagnostics=buildJsonObject {
                        put("state",task.state);put("tools",JsonArray(completed.currentMessages.flatMap { it.getTools() }.map { t ->
                            buildJsonObject { put("tool",t.toolName);put("executionState",t.executionState.name);put("output",t.output.toString()) }
                        }));put("errors",service.errors.value.joinToString { it.error.toString() })
                    }
                    context.cacheDir.resolve("sandbox-story-failure.json").writeText(diagnostics.toString())
                    println("QA_SANDBOX_DIAGNOSTICS $diagnostics")
                }
                assertEquals("SUCCEEDED",task.state);assertEquals(accepted.modelId,task.modelId);assertEquals(accepted.id,task.id)
                val output=completed.currentMessages.flatMap { it.getTools() }.single { it.toolName==ExtensionMcpRuntime.TOOL }.output.single() as UIMessagePart.Text
                val receipt=Json.parseToJsonElement(output.text).jsonObject.getValue("untrusted_result").jsonObject
                assertEquals(JsonPrimitive(code),receipt["script_sha256"])
                assertEquals(JsonPrimitive(6),Json.parseToJsonElement(receipt.getValue("output_json").jsonPrimitive.content).jsonObject["sum"])
                assertEquals(1,semantic.get())
                assertTrue(scheduler.plan(owner,task.id).acceptanceVerified)
            } finally {
                service.stopGeneration(id);service.removeConversationReference(id);repo.getConversationById(id)?.let { repo.deleteConversation(it) }
                settings.update(original);settings.settingsFlow.first { it.assistantId==original.assistantId }
                ExtensionInstallationStore(context).use { db -> val current=requireNotNull(db.get(owner,"sandbox-story"));db.command(owner,current.slot,current.id,current.revision,"uninstall") }
            }
        }
    }
}
