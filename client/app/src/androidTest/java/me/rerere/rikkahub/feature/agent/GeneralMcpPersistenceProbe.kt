package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Host driver must observe durable readiness + live PID, then force-stop. Never batch-run. */
@RunWith(AndroidJUnit4::class)
class GeneralMcpPersistenceProbe {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture get()=requireNotNull(InstrumentationRegistry.getArguments().getString("fixture")).also { require(it.matches(Regex("[a-f0-9-]{36}"))) }
    private val owner get()="qa-general-$fixture"
    private val mode get()=InstrumentationRegistry.getArguments().getString("mode") ?: "installation"
    private val marker get()=context.noBackupFilesDir.resolve("mcp-probe-$fixture.txt")
    private val database get()="general-process-$fixture.db"
    @Test fun prepare()=runBlocking {
        val server=GeneralMcpFixture()
        ExtensionInstallationStore(context).use { db ->
            val p=db.install(owner,"one",0,server.config(),"")
            val live=db.command(owner,"one",p.id,p.revision,"enable")
            ExtensionToolGrantStore(context).use { it.grant(owner, live.id, "read_a", live.grantEpoch, ExtensionToolAdmission.contractHash(live.config.tools.first { it.name == "read_a" })) }
            if(mode=="revoked") db.command(owner,"one",live.id,live.revision,"revoke")
            if(mode in setOf("unknown","old_approval")) AgentTaskStore(context,database).use { store ->
                val task=store.accept(owner,fixture,"once","Read approved service","ref","provider","model","OFF","revision")
                val lease=store.claim(owner,task.id)
                val tool=UIMessagePart.Tool("once",ExtensionMcpRuntime.TOOL,server.input(live).toString(),approvalState=ToolApprovalState.Pending)
                store.waitForToolApproval(lease,listOf(tool));store.decideTool(owner,task.id,tool,ToolApprovalState.Approved,1);store.beginCall(lease,tool.toolCallId,tool.toolName,tool.input)
                if(mode=="unknown") {
                    ExtensionMcpRuntime.invoke(owner,server.input(live),null,"once") { store.checkToolDispatch(lease,tool) }
                    assertEquals(1,server.reads.get())
                } else {
                    val disabled=db.command(owner,"one",live.id,live.revision,"disable")
                    val replacement=db.install(owner,"one",disabled.revision,server.config(),"")
                    db.command(owner,"one",replacement.id,replacement.revision,"enable")
                }
                marker.resolveSibling(marker.name+".task").outputStream().use { it.write((task.id+"\n"+tool.input+"\n"+lease.fence).toByteArray());it.fd.sync() }
            }
        }
        server.close()
        marker.resolveSibling(marker.name+".ready").outputStream().use { it.write(mode.toByteArray());it.fd.sync() }
        println("QA_GENERAL_READY_$mode")
        Thread.sleep(120_000)
        fail("Host did not stop prepared process")
    }
    @Test fun verify()=runBlocking {
        ExtensionInstallationStore(context).use { db ->
            val current=requireNotNull(db.get(owner,"one"))
            when(mode) {
                "installation" -> { assertEquals(marker.readText(),current.id);db.check(current);assertNotNull(ExtensionMcpRuntime.toolFor(owner)) }
                "revoked" -> { assertEquals("REVOKED",current.state);assertNull(ExtensionMcpRuntime.toolFor(owner));assertTrue(runCatching { db.command(owner,"one",current.id,current.revision,"enable") }.isFailure) }
                else -> {
                    val (id,input,fence)=marker.resolveSibling(marker.name+".task").readText().split("\n",limit=3)
                    AgentTaskStore(context,database).use { store ->
                        store.recover();assertEquals("UNCERTAIN_SIDE_EFFECT",store.get(owner,id).state)
                        assertTrue(runCatching { store.resume(owner,id) }.isFailure)
                        val other=store.accept("other",fixture,"other","Other task","ref","p","m","OFF","r")
                        assertTrue(runCatching { store.claim(other.owner,other.id) }.isFailure)
                        var reached=false
                        val tool=UIMessagePart.Tool("once",ExtensionMcpRuntime.TOOL,input)
                        assertTrue(runCatching { ExtensionMcpRuntime.invoke(owner,Json.parseToJsonElement(input).jsonObject,null,"once") {
                            reached=true;store.checkToolDispatch(AgentLease(store.get(owner,id),fence.toLong()),tool)
                        } }.isFailure)
                        assertEquals(mode=="unknown",reached)
                    }
                }
            }
        }
    }
}
