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
class AgentUsageLedgerRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun withStore(block: (AgentTaskStore) -> Unit) {
        val name="qa-usage-${UUID.randomUUID()}.db"
        AgentTaskStore(context,name).use { store -> try { block(store) } finally {
            store.close(); SQLiteCleanup.delete(context.noBackupFilesDir.resolve(name))
        } }
    }
    private fun task(store: AgentTaskStore,owner: String="owner-a") =
        store.accept(owner,"conversation-$owner",UUID.randomUUID().toString(),"synthetic goal","private-vault-reference",
            "provider-a","model-a","HIGH","revision-a")
    private fun page(store: AgentTaskStore,task: AgentTask?,owner: String=task?.owner ?: "owner-a",
        from: Long?=null,until: Long?=null,cursor: String?=null,limit: Int=100) =
        store.readUsage(owner,task?.id,from,until,cursor,limit)
    /** Old/estimated receipt fixture: identity created by actual Store; no external dispatch. */
    private fun receipt(store: AgentTaskStore,task: AgentTask,role: String="main",provenance: String="reported",
        at: Long?=100,input: Long?=10,output: Long?=2,cached: Long?=null,total: Long?=12,state: String="VERIFIED"): String {
        val id=UUID.randomUUID().toString()
        store.writableDatabase.insertOrThrow("usage",null,ContentValues().apply {
            put("request_id",id);put("task_id",task.id);put("role",role);put("provenance",provenance)
            put("created_at",at);put("prompt_tokens",input);put("completion_tokens",output);put("cached_tokens",cached);put("total_tokens",total)
            put("provider_id",task.providerId);put("model_id",task.modelId);put("reasoning",task.reasoning)
        })
        store.writableDatabase.insertOrThrow("calls",null,ContentValues().apply {
            put("task_id",task.id);put("call_id",id);put("tool","provider:$role");put("input_hash","synthetic-hash");put("state",state)
        })
        return id
    }
    @Test fun physicalReceiptsAndDuplicateAckProduceOneLedgerEntry() = withStore { store ->
        val task=task(store);val lease=store.claim(task.owner,task.id)
        val request=store.beginRequest(lease,"planning","synthetic",AgentRequestSelection(task.providerId,task.modelId,task.reasoning,task.configurationReference))
        val usage=TokenUsage(promptTokens=0,completionTokens=7)
        store.finishRequest(lease,request,usage);store.finishRequest(lease,request,usage)
        val before=store.snapshot(task.owner,task.id)
        val result=page(store,task)
        assertEquals(1L,result.requestCount);assertEquals(1L,result.reportedRequests)
        assertEquals(0L,result.input.reported);assertEquals(7L,result.output.reported)
        assertNull(result.total.reported);assertEquals(1L,result.total.unknownRequests)
        assertNull(result.cached.reported);assertEquals(1L,result.cached.unknownRequests)
        assertEquals(request,result.entries.single().requestId);assertEquals("planning",result.entries.single().role)
        assertEquals(before,store.snapshot(task.owner,task.id))
    }
    @Test fun historicalUnknownZerosAndMissingSelectionAreNotReportedAsRealUsage() = withStore { store ->
        val task=task(store)
        val id=receipt(store,task,provenance="unknown",at=null,input=0,output=0,cached=0,total=0,state="UNKNOWN")
        store.writableDatabase.execSQL("UPDATE usage SET provider_id=NULL,model_id=NULL,reasoning=NULL WHERE request_id=?",arrayOf(id))
        val result=page(store,task)
        assertEquals(1L,result.unknownRequests);assertEquals(1L,result.undatedRequests)
        assertNull(result.input.reported);assertNull(result.total.reported);assertEquals(1L,result.total.unknownRequests)
        val entry=result.entries.single()
        assertNull(entry.inputTokens);assertNull(entry.totalTokens);assertNull(entry.modelId);assertNull(entry.providerId)
        assertEquals("UNKNOWN",entry.state)
        val period=page(store,task,from=0,until=200)
        assertEquals(0L,period.requestCount);assertEquals(1L,period.undatedRequests)
    }
    @Test fun reportsAndEstimatesStaySeparateAndSummaryCoversAllPages() = withStore { store ->
        val task=task(store)
        receipt(store,task,input=10,total=12)
        receipt(store,task,role="ocr",provenance="estimated",input=5,total=6)
        receipt(store,task,role="image",provenance="unknown",input=null,total=null,state="UNKNOWN")
        val result=page(store,task,limit=1)
        assertEquals(3L,result.requestCount);assertEquals(1,result.entries.size)
        assertEquals(10L,result.input.reported);assertEquals(5L,result.input.estimated);assertEquals(1L,result.input.unknownRequests)
        assertEquals(1L,result.reportedRequests);assertEquals(1L,result.estimatedRequests);assertEquals(1L,result.unknownRequests)
        assertNotNull(result.nextCursor)
    }
    @Test fun ownerIsolationAndAuxiliaryRolesUsePhysicalRequestSelection() = withStore { store ->
        val own=task(store);val auxiliary=task(store);val other=task(store,"owner-b")
        val mainId=receipt(store,own)
        val titleId=receipt(store,auxiliary,role="title")
        store.writableDatabase.execSQL("UPDATE usage SET model_id='dedicated-title-model' WHERE request_id=?",arrayOf(titleId))
        receipt(store,other,role="ocr",input=999)
        val global=page(store,null)
        assertEquals(setOf(mainId,titleId),global.entries.map { it.requestId }.toSet())
        assertEquals(20L,global.input.reported)
        assertEquals("dedicated-title-model",global.entries.single { it.role=="title" }.modelId)
        assertEquals(1L,page(store,own).requestCount)
        assertTrue(runCatching { page(store,other,owner=own.owner) }.isFailure)
    }
    @Test fun periodBoundariesAreHalfOpenAndUndatedRowsAreVisibleOnlyInAll() = withStore { store ->
        val task=task(store)
        listOf(99L,100L,199L,200L,null).forEach { receipt(store,task,at=it) }
        val result=page(store,task,from=100,until=200)
        assertEquals(setOf(100L,199L),result.entries.map { it.createdAt }.toSet())
        assertEquals(2L,result.requestCount);assertEquals(1L,result.undatedRequests)
        assertEquals(5L,page(store,task).requestCount)
    }
    @Test fun paginationWatermarkPreventsInsertedRequestsFromDuplicatingOldPages() = withStore { store ->
        val task=task(store)
        val ids=(1..4).map { receipt(store,task) }.toSet()
        val first=page(store,task,limit=2)
        val added=receipt(store,task)
        val second=page(store,task,cursor=first.nextCursor,limit=2)
        assertEquals(ids,(first.entries+second.entries).map { it.requestId }.toSet())
        assertFalse((first.entries+second.entries).any { it.requestId==added })
        assertEquals(4L,second.requestCount);assertNull(second.nextCursor)
        assertEquals(5L,page(store,task).requestCount)
        assertTrue(runCatching { page(store,null,cursor=first.nextCursor) }.isFailure)
        assertTrue(runCatching { page(store,task,owner="owner-b",cursor=first.nextCursor) }.isFailure)
    }
    @Test fun invalidQueriesAndReadsDoNotReleaseUnknownExecutionOrChangePausedAuthorization() = withStore { store ->
        val task=task(store);val lease=store.claim(task.owner,task.id)
        store.beginRequest(lease,"main","synthetic",AgentRequestSelection(task.providerId,task.modelId,task.reasoning,task.configurationReference))
        store.finish(lease,"FAILED_BLOCKED")
        val before=store.snapshot(task.owner,task.id)
        assertEquals("UNKNOWN",page(store,task).entries.single().state)
        for (limit in listOf(0,101,Int.MAX_VALUE)) assertTrue(runCatching { page(store,task,limit=limit) }.isFailure)
        assertTrue(runCatching { page(store,task,from=200,until=100) }.isFailure)
        assertTrue(runCatching { page(store,task,from=100) }.isFailure)
        assertTrue(runCatching { page(store,task,cursor="malformed") }.isFailure)
        assertEquals(before,store.snapshot(task.owner,task.id))
        val other=task(store,"owner-b")
        assertTrue(runCatching { store.claim(other.owner,other.id) }.exceptionOrNull() is AgentTaskBlocked)
    }
    @Test fun facadeChecksActiveProfileAndEpochAndReturnsGeneratedNullableDto(): Unit = runBlocking {
        val koin=GlobalContext.get()
        val settings=koin.get<SettingsStore>()
        val owner=settings.settingsFlow.first { !it.init }.activeProfileId
        val facade=StudyHostFacade(context,settings,koin.get(),koin.get(),koin.get(),koin.get(),koin.get(),pickFiles={emptyList()})
        try {
            val epoch=facade.getBootstrap(owner,1L).contextEpoch
            AgentTaskStore(context).use { store ->
                val task=task(store,owner)
                receipt(store,task,role="compression",provenance="unknown",total=0)
                val result=facade.getAgentUsage(owner,task.id,null,null,null,30,epoch)
                assertEquals(owner,result.owner);assertEquals(task.id,result.taskId);assertEquals(1L,result.requestCount)
                assertNull(result.total.reported);assertNull(result.entries.single().totalTokens)
                assertTrue(runCatching { facade.getAgentUsage(owner,task.id,null,null,null,30,epoch-1) }.isFailure)
                assertTrue(runCatching { facade.getAgentUsage("owner-other",task.id,null,null,null,30,epoch) }.isFailure)
                assertTrue(runCatching { facade.getAgentUsage(owner,task.id,null,null,null,Long.MAX_VALUE,epoch) }.isFailure)
            }
        } finally { facade.close() }
    }
}
