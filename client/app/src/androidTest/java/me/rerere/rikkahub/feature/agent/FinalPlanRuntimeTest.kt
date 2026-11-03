package me.rerere.rikkahub.feature.agent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.material.MaterialFiles
import me.rerere.rikkahub.service.ChatService
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/** Original Chat, real tool journals and files; only provider responses are loopback fixtures. */
class FinalPlanRuntimeTest {
    @Test fun correctedMathCanSupersedeSettledRejectedAttemptWithoutRewritingItsJournal() = scenario(false)
    @Test fun twoRealFilesBindAcceptanceToTheirProducingSteps() = scenario(true)
    @Test fun finalProposalCannotBindArtifactPredicateToTextReadbackAgain() = scenario(true,badPlan=true)
    @Test fun repairedPlanCannotBypassFalseWholeGoalJudgment() = scenario(false,semanticAccepted=false)
    @Test fun revokeDuringFinalPlanningCannotPublishAPlanOrGoal() = scenario(false,controlFinal="revoke")
    @Test fun manualPauseDuringFinalPlanningRemainsAnIndependentBlocker() = scenario(false,controlFinal="pause")
    @Test fun aRequiredRejectedClaimCannotDisappearFromWholeGoalEvidence() = scenario(false,semanticAccepted=false,requiredRejected=true)

