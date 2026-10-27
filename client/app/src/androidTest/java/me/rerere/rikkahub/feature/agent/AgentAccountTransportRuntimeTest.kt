package me.rerere.rikkahub.feature.agent

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import me.rerere.rikkahub.data.datastore.CredentialVault
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AgentAccountTransportRuntimeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val secret="synthetic-provider-secret-only-for-a26"
    private val tables=listOf("agent_executor_bindings","agent_hosted_results","agent_provider_commands","agent_transport_audit","agent_host_credentials","agent_sessions","agent_account_profiles","agent_accounts")
    private fun task(store: AgentTaskStore,owner: String="owner")=store.accept(owner,"conversation",UUID.randomUUID().toString(),"synthetic goal","encrypted-ref","provider","locked-model","HIGH","config-hash")
    private fun selection(t: AgentTask)=AgentRequestSelection(t.providerId,t.modelId,t.reasoning,t.configurationReference)
    private fun fixture(block: suspend CoroutineScope.(AgentTaskStore,AgentAccountAuthority,String)->Unit)=runBlocking {
        val name="qa-account-${UUID.randomUUID()}.db"
        try { AgentTaskStore(context,name).use { store ->
            val authority=AgentAccountAuthority(store,CredentialVault(context,"qa-account-transport"))
            authority.enroll("account",setOf("owner","second-profile"))
            block(store,authority,name)
        } } finally { SQLiteCleanup.delete(context.noBackupFilesDir.resolve(name)) }
    }
    private fun client(a: AgentAccountAuthority,t: AgentTask,executor: String="cloud")=a.transport(a.issue("account",t.owner,t.id,executor,System.currentTimeMillis()+60_000))
    private suspend fun stop(store: AgentTaskStore,lease: AgentLease,request: String,target: String): ExecutorHandoff {
        val job=Job();val supervised=store.supervise(lease,job)
        store.requestHandoff(lease,request,target)
        return store.recordExecutorStopped(supervised.stop(request))
    }
    private suspend fun cloud(store: AgentTaskStore,a: AgentAccountAuthority,t: AgentTask): Pair<AgentAccountTransport,AgentLease> {
        stop(store,store.claim(t.owner,t.id),"out","cloud")
        val transport=client(a,t)
        return transport to transport.activate(t.owner,t.id,"out","cloud")
    }
    private fun rejected(block: ()->Any?) { assertTrue("Request must be rejected",runCatching(block).isFailure) }

    @Test fun validAccountTransfersAndRoundTripsOriginalSlotAndLockedModel()=fixture { store,a,_ ->
        val t=task(store);val other=task(store,"second-profile")
        val (api,lease)=cloud(store,a,t)
        assertEquals("locked-model",api.task(t.owner,t.id).modelId)
        assertEquals(lease,api.activate(t.owner,t.id,"out","cloud"))
        rejected { store.claim(other.owner,other.id) }
        val credential=a.protect("account",t.owner,t.id,secret)
        val admission=api.admitProvider(lease,credential,"request-1","input",selection(t))
        assertTrue(admission.dispatch)
        assertEquals(secret,api.checkProviderSecret(lease,credential))
        // Only trusted provider adapter returns usage, never client JSON.
        store.finishRequest(lease,admission.requestId,null)
        assertEquals(1,store.physicalRequestCount(t.owner,t.id))
        stop(store,lease,"back","local")
        val returned=store.activateHandoff(t.owner,t.id,"back","local")
        assertTrue(returned.fence>lease.fence)
        store.finish(returned,"COMPLETED_UNVERIFIED")
        assertEquals("COMPLETED_UNVERIFIED",store.get(t.owner,t.id).state)
        assertTrue(store.claim(other.owner,other.id).fence>returned.fence)
    }

    @Test fun bearerCannotChooseAnotherOwnerTaskExecutorOrAccount()=fixture { store,a,_ ->
        val t=task(store);val other=task(store,"second-profile");val api=client(a,t)
        rejected { api.task(other.owner,other.id) }
        rejected { api.task(t.owner,other.id) }
        rejected { api.task(other.owner,t.id) }
        rejected { api.activate(t.owner,t.id,"out","attacker") }
        rejected { a.transport(AgentBearer("x".repeat(43))).task(t.owner,t.id) }
        rejected { a.transport(AgentBearer("invalid")).task(t.owner,t.id) }
        rejected { a.enroll("other-account",setOf("owner")) }
        store.readableDatabase.rawQuery("SELECT 1 FROM agent_accounts WHERE id='other-account'",null).use { assertFalse(it.moveToFirst()) }
        assertEquals(t,api.task(t.owner,t.id))
    }

    @Test fun expiredAndRevokedSessionsAreCheckedAgainWithoutChangingTaskControls()=fixture { store,_,_ ->
        var time=10_000L
        val a=AgentAccountAuthority(store,CredentialVault(context,"qa-account-transport")) { time }
        val t=task(store);val token=a.issue("account",t.owner,t.id,"cloud",10_010)
        val api=a.transport(token);assertEquals(t,api.task(t.owner,t.id))
        time=10_010;rejected { api.task(t.owner,t.id) }
        assertEquals("ACTIVE",store.control(t.owner,t.id).authorization)
        val replacement=a.issue("account",t.owner,t.id,"cloud",10_020)
        a.revokeSession(replacement);rejected { a.transport(replacement).task(t.owner,t.id) }
        assertFalse(token.toString().contains(token.value))
    }

    @Test fun lostAdmissionAckAndCompletedAckCannotCreateAnotherPhysicalRequest()=fixture { store,a,_ ->
        val t=task(store);val (api,lease)=cloud(store,a,t);val credential=a.protect("account",t.owner,t.id,secret)
        val first=api.admitProvider(lease,credential,"stable-command","input",selection(t))
        val retry=api.admitProvider(lease,credential,"stable-command","input",selection(t))
        assertFalse(retry.dispatch);assertEquals(first.requestId,retry.requestId)
        rejected { api.admitProvider(lease,credential,"stable-command","changed",selection(t)) }
        rejected { api.admitProvider(lease,credential,"another-command","input",selection(t)) }
        store.finishRequest(lease,first.requestId,null)
        assertFalse(api.admitProvider(lease,credential,"stable-command","input",selection(t)).dispatch)
        assertEquals(1,store.physicalRequestCount(t.owner,t.id))
        store.readableDatabase.rawQuery("SELECT provenance,total_tokens FROM usage WHERE request_id=?",arrayOf(first.requestId)).use {
            assertTrue(it.moveToFirst());assertEquals("unknown",it.getString(0));assertTrue(it.isNull(1))
        }
    }

    @Test fun accountRevokePersistsAndCannotReleaseUnknownOrReenrollItAway()=fixture { store,a,name ->
        val t=task(store);val (api,lease)=cloud(store,a,t);val credential=a.protect("account",t.owner,t.id,secret)
        api.admitProvider(lease,credential,"command","input",selection(t))
        a.revoke("account")
        rejected { api.admitProvider(lease,credential,"later","input",selection(t)) }
        rejected { api.checkProviderSecret(lease,credential) }
        a.enroll("account",setOf("owner"))
        rejected { client(a,t) }
        store.finish(lease,"INTERRUPTED")
        assertEquals("UNCERTAIN_SIDE_EFFECT",store.get(t.owner,t.id).state)
        store.close()
        AgentTaskStore(context,name).use { reopened ->
            reopened.recover();val next=task(reopened,"second-profile")
            rejected { reopened.claim(next.owner,next.id) }
            val again=AgentAccountAuthority(reopened,CredentialVault(context,"qa-account-transport"))
            rejected { client(again,t) }
            assertEquals("ACTIVE",reopened.control(t.owner,t.id).authorization)
            assertEquals(1,reopened.physicalRequestCount(t.owner,t.id))
        }
    }

    @Test fun rotationRevocationAndCrossTaskCredentialChecksNeverChangeTheModelOrPause()=fixture { store,a,_ ->
        val t=task(store);val (api,lease)=cloud(store,a,t)
        val old=a.protect("account",t.owner,t.id,secret)
        val current=a.protect("account",t.owner,t.id,secret+"-rotated",old)
        rejected { api.checkProviderSecret(lease,old) }
        assertEquals(secret+"-rotated",api.checkProviderSecret(lease,current))
        val other=task(store);val wrong=a.protect("account",other.owner,other.id,secret)
        rejected { api.checkProviderSecret(lease,wrong) }
        rejected { api.admitProvider(lease,current,"changed-model","input",selection(t).copy(modelId="other-model")) }
        rejected { api.admitProvider(lease,current,"changed-reasoning","input",selection(t).copy(reasoning="LOW")) }
        rejected { api.admitProvider(lease,current,"changed-config","input",selection(t).copy(configurationReference="changed")) }
        a.revokeCredential("account",t.owner,t.id,current)
        rejected { api.checkProviderSecret(lease,current) }
        rejected { a.protect("account",t.owner,t.id,secret,current) }
        assertEquals(t.configurationRevision,store.get(t.owner,t.id).configurationRevision)
        assertEquals(0,store.physicalRequestCount(t.owner,t.id))
    }

    @Test fun protectedSecretIsCiphertextAndTamperedBindingAndPlaintextFallbackAreRejected()=fixture { store,a,name ->
        val t=task(store);val (api,lease)=cloud(store,a,t);val handle=a.protect("account",t.owner,t.id,secret)
        val db=store.writableDatabase
        val reference=db.rawQuery("SELECT reference FROM agent_host_credentials WHERE id=?",arrayOf(handle.id)).use { assertTrue(it.moveToFirst());it.getString(0) }
        assertTrue(CredentialVault.isReference(reference))
        assertFalse(context.noBackupFilesDir.resolve(name).readBytes().toString(Charsets.ISO_8859_1).contains(secret))
        val blob=context.noBackupFilesDir.resolve("credentials-v1/"+reference.removePrefix("credential-v1:")+".bin")
        assertFalse(blob.readBytes().toString(Charsets.ISO_8859_1).contains(secret))
        db.execSQL("UPDATE agent_host_credentials SET reference=? WHERE id=?",arrayOf(secret,handle.id))
        rejected { api.checkProviderSecret(lease,handle) }
        val wrong=CredentialVault(context,"qa-account-transport").protect("{\"account\":\"attacker\",\"owner\":\"owner\",\"task\":\"${t.id}\",\"provider\":\"provider\",\"secret\":\"synthetic\"}")
        db.execSQL("UPDATE agent_host_credentials SET reference=? WHERE id=?",arrayOf(wrong,handle.id))
        rejected { api.checkProviderSecret(lease,handle) }
        assertEquals(0,store.physicalRequestCount(t.owner,t.id))
    }

    @Test fun freshCredentialsAndTokensDoNotClearIndependentBlockersOrGrantEpoch()=fixture { store,a,_ ->
        val t=task(store);val (api,lease)=cloud(store,a,t);val handle=a.protect("account",t.owner,t.id,secret)
        fun command(action: String) {
            val latest=store.get(t.owner,t.id)
            store.command(t.owner,t.id,UUID.randomUUID().toString(),latest.revision,latest.fence,action,"")
        }
        command("pause");command("require_device_confirmation")
        val replacement=a.protect("account",t.owner,t.id,secret+"-new",handle)
        val fresh=client(a,t)
        rejected { fresh.admitProvider(lease,replacement,"blocked","input",selection(t)) }
        assertTrue(store.get(t.owner,t.id).manualPaused);assertTrue(store.control(t.owner,t.id).deviceConfirmation)
        command("revoke")
        rejected { api.task(t.owner,t.id) }
        rejected { client(a,t) }
        assertEquals("REVOKED",store.control(t.owner,t.id).authorization)
    }

    @Test fun revokeSerializesWithAdmissionAcrossTwoActualDatabaseConnections()=fixture { store,a,name ->
        val t=task(store);val (api,lease)=cloud(store,a,t);val credential=a.protect("account",t.owner,t.id,secret)
        AgentTaskStore(context,name).use { second ->
            val other=AgentAccountAuthority(second,CredentialVault(context,"qa-account-transport"))
            val gate=java.util.concurrent.CountDownLatch(1)
            val admitting=async(Dispatchers.IO) { gate.await();runCatching { api.admitProvider(lease,credential,"race","input",selection(t)) } }
            val revoking=async(Dispatchers.IO) { gate.await();other.revoke("account") }
            gate.countDown();admitting.await();revoking.await()
            repeat(5) { rejected { api.admitProvider(lease,credential,"after-$it","input",selection(t)) } }
            assertTrue(store.physicalRequestCount(t.owner,t.id) in 0..1)
        }
    }

    @Test fun failedCommandCommitRollsBackJournalAndUnknownUsageTogether()=fixture { store,a,_ ->
        val t=task(store);val (api,lease)=cloud(store,a,t);val credential=a.protect("account",t.owner,t.id,secret)
        store.writableDatabase.execSQL("CREATE TRIGGER fail_account_command BEFORE INSERT ON agent_provider_commands BEGIN SELECT RAISE(ABORT,'synthetic commit failure'); END")
        rejected { api.admitProvider(lease,credential,"command","input",selection(t)) }
        assertEquals(0,store.physicalRequestCount(t.owner,t.id))
        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM calls WHERE task_id=?",arrayOf(t.id)).use { assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0)) }
        store.writableDatabase.execSQL("DROP TRIGGER fail_account_command")
        assertTrue(api.admitProvider(lease,credential,"command","input",selection(t)).dispatch)
        assertEquals(1,store.physicalRequestCount(t.owner,t.id))
    }

    @Test fun independentReadbackRejectsCorruptPersistedOutputAndNeverCallsItSuccess()=fixture { store,a,_ ->
        val t=task(store);stop(store,store.claim(t.owner,t.id),"out","cloud")
        val api=client(a,t);val credential=a.protect("account",t.owner,t.id,secret)
        store.writableDatabase.execSQL("CREATE TRIGGER corrupt_output AFTER INSERT ON agent_hosted_results BEGIN UPDATE agent_hosted_results SET response_hash='synthetic-corruption' WHERE request_id=NEW.request_id; END")
        var count=0
        val host=AgentAccountExecutor(store,api,AgentHostedProvider { _,_,_,_ -> count++;AgentHostedReply("untrusted-bytes-after-storage",null) })
        assertTrue(runCatching { host.run(t.owner,t.id,"out","cloud",credential,"once","input") }.isFailure)
        assertEquals("INTERRUPTED",store.get(t.owner,t.id).state)
        assertEquals(1,count)
        store.readableDatabase.rawQuery("SELECT response_text FROM agent_hosted_results",null).use { assertTrue(it.moveToFirst());assertEquals("untrusted-bytes-after-storage",it.getString(0)) }
    }

    @Test fun transportCannotStopAHostOrActivateWithoutIndependentJobExit()=fixture { store,a,_ ->
        val t=task(store);val lease=store.claim(t.owner,t.id);val api=client(a,t)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val job=launch(Dispatchers.Default) { withContext(NonCancellable) { entered.complete(Unit);release.await() } }
        entered.await();val supervisor=store.supervise(lease,job)
        store.requestHandoff(lease,"out","cloud")
        val stopping=async(Dispatchers.Default) { store.recordExecutorStopped(supervisor.stop("out")) }
        try {
            assertEquals("REQUESTED",api.handoff(t.owner,t.id,"out")?.state)
            rejected { api.activate(t.owner,t.id,"out","cloud") }
            assertFalse(stopping.isCompleted)
        } finally { release.complete(Unit) }
        assertEquals("STOPPED",stopping.await().state)
        assertTrue(api.activate(t.owner,t.id,"out","cloud").fence>lease.fence)
    }

    @Test fun supervisedReceiverUsesLockedTaskAndReadsBackDurableOutputWithoutClaimingGoalSuccess()=fixture { store,a,_ ->
        val t=task(store);val local=store.claim(t.owner,t.id)
        stop(store,local,"out","cloud")
        val api=client(a,t);val handle=a.protect("account",t.owner,t.id,secret)
        var calls=0
        val host=AgentAccountExecutor(store,api,AgentHostedProvider { locked,_,key,input ->
            assertEquals("HIGH",locked.reasoning);assertEquals("locked-model",locked.modelId)
            assertEquals(secret,key);assertEquals("synthetic-input",input);calls++
            rejected { store.check(local) }
            AgentHostedReply("synthetic output",null)
        })
        assertEquals("synthetic output",host.run(t.owner,t.id,"out","cloud",handle,"once","synthetic-input"))
        assertEquals(1,calls);assertEquals(1,store.physicalRequestCount(t.owner,t.id))
        assertEquals("COMPLETED_UNVERIFIED",store.get(t.owner,t.id).state)
        rejected { runBlocking { host.run(t.owner,t.id,"out","cloud",handle,"once","synthetic-input") } }
        assertEquals(1,calls)
        val other=task(store,"second-profile");assertTrue(store.claim(other.owner,other.id).fence>local.fence)
    }

    @Test fun receiverFailureAfterPhysicalEffectKeepsUnknownAndNeverReplays()=fixture { store,a,_ ->
        val t=task(store);stop(store,store.claim(t.owner,t.id),"out","cloud")
        val api=client(a,t);val handle=a.protect("account",t.owner,t.id,secret)
        val effect=context.cacheDir.resolve("account-effect-${UUID.randomUUID()}")
        try {
            val host=AgentAccountExecutor(store,api,AgentHostedProvider { _,_,_,_ ->
                check(!effect.exists());effect.writeText("one physical synthetic effect")
                error("synthetic response lost")
            })
            assertTrue(runCatching { host.run(t.owner,t.id,"out","cloud",handle,"once","input") }.isFailure)
            assertEquals("UNCERTAIN_SIDE_EFFECT",store.get(t.owner,t.id).state)
            assertTrue(runCatching { host.run(t.owner,t.id,"out","cloud",handle,"once","input") }.isFailure)
            assertEquals("one physical synthetic effect",effect.readText())
            assertEquals(1,store.physicalRequestCount(t.owner,t.id))
            rejected { store.claim("second-profile",task(store,"second-profile").id) }
        } finally { effect.delete() }
    }

    @Test fun twoActualStoreConnectionsCannotBindTheSameExecutorFence()=fixture { store,a,name ->
        val t=task(store);stop(store,store.claim(t.owner,t.id),"out","cloud")
        val api=client(a,t);val credential=a.protect("account",t.owner,t.id,secret)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val first=AgentAccountExecutor(store,api,AgentHostedProvider { _,request,_,_ ->
            // A physical usage callback may arrive before the supervising host exits.
            val lease=api.activate(t.owner,t.id,"out","cloud")
            store.finishRequest(lease,request,null)
            entered.complete(Unit);release.await();AgentHostedReply("first output",null)
        })
        val running=async { runCatching { first.run(t.owner,t.id,"out","cloud",credential,"first","input") } }
        entered.await()
        var duplicateCalls=0
        try {
            AgentTaskStore(context,name).use { secondStore ->
                val secondAuthority=AgentAccountAuthority(secondStore,CredentialVault(context,"qa-account-transport"))
                val second=AgentAccountExecutor(secondStore,client(secondAuthority,t),AgentHostedProvider { _,_,_,_ ->
                    duplicateCalls++;AgentHostedReply("duplicate output",null)
                })
                assertTrue("Another connection must not bind the same executor fence",runCatching {
                    second.run(t.owner,t.id,"out","cloud",credential,"different-command","input")
                }.isFailure)
                assertEquals(0,duplicateCalls)
            }
        } finally { release.complete(Unit);running.await() }
        assertEquals(1,store.physicalRequestCount(t.owner,t.id))
    }

    @Test fun schema11MigrationPreservesEveryOldRowAndFutureSchemaFailsClosed()=fixture { store,_,name ->
        val t=task(store);val lease=store.claim(t.owner,t.id)
        val request=store.beginRequest(lease,"main","input",selection(t))
        val db=store.writableDatabase
        tables.forEach { db.execSQL("DROP TABLE $it") };db.version=11
        val old=rows(db);store.close()
        AgentTaskStore(context,name).use { migrated ->
            assertEquals(13,migrated.writableDatabase.version)
            val current=rows(migrated.readableDatabase).filterKeys { it in old }
            assertEquals(old,current)
            assertEquals(lease.fence,migrated.get(t.owner,t.id).fence)
            migrated.readableDatabase.rawQuery("SELECT state FROM calls WHERE call_id=?",arrayOf(request)).use { assertTrue(it.moveToFirst());assertEquals("IN_FLIGHT",it.getString(0)) }
            migrated.writableDatabase.version=14
        }
        AgentTaskStore(context,name).use { rejected { it.writableDatabase } }
        SQLiteDatabase.openDatabase(context.noBackupFilesDir.resolve(name).path,null,SQLiteDatabase.OPEN_READONLY).use { assertEquals(14,it.version) }
    }

    @Test fun migrationFailureRollsBackNewTablesAndLeavesSchema11Rows()=fixture { store,_,name ->
        task(store);val db=store.writableDatabase
        tables.forEach { db.execSQL("DROP TABLE $it") }
        db.execSQL("CREATE INDEX agent_accounts ON tasks(owner)");db.version=11
        val before=rows(db);store.close()
        AgentTaskStore(context,name).use { rejected { it.writableDatabase } }
        SQLiteDatabase.openDatabase(context.noBackupFilesDir.resolve(name).path,null,SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(11,it.version);assertEquals(before,rows(it))
        }
    }
    @Test fun schema12MigrationPreservesAccountsTokensAndEncryptedCredentials()=fixture { store,a,name ->
        val t=task(store);val token=a.issue("account",t.owner,t.id,"cloud",System.currentTimeMillis()+60_000)
        val credential=a.protect("account",t.owner,t.id,secret)
        val db=store.writableDatabase
        db.execSQL("DROP TABLE agent_executor_bindings");db.version=12
        val before=rows(db);store.close()
        AgentTaskStore(context,name).use { migrated ->
            assertEquals(13,migrated.writableDatabase.version)
            assertEquals(before,rows(migrated.readableDatabase).filterKeys { it in before })
            val again=AgentAccountAuthority(migrated,CredentialVault(context,"qa-account-transport"))
            val api=again.transport(token);assertEquals(t,api.task(t.owner,t.id))
            stop(migrated,migrated.claim(t.owner,t.id),"out","cloud")
            val lease=api.activate(t.owner,t.id,"out","cloud")
            assertEquals(secret,api.checkProviderSecret(lease,credential))
        }
    }
    private fun rows(db: SQLiteDatabase): Map<String,List<List<String?>>> {
        val names=db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",null).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
        return names.associateWith { name -> db.rawQuery("SELECT * FROM $name ORDER BY rowid",null).use { c -> buildList { while(c.moveToNext()) add((0 until c.columnCount).map { if(c.isNull(it)) null else c.getString(it) }) } } }
    }
}
