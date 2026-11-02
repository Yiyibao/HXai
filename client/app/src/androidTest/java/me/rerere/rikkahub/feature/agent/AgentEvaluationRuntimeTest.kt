package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.feature.material.MaterialFiles
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.flutter.StudyHostFacade
import io.flutter.plugin.common.MethodChannel
import org.koin.core.context.GlobalContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class AgentEvaluationRuntimeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(test: suspend (AgentTaskStore,MaterialFiles,String)->Unit)=runBlocking {
        val owner=UUID.randomUUID().toString();val name="qa-evaluation-$owner.db"
        try { AgentTaskStore(context,name).use { test(it,MaterialFiles(context),owner) } }
        finally { SQLiteCleanup.delete(context.noBackupFilesDir.resolve(name));context.filesDir.resolve("materials/$owner").deleteRecursively() }
    }
    private fun task(s: AgentTaskStore,o: String)=s.accept(o,Uuid.random().toString(),UUID.randomUUID().toString(),"公开题目🙂",
        "MUST_NOT_EXPORT_PRIVATE_VAULT","locked-provider","locked-model","HIGH","accepted-config-revision")
    private fun snapshot(s: AgentTaskStore,t: AgentTask)=AgentEvaluationSnapshot.assemble(
        s.captureEvaluation(t.owner,t.id,t.revision,t.fence),AgentEvaluationSnapshot.answer(null,t.owner,t.id,t.conversationId))

    @Test fun noToolTaskExportsLockedMetadataAndDoesNotLoadPrivateConfiguration()=fixture { s,_,o ->
        val t=task(s,o);val before=s.snapshot(o,t.id)
        val value=s.captureEvaluation(o,t.id,t.revision,t.fence)
        assertFalse(value.toString().contains("MUST_NOT_EXPORT_PRIVATE_VAULT"))
        assertEquals("locked-model",value.getValue("task").jsonObject.getValue("modelId").jsonPrimitive.content)
        assertEquals(0,value.getValue("calls").jsonArray.size)
        assertEquals(before,s.snapshot(o,t.id))
        assertTrue(runCatching { s.captureEvaluation("foreign",t.id,t.revision,t.fence) }.isFailure)
        assertTrue(runCatching { s.captureEvaluation(o,t.id,99,t.fence) }.isFailure)
    }
    @Test fun selectedAnswerExcludesOtherTasksBranchesAttachmentsAndReasoning()=fixture { s,_,o ->
        val t=task(s,o)
        val selected=UIMessage(role=MessageRole.ASSISTANT,generationTaskId=t.id,parts=listOf(
            UIMessagePart.Text("原回答🙂"),UIMessagePart.Reasoning("HIDDEN_REASONING"),UIMessagePart.Image("PRIVATE_IMAGE")))
        val conv=Conversation.ofId(Uuid.parse(t.conversationId)).copy(profileId=o,messageNodes=listOf(
            MessageNode(messages=listOf(selected,selected.copy(id=Uuid.random(),parts=listOf(UIMessagePart.Text("OLD_BRANCH"))))),
            MessageNode(messages=listOf(selected.copy(id=Uuid.random(),generationTaskId="other-task",parts=listOf(UIMessagePart.Text("OTHER_TASK")))))))
        val answer=AgentEvaluationSnapshot.answer(conv,o,t.id,t.conversationId)
        assertEquals("原回答🙂",answer.getValue("messages").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
        listOf("HIDDEN_REASONING","PRIVATE_IMAGE","OLD_BRANCH","OTHER_TASK").forEach { assertFalse(answer.toString().contains(it)) }
        assertTrue(runCatching { AgentEvaluationSnapshot.answer(conv,"foreign",t.id,t.conversationId) }.isFailure)
    }
    @Test fun allCallsAndUnknownUsageSurviveWithoutReleasingSlotOrGrant()=fixture { s,_,o ->
        val t=task(s,o);val lease=s.claim(o,t.id)
        repeat(31) { i -> s.beginCall(lease,"read-$i","get_time_info","{}");s.finishCall(lease,"read-$i",AgentTaskStore.hash("receipt"),true) }
        s.beginCall(lease,"unknown","create_material_file","{}")
        s.finish(lease,"INTERRUPTED")
        val now=s.get(o,t.id);val before=s.snapshot(o,t.id)
        val value=s.captureEvaluation(o,t.id,now.revision,now.fence)
        assertEquals(32,value.getValue("calls").jsonArray.size)
        assertEquals("UNKNOWN",value.getValue("calls").jsonArray.last().jsonObject.getValue("state").jsonPrimitive.content)
        assertEquals(before,s.snapshot(o,t.id))
        val next=task(s,o);assertTrue(runCatching { s.claim(o,next.id) }.isFailure)
    }
    @Test fun lostExportAckReadsOriginalBytesEvenWhenTaskHasChanged()=fixture { s,f,o ->
        val t=task(s,o);val request=UUID.randomUUID().toString();var captures=0
        suspend fun export(crash: Boolean=false)=AgentEvaluationExport.export(f,o,t.id,request,t.revision,t.fence,
            { captures++;snapshot(s,t) },{}, { if(crash && it=="after_move") error("lost ack") })
        assertTrue(runCatching { export(true) }.isFailure)
        val discovered=AgentEvaluationExport.last(MaterialFiles(context),o,t.id)!!
        assertEquals(request,discovered["requestId"]);assertEquals(t.revision.toString(),discovered["revision"])
        assertNull(AgentEvaluationExport.last(f,UUID.randomUUID().toString(),t.id))
        val original=f.file(f.get(o,AgentEvaluationExport.artifactId(o,request))).readBytes()
        s.reviseQueued(o,t.id,"用户追加要求")
        val result=export()
        assertEquals(1,captures);assertEquals("SUCCEEDED",result.state)
        assertTrue(original.contentEquals(f.verifiedFile(o,result.id).readBytes()))
        assertTrue(runCatching { AgentEvaluationExport.export(f,o,t.id,request,99,t.fence,{ error("recapture") },{}) }.isFailure)
    }
    @Test fun incompleteCorruptAndDeletedExportsNeverRecaptureOrOverwrite()=fixture { s,f,o ->
        val t=task(s,o)
        val pending=UUID.randomUUID().toString()
        assertTrue(runCatching { AgentEvaluationExport.export(f,o,t.id,pending,0,0,{ snapshot(s,t) },{}, { error("after intent") }) }.isFailure)
        assertTrue(runCatching { AgentEvaluationExport.export(f,o,t.id,pending,0,0,{ error("must not recapture") },{}) }.isFailure)
        val request=UUID.randomUUID().toString()
        val item=AgentEvaluationExport.export(f,o,t.id,request,0,0,{ snapshot(s,t) },{})
        val original=f.verifiedFile(o,item.id).readBytes()
        f.file(item).appendText("damaged")
        assertTrue(runCatching { AgentEvaluationExport.export(f,o,t.id,request,0,0,{ error("must not recapture") },{}) }.isFailure)
        f.file(item).writeBytes(original)
        f.delete(o,item.id,item.sha256)
        assertEquals(pending,AgentEvaluationExport.last(f,o,t.id)!!["requestId"])
        assertTrue(runCatching { AgentEvaluationExport.export(f,o,t.id,request,0,0,{ error("must not recapture") },{}) }.isFailure)
        assertEquals(item.sha256,MaterialFiles.digest(f.file(item)))
    }
    @Test fun epochChangeAfterMoveWithholdsReceiptButAllowsOriginalOwnedReadback()=fixture { s,f,o ->
        val t=task(s,o);val request=UUID.randomUUID().toString();var active=true;var captures=0
        suspend fun export(change: Boolean)=AgentEvaluationExport.export(f,o,t.id,request,0,0,
            { captures++;snapshot(s,t) },{ check(active) },{ if(change && it=="after_move") active=false })
        assertTrue(runCatching { export(true) }.isFailure)
        assertEquals("FAILED",f.get(o,AgentEvaluationExport.artifactId(o,request)).state)
        active=true;assertEquals("SUCCEEDED",export(false).state);assertEquals(1,captures)
    }
    @Test fun oversizedEvidenceIsRejectedWholeBeforeExportIntent()=fixture { s,f,o ->
        val t=task(s,o)
        s.writableDatabase.execSQL("UPDATE plans SET goal=? WHERE task_id=?",arrayOf("x".repeat(262145),t.id))
        val request=UUID.randomUUID().toString()
        assertTrue(runCatching { AgentEvaluationExport.export(f,o,t.id,request,0,0,{ snapshot(s,t) },{}) }.isFailure)
        assertTrue(f.list(o).isEmpty())
    }
    @Test fun actualFrontendCallbackExportsSelectedAnswerAndRejectsMissingConsentAndOldEpoch():Unit=runBlocking {
        check(context.packageName=="org.hxsi.agent.nativeqa")
        val koin=GlobalContext.get();val settings=koin.get<SettingsStore>()
        val owner=settings.settingsFlow.first { !it.init }.activeProfileId
        val files=koin.get<MaterialFiles>();val repo=koin.get<ConversationRepository>()
        val host=StudyHostFacade(context,settings,repo,koin.get(),koin.get(),files,koin.get(),pickFiles={ emptyList() },foregroundConfirmationAllowed={ true })
        val requests=mutableListOf<String>()
        var conversation: Conversation?=null
        try {
            val epoch=host.getBootstrap(owner,1).contextEpoch
            AgentTaskStore(context).use { store ->
                val task=task(store,owner)
                conversation=Conversation.ofId(Uuid.parse(task.conversationId)).copy(profileId=owner,messageNodes=listOf(
                    MessageNode(messages=listOf(UIMessage(role=MessageRole.ASSISTANT,generationTaskId=task.id,
                        parts=listOf(UIMessagePart.Text("真实Host读回的合成公开回答")),generationState="COMPLETED")))))
                repo.insertConversation(conversation!!)
                suspend fun call(consent: Boolean,selectedEpoch: Long=epoch): Map<*,*> {
                    val request=UUID.randomUUID().toString();requests+=request
                    val reply=CompletableDeferred<Map<*,*>>()
                    host.handleFrontendFeature("evaluation.export",mapOf("profileId" to owner,"epoch" to selectedEpoch,"taskId" to task.id,
                        "revision" to 0L,"fence" to 0L,"requestId" to request,"consent" to consent),object: MethodChannel.Result {
                        override fun success(result: Any?) { reply.complete(result as Map<*,*>) }
                        override fun error(code: String,message: String?,details: Any?) { reply.completeExceptionally(IllegalStateException(code)) }
                        override fun notImplemented() { reply.completeExceptionally(IllegalStateException("not implemented")) }
                    })
                    return withTimeout(30000) { reply.await() }
                }
                assertTrue(runCatching { call(false) }.isFailure)
                assertTrue(runCatching { call(true,epoch-1) }.isFailure)
                val result=call(true)
                val artifact=files.verifiedFile(owner,result["artifactId"] as String)
                assertTrue(artifact.readText().contains("真实Host读回的合成公开回答"))
                assertFalse(artifact.readText().contains("MUST_NOT_EXPORT_PRIVATE_VAULT"))
                assertEquals(result["sha256"],MaterialFiles.digest(artifact))
                assertEquals("QUEUED",store.get(owner,task.id).state)
            }
        } finally {
            host.close();conversation?.let { repo.deleteConversation(it) }
            requests.forEach { request ->
                val id=AgentEvaluationExport.artifactId(owner,request)
                // Only fixture-created JSON/metadata in the synthetic QA package.
                context.filesDir.resolve("materials/$owner/$id.json").delete()
                context.filesDir.resolve("materials/$owner/$id.meta.json").delete()
            }
        }
    }
}
