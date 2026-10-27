package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart

/** Two actual instrumentation processes separated by a host force-stop. No network. */
@RunWith(AndroidJUnit4::class)
class SyntheticMcpPersistenceProbe {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture get()=requireNotNull(InstrumentationRegistry.getArguments().getString("fixture"))
        .also { require(it.matches(Regex("[a-f0-9-]{36}"))) }
    private val owner get()="qa-persist-$fixture"
    private val file get()=context.noBackupFilesDir.resolve("mcp-probe-$fixture.txt")
    private val mode get()=InstrumentationRegistry.getArguments().getString("mode") ?: "installation"
    private val database get()="qa-mcp-process-$fixture.db"
    @Test fun prepare() {
        val id=SyntheticMcpRuntime.installForQa(owner,18889,"fixture",AgentTaskStore.hash("synthetic"))
        file.outputStream().use { it.write(id.toByteArray());it.fd.sync() }
        assertNotNull(SyntheticMcpRuntime.toolFor(owner))
        when(mode) {
            "installation" -> Unit
            "revoked" -> McpInstallationStore(context).use { registry ->
                val pin=registry.get(owner)!!;registry.command(owner,pin.id,pin.revision,"revoke")
            }
            "old_approval", "unknown" -> {
                val server=if(mode=="unknown") SyntheticMcpServer().also {
                    SyntheticMcpRuntime.uninstallForQa(owner);it.text="synthetic";it.install(owner)
                } else null
                AgentTaskStore(context,database).use { store ->
                    val task=store.accept(owner,fixture,"once","synthetic pinned read","ref","p","m","OFF","revision")
                    val lease=store.claim(owner,task.id)
                    val input=(server?.input() ?: buildJsonObject { put("record_id","fixture");put("expected_sha256",AgentTaskStore.hash("synthetic"));put("installation_id",id) }).toString()
                    val tool=UIMessagePart.Tool("read-once",SyntheticMcpRuntime.TOOL,input,approvalState=ToolApprovalState.Pending)
                    store.waitForToolApproval(lease,listOf(tool));store.decideTool(owner,task.id,tool,ToolApprovalState.Approved,1)
                    store.beginCall(lease,tool.toolCallId,tool.toolName,tool.input)
                    if(mode=="unknown") {
                        // Actual protocol result returned, but RETURNED/VERIFIED checkpoint deliberately lost.
                        runBlocking { SyntheticMcpRuntime.read(owner,server!!.input()) { store.checkToolDispatch(lease,tool) } }
                        assertEquals(1,server!!.reads.get())
                        server.server.close() // keep installation/grant, lose only remote session and call ACK
                    } else {
                        SyntheticMcpRuntime.uninstallForQa(owner)
                        SyntheticMcpRuntime.installForQa(owner,18889,"fixture",AgentTaskStore.hash("synthetic"))
                    }
                    file.resolveSibling(file.name+".task").outputStream().use {
                        it.write((task.id+"\n"+input+"\n"+lease.fence).toByteArray());it.fd.sync()
                    }
                }
            }
            else -> error("Unknown process mode")
        }
        file.resolveSibling(file.name+".ready").outputStream().use { it.write(mode.toByteArray());it.fd.sync() }
        println("QA_MCP_PROCESS_READY_$mode")
        Thread.sleep(120_000)
        fail("Host did not terminate the prepared MCP process")
    }
    @Test fun verify() {
        if(mode=="installation") {
            val tool=requireNotNull(SyntheticMcpRuntime.toolFor(owner)) { "Persisted installation disappeared after real process exit" }
            assertTrue(tool.parameters().toString().contains(file.readText()))
        } else if(mode=="revoked") {
            assertNull(SyntheticMcpRuntime.toolFor(owner))
            McpInstallationStore(context).use { registry ->
                val pin=registry.get(owner)!!;assertEquals("REVOKED",pin.state)
                assertTrue(runCatching { registry.command(owner,pin.id,pin.revision,"enable") }.isFailure)
            }
        } else {
            val (taskId,input,fence)=file.resolveSibling(file.name+".task").readText().split("\n",limit=3)
            AgentTaskStore(context,database).use { store ->
                store.recover();assertEquals("UNCERTAIN_SIDE_EFFECT",store.get(owner,taskId).state)
                assertTrue(runCatching { store.resume(owner,taskId) }.isFailure)
                val other=store.accept("other-$fixture",fixture,"other","synthetic","ref","p","m","OFF","revision")
                assertTrue(runCatching { store.claim(other.owner,other.id) }.isFailure)
                var reachedGuard=false
                val oldTool=UIMessagePart.Tool("read-once",SyntheticMcpRuntime.TOOL,input)
                assertTrue(runCatching { runBlocking { SyntheticMcpRuntime.read(owner,Json.parseToJsonElement(input).jsonObject) {
                    reachedGuard=true;store.checkToolDispatch(AgentLease(store.get(owner,taskId),fence.toLong()),oldTool)
                } } }.isFailure)
                assertEquals(mode=="unknown",reachedGuard) // old install rejected at identity; UNKNOWN at dispatch, before HTTP
            }
        }
    }
}
