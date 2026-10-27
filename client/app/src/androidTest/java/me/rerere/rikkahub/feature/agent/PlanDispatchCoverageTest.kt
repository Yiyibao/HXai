package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PlanDispatchCoverageTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun scenario(test:(AgentTaskStore,AgentTask,AgentLease,String)->Unit) {
        val name="qa-plan-coverage-${UUID.randomUUID()}.db"
        try { AgentTaskStore(context,name).use { store ->
            val task=store.accept("owner","conversation","request","synthetic goal","configuration","provider","model","OFF","revision")
            test(store,task,store.claim(task.owner,task.id),name)
        } } finally { listOf(name,"$name-wal","$name-shm").forEach { context.noBackupFilesDir.resolve(it).delete() } }
    }
    private fun plan(vararg ids:String)=AgentPlan(ids.map { PlanStep(it,"get_time_info","Read synthetic time for $it") },
        listOf(AcceptancePredicate(ids.last(),"tool_verified")))
    private fun finish(store:AgentTaskStore,lease:AgentLease,id:String):String {
        val result=Json.encodeToString(listOf<UIMessagePart>(UIMessagePart.Text("synthetic $id")))
        store.finishCall(lease,id,AgentTaskStore.hash(result),true,result)
        return result
    }
    private fun calls(store:AgentTaskStore,task:AgentTask)=store.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM calls WHERE task_id=?",arrayOf(task.id)).use { assertTrue(it.moveToFirst());it.getInt(0) }
    @Test fun consumedPlanSlotCannotAuthorizeAnotherCallAndTransactionKeepsReceipt()=scenario { store,task,lease,_ ->
        store.publishPlan(lease,1,plan("first"),setOf("get_time_info"))
        store.beginCall(lease,"first-call","get_time_info","first-input")
        val receipt=finish(store,lease,"first-call")
        assertTrue("Second call must not dispatch without a second step",runCatching {
            store.beginCall(lease,"extra-call","get_time_info","different-input")
        }.isFailure)
        assertEquals(1,calls(store,task))
        assertEquals(receipt,store.completedResult(task.owner,task.id,"get_time_info","first-input"))
        assertEquals(listOf("VERIFIED"),store.plan(task.owner,task.id).steps.map { it.state })
    }
    @Test fun supplementedGoalMustBeReplannedBeforeDispatch()=scenario { store,task,lease,_ ->
        store.publishPlan(lease,1,plan("first"),setOf("get_time_info"))
        val before=store.get(task.owner,task.id)
        store.command(task.owner,task.id,"supplement",before.revision,before.fence,"supplement","also read again")
        assertTrue(runCatching { store.beginCall(lease,"stale-plan-call","get_time_info","input") }.isFailure)
        assertEquals(0,calls(store,task))
        store.publishPlan(lease,2,plan("first","second"),setOf("get_time_info"))
        store.beginCall(lease,"fresh-plan-call","get_time_info","input")
        assertEquals(1,calls(store,task))
    }
    @Test fun twoRealSlotsKeepTwoDifferentReceipts()=scenario { store,task,lease,_ ->
        store.publishPlan(lease,1,plan("first","second"),setOf("get_time_info"))
        listOf("first","second").forEach { id -> store.beginCall(lease,"$id-call","get_time_info",id);finish(store,lease,"$id-call") }
        assertEquals(2,calls(store,task))
        assertEquals(listOf("first-call","second-call"),store.plan(task.owner,task.id).steps.map { it.callId })
        assertTrue(store.plan(task.owner,task.id).steps.all { it.state=="VERIFIED" })
    }
    @Test fun replanProjectionUsesNewOrderAndPurposeWithoutLosingVerifiedReceiptAfterReopen()=scenario { store,task,lease,name ->
        store.publishPlan(lease,1,plan("verified","discard"),setOf("get_time_info"))
        store.beginCall(lease,"verified-call","get_time_info","original-input")
        val receipt=finish(store,lease,"verified-call")
        val before=store.get(task.owner,task.id)
        store.command(task.owner,task.id,"supplement",before.revision,before.fence,"supplement","new order and more reads")
        val next=plan("new-first","verified","new-last").let { it.copy(steps=it.steps.map { step -> step.copy(purpose="Revised ${step.id}") }) }
        store.publishPlan(lease,2,next,setOf("get_time_info"))
        store.close()
        AgentTaskStore(context,name).use { reopened ->
            assertEquals(next,reopened.plan(task.owner,task.id).plan)
            assertEquals(next.steps.map { it.id },reopened.plan(task.owner,task.id).steps.map { it.id })
            reopened.readableDatabase.rawQuery("SELECT purpose,ordinal FROM plan_steps WHERE task_id=? ORDER BY ordinal",arrayOf(task.id)).use { rows ->
                next.steps.forEachIndexed { index,step -> assertTrue(rows.moveToNext());assertEquals(step.purpose,rows.getString(0));assertEquals(index,rows.getInt(1)) }
                assertFalse(rows.moveToNext())
            }
            assertEquals(receipt,reopened.completedResult(task.owner,task.id,"get_time_info","original-input"))
            val kept=reopened.plan(task.owner,task.id).steps.single { it.id=="verified" }
            assertEquals("verified-call",kept.callId);assertEquals("VERIFIED",kept.state);assertEquals(receipt,kept.resultJson)
            reopened.beginCall(lease,"new-first-call","get_time_info","new-input")
            assertEquals("new-first-call",reopened.plan(task.owner,task.id).steps.single { it.id=="new-first" }.callId)
        }
    }
    @Test fun rejectedReplanCannotEraseReceiptsOrDispatchUnknownEffects()=scenario { store,task,lease,_ ->
        store.publishPlan(lease,1,plan("first","second"),setOf("get_time_info"))
        store.beginCall(lease,"first-call","get_time_info","first")
        val receipt=finish(store,lease,"first-call")
        val original=store.plan(task.owner,task.id)
        assertTrue(runCatching { store.publishPlan(lease,1,plan("second"),setOf("get_time_info")) }.isFailure)
        assertEquals(original,store.plan(task.owner,task.id))
        assertEquals(receipt,store.completedResult(task.owner,task.id,"get_time_info","first"))
        store.beginCall(lease,"second-call","get_time_info","second")
        assertTrue(runCatching { store.publishPlan(lease,1,plan("first","second","third"),setOf("get_time_info")) }.isFailure)
        assertEquals(2,calls(store,task))
    }
    @Test fun trustedToolWithoutAnActionPlanRemainsUsable()=scenario { store,task,lease,_ ->
        store.beginCall(lease,"read-only","get_time_info","{}")
        finish(store,lease,"read-only")
        assertEquals(1,calls(store,task));assertNull(store.plan(task.owner,task.id).plan)
    }
}
