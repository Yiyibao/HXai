package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ResponseCompletionStoreTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(block: (AgentTaskStore,AgentLease) -> Unit) {
        val file="qa-response-${UUID.randomUUID()}.db"
        AgentTaskStore(context,file).use {store ->
            try {
                val task=store.accept("owner","conversation","request","goal","ref","provider","model","HIGH","config")
                block(store,store.claim(task.owner,task.id))
            } finally { /* persistent fixtures stay until this scoped store is closed */ }
        }
        SQLiteCleanup.delete(context.noBackupFilesDir.resolve(file))
    }
    private fun evidence(lease: AgentLease)=buildJsonObject {
        val task=lease.task
        put("owner",task.owner);put("taskId",task.id);put("conversationId",task.conversationId)
        put("fence",lease.fence);put("grantEpoch",1);put("goalRevision",1);put("goalHash",AgentTaskStore.hash("goal"))
        put("providerId",task.providerId);put("modelId",task.modelId);put("reasoning",task.reasoning)
        put("configurationRevision",task.configurationRevision);put("text","请提供讲义。")
    }
    private fun decision(evidence: JsonObject,outcome: String="delivered")=buildJsonObject {
        put("outcome",outcome);put("goalRevision",1);put("evidenceHash",AgentTaskStore.hash(evidence.toString()))
        put("responseQuote","请提供讲义。");put("reason","synthetic disposition");put("requiresIndependentEvidence",false)
    }
    @Test fun settledProviderCallsDoNotInventToolRequirements()=fixture {store,lease ->
        val task=lease.task
        val request=store.beginRequest(lease,"main","synthetic",AgentRequestSelection(task.providerId,task.modelId,task.reasoning,task.configurationReference))
        assertFalse(store.responseEligible(lease))
        store.finishRequest(lease,request,null)
        assertTrue(store.responseEligible(lease))
        val evidence=evidence(lease);store.recordResponse(lease,1,evidence,decision(evidence))
        store.finish(lease,"SUCCEEDED");assertEquals("SUCCEEDED",store.get(task.owner,task.id).state)
        assertTrue(store.plan(task.owner,task.id).acceptanceVerified)
    }
    @Test fun textCannotErasePlannedOrDispatchedTools()=fixture {store,lease ->
        store.publishPlan(lease,1,AgentPlan(steps=listOf(PlanStep("write","create_material_file","required file"))),setOf("create_material_file"))
        assertFalse(store.responseEligible(lease))
        val evidence=evidence(lease)
        assertTrue(runCatching {store.recordResponse(lease,1,evidence,decision(evidence))}.isFailure)
        store.beginCall(lease,"effect","create_material_file","{}")
        assertFalse(store.responseEligible(lease));store.finish(lease,"SUCCEEDED")
        assertEquals("UNCERTAIN_SIDE_EFFECT",store.get(lease.task.owner,lease.task.id).state)
    }
    @Test fun publishingResponsePredicateAloneCannotComplete()=fixture {store,lease ->
        store.publishPlan(lease,1,AgentPlan(acceptance=listOf(AcceptancePredicate(ResponseCompletion.STEP,
            ResponseCompletion.KIND,contains=listOf("a".repeat(64))))),emptySet())
        assertTrue(runCatching {store.acceptGoal(lease,1,"{}")}.isFailure)
        store.finish(lease,"SUCCEEDED");assertEquals("COMPLETED_UNVERIFIED",store.get(lease.task.owner,lease.task.id).state)
    }
    @Test fun changedGoalAndCrossOwnerCannotAcceptOldResponse()=fixture {store,lease ->
        val evidence=evidence(lease);val task=store.get(lease.task.owner,lease.task.id)
        val forged=JsonObject(evidence+mapOf("owner" to JsonPrimitive("other")))
        assertTrue(runCatching {store.recordResponse(lease,1,forged,decision(forged))}.isFailure)
        store.command(task.owner,task.id,"supplement",task.revision,task.fence,"supplement","new requirement")
        assertTrue(runCatching {store.recordResponse(lease,1,evidence,decision(evidence))}.isFailure)
        assertFalse(store.plan(task.owner,task.id).acceptanceVerified)
    }
    @Test fun pauseAndRevocationCannotPublishLateResponse()=fixture {store,lease ->
        val evidence=evidence(lease);var task=store.get(lease.task.owner,lease.task.id)
        store.command(task.owner,task.id,"pause",task.revision,task.fence,"pause")
        assertTrue(runCatching {store.recordResponse(lease,1,evidence,decision(evidence))}.isFailure)
        task=store.get(task.owner,task.id)
        store.command(task.owner,task.id,"revoke",task.revision,task.fence,"revoke")
        assertTrue(runCatching {store.recordResponse(lease,1,evidence,decision(evidence))}.isFailure)
        store.finish(lease,"SUCCEEDED");assertEquals("PAUSED_BY_USER",store.get(task.owner,task.id).state)
    }
    @Test fun inputWaitingSurvivesRecoveryAndDoesNotClearManualPause()=fixture {store,lease ->
        val evidence=evidence(lease);val task=lease.task
        store.recordResponse(lease,1,evidence,decision(evidence,"needs_input"));store.finish(lease,"SUCCEEDED")
        store.recover();assertEquals("WAITING_INPUT",store.get(task.owner,task.id).state)
        assertTrue(runCatching {store.resume(task.owner,task.id)}.isFailure)
        var current=store.get(task.owner,task.id)
        store.command(current.owner,current.id,"pause",current.revision,current.fence,"pause")
        current=store.get(task.owner,task.id)
        store.command(current.owner,current.id,"provide",current.revision,current.fence,"provide_input","handout received")
        assertFalse(store.control(task.owner,task.id).inputBlocked)
        assertTrue(store.get(task.owner,task.id).manualPaused)
        assertEquals(2L,store.plan(task.owner,task.id).revision)
        assertFalse(store.plan(task.owner,task.id).acceptanceVerified)
    }
}
