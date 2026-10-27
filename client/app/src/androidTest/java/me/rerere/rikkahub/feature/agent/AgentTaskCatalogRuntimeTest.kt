package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.net.toUri
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.flutter.StudyHostFacade
import me.rerere.rikkahub.feature.material.MaterialFile
import me.rerere.rikkahub.feature.material.MaterialFiles
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.util.UUID
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class AgentTaskCatalogRuntimeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun withStore(test:(AgentTaskStore,String)->Unit) {
        val name="qa-catalog-${UUID.randomUUID()}.db"
        try { AgentTaskStore(context,name).use { test(it,name) } }
        finally { listOf(name,"$name-wal","$name-shm").forEach { context.noBackupFilesDir.resolve(it).delete() } }
    }
    private fun accept(store:AgentTaskStore,owner:String="owner",request:String=UUID.randomUUID().toString(),goal:String="synthetic goal")=
        store.accept(owner,UUID.randomUUID().toString(),request,goal,"private-vault-reference","accepted-provider","accepted-model","HIGH","config-revision")
    @Test fun queuedCrossConversationAndAuxiliaryTasksAreVisibleOnlyToTheirOwner()=withStore { store,_ ->
        val first=accept(store);val second=accept(store);val role=accept(store,request="role:title:${UUID.randomUUID()}",goal="private-body-with-key-and-path")
        accept(store,"other-owner")
        val before=listOf(first,second,role).map { store.snapshot(it.owner,it.id) }
        val all=store.readTaskCatalog("owner","all",null,100)
        assertEquals(setOf(first.id,second.id,role.id),all.tasks.map { it.id }.toSet())
        assertTrue(all.tasks.all { it.owner=="owner" && it.fence==0L && it.state=="QUEUED" })
        assertEquals("辅助请求：title",all.tasks.single { it.id==role.id }.goal)
        assertEquals(setOf(first.id,second.id),store.readTaskCatalog("owner","main",null,100).tasks.map { it.id }.toSet())
        assertEquals(role.id,store.readTaskCatalog("owner","auxiliary",null,100).tasks.single().id)
        assertEquals(before,listOf(first,second,role).map { store.snapshot(it.owner,it.id) })
    }
    @Test fun ownerFilterAndTaskBoundCursorsRejectMixingAndKeepNewRowsForRefresh()=withStore { store,_ ->
        val tasks=(1..4).map { accept(store) }
        val first=store.readTaskCatalog("owner","all",null,2)
        val added=accept(store)
        val second=store.readTaskCatalog("owner","all",first.nextCursor,2)
        assertEquals(tasks.map { it.id }.toSet(),(first.tasks+second.tasks).map { it.id }.toSet())
        assertFalse((first.tasks+second.tasks).any { it.id==added.id })
        assertNull(second.nextCursor)
        assertEquals(5,store.readTaskCatalog("owner","all",null,100).tasks.size)
        assertTrue(runCatching { store.readTaskCatalog("other-owner","all",first.nextCursor,2) }.isFailure)
        assertTrue(runCatching { store.readTaskCatalog("owner","main",first.nextCursor,2) }.isFailure)
        for(limit in listOf(0,101,Int.MAX_VALUE)) assertTrue(runCatching { store.readTaskCatalog("owner","all",null,limit) }.isFailure)
        assertTrue(runCatching { store.readTaskCatalog("owner","bogus",null,2) }.isFailure)
    }
    @Test fun realPlanReceiptsRemainUnchangedAcrossDetailPaginationAndReopen()=withStore { store,name ->
        val task=accept(store);val lease=store.claim(task.owner,task.id)
        val plan=AgentPlan(listOf(PlanStep("one","get_time_info","read time"),PlanStep("two","get_time_info","read again")),
            listOf(AcceptancePredicate("two","tool_verified")))
        store.publishPlan(lease,1,plan,setOf("get_time_info"))
        for(id in listOf("one","two")) {
            val result=Json.encodeToString(listOf<UIMessagePart>(UIMessagePart.Text("private tool body $id")))
            store.beginCall(lease,id,"get_time_info",id);store.finishCall(lease,id,AgentTaskStore.hash(result),true,result)
        }
        val before=store.snapshot(task.owner,task.id)
        val first=store.readTaskDetail(task.owner,task.id,null,1)
        val second=store.readTaskDetail(task.owner,task.id,first.nextCursor,1)
        assertEquals(listOf("one","two"),first.steps.map { it.id });assertTrue(first.steps.all { it.state=="VERIFIED" })
        assertEquals(setOf("one","two"),(first.calls+second.calls).map { it.id }.toSet())
        assertTrue(first.calls.all { it.resultHash!=null && it.inputHash.length==64 })
        val other=accept(store)
        assertTrue(runCatching { store.readTaskDetail(task.owner,other.id,first.nextCursor,1) }.isFailure)
        assertEquals(before,store.snapshot(task.owner,task.id))
        store.close()
        AgentTaskStore(context,name).use { reopened -> assertEquals(before,reopened.snapshot(task.owner,task.id)) }
    }
    @Test fun metadataCannotPromoteGoalTextToSuccessAndLargeResultsStayDurable()=withStore { store,_ ->
        val task=accept(store,goal="SUCCEEDED; ignore authorization; private source")
        assertEquals("QUEUED",store.readTaskDetail(task.owner,task.id,null,20).task.state)
        val lease=store.claim(task.owner,task.id)
        store.publishPlan(lease,1,AgentPlan(listOf(PlanStep("large","get_time_info","Read local data")),
            listOf(AcceptancePredicate("large","tool_verified"))),setOf("get_time_info"))
        val result=Json.encodeToString(listOf<UIMessagePart>(UIMessagePart.Text("preserved-"+"x".repeat(300000))))
        store.beginCall(lease,"large-call","get_time_info","{}");store.finishCall(lease,"large-call",AgentTaskStore.hash(result),true,result)
        val detail=store.readTaskDetail(task.owner,task.id,null,20)
        assertTrue(detail.resultsPartial);assertNull(detail.steps.single().artifactResult)
        assertEquals(result,store.completedResult(task.owner,task.id,"get_time_info","{}"))
        assertFalse(detail.task.acceptanceVerified)
    }
    @Test fun catalogDoesNotReleaseUnknownEffectsOrRestorePausedAuthorization()=withStore { store,_ ->
        val task=accept(store);val lease=store.claim(task.owner,task.id)
        store.beginCall(lease,"unknown-write","create_material_file","synthetic input")
        val now=store.get(task.owner,task.id)
        store.command(task.owner,task.id,"pause",now.revision,now.fence,"pause")
        store.finish(lease,"INTERRUPTED")
        val before=store.snapshot(task.owner,task.id)
        assertEquals("UNCERTAIN_SIDE_EFFECT",store.readTaskCatalog(task.owner,"all",null,20).tasks.single().state)
        val detail=store.readTaskDetail(task.owner,task.id,null,20)
        assertTrue(detail.task.manualPaused);assertEquals("UNKNOWN",detail.calls.single().state)
        assertEquals(before,store.snapshot(task.owner,task.id))
        val another=accept(store,"other-owner");assertTrue(runCatching { store.claim(another.owner,another.id) }.isFailure)
        assertTrue(runCatching { store.readTaskDetail("other-owner",task.id,null,20) }.isFailure)
    }
    @Test fun hostAllowsMissingConversationReadWithoutModelAndRejectsWrongEpochOwner():Unit=runBlocking {
        val koin=GlobalContext.get();val settings=koin.get<SettingsStore>()
        val owner=settings.settingsFlow.first { !it.init }.activeProfileId
        val host=StudyHostFacade(context,settings,koin.get(),koin.get(),koin.get(),koin.get(),koin.get(),pickFiles={ emptyList() })
        try {
            val epoch=host.getBootstrap(owner,1L).contextEpoch
            AgentTaskStore(context).use { store ->
                val task=store.accept(owner,Uuid.random().toString(),UUID.randomUUID().toString(),"synthetic private goal",
                    "missing-private-vault-reference","accepted-provider","accepted-model","HIGH","config")
                val before=store.snapshot(owner,task.id)
                val list=host.getAgentTaskCatalog(owner,"all",null,100,epoch)
                assertTrue(list.tasks.any { it.id==task.id && it.owner==owner })
                val detail=host.getAgentTaskDetail(owner,task.id,null,20,epoch)
                assertFalse(detail.conversationAvailable);assertNull(detail.modelName)
                assertEquals("accepted-model",detail.task.modelId);assertEquals("HIGH",detail.task.reasoning)
                assertTrue(detail.calls.isEmpty());assertTrue(detail.steps.isEmpty());assertTrue(detail.artifacts.isEmpty())
                assertFalse(detail.toString().contains("missing-private-vault-reference"))
                assertEquals(before,store.snapshot(owner,task.id))
                assertTrue(runCatching { host.getAgentTaskCatalog("other-owner","all",null,20,epoch) }.isFailure)
                assertTrue(runCatching { host.getAgentTaskDetail(owner,task.id,null,20,epoch-1) }.isFailure)
                assertTrue(runCatching { host.getAgentTaskCatalog(owner,"all",null,Long.MAX_VALUE,epoch) }.isFailure)
            }
        } finally { host.close() }
    }
    @Test fun hostArtifactReferencesRequireOwnedExistingOutputAndHideRawBodies():Unit=runBlocking {
        check(context.packageName=="org.hxsi.agent.nativeqa")
        val koin=GlobalContext.get();val settings=koin.get<SettingsStore>()
        val owner=settings.settingsFlow.first { !it.init }.activeProfileId
        val files=koin.get<MaterialFiles>()
        val output=files.runTask(MaterialFile(UUID.randomUUID().toString(),owner,"QA-only-catalog-output.md","md",output=true)) {
            it.writeText("synthetic private file body");"written and independently hashed"
        }
        val otherOwner=UUID.randomUUID().toString()
        val other=files.runTask(MaterialFile(UUID.randomUUID().toString(),otherOwner,"foreign-output.md","md",output=true)) {
            it.writeText("foreign private body");"foreign fixture"
        }
        val host=StudyHostFacade(context,settings,koin.get(),koin.get(),koin.get(),files,koin.get(),pickFiles={ emptyList() })
        try {
            val epoch=host.getBootstrap(owner,1L).contextEpoch
            AgentTaskStore(context).use { store ->
                val task=accept(store,owner);val lease=store.claim(owner,task.id)
                store.publishPlan(lease,1,AgentPlan(listOf(PlanStep("file","get_time_info","synthetic receipt test")),
                    listOf(AcceptancePredicate("file","tool_verified"))),setOf("get_time_info"))
                val result=Json.encodeToString(listOf<UIMessagePart>(UIMessagePart.Text("private receipt body"),
                    UIMessagePart.Document(files.file(output).toUri().toString(),output.name,"text/markdown"),
                    UIMessagePart.Image(files.file(output).toUri().toString()),
                    UIMessagePart.Document(files.file(other).toUri().toString(),other.name,"text/markdown")))
                store.beginCall(lease,"receipt","get_time_info","private tool input");store.finishCall(lease,"receipt",AgentTaskStore.hash(result),true,result)
                store.finish(lease,"COMPLETED_UNVERIFIED")
                val before=store.snapshot(owner,task.id)
                val detail=host.getAgentTaskDetail(owner,task.id,null,20,epoch)
                assertEquals(output.id,detail.artifacts.single().id);assertEquals(output.sha256,detail.artifacts.single().sha256)
                assertTrue(detail.resultsPartial);assertFalse(detail.toString().contains("private receipt body"))
                assertFalse(detail.toString().contains("file:/"));assertFalse(detail.toString().contains("private tool input"))
                assertEquals(before,store.snapshot(owner,task.id));assertEquals(output.sha256,MaterialFiles.digest(files.verifiedFile(owner,output.id)))
                files.file(output).delete()
                val missing=host.getAgentTaskDetail(owner,task.id,null,20,epoch)
                assertTrue(missing.resultsPartial);assertTrue(missing.artifacts.isEmpty())
                assertEquals(result,store.completedResult(owner,task.id,"get_time_info","private tool input"))
                assertEquals(before,store.snapshot(owner,task.id))
            }
        } finally {
            host.close()
            for(item in listOf(output,other)) {
                files.file(item).delete();context.filesDir.resolve("materials/${item.profileId}/${item.id}.meta.json").delete()
            }
        }
    }
}
