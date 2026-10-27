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
class AgentUsageAlertsRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun withStore(block: (AgentTaskStore) -> Unit) {
        val name="qa-alerts-${UUID.randomUUID()}.db"
        try { AgentTaskStore(context,name).use(block) }
        finally { SQLiteCleanup.delete(context.noBackupFilesDir.resolve(name)) }
    }
    private fun task(store: AgentTaskStore,owner: String="owner-a") = store.accept(owner,"synthetic-conversation",
        UUID.randomUUID().toString(),"synthetic goal","private-reference","provider","model","HIGH","revision")
    private fun row(store: AgentTaskStore,t: AgentTask,state: String?="VERIFIED",provenance: String="reported",at: Long?=500_000): String {
        val id=UUID.randomUUID().toString()
        store.writableDatabase.insertOrThrow("usage",null,ContentValues().apply {
            put("request_id",id);put("task_id",t.id);put("role","ocr");put("provenance",provenance);put("created_at",at)
            put("prompt_tokens",0);put("total_tokens",0)
        })
        if(state!=null) store.writableDatabase.insertOrThrow("calls",null,ContentValues().apply {
            put("task_id",t.id);put("call_id",id);put("tool","provider:ocr");put("input_hash","synthetic");put("state",state)
        })
        return id
    }
    @Test fun fullScopeCountsUnknownAndMissingReceiptsWithoutInventingCharges() = withStore { s ->
        val t=task(s);repeat(35) { row(s,t,state="UNKNOWN",at=100) }
        row(s,t,state=null,at=100);row(s,t,provenance="unknown",at=100)
        row(s,t,provenance="estimated",at=100);row(s,t,state="IN_FLIGHT",provenance="unknown",at=100)
        val p=s.readUsageAlerts(t.owner,t.id,null,null,500_000)
        assertEquals(36L,p.uncertainRequests);assertEquals(1L,p.unreportedCompletedRequests)
        assertEquals(0L,p.denseTaskCount);assertEquals(30,s.readUsage(t.owner,t.id,null,null,null,30).entries.size)
    }
    @Test fun denseThresholdUsesTaskIdentityAndRecentWindowIncludingAllPhysicalRoles() = withStore { s ->
        val t=task(s);repeat(19) { row(s,t,at=200_000) }
        assertEquals(0L,s.readUsageAlerts(t.owner,null,null,null,500_000).denseTaskCount)
        row(s,t,at=500_000)
        row(s,t,at=199_999);row(s,t,at=500_001);row(s,t,at=null)
        val other=task(s);repeat(19) { row(s,other) }
        val p=s.readUsageAlerts(t.owner,null,null,null,500_000)
        assertEquals(1L,p.denseTaskCount);assertEquals(20L,p.denseRequests);assertEquals(1L,p.undatedRequests)
        assertEquals(0L,s.readUsageAlerts(t.owner,t.id,null,null,800_001).denseTaskCount)
    }
    @Test fun rangeOwnerAndUndatedBoundariesAreRespected() = withStore { s ->
        val t=task(s);listOf(99L,100L,199L,200L,null).forEach { row(s,t,state="UNKNOWN",at=it) }
        val other=task(s,"owner-b");repeat(30) { row(s,other,state="UNKNOWN") }
        val p=s.readUsageAlerts(t.owner,null,100,200,500_000)
        assertEquals(2L,p.uncertainRequests);assertEquals(1L,p.undatedRequests);assertEquals(0L,p.denseTaskCount)
        assertEquals(5L,s.readUsageAlerts(t.owner,null,null,null,500_000).uncertainRequests)
        assertTrue(runCatching { s.readUsageAlerts(t.owner,other.id,null,null) }.isFailure)
        assertTrue(runCatching { s.readUsageAlerts(t.owner,t.id,200,100) }.isFailure)
        assertTrue(runCatching { s.readUsageAlerts(t.owner,t.id,100,null) }.isFailure)
        assertTrue(runCatching { s.readUsageAlerts(t.owner,t.id,null,null,-1) }.isFailure)
    }
    @Test fun duplicateAckReadAndLateReceiptNeverChangeTaskOrUnknownSlot() = withStore { s ->
        val t=task(s);val lease=s.claim(t.owner,t.id)
        val request=s.beginRequest(lease,"main","synthetic",AgentRequestSelection(t.providerId,t.modelId,t.reasoning,t.configurationReference))
        assertEquals(0L,s.readUsageAlerts(t.owner,t.id,null,null).uncertainRequests)
        s.finish(lease,"FAILED_BLOCKED")
        val before=s.snapshot(t.owner,t.id)
        repeat(2) { assertEquals(1L,s.readUsageAlerts(t.owner,t.id,null,null).uncertainRequests) }
        assertEquals(before,s.snapshot(t.owner,t.id));assertTrue(runCatching { s.claim("owner-b",task(s,"owner-b").id) }.isFailure)
        s.finishRequest(lease,request,TokenUsage(promptTokens=0,completionTokens=0,totalTokens=0))
        s.finishRequest(lease,request,TokenUsage(promptTokens=0,completionTokens=0,totalTokens=0))
        val settled=s.readUsageAlerts(t.owner,t.id,null,null)
        assertEquals(0L,settled.uncertainRequests);assertEquals(0L,settled.unreportedCompletedRequests)
        assertEquals(1,s.physicalRequestCount(t.owner,t.id))
    }
    @Test fun reopenRecomputesAlertsAndPreservesOriginalRows() {
        val name="qa-alerts-reopen-${UUID.randomUUID()}.db"
        try {
            val t=AgentTaskStore(context,name).use { s -> task(s).also { row(s,it,state="UNKNOWN");row(s,it,provenance="unknown") } }
            AgentTaskStore(context,name).use { s ->
                val p=s.readUsageAlerts(t.owner,t.id,null,null,500_000)
                assertEquals(1L,p.uncertainRequests);assertEquals(1L,p.unreportedCompletedRequests)
                assertEquals(2,s.physicalRequestCount(t.owner,t.id))
            }
        } finally { SQLiteCleanup.delete(context.noBackupFilesDir.resolve(name)) }
    }
    @Test fun facadeRejectsOldEpochAndForeignOwner(): Unit = runBlocking {
        val k=GlobalContext.get();val settings=k.get<SettingsStore>();val owner=settings.settingsFlow.first { !it.init }.activeProfileId
        val facade=StudyHostFacade(context,settings,k.get(),k.get(),k.get(),k.get(),k.get(),pickFiles={emptyList()})
        try {
            val epoch=facade.getBootstrap(owner,1).contextEpoch
            AgentTaskStore(context).use { s ->
                val t=task(s,owner);row(s,t,state="UNKNOWN")
                val p=facade.getAgentUsageAlerts(owner,t.id,null,null,epoch)
                assertEquals(owner,p.owner);assertEquals(1L,p.uncertainRequests)
                assertTrue(runCatching { facade.getAgentUsageAlerts(owner,t.id,null,null,epoch-1) }.isFailure)
                assertTrue(runCatching { facade.getAgentUsageAlerts("foreign",t.id,null,null,epoch) }.isFailure)
            }
        } finally { facade.close() }
    }
}