    private fun scenario(filesCase: Boolean,badPlan: Boolean=false,semanticAccepted: Boolean=true,controlFinal: String?=null,requiredRejected: Boolean=false): Unit = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="org.hxsi.agent.nativeqa")
        val k=GlobalContext.get();val settings=k.get<SettingsStore>()
        val original=withTimeout(20000) { settings.settingsFlow.first { !it.init } }
        val owner=original.activeProfileId;val materials=k.get<MaterialFiles>()
        val id=Uuid.random()
        val marker="FinalPlan_${Uuid.random().toString().take(8)}"
        val main=AtomicInteger();val finals=AtomicInteger();val semantics=AtomicInteger()
        fun step(id: String,tool: String)=buildJsonObject {put("id",id);put("tool",tool);put("purpose","Public final-plan regression $id")}
        fun predicate(id: String,format: String?=null)=buildJsonObject {
            put("stepId",id);put("kind",if(format==null)"tool_verified" else "artifact_content")
            if(format!=null) {put("format",format);put("contains",buildJsonArray {for(value in listOf(marker,"apples","pears","3","5"))add(value)})}
        }
        fun plan(final: Boolean)=buildJsonObject {
            put("steps",buildJsonArray {
                if(filesCase) {add(step("word","create_material_file"));add(step("sheet","create_material_file"));add(step("read_word","read_material_file"));add(step("read_sheet","inspect_material_edit"))}
                else {if(!final)add(step("rejected","math_verify"));add(step("corrected","math_verify"))}
            })
            put("acceptance",buildJsonArray {
                if(filesCase) {add(predicate(if(final && !badPlan)"word" else "read_word","docx"));add(predicate(if(final && !badPlan)"sheet" else "read_sheet","xlsx"))}
                else add(predicate("corrected"))
            })
        }
        fun call(id: String,name: String,args: JsonObject)=buildJsonObject {
            put("id",id);put("type","function");put("function",buildJsonObject {put("name",name);put("arguments",args.toString())})
        }
        fun calls(items: List<JsonObject>)=buildJsonObject {
            put("id","synthetic-final-plan");put("model","qa-final-plan")
            put("choices",buildJsonArray {add(buildJsonObject {
                put("index",0);put("finish_reason","tool_calls");put("message",buildJsonObject {
                    put("role","assistant");put("content","");put("tool_calls",JsonArray(items))
                })
            })})
        }.toString()
        val server=LocalModelServer { body ->
            val messages=Json.parseToJsonElement(body).jsonObject.getValue("messages").jsonArray
            val first=messages.first().jsonObject.getValue("content").toString()
            when {
                "HXAI_FINAL_PLAN_V1" in first -> {
                    finals.incrementAndGet()
                    if(controlFinal!=null)AgentTaskStore(context).use {store ->
                        val task=requireNotNull(store.current(owner,id.toString()))
                        store.command(owner,task.id,Uuid.random().toString(),task.revision,task.fence,controlFinal,"")
                    }
                    LocalModelServer.completion(plan(true).toString())
                }
                "HXAI_TASK_PLAN_V6" in first -> LocalModelServer.completion(plan(false).toString())
                "HXAI_GOAL_VERIFY_V6" in first -> {
                    semantics.incrementAndGet()
                    val evidence=Json.parseToJsonElement(messages.last().jsonObject.getValue("content").jsonPrimitive.content).jsonObject
                    val rejectionPresent=filesCase || evidence.getValue("evidence").jsonArray.any {
                        it.jsonObject["kind"]==JsonPrimitive("settled_rejected_math_not_proof") &&
                            it.jsonObject["input"]?.jsonPrimitive?.content?.contains("3*x")==true
                    }
                    LocalModelServer.completion(buildJsonObject {
                        put("accepted",semanticAccepted && rejectionPresent);put("goalRevision",evidence.getValue("goalRevision"));put("evidenceHash",evidence.getValue("evidenceHash"));put("reason",if(rejectionPresent)"Controlled whole-goal judgment includes rejected claims" else "Missing rejected-claim evidence; cannot independently judge the complete goal")
                    }.toString())
                }
                else -> when(val stage=main.incrementAndGet()) {
                    1 -> if(filesCase) calls(listOf(
                        call("word-call","create_material_file",buildJsonObject {put("format","docx");put("title",marker+"Word");put("content",buildJsonObject {put("body",marker+" apples 3 pears 5")}.toString())}),
                        call("sheet-call","create_material_file",buildJsonObject {put("format","xlsx");put("title",marker+"Sheet");put("content",buildJsonObject {put("rows",buildJsonArray {
                            add(buildJsonArray {add(marker);add("quantity")});add(buildJsonArray {add("apples");add(3)});add(buildJsonArray {add("pears");add(5)})
                        })}.toString())})
                    )) else calls(listOf(call("rejected-call","math_verify",buildJsonObject {put("operation","identity");put("left","x+x");put("right","3*x")})))
                    2 -> if(filesCase) {
                        val word=materials.list(owner).single {it.name==marker+"Word.docx"}
                        val sheet=materials.list(owner).single {it.name==marker+"Sheet.xlsx"}
                        calls(listOf(call("read-word-call","read_material_file",buildJsonObject {put("id",word.id)}),
                            call("read-sheet-call","inspect_material_edit",buildJsonObject {put("id",sheet.id);put("selection","""{"sheet":"Sheet1","offset":0,"count":10}""")})))
                    } else calls(listOf(call("corrected-call","math_verify",buildJsonObject {put("operation","identity");put("left","x+x");put("right","2*x")})))
                    else -> {check(stage==3);LocalModelServer.completion("Visible final response; host must independently accept the complete goal.")}
                }
            }
        }
        val model=Model(modelId="qa-final-plan",abilities=listOf(ModelAbility.TOOL))
        val provider=ProviderSetting.OpenAI(name="QA final plan",baseUrl=server.baseUrl,apiKey="synthetic",models=listOf(model))
        val assistant=Assistant(name=marker,chatModelId=model.id,reasoningLevel=ReasoningLevel.OFF,streamOutput=false,localTools=emptyList(),enableMemory=false)
        val service=k.get<ChatService>();val repo=k.get<ConversationRepository>();val scheduler=AgentScheduler.shared(context)
        try {
            settings.update(original.copy(providers=listOf(provider),assistants=original.assistants+assistant,assistantId=assistant.id,
                chatModelId=model.id,enableSuggestion=false,learnerProfiles=original.learnerProfiles.map {it.copy(memoryEnabled=false)},
                networkSetting=original.networkSetting.copy(proxyUrl="",enableAutoRetry=false)))
            settings.settingsFlow.first {it.assistantId==assistant.id}
            service.addConversationReference(id)
            service.saveConversation(id,Conversation.ofId(id,assistant.id).copy(profileId=owner,title=marker,newConversation=true))
            val goal=if(filesCase) "Create two real editable Word and Excel files containing $marker, apples 3 and pears 5, then independently read both back."
                else if(requiredRejected)"Prove BOTH x+x=3*x and x+x=2*x. Do not weaken or omit either proof."
                else "Use math_verify to prove exactly x+x=2*x. A rejected unrelated 3*x attempt is not proof and may be corrected; do not repeat any side effect."
            service.sendMessageCommitted(id,listOf(UIMessagePart.Text(goal)),Uuid.random())
            withTimeout(60000) {while(true) {
                val current=service.getConversationFlow(id).first();val task=scheduler.current(current)
                if(task!=null && task.state !in setOf("RUNNING","QUEUED") && service.getGenerationJobStateFlow(id).first()==null)break
                delay(30)
            }}
            val conversation=requireNotNull(repo.getConversationById(id,owner));val task=requireNotNull(scheduler.current(conversation))
            val expected=semanticAccepted && !badPlan && controlFinal==null
            assertEquals(expected,task.state=="SUCCEEDED");assertEquals(1,finals.get())
            assertEquals(if(badPlan || controlFinal!=null)0 else 1,semantics.get())
            if(controlFinal!=null)assertEquals(if(controlFinal=="pause")"PAUSED_BY_USER" else "WAITING_AUTHORIZATION",task.state)
            val tools=conversation.currentMessages.flatMap {it.getTools()};assertEquals(if(filesCase)4 else 2,tools.size)
            AgentTaskStore(context).use {store ->
                val plan=store.plan(owner,task.id);assertEquals(expected,plan.acceptanceVerified)
                if(!filesCase) {
                    store.readableDatabase.rawQuery("SELECT state FROM calls WHERE task_id=? AND call_id='rejected-call'",arrayOf(task.id)).use {assertTrue(it.moveToFirst());assertEquals("RETURNED",it.getString(0))}
                    assertEquals(if(controlFinal==null)listOf("corrected") else listOf("rejected","corrected"),plan.steps.map {it.id})
                } else assertEquals(2,materials.list(owner).count {it.output && it.name.startsWith(marker) && it.state=="SUCCEEDED"})
            }
            val out=context.noBackupFilesDir.resolve("final-plan-regressions").apply {mkdirs()}
            out.resolve("$marker.json").writeText(buildJsonObject {put("taskId",task.id);put("owner",owner);put("state",task.state);put("filesCase",filesCase);put("badPlan",badPlan);put("semanticAccepted",semanticAccepted);put("finalRequests",finals.get());put("toolCalls",tools.size)}.toString())
        } finally {
            service.stopGeneration(id);repo.getConversationById(id,owner)?.let {service.deleteConversation(it)}
            service.removeConversationReference(id);settings.update(original);server.close()
        }
    }
}
