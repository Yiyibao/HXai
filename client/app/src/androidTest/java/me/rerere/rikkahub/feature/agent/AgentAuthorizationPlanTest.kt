package me.rerere.rikkahub.feature.agent

import android.database.sqlite.SQLiteDatabase
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
class AgentAuthorizationPlanTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun withStore(test:(AgentTaskStore,String)->Unit) {
        val name="qa-control-${UUID.randomUUID()}.db"
        try { AgentTaskStore(context,name).use { test(it,name) } }
        finally { context.noBackupFilesDir.resolve(name).delete();context.noBackupFilesDir.resolve("$name-wal").delete();context.noBackupFilesDir.resolve("$name-shm").delete() }
    }
    private fun task(store:AgentTaskStore)=store.accept("owner","conversation","request","write owned text","configuration","provider","model","OFF","revision")
    private fun command(store:AgentTaskStore,task:AgentTask,action:String,payload:String=""):AgentTask {
        val current=store.get(task.owner,task.id)
        return store.command(task.owner,task.id,UUID.randomUUID().toString(),current.revision,current.fence,action,payload)
    }
    @Test fun revokedPermitAndWrongIdentityCannotDispatchAndLateReceiptStillPersists()=withStore { store,_ ->
        val task=task(store);val lease=store.claim(task.owner,task.id)
        val permit=store.permit(lease,"create_material_file","{}");store.beginCall(lease,"started","create_material_file","{\"before\":true}")
        command(store,task,"revoke")
        assertTrue(runCatching { store.beginCall(lease,"late","create_material_file","{}",permit) }.isFailure)
        assertTrue(runCatching { store.beginCall(lease,"wrong","create_material_file","{}",permit.copy(owner="other")) }.isFailure)
        assertTrue(runCatching { store.restoreAuthorization(task.owner,task.id,1,store.grantScopeHash(task.owner,task.id)) }.isFailure)
        val receipt=Json.encodeToString(listOf<UIMessagePart>(UIMessagePart.Text("independently read result")))
        store.finishCall(lease,"started",AgentTaskStore.hash(receipt),true,receipt)
        store.finish(lease,"SUCCEEDED")
        assertEquals("WAITING_AUTHORIZATION",store.get(task.owner,task.id).state)
        assertEquals(receipt,store.completedResult(task.owner,task.id,"create_material_file","{\"before\":true}"))
    }
    @Test fun credentialRecoveryDoesNotClearPauseInputDeviceOrConfirmation()=withStore { store,_ ->
        val task=task(store);val lease=store.claim(task.owner,task.id)
        command(store,task,"wait_input");command(store,task,"wait_device");command(store,task,"require_device_confirmation");command(store,task,"pause")
        val scopes=store.grantScopeHash(task.owner,task.id)
        store.expireAuthorization(task.owner,task.id);store.restoreAuthorization(task.owner,task.id,1,scopes)
        store.finish(lease,"COMPLETED_UNVERIFIED")
        assertEquals("PAUSED_BY_USER",store.get(task.owner,task.id).state)
        assertTrue(runCatching { store.resume(task.owner,task.id) }.isFailure)
        command(store,task,"provide_input","synthetic required input");command(store,task,"device_available")
        assertTrue(runCatching { store.resume(task.owner,task.id) }.isFailure)
        assertTrue(store.control(task.owner,task.id).deviceConfirmation)
        command(store,task,"confirm_device");store.resume(task.owner,task.id)
        val next=store.claim(task.owner,task.id);assertTrue(next.fence>lease.fence)
        assertTrue(runCatching { store.beginCall(lease,"stale","get_time_info","{}") }.isFailure)
        store.finish(next,"COMPLETED_UNVERIFIED")
    }
    @Test fun supplementKeepsVerifiedResultsAndRejectsStaleCommandWithoutRepeatingEffect()=withStore { store,name ->
        val task=task(store);val lease=store.claim(task.owner,task.id)
        val first=AgentPlan(listOf(PlanStep("write","create_material_file","write verified file")),listOf(AcceptancePredicate("write","artifact_content","txt",listOf("required"))))
        store.publishPlan(lease,1,first,setOf("create_material_file"));store.beginCall(lease,"write-once","create_material_file","same-input")
        val result=Json.encodeToString(listOf<UIMessagePart>(UIMessagePart.Text("required")))
        store.finishCall(lease,"write-once",AgentTaskStore.hash(result),true,result)
        val before=store.get(task.owner,task.id)
        val request=UUID.randomUUID().toString()
        val updated=store.command(task.owner,task.id,request,before.revision,before.fence,"supplement","also read time")
        assertEquals(updated,store.command(task.owner,task.id,request,before.revision,before.fence,"supplement","also read time"))
        assertTrue(runCatching { store.command(task.owner,task.id,UUID.randomUUID().toString(),before.revision,before.fence,"supplement","wrong revision") }.isFailure)
        assertTrue(runCatching { store.publishPlan(lease,2,AgentPlan(),emptySet()) }.isFailure)
        store.publishPlan(lease,2,first.copy(steps=first.steps+PlanStep("time","get_time_info","read time")),setOf("create_material_file","get_time_info"))
        store.finish(lease,"COMPLETED_UNVERIFIED");store.close()
        AgentTaskStore(context,name).use { reopened ->
            assertEquals("VERIFIED",reopened.plan(task.owner,task.id).steps.first().state)
            assertEquals(result,reopened.completedResult(task.owner,task.id,"create_material_file","same-input"))
            assertEquals(2L,reopened.plan(task.owner,task.id).revision)
            assertFalse(reopened.plan(task.owner,task.id).acceptanceVerified)
        }
    }
    @Test fun plannedStepOutsideAcceptanceCannotAcquireGoalSuccess()=completionGate("PLANNED",false)
    @Test fun returnedButUnverifiedStepCannotAcquireGoalSuccess()=completionGate("RETURNED",false)
    @Test fun everyVerifiedStepAllowsDurableGoalSuccess()=completionGate("VERIFIED",false)
    @Test fun legacyPartialAcceptanceMarkerCannotPublishSuccess()=completionGate("PLANNED",true)
    private fun completionGate(secondState:String,legacyMarker:Boolean)=withStore { store,name ->
        val task=task(store);val lease=store.claim(task.owner,task.id)
        val plan=AgentPlan(listOf(PlanStep("first","get_time_info","Read the current time"),
            PlanStep("second","read_material_file","Read the required material")),
            listOf(AcceptancePredicate("first","tool_verified")))
        store.publishPlan(lease,1,plan,setOf("get_time_info","read_material_file"))
        val receipt=Json.encodeToString(listOf<UIMessagePart>(UIMessagePart.Text("synthetic local receipt")))
        store.beginCall(lease,"first-call","get_time_info","{}")
        store.finishCall(lease,"first-call",AgentTaskStore.hash(receipt),true,receipt)
        if(secondState!="PLANNED") {
            store.beginCall(lease,"second-call","read_material_file","{}")
            store.finishCall(lease,"second-call",AgentTaskStore.hash(receipt),secondState=="VERIFIED",receipt)
        }
        if(legacyMarker) {
            // Recreate an old version's persisted partial-success marker, without changing receipts.
            store.writableDatabase.execSQL("UPDATE plans SET acceptance_verified=1,evidence_json='legacy partial acceptance' WHERE task_id=?",arrayOf(task.id))
        } else {
            val accepted=runCatching { store.acceptGoal(lease,1,"synthetic evidence bound by the trusted caller") }.isSuccess
            assertEquals(secondState=="VERIFIED",accepted)
            assertEquals(secondState=="VERIFIED",store.plan(task.owner,task.id).acceptanceVerified)
        }
        store.finish(lease,"SUCCEEDED");store.close()
        AgentTaskStore(context,name).use { reopened ->
            assertEquals(if(secondState=="VERIFIED") "SUCCEEDED" else "COMPLETED_UNVERIFIED",reopened.get(task.owner,task.id).state)
            assertEquals(listOf("VERIFIED",secondState),reopened.plan(task.owner,task.id).steps.map { it.state })
            assertEquals(receipt,reopened.completedResult(task.owner,task.id,"get_time_info","{}"))
        }
    }
    @Test fun sameFenceLateToolReceiptSettlesUnknownWithoutRepeatingEffectAndRejectsConflictingAck()=withStore { store,name ->
        val task=task(store);val lease=store.claim(task.owner,task.id)
        val input="{\"synthetic\":\"late receipt\"}"
        store.beginCall(lease,"late-write","create_material_file",input)
        command(store,task,"pause")
        store.finish(lease,"INTERRUPTED")
        store.close()
        AgentTaskStore(context,name).use { reopened ->
            reopened.recover()
            assertEquals("UNCERTAIN_SIDE_EFFECT",reopened.get(task.owner,task.id).state)
            assertTrue(runCatching { reopened.claim(task.owner,task.id) }.isFailure)
            val result=Json.encodeToString(listOf<UIMessagePart>(UIMessagePart.Text("trusted independent readback")))
            val hash=AgentTaskStore.hash(result)
            reopened.finishCall(lease,"late-write",hash,true,result)
            reopened.finishCall(lease,"late-write",hash,true,result)
            assertTrue(runCatching { reopened.finishCall(lease,"late-write",AgentTaskStore.hash("conflicting"),true,"conflicting") }.isFailure)
            reopened.reconcileKnownResults(lease)
            assertEquals("PAUSED_BY_USER",reopened.get(task.owner,task.id).state)
            assertEquals(result,reopened.completedResult(task.owner,task.id,"create_material_file",input))
            val other=reopened.accept("other-owner","other-conversation","other-request","read time","other-configuration","provider","model","OFF","revision")
            val next=reopened.claim(other.owner,other.id)
            assertTrue(next.fence>lease.fence)
            assertTrue(runCatching { reopened.finishCall(lease,"late-write",hash,true,result) }.isFailure)
            reopened.finish(next,"COMPLETED_UNVERIFIED")
        }
    }
    @Test fun unacknowledgedRemoteSubmissionKeepsSlotAndCannotBecomeKnownPendingReceipt()=withStore { store,name ->
        val task=task(store);val lease=store.claim(task.owner,task.id);val input="{\"id\":\"synthetic-source\"}"
        store.writableDatabase.execSQL("INSERT INTO grants(task_id,capability,scope,grant_epoch,input_hash) VALUES(?,'document.remote',?,1,?)",
            arrayOf(task.id,task.owner,AgentTaskStore.hash(input)))
        store.beginCall(lease,"missing-ack","convert_material_to_pdf",input)
        val result=Json.encodeToString(listOf<UIMessagePart>(UIMessagePart.Text("{\"operation\":\"office_pdf\",\"task_id\":\"local-request\",\"source_id\":\"synthetic-source\",\"state\":\"SUBMITTING\",\"downloaded\":false}")))
        store.finishCall(lease,"missing-ack",AgentTaskStore.hash(result),false,result)
        store.finish(lease,"COMPLETED_UNVERIFIED");store.close()
        AgentTaskStore(context,name).use { reopened ->
            reopened.recover()
            assertEquals("UNCERTAIN_SIDE_EFFECT",reopened.get(task.owner,task.id).state)
            assertNull(reopened.completedResult(task.owner,task.id,"convert_material_to_pdf",input))
            assertTrue(runCatching { reopened.resume(task.owner,task.id) }.isFailure)
            reopened.readableDatabase.rawQuery("SELECT state,result_json FROM calls WHERE task_id=? AND call_id='missing-ack'",arrayOf(task.id)).use {
                assertTrue(it.moveToFirst());assertEquals("UNKNOWN",it.getString(0));assertEquals(result,it.getString(1))
            }
            val other=reopened.accept("other-owner","other-conversation","other-request","read time","other-configuration","provider","model","OFF","revision")
            assertTrue(runCatching { reopened.claim(other.owner,other.id) }.isFailure)
        }
    }
    @Test fun genuineSchema5MigrationPreservesUnknownSlotFenceReceiptsAndUsage() {
        val name="qa-schema5-${UUID.randomUUID()}.db";val path=context.noBackupFilesDir.resolve(name)
        try {
            SQLiteDatabase.openOrCreateDatabase(path,null).use { db ->
                db.execSQL("CREATE TABLE tasks(id TEXT PRIMARY KEY,owner TEXT,conversation_id TEXT,request_id TEXT,intent_hash TEXT,configuration_reference TEXT,provider_id TEXT,model_id TEXT,reasoning TEXT,configuration_revision TEXT,state TEXT,fence INTEGER,revision INTEGER,manual_pause INTEGER)")
                db.execSQL("INSERT INTO tasks VALUES('task','owner','conversation','request','input','configuration','provider','model','OFF','revision','UNCERTAIN_SIDE_EFFECT',41,7,1)")
                db.execSQL("CREATE TABLE slot(singleton INTEGER PRIMARY KEY,task_id TEXT,fence INTEGER)");db.execSQL("INSERT INTO slot VALUES(1,'task',41)")
                db.execSQL("CREATE TABLE calls(task_id TEXT,call_id TEXT,tool TEXT,input_hash TEXT,state TEXT,result_hash TEXT,PRIMARY KEY(task_id,call_id))")
                db.execSQL("INSERT INTO calls VALUES('task','unknown','create_material_file','input','UNKNOWN',NULL)")
                db.execSQL("INSERT INTO calls VALUES('task','receipt','get_time_info','input','VERIFIED','original-receipt')")
                db.execSQL("CREATE TABLE usage(request_id TEXT PRIMARY KEY,task_id TEXT,role TEXT,provenance TEXT,prompt_tokens INTEGER,completion_tokens INTEGER,cached_tokens INTEGER,created_at INTEGER,total_tokens INTEGER,provider_id TEXT,model_id TEXT,reasoning TEXT,configuration_reference TEXT)")
                db.execSQL("INSERT INTO usage VALUES('unknown','task','main','unknown',NULL,NULL,NULL,NULL,NULL,'provider','model','OFF','configuration')");db.version=5
            }
            AgentTaskStore(context,name).use { store ->
                assertEquals(11,store.readableDatabase.version)
                assertEquals(41L,store.get("owner","task").fence);assertTrue(store.get("owner","task").manualPaused)
                assertEquals("UNCERTAIN_SIDE_EFFECT",store.get("owner","task").state)
                store.readableDatabase.rawQuery("SELECT state,result_hash FROM calls WHERE call_id='receipt'",null).use { assertTrue(it.moveToFirst());assertEquals("original-receipt",it.getString(1)) }
                store.readableDatabase.rawQuery("SELECT provenance,prompt_tokens,total_tokens FROM usage",null).use { assertTrue(it.moveToFirst());assertEquals("unknown",it.getString(0));assertTrue(it.isNull(1));assertTrue(it.isNull(2)) }
                assertTrue(runCatching { store.resume("owner","task") }.isFailure)
                store.readableDatabase.rawQuery("SELECT task_id,fence FROM slot",null).use { assertTrue(it.moveToFirst());assertEquals("task",it.getString(0));assertEquals(41L,it.getLong(1)) }
            }
        } finally { path.delete() }
    }
}
