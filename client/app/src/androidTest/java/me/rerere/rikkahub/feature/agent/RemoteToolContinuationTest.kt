package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolExecutionState
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.material.*
import me.rerere.rikkahub.flutter.StudyHostFacade
import me.rerere.rikkahub.service.ChatService
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import kotlin.uuid.Uuid

/** Controlled document transport is not an Office-engine test. Model dispatch is real localhost HTTP. */
@RunWith(AndroidJUnit4::class)
class RemoteToolContinuationTest {
    @Test fun asynchronousPdfResumesOriginalTaskModelAndUserGoalRatherThanCreatingAnotherUserTurn()=scenario(false)
    @Test fun explicitRetryOfKnownFailureKeepsOriginalTaskModelAndDoesNotInsertUserTurn()=scenario(true)
    @Test fun pendingConversionCommitCannotBeRolledBackByConcurrentReader() {
        val context = GlobalContext.get().get<android.content.Context>()
        check(context.packageName == "org.hxsi.agent.nativeqa")
        val office = GlobalContext.get().get<DocumentServiceRepository>().officeConversions
        val task = OfficeConversionTask(Uuid.random().toString(), Uuid.random().toString(), "a".repeat(64),
            "docx", "QA atomic conversion", "synthetic configuration", "synthetic-1", state = "SUCCEEDED")
        val file = File(context.noBackupFilesDir, "document-service/default-learner/${task.id}.office.json")
        file.parentFile!!.mkdirs()
        val atomic = android.util.AtomicFile(file)
        fun bytes(value: OfficeConversionTask) = Json.encodeToString(value).toByteArray(Charsets.UTF_8)
        val initial = atomic.startWrite()
        initial.write(bytes(task)); atomic.finishWrite(initial)
        val started = java.util.concurrent.CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference<Result<List<OfficeConversionTask>>>()
        var reader: Thread? = null
        try {
            // Same monitor as the repository's short metadata writer; no network is held.
            synchronized(office) {
                val pending = atomic.startWrite()
                pending.write(bytes(task.copy(downloaded = true)))
                try {
                    reader = kotlin.concurrent.thread(name = "qa-conversion-reader") {
                        started.countDown()
                        result.set(runCatching { office.tasks("default-learner") })
                    }
                    check(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
                    // Old readers finish and roll back the pending write. Safe readers wait
                    // at tasks()' monitor until commit. Accept either path for orchestration;
                    // the assertion below checks the consumer-visible committed state.
                    while (reader.isAlive && !(reader.state == Thread.State.BLOCKED &&
                        reader.stackTrace.firstOrNull()?.let {
                            it.className == OfficeConversionRepository::class.java.name && it.methodName == "tasks"
                        } == true)) {
                        check(System.nanoTime() < deadline) { "Reader neither completed nor waited for metadata commit" }
                        Thread.yield()
                    }
                    atomic.finishWrite(pending)
                } catch (error: Throwable) {
                    atomic.failWrite(pending); throw error
                }
            }
            reader!!.join(5_000)
            check(!reader.isAlive)
            result.get().getOrThrow()
            assertTrue("Concurrent read must not discard the completed artifact commit",
                office.task("default-learner", task.id).downloaded)
        } finally {
            reader?.join(5_000)
            listOf(file, File(file.path + ".new"), File(file.path + ".bak")).forEach { it.delete() }
        }
    }
    private fun scenario(retryKnownFailure: Boolean)=runBlocking {
        val koin=GlobalContext.get();val context=koin.get<android.content.Context>()
        check(context.packageName=="org.hxsi.agent.nativeqa")
        val settings=koin.get<SettingsStore>();val original=settings.settingsFlow.first { !it.init }
        val materials=koin.get<MaterialFiles>();val documents=koin.get<DocumentServiceRepository>()
        val office=documents.officeConversions
        val repo=koin.get<ConversationRepository>();val service=koin.get<ChatService>()
        val server=LocalModelServer { body ->
            if(body.contains("HXAI_TASK_PLAN_V6")) LocalModelServer.completion(
                Json.encodeToString(AgentPlan(steps=listOf(PlanStep("pdf","convert_material_to_pdf","Retry the explicitly authorized original source")),
                    explanation="Synthetic transport wiring test; goal quality is not accepted by an empty predicate list")))
            else LocalModelServer.completion("Known PDF receipt received; do not upload or replace the original source again.")
        }
        val locked=Model(modelId="qa-original-document-model")
        val changed=Model(modelId="qa-changed-picker-model")
        val provider=ProviderSetting.OpenAI(name="QA local document continuation",baseUrl=server.baseUrl,apiKey="synthetic",models=listOf(locked,changed))
        val assistant=Assistant(name="QA document continuation",chatModelId=locked.id,reasoningLevel=ReasoningLevel.OFF,streamOutput=false,localTools=emptyList())
        val accepted=original.copy(providers=listOf(provider),assistants=original.assistants+assistant,assistantId=assistant.id,
            chatModelId=locked.id,enableSuggestion=false,learnerProfiles=original.learnerProfiles.map { it.copy(memoryEnabled=false) },
            networkSetting=original.networkSetting.copy(proxyUrl="",enableAutoRetry=false))
        val owner=original.activeProfileId;val id=Uuid.random()
        val user=UIMessage.user("将原Word文件转换为PDF，保留原任务要求")
        val base=Conversation.ofId(id,assistant.id).copy(profileId=owner,title="QA async source-bound PDF",newConversation=true,
            messageNodes=listOf(MessageNode(messages=listOf(user))))
        val configFile=File(context.noBackupFilesDir,"document-service/$owner/config.json")
        val oldConfig=configFile.takeIf { it.exists() }?.readBytes()
        val httpField=OfficeConversionRepository::class.java.getDeclaredField("http").apply { isAccessible=true }
        val docsHttp=DocumentServiceRepository::class.java.getDeclaredField("http").apply { isAccessible=true }
        val originalHttp=httpField.get(office);val originalDocsHttp=docsHttp.get(documents)
        val pdf=File(context.cacheDir,"qa-pdf-${Uuid.random()}.pdf")
        val remote=Uuid.random().toString()
        val uploads=java.util.concurrent.atomic.AtomicInteger()
        var source:MaterialFile?=null;var artifact:MaterialFile?=null
        var facade:StudyHostFacade?=null
        try {
            settings.update(accepted);settings.settingsFlow.first { it.chatModelId==locked.id }
            source=materials.runTask(MaterialFile(Uuid.random().toString(),owner,"QA original.docx","docx")) { file ->
                MaterialWriter.write(file,"docx","Only synthetic source contents",emptyList(),emptyList())
            }
            MaterialWriter.write(pdf,"pdf","Only synthetic source contents",emptyList(),emptyList())
            val actualSource=requireNotNull(source)
            val client=OkHttpClient.Builder().addInterceptor { chain ->
                val request=chain.request();val path=request.url.encodedPath
                val response=when {
                    path.endsWith("/capabilities") -> """{"officePdf":{"ready":true,"rendererVersion":"synthetic-1"}}"""
                    path.endsWith("/conversions") -> {
                        uploads.incrementAndGet()
                        """{"taskId":"$remote","operation":"office_pdf","state":"QUEUED","rendererVersion":"synthetic-1"}"""
                    }
                    path.endsWith("/manifest") -> Json.encodeToString(OfficePdfManifest(1,"office_pdf",actualSource.sha256,"docx","LibreOffice","synthetic-1",OfficeRenderOptions(),1,
                        listOf("Synthetic transport; Office engine quality NOT_RUN"),listOf(DocumentAsset("pdf","output.pdf","application/pdf",pdf.length(),MaterialFiles.digest(pdf)))))
                    path.endsWith("/assets/pdf") -> null
                    else -> """{"taskId":"$remote","operation":"office_pdf","state":"${if(request.method=="DELETE") "EXPIRED" else if(retryKnownFailure && uploads.get()==1) "FAILED" else "SUCCEEDED"}"}"""
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(response?.toResponseBody("application/json".toMediaType()) ?: pdf.readBytes().toResponseBody("application/pdf".toMediaType())).build()
            }.build()
            httpField.set(office,client);docsHttp.set(documents,client)
            documents.configure(owner,"https://qa.example.org","q".repeat(32),true)
            service.addConversationReference(id);service.saveConversation(id,base)
            val scheduler=AgentScheduler.shared(context)
            val task=scheduler.accept(base,user.id.toString(),Json.encodeToString(user.parts),accepted)
            val input=buildJsonObject { put("id",actualSource.id);put("expected_sha256",actualSource.sha256);put("title","QA PDF") }.toString()
            AgentTaskStore(context).use { store ->
                // Explicit trusted fixture grant; no model or document supplies this authority.
                store.writableDatabase.execSQL("INSERT INTO grants(task_id,capability,scope,grant_epoch,input_hash) VALUES(?,'document.remote',?,1,?)",arrayOf(task.id,owner,AgentTaskStore.hash(input)))
                val lease=store.claim(owner,task.id);store.beginCall(lease,"office-original","convert_material_to_pdf",input)
                val pending=office.submit(owner,actualSource.id,actualSource.sha256,"QA PDF")
                val output=listOf<UIMessagePart>(UIMessagePart.Text(buildJsonObject {
                    put("operation","office_pdf");put("task_id",pending.id);put("source_id",actualSource.id);put("state",pending.state);put("downloaded",false)
                }.toString()))
                val tool=UIMessagePart.Tool(toolCallId="office-original",toolName="convert_material_to_pdf",input=input,output=output,executionState=ToolExecutionState.COMPLETED)
                service.saveConversation(id,requireNotNull(repo.getConversationById(id)).copy(messageNodes=base.messageNodes+MessageNode(messages=listOf(
                    UIMessage(role=me.rerere.ai.core.MessageRole.ASSISTANT,parts=listOf(tool),generationState="COMPLETED")))))
                val receipt=Json.encodeToString(output);store.finishCall(lease,"office-original",AgentTaskStore.hash(receipt),false,receipt)
                store.finish(lease,"COMPLETED_UNVERIFIED")
            }
            settings.update(accepted.copy(chatModelId=changed.id,assistants=accepted.assistants.map {
                if(it.id==assistant.id) it.copy(chatModelId=changed.id) else it
            }))
            settings.settingsFlow.first { it.chatModelId==changed.id }
            facade=StudyHostFacade(context,settings,koin.get(),service,koin.get(),materials,koin.get(),pickFiles={ emptyList() },documents=documents)
            val bootstrap=facade.getBootstrap(owner,1L)
            if(retryKnownFailure) {
                val failed=office.refresh(owner,office.tasks(owner).single { it.sourceId==actualSource.id }.id)
                assertEquals("FAILED",failed.state)
                assertTrue(facade.documentTaskAction(owner,id.toString(),requireNotNull(repo.getConversationById(id)).currentMessages.last().id.toString(),
                    "office-original","retry",bootstrap.contextEpoch).success)
            }
            facade.watchConversation(owner,id.toString(),bootstrap.contextEpoch)
            withTimeout(30_000) { while(server.bodies.isEmpty()) delay(20) }
            withTimeout(30_000) { while(service.getGenerationJobStateFlow(id).first()!=null) delay(20) }
            server.bodies.forEach { assertEquals("qa-original-document-model",Json.parseToJsonElement(it).jsonObject.getValue("model").jsonPrimitive.content) }
            assertEquals(if(retryKnownFailure) 2 else 1,uploads.get())
            val after=requireNotNull(repo.getConversationById(id))
            assertEquals(listOf(user.id),after.currentMessages.filter { it.role==me.rerere.ai.core.MessageRole.USER }.map { it.id })
            assertEquals(task.id,requireNotNull(scheduler.current(after)).id)
            withTimeout(30_000) {
                while(artifact==null) {
                    artifact=office.tasks(owner).filter { it.sourceId==actualSource.id && it.downloaded }
                        .firstNotNullOfOrNull { office.artifact(owner,it.id) }
                    if(artifact==null) delay(20)
                }
            }
            val actualArtifact=requireNotNull(artifact)
            assertEquals(actualSource.id,actualArtifact.sourceId)
            assertEquals(MaterialFiles.digest(pdf),actualArtifact.sha256)
            if(retryKnownFailure) {
                assertEquals(1,office.tasks(owner).count { it.sourceId==actualSource.id && it.state=="FAILED" && !it.downloaded })
                assertEquals(1,office.tasks(owner).count { it.sourceId==actualSource.id && it.downloaded })
            }
        } finally {
            facade?.close();service.stopGeneration(id);service.removeConversationReference(id)
            repo.getConversationById(id)?.let { repo.deleteConversation(it) }
            httpField.set(office,originalHttp);docsHttp.set(documents,originalDocsHttp)
            if(oldConfig==null) configFile.delete() else configFile.writeBytes(oldConfig)
            source?.let { materials.file(it).delete();File(context.filesDir,"materials/$owner/${it.id}.meta.json").delete() }
            artifact?.let { materials.file(it).delete();File(context.filesDir,"materials/$owner/${it.id}.meta.json").delete() }
            pdf.delete();settings.update(original);settings.settingsFlow.first { it.assistantId==original.assistantId };server.close()
        }
    }
}
