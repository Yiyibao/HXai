package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.CredentialVault
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AgentAccountProcessProbe {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val args get()=InstrumentationRegistry.getArguments()
    private val fixture get()=requireNotNull(args.getString("fixture")).also { require(it.matches(Regex("[a-f0-9-]{36}"))) }
    private val stage get()=requireNotNull(args.getString("stage"))
    private val folder get()=context.cacheDir.resolve("account-process-$fixture")
    private val name get()="qa-account-process-$fixture.db"
    private fun durable(file: File,value: String) { file.parentFile!!.mkdirs();file.outputStream().use { it.write(value.toByteArray());it.fd.sync() } }
    @Test fun prepare()=runBlocking {
        check(context.packageName=="org.hxsi.agent.nativeqa");require(stage in setOf("revoked","unknown","settled"))
        val store=AgentTaskStore(context,name)
        val a=AgentAccountAuthority(store,CredentialVault(context,"qa-account-process"))
        a.enroll("account",setOf("owner"))
        val t=store.accept("owner",fixture,"once","synthetic goal","encrypted-ref","provider","locked","HIGH","config")
        val local=store.claim(t.owner,t.id);val host=store.supervise(local,Job())
        store.requestHandoff(local,"out","cloud");store.recordExecutorStopped(host.stop("out"))
        val token=a.issue("account",t.owner,t.id,"cloud",System.currentTimeMillis()+3_600_000)
        val api=a.transport(token);val cloud=api.activate(t.owner,t.id,"out","cloud")
        val credential=a.protect("account",t.owner,t.id,"synthetic-never-real-secret")
        val receipt=api.admitProvider(cloud,credential,"once","input",AgentRequestSelection(t.providerId,t.modelId,t.reasoning,t.configurationReference))
        durable(folder.resolve("task"),t.id);durable(folder.resolve("token"),token.value)
        durable(folder.resolve("fence"),cloud.fence.toString());durable(folder.resolve("request"),receipt.requestId)
        if(stage=="unknown") durable(folder.resolve("effect"),"one physical synthetic effect")
        if(stage=="settled") store.accountTransaction { db ->
            db.execSQL("INSERT INTO agent_hosted_results(request_id,response_text,response_hash) VALUES(?,?,?)",arrayOf(receipt.requestId,"durable output",AgentTaskStore.hash("durable output")))
            store.finishRequest(cloud,receipt.requestId,null)
        }
        if(stage=="revoked") a.revoke("account")
        durable(folder.resolve("ready"),stage)
        Thread.sleep(120_000);fail("Host must terminate actual process")
    }
    @Test fun verify() {
        val id=folder.resolve("task").readText();val request=folder.resolve("request").readText()
        AgentTaskStore(context,name).use { store ->
            store.recover()
            val task=store.get("owner",id);val lease=AgentLease(task,folder.resolve("fence").readText().toLong(),"cloud")
            val a=AgentAccountAuthority(store,CredentialVault(context,"qa-account-process"))
            val api=a.transport(AgentBearer(folder.resolve("token").readText()))
            assertEquals(1,store.physicalRequestCount("owner",id))
            assertTrue(runCatching { store.check(lease) }.isFailure)
            val other=store.accept("other",fixture,"next","synthetic","ref","p","m","LOW","config")
            assertTrue(runCatching { store.claim(other.owner,other.id) }.isFailure)
            if(stage=="revoked") {
                assertTrue(runCatching { api.task("owner",id) }.isFailure)
                assertTrue(runCatching { a.issue("account","owner",id,"cloud",System.currentTimeMillis()+60_000) }.isFailure)
            } else if(stage=="settled") {
                assertEquals("durable output",api.result(lease,request));assertEquals("STOP_REQUESTED",task.state)
            } else {
                assertNull(api.result(lease,request));assertEquals("UNCERTAIN_SIDE_EFFECT",task.state)
                assertEquals("one physical synthetic effect",folder.resolve("effect").readText())
            }
            assertEquals("locked",task.modelId);assertEquals("HIGH",task.reasoning)
            store.readableDatabase.rawQuery("SELECT provenance,total_tokens FROM usage WHERE request_id=?",arrayOf(request)).use {
                assertTrue(it.moveToFirst());assertEquals("unknown",it.getString(0));assertTrue(it.isNull(1))
            }
        }
        println("QA_ACCOUNT_VERIFIED_$stage")
    }
}
