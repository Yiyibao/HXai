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
import kotlin.uuid.Uuid

/** Real Chat/Room/TaskStore; synthetic loopback model, never an accuracy evaluation. */
@RunWith(AndroidJUnit4::class)
class ResponseCompletionRuntimeTest {
    @Test fun greetingCompletesWithoutInventingATool() = scenario("你好", "你好！", "delivered", "SUCCEEDED")
    @Test fun missingHandoutWaitsForInputDurably() = scenario("按我没有上传的讲义总结", "请提供讲义。", "needs_input", "WAITING_INPUT")
    @Test fun unsupportedProofRemainsUnverified() = scenario("严格证明一般函数的定理", "目前没有可核对的证明。", "unverified", "COMPLETED_UNVERIFIED")
    @Test fun wrongResponseEvidenceHashCannotAccept() = scenario("你好", "你好！", "delivered", "COMPLETED_UNVERIFIED", badHash=true)
    @Test fun aMissingInputQuoteMustActuallyBeInTheReply() = scenario("你好", "你好！", "needs_input", "COMPLETED_UNVERIFIED", badQuote=true)
    @Test fun fileClaimCannotReplaceRequiredIndependentEvidence() = scenario("创建可编辑Word文件", "文件已完成。",
        "delivered", "COMPLETED_UNVERIFIED", independentEvidence=true)
    @Test fun textRefutationDoesNotClaimMathematicalCertification() = scenario("请用文字指出“所有数都等于零”不对",
        "断言不对，例如1不等于0。", "delivered", "SUCCEEDED")

    private fun scenario(goal: String, reply: String, decision: String, expected: String,
        badHash: Boolean=false, badQuote: Boolean=false, independentEvidence: Boolean=false): Unit = runBlocking {
        val k=GlobalContext.get();val settings=k.get<SettingsStore>()
        val original=withTimeout(20_000) {settings.settingsFlow.first {!it.init}}
        val server=LocalModelServer {body ->
            val messages=Json.parseToJsonElement(body).jsonObject.getValue("messages").jsonArray
            val instruction=messages.first().jsonObject["content"].toString()
            if("HXAI_RESPONSE_DISPOSITION_V1" in instruction) {
                val input=Json.parseToJsonElement(messages.last().jsonObject.getValue("content").jsonPrimitive.content).jsonObject
                LocalModelServer.completion(buildJsonObject {
                    put("outcome",decision);put("goalRevision",input.getValue("goalRevision"))
                    put("evidenceHash",if(badHash) JsonPrimitive("wrong") else input.getValue("evidenceHash"))
                    put("responseQuote",if(badQuote) "not in the reply" else reply)
                    put("reason","synthetic bounded response disposition")
                    put("requiresIndependentEvidence",independentEvidence || decision=="unverified")
                }.toString())
            } else LocalModelServer.completion(reply)
        }
        val model=Model(modelId="qa-response",abilities=listOf(ModelAbility.TOOL))
        val provider=ProviderSetting.OpenAI(name="QA response",baseUrl=server.baseUrl,apiKey="synthetic",models=listOf(model))
        val assistant=Assistant(name="QA response",chatModelId=model.id,reasoningLevel=ReasoningLevel.OFF,
            streamOutput=false,localTools=emptyList())
        val service=k.get<ChatService>();val repo=k.get<ConversationRepository>()
        val scheduler=AgentScheduler.shared(k.get());val id=Uuid.random()
        try {
            settings.update(original.copy(providers=listOf(provider),assistants=original.assistants+assistant,
                assistantId=assistant.id,chatModelId=model.id,fastModelId=model.id,enableSuggestion=false,
                learnerProfiles=original.learnerProfiles.map {it.copy(memoryEnabled=false)},
                networkSetting=original.networkSetting.copy(proxyUrl="",enableAutoRetry=false)))
            settings.settingsFlow.first {it.assistantId==assistant.id}
            service.addConversationReference(id)
            service.saveConversation(id,Conversation.ofId(id,assistant.id).copy(profileId=original.activeProfileId,
                title="QA response contract",newConversation=true))
            service.sendMessageCommitted(id,listOf(UIMessagePart.Text(goal)),Uuid.random())
            withTimeout(50_000) {while(true) {
                val current=service.getConversationFlow(id).first();val task=scheduler.current(current)
                if(task!=null && task.state !in setOf("RUNNING","QUEUED") && service.getGenerationJobStateFlow(id).first()==null)break
                delay(20)
            }}
            val persisted=requireNotNull(repo.getConversationById(id));val task=requireNotNull(scheduler.current(persisted))
            assertEquals(reply,persisted.currentMessages.last().toText())
            assertEquals(expected,task.state)
            assertTrue(persisted.currentMessages.flatMap {it.getTools()}.isEmpty())
            AgentTaskStore(k.get()).use {store ->
                assertEquals(expected=="SUCCEEDED",store.plan(task.owner,task.id).acceptanceVerified)
                assertEquals(expected=="WAITING_INPUT",store.control(task.owner,task.id).inputBlocked)
                store.readableDatabase.rawQuery("SELECT COUNT(*) FROM calls WHERE task_id=? AND tool NOT LIKE 'provider:%'",arrayOf(task.id)).use {
                    assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))
                }
                if(expected=="SUCCEEDED")store.readableDatabase.rawQuery("SELECT evidence_json FROM plans WHERE task_id=?",arrayOf(task.id)).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(JsonPrimitive("NOT_EVALUATED"),Json.parseToJsonElement(it.getString(0)).jsonObject["mathematics_status"])
                }
            }
            if(expected=="WAITING_INPUT") AgentTaskStore(k.get()).use {reopened ->
                assertTrue(reopened.control(task.owner,task.id).inputBlocked)
                assertEquals("WAITING_INPUT",reopened.get(task.owner,task.id).state)
            }
            assertTrue(server.bodies.all {Json.parseToJsonElement(it).jsonObject["model"]==JsonPrimitive(model.modelId)})
        } finally {
            service.stopGeneration(id)
            repo.getConversationById(id)?.let {service.deleteConversation(it)}
            service.removeConversationReference(id)
            settings.update(original);settings.settingsFlow.first {it.assistantId==original.assistantId}
            server.close()
        }
    }
}
