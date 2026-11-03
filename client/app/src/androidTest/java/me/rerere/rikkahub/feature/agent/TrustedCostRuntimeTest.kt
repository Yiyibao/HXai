package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import me.rerere.ai.core.TokenUsage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class TrustedCostRuntimeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun task(db: AgentTaskStore,owner: String="owner")=db.accept(owner,"conversation",UUID.randomUUID().toString(),"goal","ref","p","m","OFF","r")
    private fun basis()=TrustedCost.basis("https://api.openai.com/v1","gpt-4.1",buildJsonObject {},Instant.parse("2026-10-22T12:00:00Z").toEpochMilli())
    @Test fun customProviderCannotImpersonateOfficialReportedCost(): Unit=kotlinx.coroutines.runBlocking {
        LocalModelServer { body ->
            val result=Json.parseToJsonElement(LocalModelServer.completion("done")).jsonObject
            JsonObject(result+("usage" to buildJsonObject { put("prompt_tokens",10);put("completion_tokens",2);put("cached_tokens",0);put("total_tokens",12);put("cost",999) })).toString()
        }.use { server ->
            val model=me.rerere.ai.provider.Model(modelId="gpt-4.1")
            val provider=me.rerere.ai.provider.ProviderSetting.OpenAI(models=listOf(model),apiKey="synthetic",baseUrl=server.baseUrl)
            val assistant=me.rerere.rikkahub.data.model.Assistant(chatModelId=model.id,reasoningLevel=me.rerere.ai.core.ReasoningLevel.OFF)
            val settings=me.rerere.rikkahub.data.datastore.Settings(providers=listOf(provider),assistants=listOf(assistant),assistantId=assistant.id,chatModelId=model.id)
            val conversation=me.rerere.rikkahub.data.model.Conversation.ofId(kotlin.uuid.Uuid.random(),assistant.id).copy(profileId=settings.activeProfileId)
            val name="qa-cost-origin-${UUID.randomUUID()}.db"
            AgentScheduler(context,name).use { scheduler ->
                val task=scheduler.accept(conversation,"request","synthetic",settings)
                scheduler.run(task,{settings},{},{"COMPLETED_UNVERIFIED"},{false}) {
                    val execution=requireNotNull(kotlinx.coroutines.currentCoroutineContext()[AgentExecution])
                    kotlinx.coroutines.withContext(execution.accounting("main")) {
                        val result=org.koin.core.context.GlobalContext.get().get<me.rerere.ai.provider.ProviderManager>().getProviderByType(provider).generateText(provider,
                            listOf(me.rerere.ai.ui.UIMessage.user("synthetic")),me.rerere.ai.provider.TextGenerationParams(model=model,reasoningLevel=me.rerere.ai.core.ReasoningLevel.OFF))
                        assertNull(result.usage?.costUsd);assertNull(result.usage?.costSource)
                    }
                }
                val p=scheduler.readUsage(task.owner,task.id,null,null,null,100)
                assertEquals(1L,p.requestCount);assertEquals("unknown",Json.parseToJsonElement(p.entries.single().costJson!!).jsonObject.getValue("status").jsonPrimitive.content)
            }
        }
    }
    @Test fun receiptsPersistGroupFilterAndExportWithoutRepricingOrDoubleCounting() {
        val name="qa-money-${UUID.randomUUID()}.db"
        var ownerTask="";var request=""
        AgentTaskStore(context,name).use { db ->
            val t=task(db);ownerTask=t.id;val l=db.claim(t.owner,t.id)
            request=db.beginRequest(l,"ocr","{}",AgentRequestSelection("p","m","OFF","ref",basis()))
            val u=TokenUsage(1000,100,200,1100)
            db.finishRequest(l,request,u);db.finishRequest(l,request,u)
            assertTrue(runCatching { db.finishRequest(l,request,u.copy(completionTokens=101)) }.isFailure)
            val p=db.readUsage(t.owner,t.id,null,null,null,100)
            assertEquals(1L,p.requestCount)
            assertEquals("0.0025",Json.parseToJsonElement(p.entries.single().costJson!!).jsonObject.getValue("amount").jsonPrimitive.content)
            val group=db.readUsageGroups("owner",t.id,null,null,"role",null,100)
            assertEquals(p.costJson,group.groups.single().costJson)
            val snapshot=db.captureUsage("owner",t.id,null,null,"requests",UUID.randomUUID().toString())
            assertEquals(Json.parseToJsonElement(p.costJson!!),snapshot["cost"])
            assertEquals(Json.parseToJsonElement(p.entries.single().costJson!!),snapshot.getValue("records").jsonArray.single().jsonObject["cost"])
            assertEquals(0L,db.readUsage("other",null,null,null,null,100).requestCount)
            assertTrue(runCatching { db.readUsage("other",t.id,null,null,null,100) }.isFailure)
            db.finish(l,"COMPLETED_UNVERIFIED")
        }
        AgentTaskStore(context,name).use { db ->
            val p=db.readUsage("owner",ownerTask,null,null,null,100)
            assertEquals(request,p.entries.single().requestId)
            assertEquals("estimated",Json.parseToJsonElement(p.entries.single().costJson!!).jsonObject.getValue("status").jsonPrimitive.content)
        }
    }
    @Test fun schema9UnknownRequestAndSlotSurviveMigration() {
        val name="qa-cost-migration-${UUID.randomUUID()}.db"
        lateinit var task: AgentTask
        AgentTaskStore(context,name).use { db ->
            task=task(db);val l=db.claim(task.owner,task.id)
            db.beginRequest(l,"main","{}",AgentRequestSelection("p","m","OFF","ref"))
            db.writableDatabase.execSQL("DROP TABLE usage_costs");db.writableDatabase.version=9
        }
        AgentTaskStore(context,name).use { db ->
            assertEquals(10,db.readableDatabase.version)
            assertEquals(1L,db.readUsage("owner",task.id,null,null,null,100).requestCount)
            assertEquals(JsonPrimitive("1"),Json.parseToJsonElement(db.readUsage("owner",task.id,null,null,null,100).costJson!!).jsonObject["unknownRequests"])
            val other=task(db,"other");assertTrue(runCatching { db.claim("other",other.id) }.isFailure)
        }
    }
    @Test fun costOnlySupplierReceiptAndConflictingAckAreAtomic() {
        AgentTaskStore(context,"qa-supplier-${UUID.randomUUID()}.db").use { db ->
            val t=task(db);val l=db.claim(t.owner,t.id)
            val p=TrustedCost.basis("https://openrouter.ai/api/v1","any",buildJsonObject {})
            val id=db.beginRequest(l,"main","{}",AgentRequestSelection("p","m","OFF","ref",p))
            val u=TokenUsage(costUsd="0.000000001",costSource="https://openrouter.ai/api/v1/chat/completions")
            db.finishRequest(l,id,u);db.finishRequest(l,id,u)
            assertTrue(runCatching { db.finishRequest(l,id,u.copy(costUsd="1")) }.isFailure)
            val page=db.readUsage("owner",t.id,null,null,null,100)
            assertEquals(1L,page.unknownRequests) // Tokens unknown, reported money is independently known.
            assertEquals("0.000000001",Json.parseToJsonElement(page.entries.single().costJson!!).jsonObject.getValue("amount").jsonPrimitive.content)
        }
    }
}
