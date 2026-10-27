package me.rerere.rikkahub.feature.agent

import android.content.ContentValues
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.TokenUsage
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.flutter.StudyHostFacade
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AgentUsageGroupsRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun withStore(test: (AgentTaskStore, String) -> Unit) {
        val name="qa-groups-${UUID.randomUUID()}.db"
        try { AgentTaskStore(context,name).use { test(it,name) } }
        finally { SQLiteCleanup.delete(context.noBackupFilesDir.resolve(name)) }
    }
    private fun task(store: AgentTaskStore, owner: String="owner") = store.accept(owner,"conversation-${UUID.randomUUID()}",
        UUID.randomUUID().toString(),"synthetic group goal","private-vault-reference","task-provider","task-model","HIGH","config")
    private fun row(store: AgentTaskStore, task: AgentTask, provider: String?="p", model: String?="shared", role: String="main",
        provenance: String="reported", input: Long?=10, total: Long?=10, at: Long?=100, state: String="FAILED"): String {
        val id=UUID.randomUUID().toString()
        store.writableDatabase.insertOrThrow("usage",null,ContentValues().apply {
            put("request_id",id);put("task_id",task.id);put("role",role);put("provenance",provenance)
            put("provider_id",provider);put("model_id",model);put("created_at",at);put("prompt_tokens",input);put("total_tokens",total)
        })
        store.writableDatabase.insertOrThrow("calls",null,ContentValues().apply {
            put("task_id",task.id);put("call_id",id);put("tool","provider:$role");put("input_hash","synthetic");put("state",state)
        })
        return id
    }
    private fun read(store: AgentTaskStore, mode: String="model", id: String?=null, from: Long?=null, until: Long?=null,
        cursor: String?=null, limit: Int=100, owner: String="owner") = store.readUsageGroups(owner,id,from,until,mode,cursor,limit)

    @Test fun physicalSelectionAndDuplicateAckDoNotBorrowTaskIdentityOrDoubleCount()=withStore { store,_ ->
        val task=task(store);val lease=store.claim(task.owner,task.id)
        assertTrue(runCatching { store.beginRequest(lease,"ocr","synthetic",
            AgentRequestSelection("ocr-provider","ocr-model","LOW","private-role-ref")) }.isFailure)
        val request=store.beginRequest(lease,"ocr","synthetic",AgentRequestSelection("ocr-provider","ocr-model","LOW",task.configurationReference))
        val usage=TokenUsage(promptTokens=0,completionTokens=7)
        store.finishRequest(lease,request,usage);store.finishRequest(lease,request,usage)
        val before=store.snapshot(task.owner,task.id)
        val page=read(store)
        assertEquals(1L,page.requestCount);assertEquals(1L,page.groupCount)
        val group=page.groups.single()
        assertEquals("ocr-provider",group.providerId);assertEquals("ocr-model",group.modelId)
        assertEquals(0L,group.input.reported);assertEquals(7L,group.output.reported)
        assertNull(group.total.reported);assertEquals(1L,group.total.unknownRequests)
        assertEquals("ocr",read(store,"role").groups.single().role)
        assertEquals(before,store.snapshot(task.owner,task.id))
        assertFalse(page.toString().contains("private-role-ref"))
    }
    @Test fun sameModelAcrossProvidersMissingIdentityAndFailureRequestsRemainSeparate()=withStore { store,_ ->
        val a=task(store);val b=task(store);val foreign=task(store,"other")
        row(store,a,provider="A",model="same",input=0,total=0)
        row(store,b,provider="B",model="same",role="title",provenance="estimated",input=5,total=6)
        row(store,b,provider=null,model=null,role="ocr",provenance="unknown",input=0,total=0,state="UNKNOWN",at=null)
        row(store,b,provider=" ",model="",role=" ",provenance="unexpected",input=0,total=0)
        row(store,foreign,input=999)
        val models=read(store)
        assertEquals(4L,models.requestCount);assertEquals(3L,models.groupCount)
        assertEquals(setOf("A" to "same","B" to "same",null to null),models.groups.map { it.providerId to it.modelId }.toSet())
        val unknown=models.groups.single { it.providerId==null }
        assertEquals(2L,unknown.unknownRequests);assertNull(unknown.total.reported);assertEquals(2L,unknown.total.unknownRequests)
        assertEquals(0L,models.groups.single { it.providerId=="A" }.total.reported)
        assertEquals(6L,models.groups.single { it.providerId=="B" }.total.estimated)
        assertEquals(3L,read(store,"provider").groupCount)
        assertEquals(setOf("main","title","ocr",null),read(store,"role").groups.map { it.role }.toSet())
        assertEquals(1L,read(store,id=a.id).requestCount)
        assertTrue(runCatching { read(store,id=foreign.id) }.isFailure)
    }
    @Test fun summaryIncludesUnloadedGroupsAndCursorWatermarkExcludesInsertedRows()=withStore { store,_ ->
        val task=task(store)
        for(provider in listOf("A","B","C")) row(store,task,provider=provider)
        val first=read(store,limit=1)
        assertEquals(3L,first.requestCount);assertEquals(3L,first.groupCount);assertEquals(1,first.groups.size)
        row(store,task,provider="AA")
        val second=read(store,cursor=first.nextCursor,limit=1)
        val third=read(store,cursor=second.nextCursor,limit=1)
        assertEquals(listOf("A","B","C"),(first.groups+second.groups+third.groups).map { it.providerId })
        assertEquals(3L,third.requestCount);assertNull(third.nextCursor)
        assertEquals(4L,read(store).requestCount)
        assertTrue(runCatching { read(store,"provider",cursor=first.nextCursor) }.isFailure)
        assertTrue(runCatching { read(store,id=task.id,cursor=first.nextCursor) }.isFailure)
        assertTrue(runCatching { read(store,owner="other",cursor=first.nextCursor) }.isFailure)
        assertTrue(runCatching { read(store,from=100,until=200,cursor=first.nextCursor) }.isFailure)
    }
    @Test fun nullAndCaseSensitiveCompositeKeysRoundTripWithoutCursorCollisions()=withStore { store,_ ->
        val task=task(store)
        val identities=listOf(null to null,null to "same","A" to null,"A" to "same","a" to "same","A" to " 模型:🙂")
        for((provider,model) in identities) row(store,task,provider=provider,model=model)
        val seen=mutableListOf<Pair<String?,String?>>()
        var cursor: String?=null
        do { val page=read(store,cursor=cursor,limit=1);seen+=page.groups.map { it.providerId to it.modelId };cursor=page.nextCursor } while(cursor!=null)
        assertEquals(identities.toSet(),seen.toSet());assertEquals(identities.size,seen.size)
        assertTrue(runCatching { read(store,cursor="g1:bad:1:invalid") }.isFailure)
    }
    @Test fun dateBoundaryLateReportsAndReopenPreserveLedgerAndControl()=withStore { store,name ->
        val task=task(store)
        listOf(99L,100L,199L,200L,null).forEach { row(store,task,at=it,provenance="unknown",input=0,total=0) }
        val bounded=read(store,from=100,until=200)
        assertEquals(2L,bounded.requestCount);assertEquals(1L,bounded.undatedRequests)
        assertEquals(2L,bounded.groups.single().total.unknownRequests)
        val extra=row(store,task,provider="second",provenance="unknown")
        val first=read(store,limit=1)
        store.writableDatabase.execSQL("UPDATE usage SET provenance='reported',total_tokens=0 WHERE request_id=?",arrayOf(extra))
        val second=read(store,cursor=first.nextCursor,limit=1)
        assertEquals(0L,second.groups.single().total.reported)
        val before=store.snapshot(task.owner,task.id); val all=read(store)
        assertTrue(runCatching { read(store,from=200,until=100) }.isFailure)
        assertTrue(runCatching { read(store,from=0) }.isFailure)
        for(limit in listOf(0,101,Int.MAX_VALUE)) assertTrue(runCatching { read(store,limit=limit) }.isFailure)
        assertTrue(runCatching { read(store,"not-a-group") }.isFailure)
        assertEquals(before,store.snapshot(task.owner,task.id))
        store.close()
        AgentTaskStore(context,name).use { reopened -> assertEquals(all,read(reopened));assertEquals(before,reopened.snapshot(task.owner,task.id)) }
    }
    @Test fun aggregateOverflowFailsClosedAndUnknownExecutionRemainsBlocked()=withStore { store,_ ->
        val task=task(store);val lease=store.claim(task.owner,task.id)
        store.beginRequest(lease,"main","synthetic",AgentRequestSelection(task.providerId,task.modelId,task.reasoning,task.configurationReference))
        store.finish(lease,"FAILED_BLOCKED")
        val before=store.snapshot(task.owner,task.id)
        assertEquals(1L,read(store).groups.single().unknownRequests)
        assertEquals(before,store.snapshot(task.owner,task.id))
        val other=task(store,"other");assertTrue(runCatching { store.claim(other.owner,other.id) }.exceptionOrNull() is AgentTaskBlocked)
        row(store,task,input=Long.MAX_VALUE);row(store,task,input=Long.MAX_VALUE)
        assertTrue(runCatching { read(store) }.isFailure)
        assertEquals(before,store.snapshot(task.owner,task.id))
    }
    @Test fun facadeRejectsWrongOwnerEpochAndProjectsOnlyActualNullableMetadata(): Unit=runBlocking {
        val koin=GlobalContext.get();val settings=koin.get<SettingsStore>()
        val owner=settings.settingsFlow.first { !it.init }.activeProfileId
        val host=StudyHostFacade(context,settings,koin.get(),koin.get(),koin.get(),koin.get(),koin.get(),pickFiles={ emptyList() })
        try {
            val epoch=host.getBootstrap(owner,1L).contextEpoch
            AgentTaskStore(context).use { store ->
                val task=task(store,owner);row(store,task,provider=null,model=null,provenance="unknown",input=0,total=0)
                val before=store.snapshot(owner,task.id)
                val result=host.getAgentUsageGroups(owner,task.id,null,null,"model",null,1,epoch)
                assertNull(result.groups.single().modelId);assertNull(result.groups.single().total.reported)
                assertFalse(result.toString().contains("private-vault-reference"))
                assertEquals(before,store.snapshot(owner,task.id))
                assertTrue(runCatching { host.getAgentUsageGroups(owner,task.id,null,null,"model",null,1,epoch-1) }.isFailure)
                assertTrue(runCatching { host.getAgentUsageGroups("other",task.id,null,null,"model",null,1,epoch) }.isFailure)
                assertTrue(runCatching { host.getAgentUsageGroups(owner,task.id,null,null,"model",null,Long.MAX_VALUE,epoch) }.isFailure)
            }
        } finally { host.close() }
    }
}
