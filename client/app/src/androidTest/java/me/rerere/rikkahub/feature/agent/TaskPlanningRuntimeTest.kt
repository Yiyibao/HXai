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
import me.rerere.rikkahub.service.ChatService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class TaskPlanningRuntimeTest {
    @Test fun explicitNullToolNarrativeIsExplanationAndDoesNotBlockRealWordReadback()=scenario(false,false,narrative=true)
    @Test fun realWordReadbackAndEvidenceBoundSemanticAcceptanceCanSucceed()=scenario(false,false)
    @Test fun modelAndHttpSuccessCannotAcceptWrongWordContent()=scenario(true,false)
    @Test fun semanticReceiptForWrongEvidenceHashCannotAcceptCorrectFile()=scenario(false,true)
    @Test fun unfinishedStepOutsideAcceptanceCannotCompleteTheWholeGoal()=scenario(false,false,true,false)
    @Test fun allVerifiedStepsCanCompleteAMultiDocumentGoal()=scenario(false,false,true,true)
    @Test fun everyGeneratedDocumentReachesSemanticAcceptanceEvenWithoutItsOwnPredicate()=
        scenario(false,false,true,true,verifyAllArtifacts=true)
    @Test fun wrongUnreferencedDocumentCannotSatisfyTheMultiDocumentGoal()=
        scenario(false,false,true,true,verifyAllArtifacts=true,wrongSecond=true)
    @Test fun unreferencedDocumentChangedDuringAcceptanceCannotPublishSuccess()=
        scenario(false,false,true,true,corruptSecond=true)
    @Test fun unreferencedDocumentWithAnOwnedSourceCanCompleteTheGoal()=
        scenario(false,false,true,true,verifyAllArtifacts=true,withSource=true)
    @Test fun validSourceVersionChangedDuringAcceptanceCannotPublishSuccess()=
        scenario(false,false,true,true,withSource=true,changeSource=true)
    @Test fun twoSameToolRequestsNeedTwoUnverifiedPlanStepsBeforeAnyWrite()=
        scenario(false,false,true,true,underplanned=true)
    private fun scenario(wrongContent:Boolean,wrongHash:Boolean,secondStep:Boolean=false,executeSecond:Boolean=false,
        verifyAllArtifacts:Boolean=false,wrongSecond:Boolean=false,corruptSecond:Boolean=false,
        withSource:Boolean=false,changeSource:Boolean=false,underplanned:Boolean=false,narrative:Boolean=false):Unit=runBlocking {
        val koin=GlobalContext.get();val settings=koin.get<SettingsStore>()
        val original=withTimeout(20_000) { settings.settingsFlow.first { !it.init } }
        val requirement="required-proof-${Uuid.random()}"
        val content=if(wrongContent) "unrelated file contents" else requirement
        val mainCalls=AtomicInteger()
        val materials=koin.get<MaterialFiles>()
        val titles=listOf("QA Word 0 ${Uuid.random()}","QA Word 1 ${Uuid.random()}")
        val corruption=java.util.concurrent.atomic.AtomicReference<Pair<java.io.File,ByteArray>?>(null)
        val source=if(withSource) materials.runTask(me.rerere.rikkahub.feature.material.MaterialFile(
            Uuid.random().toString(),original.activeProfileId,"QA source ${Uuid.random()}.docx","docx")) { file ->
            me.rerere.rikkahub.feature.material.MaterialWriter.write(file,"docx",requirement,emptyList(),emptyList())
            "Synthetic owned source fixture; not an Office editing-engine test"
        } else null
        val sourceInput = source?.let { version ->
            val page = materials.readSourceTextPage(original.activeProfileId, version.id, version.sha256, 0, 24000)
            buildJsonObject {
                put("id", version.id); put("sha256", version.sha256); put("offset", 0); put("count", 24000)
                put("quote", page.getValue("untrusted_file_data"))
            }
        }
        val server=LocalModelServer { body ->
            val parsed=Json.parseToJsonElement(body).jsonObject
            val messages=parsed.getValue("messages").jsonArray
            val first=messages.first().jsonObject["content"].toString()
            val user=messages.last().jsonObject["content"]?.jsonPrimitive?.content.orEmpty()
            when {
                "HXAI_TASK_PLAN_V6" in first -> LocalModelServer.completion(buildJsonObject {
                    put("steps",buildJsonArray {
                        add(buildJsonObject { put("id","word");put("tool","create_material_file");put("purpose","Write the exact required Word document") })
                        if(narrative) add(buildJsonObject { put("id","explain");put("tool",JsonNull);put("purpose","Explain the verified file scope") })
                        if(secondStep && !underplanned) add(buildJsonObject { put("id","word-second");put("tool","create_material_file");put("purpose","Write the second required Word document") })
                    })
                    put("acceptance",buildJsonArray { add(buildJsonObject { put("stepId","word");put("kind","artifact_content");put("format","docx");put("contains",buildJsonArray { add(requirement) }) }) })
                }.toString())
                "HXAI_GOAL_VERIFY_V6" in first -> {
                    val evidence=Json.parseToJsonElement(user).jsonObject
                    val artifacts=evidence.getValue("evidence").jsonArray.map { it.jsonObject }.filter { "artifactId" in it }
                    val accepted= !verifyAllArtifacts || (artifacts.size==2 &&
                        artifacts.map { it.getValue("artifactId").jsonPrimitive.content }.distinct().size==2 &&
                        artifacts.all { requirement in it.getValue("content").jsonPrimitive.content })
                    if(corruptSecond) {
                        val second=materials.list(original.activeProfileId).single { it.name=="${titles[1]}.docx" }
                        val file=materials.verifiedFile(original.activeProfileId,second.id)
                        corruption.set(file to file.readBytes())
                        file.appendText("synthetic change after independent step verification")
                    }
                    if(changeSource) runBlocking {
                        val originalSource=requireNotNull(source)
                        val file=materials.verifiedFile(original.activeProfileId,originalSource.id)
                        corruption.set(file to file.readBytes())
                        me.rerere.rikkahub.feature.material.MaterialWriter.write(file,"docx","Changed synthetic source version",emptyList(),emptyList())
                        materials.record(originalSource.copy(size=file.length(),sha256=MaterialFiles.digest(file)))
                    }
                    LocalModelServer.completion(buildJsonObject { put("accepted",accepted);put("goalRevision",evidence.getValue("goalRevision"));
                        put("evidenceHash",if(wrongHash) JsonPrimitive("wrong-evidence") else evidence.getValue("evidenceHash"));put("reason","The exact synthetic requirement is present in independently read Word content") }.toString())
                }
                mainCalls.incrementAndGet()==1 -> buildJsonObject {
                    put("id","synthetic-tool");put("model","qa-plan");put("choices",buildJsonArray { add(buildJsonObject {
                        put("index",0);put("finish_reason","tool_calls");put("message",buildJsonObject { put("role","assistant");put("content","")
                            put("tool_calls",buildJsonArray {
                                repeat(if(executeSecond) 2 else 1) { index ->
                                    add(buildJsonObject { put("id","word-once-$index");put("type","function");put("function",buildJsonObject {
                                        put("name","create_material_file");put("arguments",buildJsonObject { put("format","docx");put("title",titles[index]);put("content",buildJsonObject {
                                            put("body",if(index==1 && wrongSecond) "unrelated second document" else content)
                                            if (index == 1 && sourceInput != null) put("sources", buildJsonArray { add(sourceInput) })
                                        }.toString()) }.toString())
                                    }) })
                                }
                            }) })
                    }) })
                }.toString()
                else -> {
                    LocalModelServer.completion("The model claims success, but persistent acceptance is authoritative.")
                }
            }
        }
        val model=Model(modelId="qa-plan",abilities=listOf(ModelAbility.TOOL))
        val provider=ProviderSetting.OpenAI(name="QA plan loopback",baseUrl=server.baseUrl,apiKey="synthetic",models=listOf(model))
        val assistant=Assistant(name="QA native plan",chatModelId=model.id,reasoningLevel=ReasoningLevel.OFF,streamOutput=false,localTools=emptyList())
        val id=Uuid.random();val service=koin.get<ChatService>();val repo=koin.get<ConversationRepository>()
        val scheduler=AgentScheduler.shared(koin.get())
        try {
            settings.update(original.copy(providers=listOf(provider),assistants=original.assistants+assistant,assistantId=assistant.id,chatModelId=model.id,fastModelId=model.id,
                enableSuggestion=false,learnerProfiles=original.learnerProfiles.map { it.copy(memoryEnabled=false) },networkSetting=original.networkSetting.copy(proxyUrl="",enableAutoRetry=false)))
            settings.settingsFlow.first { it.assistantId==assistant.id }
            service.addConversationReference(id);service.saveConversation(id,Conversation.ofId(id,assistant.id).copy(profileId=original.activeProfileId,title="QA goal",newConversation=true))
            val goal=if(secondStep) "Create two separate Word documents, each containing exactly $requirement" else "Create a Word document containing exactly $requirement"
            service.sendMessageCommitted(id,listOf(UIMessagePart.Text(goal+(source?.let { "; use the owned source ${it.id} at SHA256 ${it.sha256}" } ?: ""))),Uuid.random())
            withTimeout(40_000) {
                while(true) {
                    if(!corruptSecond && !changeSource && !underplanned) service.errors.value.firstOrNull { it.conversationId==id }?.let { throw it.error }
                    val current=service.getConversationFlow(id).first()
                    val currentTask=scheduler.current(current)
                    if(currentTask!=null && currentTask.state !in setOf("RUNNING","QUEUED") &&
                        service.getGenerationJobStateFlow(id).first()==null) break
                    delay(20)
                }
            }
            val persisted=requireNotNull(repo.getConversationById(id))
            val results=persisted.currentMessages.flatMap { it.getTools() }.filter { it.toolName=="create_material_file" }
            if(underplanned) {
                val task=requireNotNull(scheduler.current(persisted))
                assertEquals("FAILED_BLOCKED",task.state)
                assertTrue("Underplanned batch must not create either file",materials.list(original.activeProfileId).none { it.name in titles.map { title -> "$title.docx" } })
                assertTrue(results.all { it.output.isEmpty() })
                AgentTaskStore(koin.get()).use { store ->
                    assertFalse(store.plan(task.owner,task.id).acceptanceVerified)
                    store.readableDatabase.rawQuery("SELECT COUNT(*) FROM calls WHERE task_id=? AND tool='create_material_file'",arrayOf(task.id)).use {
                        assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))
                    }
                }
                assertEquals(2,server.bodies.size)
                return@runBlocking
            }
            val expectedFiles=if(executeSecond) 2 else 1
            assertEquals(expectedFiles,results.size)
            val files=results.map { result ->
                val document=result.output.filterIsInstance<UIMessagePart.Document>().single()
                materials.list(original.activeProfileId).single { materials.file(it).toURI().path==java.net.URI(document.url).path }
            }
            assertEquals(expectedFiles,files.map { it.id }.distinct().size)
            files.forEachIndexed { index,file ->
                if(corruptSecond && index==1) {
                    assertNotNull("The semantic request must reach the corruption boundary",corruption.get())
                    assertTrue(runCatching { materials.verifiedFile(original.activeProfileId,file.id) }.isFailure)
                } else {
                    val expectedContent=if(index==1 && wrongSecond) "unrelated second document" else content
                    assertTrue(MaterialReader.read(materials.verifiedFile(original.activeProfileId,file.id),"docx").text.contains(expectedContent))
                }
            }
            if(source!=null) {
                assertEquals(source.id,materials.get(original.activeProfileId,files[1].id).sourceReferences.single().id)
                val actualSource=materials.get(original.activeProfileId,source.id)
                val sourceContent=MaterialReader.read(materials.verifiedFile(original.activeProfileId,source.id),"docx").text
                if(changeSource) {
                    assertNotEquals(source.sha256,actualSource.sha256)
                    assertTrue(sourceContent.contains("Changed synthetic source version"))
                } else {
                    assertEquals(source.sha256,actualSource.sha256)
                    assertTrue(sourceContent.contains(requirement))
                }
            }
            val task=requireNotNull(scheduler.current(persisted))
            val incomplete=secondStep && !executeSecond
            assertEquals(if(corruptSecond || changeSource) "FAILED_BLOCKED" else if(wrongContent || wrongHash || incomplete || wrongSecond) "COMPLETED_UNVERIFIED" else "SUCCEEDED",task.state)
            AgentTaskStore(koin.get()).use { store ->
                val plan=store.plan(task.owner,task.id)
                assertEquals(if(!secondStep) listOf("VERIFIED") else if(executeSecond) listOf("VERIFIED","VERIFIED") else listOf("VERIFIED","PLANNED"),
                    plan.steps.map { it.state })
                assertEquals(!wrongContent && !wrongHash && !incomplete && !wrongSecond && !corruptSecond && !changeSource,plan.acceptanceVerified)
                store.readableDatabase.rawQuery("SELECT role,provenance,prompt_tokens FROM usage WHERE task_id=? ORDER BY rowid",arrayOf(task.id)).use { rows ->
                    val roles=mutableListOf<String>();while(rows.moveToNext()) { roles+=rows.getString(0);assertEquals("unknown",rows.getString(1));assertTrue(rows.isNull(2)) }
                    assertEquals(if(wrongContent || incomplete) listOf("main","planning","main") else listOf("main","planning","main","semantic_acceptance"),roles)
                }
                store.readableDatabase.rawQuery("SELECT COUNT(*) FROM calls WHERE task_id=? AND tool='create_material_file'",arrayOf(task.id)).use { assertTrue(it.moveToFirst());assertEquals(expectedFiles,it.getInt(0)) }
            }
        } finally {
            corruption.get()?.let { (file,bytes) -> file.writeBytes(bytes) }
            if(changeSource) source?.let { materials.record(it) }
            service.stopGeneration(id);service.removeConversationReference(id)
            repo.getConversationById(id)?.let { repo.deleteConversation(it) }
            settings.update(original);settings.settingsFlow.first { it.assistantId==original.assistantId };server.close()
        }
    }
}
