package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ExecutorHandoffRuntimeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun task(store: AgentTaskStore,owner: String="owner")=store.accept(owner,"conversation",UUID.randomUUID().toString(),"synthetic goal","encrypted-ref","provider","model","HIGH","config-hash")
    private fun withStore(block: suspend CoroutineScope.(AgentTaskStore,String)->Unit)=runBlocking {
        val name="qa-handoff-${UUID.randomUUID()}.db"
        try { AgentTaskStore(context,name).use { block(it,name) } }
        finally { SQLiteCleanup.delete(context.noBackupFilesDir.resolve(name)) }
    }
    private fun executor(store: AgentTaskStore,lease: AgentLease): SupervisedAgentExecutor = store.supervise(lease,Job())
    private suspend fun stopped(store: AgentTaskStore,lease: AgentLease,id: String="transfer",target: String="synthetic-cloud"): ExecutorHandoff {
        val host=executor(store,lease)
        store.requestHandoff(lease,id,target)
        return store.recordExecutorStopped(host.stop(id))
    }
    private fun command(store: AgentTaskStore,task: AgentTask,action: String,payload: String="") {
        val latest=store.get(task.owner,task.id)
        store.command(task.owner,task.id,UUID.randomUUID().toString(),latest.revision,latest.fence,action,payload)
    }

    @Test fun twoHostsShareOneAuthorityAndTransferBothDirectionsWithoutChangingModel()=withStore { store,_ ->
        val t=task(store);val other=task(store,"other-profile");val local=store.claim(t.owner,t.id)
        val request=store.beginRequest(local,"main","synthetic request",AgentRequestSelection(t.providerId,t.modelId,t.reasoning,t.configurationReference))
        store.finishRequest(local,request,null)
        stopped(store,local)
        assertTrue(runCatching { store.claim(other.owner,other.id) }.exceptionOrNull() is AgentTaskBlocked)
        val cloud=store.activateHandoff(t.owner,t.id,"transfer","synthetic-cloud")
        store.check(cloud);assertTrue(cloud.fence>local.fence)
        assertEquals(t.configurationRevision,cloud.task.configurationRevision);assertEquals("HIGH",cloud.task.reasoning)
        assertEquals(cloud,store.activateHandoff(t.owner,t.id,"transfer","synthetic-cloud"))
        assertTrue(runCatching { store.check(local) }.isFailure)
        assertTrue(runCatching { store.finish(local,"SUCCEEDED") }.isFailure)
        assertTrue(runCatching { store.finishRequest(local,request,null) }.isFailure)
        assertTrue(runCatching { store.check(AgentLease(cloud.task,cloud.fence,"local")) }.isFailure)
        assertTrue(runCatching { store.publishPlan(local,1,AgentPlan(emptyList(),emptyList()),emptySet()) }.isFailure)
        stopped(store,cloud,"return","local")
        val returned=store.activateHandoff(t.owner,t.id,"return","local")
        assertTrue(returned.fence>cloud.fence);store.check(returned)
        assertTrue(runCatching { store.activateHandoff(t.owner,t.id,"transfer","synthetic-cloud") }.isFailure)
        store.finish(returned,"COMPLETED_UNVERIFIED")
        assertEquals("COMPLETED_UNVERIFIED",store.get(t.owner,t.id).state)
        assertTrue(store.claim(other.owner,other.id).fence>returned.fence)
    }
    @Test fun requestPersistsBeforeStopAndNoTimeoutCanSubstituteForJobExit()=withStore { store,_ ->
        val t=task(store);val lease=store.claim(t.owner,t.id)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val job=launch(Dispatchers.Default) { withContext(NonCancellable) { entered.complete(Unit);release.await() } }
        entered.await();val host=store.supervise(lease,job)
        val requested=store.requestHandoff(lease,"lost-request-ack","cloud")
        assertEquals(requested,store.requestHandoff(lease,"lost-request-ack","cloud"))
        assertTrue(runCatching { store.requestHandoff(lease,"lost-request-ack","changed") }.isFailure)
        assertTrue(runCatching { store.beginCall(lease,"late","get_time_info","{}") }.isFailure)
        val stopping=async(Dispatchers.Default) { store.recordExecutorStopped(host.stop("lost-request-ack")) }
        try {
            assertNull(withTimeoutOrNull(100) { stopping.await() })
            assertEquals("REQUESTED",store.readHandoff(t.owner,t.id,"lost-request-ack")!!.state)
            assertTrue(runCatching { store.activateHandoff(t.owner,t.id,"lost-request-ack","cloud") }.isFailure)
            assertTrue(runCatching { store.abandonHandoff(t.owner,t.id,"lost-request-ack") }.isFailure)
        } finally { release.complete(Unit) }
        assertEquals("STOPPED",stopping.await().state)
        store.check(store.activateHandoff(t.owner,t.id,"lost-request-ack","cloud"))
    }
    @Test fun unrelatedCompletedJobCannotForgeRegisteredExecutorStop()=withStore { store,_ ->
        val t=task(store);val lease=store.claim(t.owner,t.id);executor(store,lease)
        assertTrue(runCatching { store.supervise(lease,Job()) }.isFailure)
        store.requestHandoff(lease,"transfer","cloud")
        val impostor=SupervisedAgentExecutor.bind(lease,Job())
        assertTrue(runCatching { store.recordExecutorStopped(impostor.stop("transfer")) }.isFailure)
        assertTrue(runCatching { SupervisedAgentExecutor.bind(lease,Job().also { it.complete() }) }.isFailure)
        assertEquals("REQUESTED",store.readHandoff(t.owner,t.id,"transfer")!!.state)
    }
    @Test fun unknownEffectBlocksActivationAndAbortUntilIndependentReadback()=withStore { store,_ ->
        val t=task(store);val lease=store.claim(t.owner,t.id);val host=executor(store,lease)
        store.beginCall(lease,"once","create_material_file","synthetic")
        val effect=context.cacheDir.resolve("handoff-effect-${UUID.randomUUID()}")
        try {
            effect.writeText("effect-before-ack")
            store.requestHandoff(lease,"transfer","cloud")
            store.finish(lease,"INTERRUPTED")
            store.recordExecutorStopped(host.stop("transfer"))
            assertEquals("UNCERTAIN_SIDE_EFFECT",store.get(t.owner,t.id).state)
            assertTrue(runCatching { store.activateHandoff(t.owner,t.id,"transfer","cloud") }.exceptionOrNull() is AgentTaskBlocked)
            assertTrue(runCatching { store.abandonHandoff(t.owner,t.id,"transfer") }.exceptionOrNull() is AgentTaskBlocked)
            assertEquals("effect-before-ack",effect.readText())
            store.finishCall(lease,"once",AgentTaskStore.hash(effect.readText()),true)
            store.reconcileKnownResults(lease)
            val cloud=store.activateHandoff(t.owner,t.id,"transfer","cloud")
            assertTrue(runCatching { store.finishCall(lease,"once","forged",true) }.isFailure)
            assertTrue(runCatching { store.beginCall(cloud,"once","create_material_file","synthetic") }.isFailure)
        } finally { effect.delete() }
    }
    @Test fun stoppedJournalSurvivesReopenAndLostActivationAckDoesNotReexecute()=withStore { store,name ->
        val t=task(store);val lease=store.claim(t.owner,t.id);stopped(store,lease)
        store.close()
        AgentTaskStore(context,name).use { reopened ->
            reopened.recover()
            assertEquals("STOPPED",reopened.readHandoff(t.owner,t.id,"transfer")!!.state)
            val cloud=reopened.activateHandoff(t.owner,t.id,"transfer","synthetic-cloud")
            val replay=reopened.activateHandoff(t.owner,t.id,"transfer","synthetic-cloud")
            assertEquals(cloud,replay);assertTrue(cloud.fence>lease.fence)
            reopened.close()
            AgentTaskStore(context,name).use { third ->
                third.recover()
                val other=task(third,"other")
                assertTrue(runCatching { third.claim(other.owner,other.id) }.exceptionOrNull() is AgentTaskBlocked)
                assertEquals("STOP_REQUESTED",third.get(t.owner,t.id).state)
                assertTrue(runCatching { third.check(cloud) }.isFailure)
            }
        }
    }
    @Test fun requestedWithoutProofStaysOccupiedAcrossReopen()=withStore { store,name ->
        val t=task(store);val lease=store.claim(t.owner,t.id)
        store.requestHandoff(lease,"no-proof","cloud");store.finish(lease,"INTERRUPTED");store.close()
        AgentTaskStore(context,name).use { reopened ->
            reopened.recover();assertEquals("STOP_REQUESTED",reopened.get(t.owner,t.id).state)
            val other=task(reopened,"other")
            assertTrue(runCatching { reopened.claim(other.owner,other.id) }.exceptionOrNull() is AgentTaskBlocked)
            assertTrue(runCatching { reopened.activateHandoff(t.owner,t.id,"no-proof","cloud") }.isFailure)
        }
    }
    @Test fun deviceInputAndConfirmationAreIndependentAndValidRecoveryIsAccepted()=withStore { store,_ ->
        val t=task(store);val lease=store.claim(t.owner,t.id);stopped(store,lease)
        command(store,t,"wait_input");command(store,t,"wait_device");command(store,t,"require_device_confirmation")
        assertTrue(runCatching { store.activateHandoff(t.owner,t.id,"transfer","synthetic-cloud") }.isFailure)
        command(store,t,"device_available")
        assertTrue(store.control(t.owner,t.id).inputBlocked);assertTrue(store.control(t.owner,t.id).deviceConfirmation)
        command(store,t,"confirm_device")
        assertTrue(runCatching { store.activateHandoff(t.owner,t.id,"transfer","synthetic-cloud") }.isFailure)
        // Providing input changes the goal, so the old transfer must be safely abandoned.
        command(store,t,"provide_input","supplement")
        assertTrue(runCatching { store.activateHandoff(t.owner,t.id,"transfer","synthetic-cloud") }.isFailure)
        val ended=store.abandonHandoff(t.owner,t.id,"transfer")
        assertEquals("INTERRUPTED",ended.state);assertTrue(ended.fence>lease.fence)
        command(store,t,"resume")
        val resumed=store.claim(t.owner,t.id);store.check(resumed)
        assertEquals(t.modelId,resumed.task.modelId);assertEquals("HIGH",resumed.task.reasoning)
    }
    @Test fun deviceAvailabilityAloneDoesNotClearConfirmationAndValidTransferEventuallyProceeds()=withStore { store,_ ->
        val t=task(store);val lease=store.claim(t.owner,t.id);stopped(store,lease)
        command(store,t,"wait_device");command(store,t,"require_device_confirmation")
        command(store,t,"device_available")
        assertTrue(runCatching { store.activateHandoff(t.owner,t.id,"transfer","synthetic-cloud") }.isFailure)
        command(store,t,"confirm_device")
        val cloud=store.activateHandoff(t.owner,t.id,"transfer","synthetic-cloud")
        store.check(cloud);assertEquals(t.configurationReference,cloud.task.configurationReference)
    }
    @Test fun pauseRevokeAndCancelCannotBeClearedByHandoffOrAbort()=withStore { store,_ ->
        val t=task(store);val lease=store.claim(t.owner,t.id);stopped(store,lease)
        command(store,t,"pause");command(store,t,"wait_input");command(store,t,"require_device_confirmation");command(store,t,"revoke")
        assertTrue(runCatching { store.activateHandoff(t.owner,t.id,"transfer","synthetic-cloud") }.isFailure)
        val ended=store.abandonHandoff(t.owner,t.id,"transfer")
        assertTrue(ended.manualPaused);val control=store.control(t.owner,t.id)
        assertEquals("REVOKED",control.authorization);assertTrue(control.grantEpoch>1)
        assertTrue(control.inputBlocked);assertTrue(control.deviceConfirmation)
        assertTrue(runCatching { command(store,t,"resume") }.isFailure)
        assertTrue(runCatching { store.check(lease) }.isFailure)
        val canceled=task(store);val next=store.claim(canceled.owner,canceled.id);stopped(store,next)
        command(store,canceled,"cancel")
        assertTrue(runCatching { store.activateHandoff(canceled.owner,canceled.id,"transfer","synthetic-cloud") }.isFailure)
        assertEquals("CANCELED",store.abandonHandoff(canceled.owner,canceled.id,"transfer").state)
    }
    @Test fun identityMismatchAndConcurrentDatabaseClientsCannotStealTransfer()=withStore { store,name ->
        val t=task(store);val lease=store.claim(t.owner,t.id);stopped(store,lease)
        assertTrue(runCatching { store.readHandoff("different",t.id,"transfer") }.isFailure)
        assertTrue(runCatching { store.activateHandoff("different",t.id,"transfer","synthetic-cloud") }.isFailure)
        assertTrue(runCatching { store.activateHandoff(t.owner,t.id,"transfer","wrong-host") }.isFailure)
        AgentTaskStore(context,name).use { second ->
            val results=listOf(store,second).map { db -> async(Dispatchers.IO) { db.activateHandoff(t.owner,t.id,"transfer","synthetic-cloud") } }.awaitAll()
            assertEquals(results[0],results[1]);assertEquals(lease.fence+1,results[0].fence)
        }
    }
    @Test fun activationTransactionFailureRollsBackFenceTaskAndJournal()=withStore { store,_ ->
        val t=task(store);val lease=store.claim(t.owner,t.id);stopped(store,lease)
        store.writableDatabase.execSQL("CREATE TEMP TRIGGER inject_activation_failure BEFORE UPDATE ON executor_handoffs WHEN NEW.state='ACTIVATED' BEGIN SELECT RAISE(ABORT,'synthetic fault'); END")
        assertTrue(runCatching { store.activateHandoff(t.owner,t.id,"transfer","synthetic-cloud") }.isFailure)
        assertEquals(lease.fence,store.get(t.owner,t.id).fence)
        assertEquals("STOPPED",store.readHandoff(t.owner,t.id,"transfer")!!.state)
        store.writableDatabase.execSQL("DROP TRIGGER inject_activation_failure")
        assertEquals(lease.fence+1,store.activateHandoff(t.owner,t.id,"transfer","synthetic-cloud").fence)
    }
    @Test fun schema10MigrationPreservesModelUnknownUsageAndIndependentControls()=withStore { store,name ->
        val t=task(store);val lease=store.claim(t.owner,t.id)
        val request=store.beginRequest(lease,"main","synthetic",AgentRequestSelection(t.providerId,t.modelId,t.reasoning,t.configurationReference))
        command(store,t,"pause");command(store,t,"require_device_confirmation");command(store,t,"revoke")
        val db=store.writableDatabase
        db.execSQL("DROP TABLE executor_handoffs")
        db.execSQL("CREATE TABLE slot_v10(singleton INTEGER PRIMARY KEY CHECK(singleton=1),task_id TEXT REFERENCES tasks(id),fence INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("INSERT INTO slot_v10 SELECT singleton,task_id,fence FROM slot")
        db.execSQL("DROP TABLE slot");db.execSQL("ALTER TABLE slot_v10 RENAME TO slot");db.version=10;store.close()
        AgentTaskStore(context,name).use { upgraded ->
            upgraded.recover();assertEquals(13,upgraded.readableDatabase.version)
            val restored=upgraded.get(t.owner,t.id)
            assertEquals(t.configurationReference,restored.configurationReference);assertTrue(restored.manualPaused)
            assertEquals("UNCERTAIN_SIDE_EFFECT",restored.state)
            assertEquals("REVOKED",upgraded.control(t.owner,t.id).authorization)
            assertTrue(upgraded.control(t.owner,t.id).deviceConfirmation)
            upgraded.readableDatabase.rawQuery("SELECT provenance,total_tokens FROM usage WHERE request_id=?",arrayOf(request)).use {
                assertTrue(it.moveToFirst());assertEquals("unknown",it.getString(0));assertTrue(it.isNull(1))
            }
            val other=task(upgraded,"other")
            assertTrue(runCatching { upgraded.claim(other.owner,other.id) }.exceptionOrNull() is AgentTaskBlocked)
        }
    }
}
